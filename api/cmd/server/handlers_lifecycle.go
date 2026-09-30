package main

import (
	"context"
	"log"
	"net/http"
	"time"
)

// ---- cloud trash lifecycle ----
//
// Per plan: user-visible deletion creates a tombstone with a 30-day retention
// window; the scheduled purge job hard-deletes rows afterwards and best-effort
// deletes the S3 objects. Trash NEVER implies local-device deletion, and local
// cleanup NEVER implies cloud deletion (those live in the native clients).

const trashWindow = 30 * 24 * time.Hour

// POST /v1/assets/{assetId}/trash
func (s *server) handleAssetTrash(w http.ResponseWriter, r *http.Request, userID string) {
	assetID := r.PathValue("assetId")
	deletedAt, err := s.store.trashAsset(r.Context(), userID, assetID)
	if err != nil {
		writeErr(w, http.StatusUnauthorized, "unauthorized", "no such asset")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"purgeAfter": deletedAt.Add(trashWindow).UTC().Format(time.RFC3339),
	})
}

// POST /v1/assets/{assetId}/restore
func (s *server) handleAssetRestore(w http.ResponseWriter, r *http.Request, userID string) {
	assetID := r.PathValue("assetId")
	if err := s.store.restoreAsset(r.Context(), userID, assetID); err != nil {
		writeErr(w, http.StatusUnauthorized, "unauthorized", "no such asset in trash")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

// startPurgeWorker runs the scheduled permanent purge: every 6h (plus once at
// startup) hard-delete assets whose 30-day trash window elapsed, then best-effort
// delete their S3 objects. Needs s3:DeleteObject on the application role; object
// deletion failures are logged and retried by the next tick (the catalog row is
// already gone, so the object becomes an orphan the reconciliation pass flags).
func (s *server) startPurgeWorker(ctx context.Context) {
	go func() {
		ticker := time.NewTicker(6 * time.Hour)
		defer ticker.Stop()
		run := func() {
			purged, err := s.store.purgeExpiredTrash(ctx, time.Now().Add(-trashWindow))
			if err != nil {
				log.Printf("purge: db error: %v", err)
				return
			}
			if len(purged) == 0 {
				return
			}
			objects := 0
			for _, pa := range purged {
				for _, key := range pa.Keys {
					if err := s.media.deleteObject(ctx, key); err != nil {
						log.Printf("purge: object delete failed (orphan): %s: %v", key, err)
						continue
					}
					objects++
				}
			}
			log.Printf("purge: removed %d assets, %d objects", len(purged), objects)
		}
		run()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				run()
			}
		}
	}()
}
