package main

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"time"

	"github.com/minio/minio-go/v7"
)

// ---- multipart upload endpoints ----
//
// The client addresses uploads by (assetId, variant) only; the server owns the
// multipart upload ID and presigns each part. Resume reconciliation is
// server-side: ListParts is the source of truth for what actually landed.

type multipartVariantReq struct {
	Variant string `json:"variant"`
}

func allowedS3Variant(v string) bool {
	return v == "original" || v == "preview" || v == "thumbnail" || v == "original_motion"
}

// pendingSession returns the owner-bound upload session, requiring status
// pending and unexpired (multipart work on a committed/expired session is
// rejected).
func (s *server) pendingSession(ctx context.Context, assetID, userID string) (*sessionRow, error) {
	sess, err := s.store.getSessionForCompletion(ctx, assetID, userID)
	if err != nil {
		return nil, err
	}
	if sess.Status != "pending" || time.Now().After(sess.ExpiresAt) {
		return nil, errExpired
	}
	return sess, nil
}

// POST /v1/upload-session/{assetId}/multipart/init  {variant, partCount}
func (s *server) handleMultipartInit(w http.ResponseWriter, r *http.Request, userID string) {
	assetID := r.PathValue("assetId")
	var body struct {
		Variant   string `json:"variant"`
		PartCount int    `json:"partCount"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_request", "invalid body")
		return
	}
	if !allowedS3Variant(body.Variant) {
		writeErr(w, http.StatusBadRequest, "bad_variant", "unknown variant")
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 60*time.Second)
	defer cancel()
	sess, err := s.pendingSession(ctx, assetID, userID)
	if err != nil {
		writeErr(w, http.StatusConflict, "expired", "upload session unknown, committed, or expired")
		return
	}
	expected := sess.ExpectedSizes[body.Variant]
	if expected <= 0 {
		writeErr(w, http.StatusBadRequest, "validation", "variant not reserved in this session")
		return
	}
	slices, err := computePartSlices(expected, body.PartCount)
	if err != nil {
		writeErr(w, http.StatusBadRequest, "validation", err.Error())
		return
	}

	key := objectKey(userID, assetID, body.Variant)
	uploadID, err := s.media.createUploadID(ctx, key)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "s3", err.Error())
		return
	}
	if err := s.store.setMultipartID(ctx, userID, assetID, body.Variant, uploadID); err != nil {
		writeErr(w, http.StatusConflict, "expired", "upload session unknown, committed, or expired")
		return
	}

	type partPlan struct {
		Number int    `json:"number"`
		Offset int64  `json:"offset"`
		Length int64  `json:"length"`
		URL    string `json:"url"`
	}
	parts := make([]partPlan, 0, len(slices))
	for _, sl := range slices {
		url, err := s.media.presignPartPut(ctx, key, uploadID, sl.Number)
		if err != nil {
			// don't leave a half-initialized multipart session behind
			_ = s.media.abortMultipart(ctx, key, uploadID)
			_ = s.store.clearMultipartID(ctx, userID, assetID, body.Variant)
			writeErr(w, http.StatusInternalServerError, "s3", err.Error())
			return
		}
		parts = append(parts, partPlan{Number: sl.Number, Offset: sl.Offset, Length: sl.Length, URL: url})
	}
	writeJSON(w, http.StatusOK, map[string]any{"size": expected, "parts": parts})
}

// POST /v1/upload-session/{assetId}/multipart/status  {variant}
// Returns the server-observed uploaded parts (resume reconciliation).
func (s *server) handleMultipartStatus(w http.ResponseWriter, r *http.Request, userID string) {
	assetID := r.PathValue("assetId")
	var body multipartVariantReq
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil || !allowedS3Variant(body.Variant) {
		writeErr(w, http.StatusBadRequest, "bad_request", "invalid variant")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 30*time.Second)
	defer cancel()
	ids, err := s.store.getMultipartIDs(ctx, userID, assetID)
	if errors.Is(err, errNotFound) {
		writeErr(w, http.StatusNotFound, "not_found", "no upload session for asset")
		return
	}
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "db", err.Error())
		return
	}
	uploadID, started := ids[body.Variant]
	if !started {
		writeJSON(w, http.StatusOK, map[string]any{"started": false, "parts": []any{}})
		return
	}
	key := objectKey(userID, assetID, body.Variant)
	uploaded, err := s.media.listUploadParts(ctx, key, uploadID)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "s3", err.Error())
		return
	}
	type partState struct {
		Number int    `json:"number"`
		ETag   string `json:"etag"`
		Size   int64  `json:"size"`
	}
	parts := make([]partState, 0, len(uploaded))
	for _, p := range uploaded {
		parts = append(parts, partState{Number: p.PartNumber, ETag: p.ETag, Size: p.Size})
	}
	writeJSON(w, http.StatusOK, map[string]any{"started": true, "parts": parts})
}

// POST /v1/upload-session/{assetId}/multipart/parts  {variant, numbers: [..]}
// Presigns additional UploadPart URLs for an EXISTING multipart upload — used by
// resume flows that already know which part numbers are missing (init covers the
// first batch; this covers retransmits).
func (s *server) handleMultipartParts(w http.ResponseWriter, r *http.Request, userID string) {
	assetID := r.PathValue("assetId")
	var body struct {
		Variant string `json:"variant"`
		Numbers []int  `json:"numbers"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil || !allowedS3Variant(body.Variant) {
		writeErr(w, http.StatusBadRequest, "bad_request", "invalid body")
		return
	}
	if len(body.Numbers) == 0 || len(body.Numbers) > maxMultipartParts {
		writeErr(w, http.StatusBadRequest, "bad_request", "numbers must be 1..1024 entries")
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 60*time.Second)
	defer cancel()
	if _, err := s.pendingSession(ctx, assetID, userID); err != nil {
		writeErr(w, http.StatusConflict, "expired", "upload session unknown, committed, or expired")
		return
	}
	ids, err := s.store.getMultipartIDs(ctx, userID, assetID)
	if err != nil {
		writeErr(w, http.StatusNotFound, "not_found", "no upload session for asset")
		return
	}
	uploadID, started := ids[body.Variant]
	if !started {
		writeErr(w, http.StatusConflict, "not_started", "multipart not initialized for variant")
		return
	}

	key := objectKey(userID, assetID, body.Variant)
	uploadedList, err := s.media.listUploadParts(ctx, key, uploadID)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "s3", err.Error())
		return
	}
	uploadedSizes := make(map[int]int64, len(uploadedList))
	for _, p := range uploadedList {
		uploadedSizes[p.PartNumber] = p.Size
	}

	type partPlan struct {
		Number int    `json:"number"`
		URL    string `json:"url"`
		// Size is the expected part length; non-nil means the part already exists
		// on S3 (client can skip it entirely).
		Size *int64 `json:"size,omitempty"`
	}
	parts := make([]partPlan, 0, len(body.Numbers))
	for _, n := range body.Numbers {
		if n < 1 || n > maxMultipartParts {
			writeErr(w, http.StatusBadRequest, "bad_request", "part number out of range")
			return
		}
		url, err := s.media.presignPartPut(ctx, key, uploadID, n)
		if err != nil {
			writeErr(w, http.StatusInternalServerError, "s3", err.Error())
			return
		}
		pp := partPlan{Number: n, URL: url}
		if size, ok := uploadedSizes[n]; ok {
			pp.Size = &size
		}
		parts = append(parts, pp)
	}
	writeJSON(w, http.StatusOK, map[string]any{"parts": parts})
}

