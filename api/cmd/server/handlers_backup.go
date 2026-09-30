package main

import "net/http"

// GET /v1/backup/status — the honest picture of what is backed up and what is safe.
// ReplicaConfigured is reported so a client never claims remote protection that the
// deployment cannot actually provide.
func (s *server) handleBackupStatus(w http.ResponseWriter, r *http.Request, userID string) {
	st, err := s.store.backupStatus(r.Context(), userID)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "db", err.Error())
		return
	}
	st.ReplicaConfigured = s.media.replicaConfigured()
	writeJSON(w, http.StatusOK, st)
}
