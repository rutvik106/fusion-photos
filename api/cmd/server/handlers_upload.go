package main

import (
	"encoding/json"
	"errors"
	"net/http"
	"time"

	"github.com/google/uuid"
)

// ---- upload session ----

type uploadItemReq struct {
	OriginalSize   int64            `json:"originalSize"`
	EncryptedSizes map[string]int64 `json:"encryptedSizes"`
	AssetIDClient  string           `json:"assetIdClient"`
}

// uploadSessionExpiry is how long a reservation stays completable. A session
// older than this cannot newly complete (the client must reserve again).
const uploadSessionExpiry = 24 * time.Hour

func (s *server) handleUploadSession(w http.ResponseWriter, r *http.Request, userID string) {
	var body struct {
		Items []uploadItemReq `json:"items"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_request", "invalid body")
		return
	}
	// Coarse structural validation (count, per-item shape, duplicate refs, batch ≤ quota).
	if _, err := validateUploadItems(body.Items, s.cfg.QuotaBytes); err != nil {
		switch {
		case errors.Is(err, errQuotaExceeded):
			writeErr(w, http.StatusPaymentRequired, "quota", "storage quota exceeded")
		case errors.Is(err, errTooManyItems):
			writeErr(w, http.StatusBadRequest, "bad_request", err.Error())
		default:
			writeErr(w, http.StatusBadRequest, "bad_request", err.Error())
		}
		return
	}

	// Idempotency: an opaque client ref may already have a reservation. A *committed*
	// one must not be re-uploaded (the client would re-encrypt under a new key and
	// corrupt the stored object), so we tell the client it is done and issue no URLs.
	refs := make([]string, 0, len(body.Items))
	for _, it := range body.Items {
		refs = append(refs, it.AssetIDClient)
	}
	existing, err := s.store.sessionsByClientRefs(r.Context(), userID, refs)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "db", err.Error())
		return
	}

	type plan struct {
		AssetIDClient string            `json:"assetIdClient"`
		AssetID       string            `json:"assetId"`
		Keys          map[string]string `json:"keys"`
		PutURLs       map[string]string `json:"putUrls"`
		MaxSizeBytes  int64             `json:"maxSizeBytes"`
		// Committed marks an idempotent replay: the asset already exists, no upload needed.
		Committed bool `json:"committed,omitempty"`
	}
	plans := make([]plan, 0, len(body.Items))
	fresh := make([]sessionInput, 0, len(body.Items))
	reuse := make([]reuseInput, 0, len(body.Items))
	expiresAt := time.Now().Add(uploadSessionExpiry)

	for _, it := range body.Items {
		prev, found := existing[it.AssetIDClient]
		assetID := uuid.NewString()
		committed := false
		if found {
			assetID = prev.AssetID
			committed = prev.Status == "committed"
		}

		keys := map[string]string{}
		putURLs := map[string]string{}
		if !committed {
			for _, v := range []string{"original", "preview", "thumbnail", "original_motion"} {
				if it.EncryptedSizes[v] <= 0 {
					continue
				}
				key := objectKey(userID, assetID, v)
				url, err := s.media.presignPut(key)
				if err != nil {
					writeErr(w, http.StatusInternalServerError, "s3", err.Error())
					return
				}
				keys[v] = key
				putURLs[v] = url
			}
		}

		plans = append(plans, plan{
			AssetIDClient: it.AssetIDClient,
			AssetID:       assetID,
			Keys:          keys,
			PutURLs:       putURLs,
			MaxSizeBytes:  s.cfg.QuotaBytes,
			Committed:     committed,
		})

		if committed {
			continue // no reservation work needed; the asset is already committed
		}
		reserved, _ := validateUploadItem(it)
		if found {
			// Refresh the existing reservation in place (same asset, current attempt's
			// sizes, fresh expiry) so a retry never duplicates the asset or the quota.
			reuse = append(reuse, reuseInput{
				sessionID:     prev.SessionID,
				expectedSizes: it.EncryptedSizes,
				reservedBytes: reserved,
				expiresAt:     expiresAt,
			})
		} else {
			fresh = append(fresh, sessionInput{
				id:            uuid.NewString(),
				assetID:       assetID,
				clientRef:     it.AssetIDClient,
				expectedSizes: it.EncryptedSizes,
				reservedBytes: reserved,
				expiresAt:     expiresAt,
			})
		}
	}

	if len(fresh)+len(reuse) > 0 {
		if err := s.store.createOrReuseUploadSessions(r.Context(), userID, fresh, reuse, s.cfg.QuotaBytes); err != nil {
			if errors.Is(err, errQuotaExceeded) {
				writeErr(w, http.StatusPaymentRequired, "quota", "storage quota exceeded")
				return
			}
			writeErr(w, http.StatusInternalServerError, "db", err.Error())
			return
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": plans})
}