// POST /v1/upload-session/{assetId}/put-urls  {variants: [...]}
// Presigned single-PUT access to RESERVED variants of a pending session — used by
// the resume flow (which never received a start-request plan) to upload fresh
// derivatives and to recover the server-chosen object keys for completion.
func (s *server) handlePutURLs(w http.ResponseWriter, r *http.Request, userID string) {
	assetID := r.PathValue("assetId")
	var body struct {
		Variants []string `json:"variants"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil || len(body.Variants) == 0 || len(body.Variants) > 4 {
		writeErr(w, http.StatusBadRequest, "bad_request", "variants required")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 30*time.Second)
	defer cancel()
	sess, err := s.pendingSession(ctx, assetID, userID)
	if err != nil {
		writeErr(w, http.StatusConflict, "expired", "upload session unknown, committed, or expired")
		return
	}
	type item struct {
		Variant   string `json:"variant"`
		ObjectKey string `json:"objectKey"`
		URL       string `json:"url"`
	}
	items := make([]item, 0, len(body.Variants))
	for _, v := range body.Variants {
		if !allowedS3Variant(v) {
			writeErr(w, http.StatusBadRequest, "bad_variant", "unknown variant")
			return
		}
		if sess.ExpectedSizes[v] <= 0 {
			writeErr(w, http.StatusBadRequest, "validation", "variant not reserved in this session")
			return
		}
		key := objectKey(userID, assetID, v)
		url, err := s.media.presignPut(key)
		if err != nil {
			writeErr(w, http.StatusInternalServerError, "s3", err.Error())
			return
		}
		items = append(items, item{Variant: v, ObjectKey: key, URL: url})
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": items})
}

// POST /v1/upload-session/{assetId}/multipart/complete  {variant, parts:[{number,etag}]}
func (s *server) handleMultipartComplete(w http.ResponseWriter, r *http.Request, userID string) {
	assetID := r.PathValue("assetId")
	var body struct {
		Variant string       `json:"variant"`
		Parts   []clientPart `json:"parts"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil || !allowedS3Variant(body.Variant) {
		writeErr(w, http.StatusBadRequest, "bad_request", "invalid body")
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 120*time.Second)
	defer cancel()
	sess, err := s.pendingSession(ctx, assetID, userID)
	if err != nil {
		writeErr(w, http.StatusConflict, "expired", "upload session unknown, committed, or expired")
		return
	}
	expected := sess.ExpectedSizes[body.Variant]
	if expected <= 0 {
		writeErr(w, http.StatusBadRequest, "validation", "variant not reserved in this session")
		return
	}

	ids, err := s.store.getMultipartIDs(ctx, userID, assetID)
	if err != nil {
		writeErr(w, http.StatusNotFound, "not_found", "no upload session for asset")
		return
	}
	uploadID, started := ids[body.Variant]
	if !started {
		writeErr(w, http.StatusConflict, "not_started", "multipart not initialized for variant")
		return
	}

	key := objectKey(userID, assetID, body.Variant)
	uploadedList, err := s.media.listUploadParts(ctx, key, uploadID)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "s3", err.Error())
		return
	}
	uploaded := make(map[int]uploadedPart, len(uploadedList))
	for _, p := range uploadedList {
		uploaded[p.PartNumber] = uploadedPart{ETag: p.ETag, Size: p.Size}
	}
	resolved, err := resolveMultipartParts(expected, body.Parts, uploaded)
	if err != nil {
		writeErr(w, http.StatusBadRequest, "validation", err.Error())
		return
	}

	minioParts := make([]minio.CompletePart, 0, len(resolved))
	for _, rp := range resolved {
		minioParts = append(minioParts, minio.CompletePart{PartNumber: rp.Number, ETag: rp.ETag})
	}
	size, err := s.media.completeMultipart(ctx, key, uploadID, minioParts)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "s3", err.Error())
		return
	}
	if size != expected {
		writeErr(w, http.StatusConflict, "size_mismatch", "completed object size mismatch")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"size": size})
}

// POST /v1/upload-session/{assetId}/multipart/abort  {variant} — idempotent.
func (s *server) handleMultipartAbort(w http.ResponseWriter, r *http.Request, userID string) {
	assetID := r.PathValue("assetId")
	var body multipartVariantReq
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil || !allowedS3Variant(body.Variant) {
		writeErr(w, http.StatusBadRequest, "bad_request", "invalid variant")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 30*time.Second)
	defer cancel()
	ids, err := s.store.getMultipartIDs(ctx, userID, assetID)
	if errors.Is(err, errNotFound) {
		writeErr(w, http.StatusNotFound, "not_found", "no upload session for asset")
		return
	}
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "db", err.Error())
		return
	}
	if uploadID, ok := ids[body.Variant]; ok {
		if err := s.media.abortMultipart(ctx, objectKey(userID, assetID, body.Variant), uploadID); err != nil {
			writeErr(w, http.StatusInternalServerError, "s3", err.Error())
			return
		}
	}
	if err := s.store.clearMultipartID(ctx, userID, assetID, body.Variant); err != nil {
		writeErr(w, http.StatusInternalServerError, "db", err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}
