package main

import (
	"fmt"
	"net/http"
	"strconv"
	"time"
)

// Export snapshots.
//
// An export is a point-in-time, resumable, CLIENT-side job: the server records the
// immutable snapshot and serves snapshot-bound presigned URLs. Decryption, file naming
// and the integrity report happen on the device, so an export stays usable without this
// app and the server never sees plaintext.

// exportSnapshotTTL is the retention window for a snapshot. Long enough to survive an
// interrupted multi-day export; the plan requires export to remain available through a
// clearly disclosed grace period even if the user cancels.
const exportSnapshotTTL = 7 * 24 * time.Hour

// POST /v1/export/snapshot
func (s *server) handleExportCreate(w http.ResponseWriter, r *http.Request, userID string) {
	snap, err := s.store.createExportSnapshot(r.Context(), userID, exportSnapshotTTL)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "db", err.Error())
		return
	}
	writeJSON(w, http.StatusOK, snap)
}

// GET /v1/export/snapshots
func (s *server) handleExportList(w http.ResponseWriter, r *http.Request, userID string) {
	snaps, err := s.store.listExportSnapshots(r.Context(), userID, 20)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "db", err.Error())
		return
	}
	if snaps == nil {
		snaps = []exportSnapshot{}
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"snapshots":        snaps,
		"retentionSeconds": int(exportSnapshotTTL.Seconds()),
	})
}

// GET /v1/export/snapshot/{id}
func (s *server) handleExportGet(w http.ResponseWriter, r *http.Request, userID string) {
	snap, err := s.store.getExportSnapshot(r.Context(), userID, r.PathValue("id"))
	if err != nil {
		writeErr(w, http.StatusNotFound, "not_found", "no such export snapshot")
		return
	}
	writeJSON(w, http.StatusOK, snap)
}

// GET /v1/export/snapshot/{id}/assets?cursor=&limit=
//
// Pages the snapshot's assets, oldest first. Each returned asset carries the same
// encrypted key envelope and manifest as sync, so the client can decrypt file names,
// dates and quality locally and build the versioned export manifest.
func (s *server) handleExportAssets(w http.ResponseWriter, r *http.Request, userID string) {
	snapshotID := r.PathValue("id")
	if _, err := s.store.getExportSnapshot(r.Context(), userID, snapshotID); err != nil {
		writeErr(w, http.StatusNotFound, "not_found", "no such export snapshot")
		return
	}
	var cursor int64
	if _, err := fmt.Sscanf(r.URL.Query().Get("cursor"), "%d", &cursor); err != nil {
		cursor = 0
	}
	limit := 100
	if n, err := strconv.Atoi(r.URL.Query().Get("limit")); err == nil && n > 0 && n <= 500 {
		limit = n
	}
	assets, next, err := s.store.exportPage(r.Context(), userID, snapshotID, cursor, limit)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "db", err.Error())
		return
	}
	if assets == nil {
		assets = []AssetRow{}
	}
	writeJSON(w, http.StatusOK, map[string]any{"cursor": next, "assets": assets})
}
