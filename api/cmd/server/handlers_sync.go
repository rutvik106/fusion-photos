package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"time"
)

// ---- upload completion ----

type completeResource struct {
	Variant        string `json:"variant"`
	ObjectKey      string `json:"objectKey"`
	CiphertextSize int64  `json:"ciphertextSize"`
	// PlaintextSHA is a legacy field; the server never trusts or persists it
	// for new uploads (the original digest lives inside the encrypted manifest).
	PlaintextSHA string `json:"plaintextSha256,omitempty"`
}

func (s *server) handleUploadComplete(w http.ResponseWriter, r *http.Request, userID string) {
	var body struct {
		Items []struct {
			AssetID     string             `json:"assetId"`
			KeyEnvelope string             `json:"keyEnvelope"`
			Manifest    manifestBlob       `json:"manifest"`
			Resources   []completeResource `json:"resources"`
		} `json:"items"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil || len(body.Items) == 0 {
		writeErr(w, http.StatusBadRequest, "bad_request", "items required")
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 60*time.Second)
	defer cancel()

	type committed struct {
		AssetID string `json:"assetId"`
		Seq     int64  `json:"seq"`
	}
	out := make([]committed, 0, len(body.Items))
	now := time.Now()

	for _, it := range body.Items {
		sess, err := s.store.getSessionForCompletion(ctx, it.AssetID, userID)
		if err != nil {
			switch {
			case errors.Is(err, errNotFound):
				writeErr(w, http.StatusNotFound, "not_found", "no upload session for asset")
			case errors.Is(err, errExpired):
				writeErr(w, http.StatusConflict, "expired", "upload session expired")
			default:
				writeErr(w, http.StatusInternalServerError, "db", err.Error())
			}
			return
		}

		// validateCompletion rejects any mismatch (wrong/extra/duplicate/missing
		// variant, wrong owner-derived key, wrong size, bad/missing manifest
		// base64, manifest-size mismatch). errAlreadyCommitted is an idempotent
		// repeat: the resource list still validated, so route it through the
		// commit transaction which returns the existing seq without recharging.
		if err := validateCompletion(userID, sess, it.Manifest, it.Resources, now); err != nil && !errors.Is(err, errAlreadyCommitted) {
			writeErr(w, http.StatusBadRequest, "validation", err.Error())
			return
		}

		// Verify every uploaded object exists with the declared ciphertext size
		// before the catalog commit (only S3 resources; manifest is in Postgres).
		for _, res := range it.Resources {
			size, err := s.media.statObject(ctx, res.ObjectKey)
			if err != nil {
				writeErr(w, http.StatusConflict, "missing_object",
					"object missing or unreadable: "+res.Variant)
				return
			}
			if size != res.CiphertextSize {
				writeErr(w, http.StatusConflict, "size_mismatch",
					"object size mismatch for "+res.Variant)
				return
			}
		}

		manifestMap := map[string]any{"headerB64": it.Manifest.HeaderB64, "blobB64": it.Manifest.BlobB64}
		seq, err := s.store.commitAssetWithSession(ctx, userID, it.AssetID, it.KeyEnvelope, manifestMap, toResources(it.Resources), sess)
		if err != nil {
			if errors.Is(err, errExpired) {
				writeErr(w, http.StatusConflict, "expired", "upload session expired")
				return
			}
			writeErr(w, http.StatusInternalServerError, "db", err.Error())
			return
		}
		out = append(out, committed{AssetID: it.AssetID, Seq: seq})
	}
	writeJSON(w, http.StatusOK, map[string]any{"assets": out})
}

func toResources(in []completeResource) []Resource {
	out := make([]Resource, 0, len(in))
	for _, r := range in {
		out = append(out, Resource{
			Variant:        r.Variant,
			ObjectKey:      r.ObjectKey,
			CiphertextSize: r.CiphertextSize,
			PlaintextSHA:   r.PlaintextSHA,
		})
	}
	return out
}

// ---- incremental sync ----

func (s *server) handleSync(w http.ResponseWriter, r *http.Request, userID string) {
	var cursor int64
	if _, err := fmt.Sscanf(r.URL.Query().Get("cursor"), "%d", &cursor); err != nil {
		cursor = 0
	}
	assets, maxSeq, err := s.store.assetsSince(r.Context(), userID, cursor, 500)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "db", err.Error())
		return
	}
	if assets == nil {
		assets = []AssetRow{}
	}
	writeJSON(w, http.StatusOK, map[string]any{"cursor": maxSeq, "assets": assets})
}

// ---- authorized resource access ----

func (s *server) handleResource(w http.ResponseWriter, r *http.Request, userID string) {
	assetID := r.PathValue("assetId")
	variant := r.PathValue("variant")
	switch variant {
	// original_motion is the video half of a Live/Motion Photo: exporting it is
	// mandatory ("never silently drop the motion component").
	case "original", "preview", "thumbnail", "manifest", "original_motion":
	default:
		writeErr(w, http.StatusBadRequest, "bad_variant", "unknown resource variant")
		return
	}
	// A snapshot-scoped request ignores the current trashed flag: an export must stay
	// reproducible even if the asset was trashed after the snapshot was created.
	var key string
	var err error
	if snapshotID := r.URL.Query().Get("snapshot"); snapshotID != "" {
		key, err = s.store.resourceForSnapshot(r.Context(), userID, snapshotID, assetID, variant)
	} else {
		key, err = s.store.resourceFor(r.Context(), userID, assetID, variant)
	}
	if err != nil {
		writeErr(w, http.StatusUnauthorized, "unauthorized", "no such resource")
		return
	}
	url, err := s.media.presignGet(key)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "s3", err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"url":       url,
		"objectKey": key,
	})
}
