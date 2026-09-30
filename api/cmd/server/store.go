package main

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"math"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// store owns all PostgreSQL access. Catalog records the server keeps are deliberately
// minimal and opaque: no filenames, no capture dates, no GPS — those live inside the
// encrypted manifests (per plan).
type store struct {
	pool *pgxpool.Pool
}

func newStore(ctx context.Context, databaseURL string) (*store, error) {
	pool, err := pgxpool.New(ctx, databaseURL)
	if err != nil {
		return nil, err
	}
	if err := pool.Ping(ctx); err != nil {
		return nil, err
	}
	if err := migrate(ctx, pool); err != nil {
		return nil, err
	}
	return &store{pool: pool}, nil
}

func (s *store) close() { s.pool.Close() }

func migrate(ctx context.Context, pool *pgxpool.Pool) error {
	_, err := pool.Exec(ctx, `
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS users (
	id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
	email TEXT NOT NULL UNIQUE,
	created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS sessions (
	token_hash TEXT PRIMARY KEY,
	user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
	expires_at TIMESTAMPTZ NOT NULL,
	created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS sessions_expiry ON sessions(expires_at);

CREATE TABLE IF NOT EXISTS vaults (
	user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
	wrapped JSONB NOT NULL,
	updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Per-user monotonic sync sequence (incremental cursor sync).
CREATE TABLE IF NOT EXISTS user_seqs (
	user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
	seq BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS assets (
	id UUID PRIMARY KEY,
	user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
	seq BIGINT NOT NULL,
	key_envelope TEXT NOT NULL,
	manifest JSONB NOT NULL,
	deleted BOOLEAN NOT NULL DEFAULT false,
	created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
	UNIQUE (user_id, seq)
);

CREATE TABLE IF NOT EXISTS asset_resources (
	asset_id UUID NOT NULL REFERENCES assets(id) ON DELETE CASCADE,
	variant TEXT NOT NULL,
	object_key TEXT NOT NULL,
	ciphertext_size BIGINT NOT NULL,
	plaintext_sha256 TEXT,
	PRIMARY KEY (asset_id, variant)
);

-- Application-level quota ledger (reserved bytes; reconciled by workers later).
CREATE TABLE IF NOT EXISTS quota_usage (
	user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
	stored_bytes BIGINT NOT NULL DEFAULT 0,
	object_count BIGINT NOT NULL DEFAULT 0
);

-- Per-asset upload reservations. One row per asset the client intends to back
-- up: the server issues the asset UUID, records the expected ciphertext sizes,
-- and admits the reservation against the user's quota in a single transaction.
-- A session commits atomically with the asset row; expired pending rows are
-- ignored by quota but never deleted here.
CREATE TABLE IF NOT EXISTS upload_sessions (
	id UUID PRIMARY KEY,
	user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
	asset_id UUID NOT NULL,
	expected_sizes JSONB NOT NULL,
	reserved_bytes BIGINT NOT NULL,
	expires_at TIMESTAMPTZ NOT NULL,
	status TEXT NOT NULL DEFAULT 'pending',
	created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS upload_sessions_completion ON upload_sessions (asset_id, user_id);
CREATE INDEX IF NOT EXISTS upload_sessions_quota ON upload_sessions (user_id, status, expires_at);

-- Trash lifecycle: user-visible deletion window before permanent purge.
ALTER TABLE assets ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;

-- Server-owned multipart upload IDs per resource variant (client never sees them).
ALTER TABLE upload_sessions ADD COLUMN IF NOT EXISTS multipart_ids JSONB NOT NULL DEFAULT '{}'::jsonb;

-- Idempotency key: an opaque, client-supplied reference for one logical upload attempt.
-- A unique (user_id, client_ref) lets a reservation that is retried (e.g. after a crash)
-- reuse the SAME asset instead of creating a duplicate and double-charging quota.
ALTER TABLE upload_sessions ADD COLUMN IF NOT EXISTS client_ref TEXT NOT NULL DEFAULT '';
CREATE UNIQUE INDEX IF NOT EXISTS upload_sessions_client_ref
  ON upload_sessions (user_id, client_ref) WHERE client_ref <> '';

-- Email OTP codes: HMAC-peppered hashes (never plaintext), single-use, short-lived,
-- attempt-capped. Replaces the previous in-memory map so codes survive restarts and
-- work across multiple API instances.
CREATE TABLE IF NOT EXISTS otp_codes (
  email TEXT PRIMARY KEY,
  code_hash TEXT NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS otp_codes_expiry ON otp_codes(expires_at);

-- Fixed-window throttle buckets for OTP request abuse, keyed "email:<addr>"/"ip:<addr>".
CREATE TABLE IF NOT EXISTS otp_throttle (
  key TEXT PRIMARY KEY,
  window_start TIMESTAMPTZ NOT NULL,
  count INT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS otp_throttle_window ON otp_throttle(window_start);

-- Remote-protection state per asset. 'replicated' is set ONLY after every resource has
-- been verified present in the independent replica bucket (plan § S3 security & DR).
ALTER TABLE assets ADD COLUMN IF NOT EXISTS replication_status TEXT NOT NULL DEFAULT 'unreplicated';
ALTER TABLE assets ADD COLUMN IF NOT EXISTS replication_checked_at TIMESTAMPTZ;
CREATE INDEX IF NOT EXISTS assets_replication ON assets (replication_status) WHERE deleted = false;

-- Per-resource evidence that the replica holds the object (the size is the check).
CREATE TABLE IF NOT EXISTS replica_objects (
  asset_id UUID NOT NULL REFERENCES assets(id) ON DELETE CASCADE,
  variant TEXT NOT NULL,
  ciphertext_size BIGINT NOT NULL,
  verified_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (asset_id, variant)
);

-- Versioned export snapshots: an immutable selection of the library captured at a
-- point in time so a large export stays reproducible even if the library changes
-- while it downloads. Metadata only; expires after a disclosed retention window.
CREATE TABLE IF NOT EXISTS export_snapshots (
  id UUID PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at TIMESTAMPTZ NOT NULL,
  max_seq BIGINT NOT NULL,
  asset_count BIGINT NOT NULL DEFAULT 0,
  byte_total BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS export_snapshots_user ON export_snapshots (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS export_snapshots_expiry ON export_snapshots (expires_at);

-- The exact assets belonging to a snapshot. Recorded explicitly so later trashing
-- cannot remove an asset from an already-created export (snapshot immutability).
CREATE TABLE IF NOT EXISTS export_snapshot_assets (
  snapshot_id UUID NOT NULL REFERENCES export_snapshots(id) ON DELETE CASCADE,
  asset_id UUID NOT NULL,
  seq BIGINT NOT NULL,
  PRIMARY KEY (snapshot_id, asset_id)
);
CREATE INDEX IF NOT EXISTS export_snapshot_assets_seq ON export_snapshot_assets (snapshot_id, seq);
`)
	return err
}

// sessionInput is one asset reservation to persist in a single start transaction.
type sessionInput struct {
	id            string
	assetID       string
	clientRef     string
	expectedSizes map[string]int64
	reservedBytes int64
	expiresAt     time.Time
}

// reuseInput refreshes an existing pending reservation for the same idempotency key:
// the same asset is kept, but the client's new ciphertext sizes and a fresh expiry
// replace the stale ones (a retry re-encrypts under a new asset key, so the whole
// resource set is re-uploaded and the sizes must match the current attempt).
type reuseInput struct {
	sessionID     string
	expectedSizes map[string]int64
	reservedBytes int64
	expiresAt     time.Time
}

// clientRefSession is the minimal view of an existing reservation for an idempotency key.
type clientRefSession struct {
	SessionID string
	AssetID   string
	Status    string
}

// sessionsByClientRefs looks up existing reservations by their opaque idempotency key
// so the caller can reuse the same asset instead of creating a duplicate.
func (s *store) sessionsByClientRefs(ctx context.Context, userID string, refs []string) (map[string]clientRefSession, error) {
	out := map[string]clientRefSession{}
	if len(refs) == 0 {
		return out, nil
	}
	rows, err := s.pool.Query(ctx, `
SELECT client_ref, id, asset_id, status FROM upload_sessions
WHERE user_id = $1 AND client_ref = ANY($2)`, userID, refs)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var ref string
		var c clientRefSession
		if err := rows.Scan(&ref, &c.SessionID, &c.AssetID, &c.Status); err != nil {
			return nil, err
		}
		out[ref] = c
	}
	return out, rows.Err()
}

// createOrReuseUploadSessions persists fresh reservations and refreshes existing ones
// (matched by idempotency key) in ONE transaction. It locks the user's quota_usage row,
// sums the pending reserved bytes NOT being refreshed, and admits the batch only if
// stored + otherPending + requested does not exceed the quota (overflow-safe). On quota
// overflow or any DB error nothing is persisted.
func (s *store) createOrReuseUploadSessions(ctx context.Context, userID string, fresh []sessionInput, reuse []reuseInput, quota int64) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)

	if _, err := tx.Exec(ctx,
		`INSERT INTO quota_usage (user_id, stored_bytes, object_count) VALUES ($1, 0, 0) ON CONFLICT DO NOTHING`,
		userID); err != nil {
		return err
	}
	var stored int64
	if err := tx.QueryRow(ctx,
		`SELECT stored_bytes FROM quota_usage WHERE user_id = $1 FOR UPDATE`, userID).Scan(&stored); err != nil {
		return err
	}

	// Pending bytes that stay reserved: everything except the rows being refreshed.
	excludeIDs := make([]string, 0, len(reuse))
	for _, r := range reuse {
		excludeIDs = append(excludeIDs, r.sessionID)
	}
	var pending int64
	if err := tx.QueryRow(ctx, `
SELECT COALESCE(SUM(reserved_bytes), 0) FROM upload_sessions
WHERE user_id = $1 AND status = 'pending' AND expires_at > now() AND id <> ALL($2::uuid[])`,
		userID, excludeIDs).Scan(&pending); err != nil {
		return err
	}

	var requested int64
	add := func(n int64) error {
		if requested > math.MaxInt64-n {
			return errSizeOverflow
		}
		requested += n
		return nil
	}
	for _, it := range fresh {
		if err := add(it.reservedBytes); err != nil {
			return err
		}
	}
	for _, r := range reuse {
		if err := add(r.reservedBytes); err != nil {
			return err
		}
	}
	if !admitQuota(stored, pending, requested, quota) {
		return errQuotaExceeded
	}

	for _, it := range fresh {
		sizesJSON, err := json.Marshal(it.expectedSizes)
		if err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `
INSERT INTO upload_sessions (id, user_id, asset_id, client_ref, expected_sizes, reserved_bytes, expires_at, status)
VALUES ($1, $2, $3, $4, $5, $6, $7, 'pending')`,
			it.id, userID, it.assetID, it.clientRef, sizesJSON, it.reservedBytes, it.expiresAt); err != nil {
			return err
		}
	}

	for _, r := range reuse {
		sizesJSON, err := json.Marshal(r.expectedSizes)
		if err != nil {
			return err
		}
		// Reset multipart_ids: the retry re-encrypts and re-uploads every resource, so
		// any multipart upload started by the previous attempt is stale.
		if _, err := tx.Exec(ctx, `
UPDATE upload_sessions
SET expected_sizes = $2, reserved_bytes = $3, expires_at = $4, multipart_ids = '{}'::jsonb
WHERE id = $1 AND user_id = $5 AND status = 'pending'`,
			r.sessionID, sizesJSON, r.reservedBytes, r.expiresAt, userID); err != nil {
			return err
		}
	}

	return tx.Commit(ctx)
}

// getSessionForCompletion fetches the owner-bound reservation for an asset.
// A missing row (or one owned by another user) returns errNotFound; an expired
// or non-pending row returns errExpired. The caller re-locks under the commit
// transaction before mutating.
func (s *store) getSessionForCompletion(ctx context.Context, assetID, userID string) (*sessionRow, error) {
	var sess sessionRow
	var sizesJSON []byte
	err := s.pool.QueryRow(ctx, `
SELECT id, user_id, asset_id, expected_sizes, reserved_bytes, expires_at, status
FROM upload_sessions WHERE asset_id = $1 AND user_id = $2`, assetID, userID).
		Scan(&sess.ID, &sess.UserID, &sess.AssetID, &sizesJSON, &sess.ReservedBytes, &sess.ExpiresAt, &sess.Status)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, errNotFound
	}
	if err != nil {
		return nil, err
	}
	if err := json.Unmarshal(sizesJSON, &sess.ExpectedSizes); err != nil {
		return nil, err
	}
	if sess.Status != "pending" && sess.Status != "committed" {
		return nil, errExpired
	}
	if sess.Status == "pending" && time.Now().After(sess.ExpiresAt) {
		return nil, errExpired
	}
	return &sess, nil
}

// ---- users / otp / sessions ----

func (s *store) ensureUser(ctx context.Context, email string) (string, error) {
	var id string
	err := s.pool.QueryRow(ctx,
		`INSERT INTO users (email) VALUES ($1) ON CONFLICT (email) DO UPDATE SET email = EXCLUDED.email RETURNING id`,
		email).Scan(&id)
	return id, err
}

func (s *store) createSession(ctx context.Context, userID string, ttl time.Duration) (string, error) {
	raw := randomToken(32)
	sum := sha256.Sum256([]byte(raw))
	_, err := s.pool.Exec(ctx,
		`INSERT INTO sessions (token_hash, user_id, expires_at) VALUES ($1, $2, $3)`,
		hex.EncodeToString(sum[:]), userID, time.Now().Add(ttl))
	if err != nil {
		return "", err
	}
	return raw, nil
}

var errUnauthorized = errors.New("unauthorized")

// emailFor returns the login email for an account id (used by GET /v1/me so a client
// can learn which account a session belongs to — the basis of catalog scoping).
func (s *store) emailFor(ctx context.Context, userID string) (string, error) {
	var email string
	err := s.pool.QueryRow(ctx, `SELECT email FROM users WHERE id = $1`, userID).Scan(&email)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", errUnauthorized
	}
	return email, err
}

func (s *store) userForToken(ctx context.Context, bearer string) (string, error) {
	sum := sha256.Sum256([]byte(bearer))
	var userID string
	err := s.pool.QueryRow(ctx,
		`SELECT user_id FROM sessions WHERE token_hash = $1 AND expires_at > now()`,
		hex.EncodeToString(sum[:])).Scan(&userID)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", errUnauthorized
	}
	return userID, err
}

// ---- OTP codes & throttling ----

// putOTP stores (or replaces) the hashed code for an email. Replacing resets the
// attempt counter so a freshly requested code always starts clean.
func (s *store) putOTP(ctx context.Context, email, codeHash string, expiresAt time.Time) error {
	_, err := s.pool.Exec(ctx, `
INSERT INTO otp_codes (email, code_hash, expires_at, attempts, created_at)
VALUES ($1, $2, $3, 0, now())
ON CONFLICT (email) DO UPDATE
  SET code_hash = EXCLUDED.code_hash, expires_at = EXCLUDED.expires_at, attempts = 0, created_at = now()`,
		email, codeHash, expiresAt)
	return err
}

// verifyOTP atomically checks a submitted code hash under a row lock and enforces
// the attempt cap. A correct code is consumed (deleted) exactly once; an expired
// or over-guessed code is deleted; a wrong guess increments the counter. The
// distinct otpResult lets the caller answer 429 (too many) versus a generic 401.
func (s *store) verifyOTP(ctx context.Context, email, codeHash string, now time.Time, maxAttempts int) (otpResult, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return otpInvalid, err
	}
	defer tx.Rollback(ctx)

	var stored string
	var expiresAt time.Time
	var attempts int
	err = tx.QueryRow(ctx,
		`SELECT code_hash, expires_at, attempts FROM otp_codes WHERE email = $1 FOR UPDATE`,
		email).Scan(&stored, &expiresAt, &attempts)
	if errors.Is(err, pgx.ErrNoRows) {
		return otpInvalid, tx.Commit(ctx)
	}
	if err != nil {
		return otpInvalid, err
	}
	// Expired or already exhausted: invalidate and report.
	if now.After(expiresAt) || attempts >= maxAttempts {
		if _, err := tx.Exec(ctx, `DELETE FROM otp_codes WHERE email = $1`, email); err != nil {
			return otpInvalid, err
		}
		if attempts >= maxAttempts {
			return otpTooManyAttempts, tx.Commit(ctx)
		}
		return otpInvalid, tx.Commit(ctx)
	}
	if !hmac.Equal([]byte(stored), []byte(codeHash)) {
		if _, err := tx.Exec(ctx, `UPDATE otp_codes SET attempts = attempts + 1 WHERE email = $1`, email); err != nil {
			return otpInvalid, err
		}
		if attempts+1 >= maxAttempts {
			return otpTooManyAttempts, tx.Commit(ctx)
		}
		return otpInvalid, tx.Commit(ctx)
	}
	if _, err := tx.Exec(ctx, `DELETE FROM otp_codes WHERE email = $1`, email); err != nil {
		return otpInvalid, err
	}
	return otpOK, tx.Commit(ctx)
}

// bumpThrottle increments a fixed-window counter and reports whether the caller is
// still within limit. It is a single atomic statement, so concurrent requests cannot
// race past the cap. A window whose start is at/before the cutoff rolls over.
func (s *store) bumpThrottle(ctx context.Context, key string, window time.Duration, limit int, now time.Time) (bool, error) {
	var count int
	err := s.pool.QueryRow(ctx, `
INSERT INTO otp_throttle (key, window_start, count) VALUES ($1, $2, 1)
ON CONFLICT (key) DO UPDATE SET
  count = CASE WHEN otp_throttle.window_start <= $3 THEN 1 ELSE otp_throttle.count + 1 END,
  window_start = CASE WHEN otp_throttle.window_start <= $3 THEN $2 ELSE otp_throttle.window_start END
RETURNING count`, key, now, now.Add(-window)).Scan(&count)
	if err != nil {
		return false, err
	}
	return admitThrottle(count, limit), nil
}

// pruneExpiredAuth removes expired/consumed codes and day-old throttle buckets so
// the auth tables stay small. Called from the scheduled lifecycle worker.
func (s *store) pruneExpiredAuth(ctx context.Context, now time.Time) error {
	if _, err := s.pool.Exec(ctx, `DELETE FROM otp_codes WHERE expires_at < $1`, now); err != nil {
		return err
	}
	_, err := s.pool.Exec(ctx, `DELETE FROM otp_throttle WHERE window_start < $1`, now.Add(-24*time.Hour))
	return err
}

// ---- remote replication & reconciliation ----

// replicaTarget is one live asset whose resources must be verified in the replica.
type replicaTarget struct {
	AssetID   string
	UserID    string
	Resources map[string]int64 // variant -> ciphertext size
}

// assetsNeedingReplication returns live assets not yet recorded as replicated, oldest
// first, bounded by limit so one tick cannot scan an unbounded library.
func (s *store) assetsNeedingReplication(ctx context.Context, limit int) ([]replicaTarget, error) {
	rows, err := s.pool.Query(ctx, `
SELECT a.id, a.user_id,
  COALESCE((SELECT jsonb_object_agg(r.variant, r.ciphertext_size) FROM asset_resources r WHERE r.asset_id = a.id), '{}'::jsonb)
FROM assets a
WHERE a.deleted = false AND a.replication_status <> 'replicated'
ORDER BY a.seq
LIMIT $1`, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []replicaTarget
	for rows.Next() {
		var t replicaTarget
		var resJSON []byte
		if err := rows.Scan(&t.AssetID, &t.UserID, &resJSON); err != nil {
			return nil, err
		}
		if err := json.Unmarshal(resJSON, &t.Resources); err != nil {
			return nil, err
		}
		out = append(out, t)
	}
	return out, rows.Err()
}

// recordReplication persists the outcome of one verification pass: the per-resource
// evidence actually observed, plus the asset-level status. complete is true only when
// every resource was confirmed, which is what flips the asset to 'replicated'.
func (s *store) recordReplication(ctx context.Context, assetID string, verified map[string]int64, complete bool) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	for variant, size := range verified {
		if _, err := tx.Exec(ctx, `
INSERT INTO replica_objects (asset_id, variant, ciphertext_size, verified_at)
VALUES ($1, $2, $3, now())
ON CONFLICT (asset_id, variant) DO UPDATE
  SET ciphertext_size = EXCLUDED.ciphertext_size, verified_at = now()`,
			assetID, variant, size); err != nil {
			return err
		}
	}
	status := "unreplicated"
	if complete {
		status = "replicated"
	}
	if _, err := tx.Exec(ctx,
		`UPDATE assets SET replication_status = $2, replication_checked_at = now() WHERE id = $1`,
		assetID, status); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

// backupStatus is the per-user picture the UI needs in order to be honest about what
// is (and is not yet) safe. ReplicaConfigured is filled in by the handler.
type backupStatus struct {
	TotalAssets        int64      `json:"totalAssets"`
	ProtectedAssets    int64      `json:"protectedAssets"`
	UnreplicatedAssets int64      `json:"unreplicatedAssets"`
	TrashedAssets      int64      `json:"trashedAssets"`
	PendingUploads     int64      `json:"pendingUploads"`
	ExpiredUploads     int64      `json:"expiredUploads"`
	LastCommittedAt    *time.Time `json:"lastCommittedAt"`
	LastReplicationAt  *time.Time `json:"lastReplicationCheck"`
	ReplicaConfigured  bool       `json:"replicaConfigured"`
}

func (s *store) backupStatus(ctx context.Context, userID string) (backupStatus, error) {
	var st backupStatus
	var lastCommitted sql.NullTime
	var lastReplication sql.NullTime
	if err := s.pool.QueryRow(ctx, `
SELECT
  COUNT(*) FILTER (WHERE deleted = false),
  COUNT(*) FILTER (WHERE deleted = false AND replication_status = 'replicated'),
  COUNT(*) FILTER (WHERE deleted = false AND replication_status <> 'replicated'),
  COUNT(*) FILTER (WHERE deleted = true),
  MAX(created_at) FILTER (WHERE deleted = false),
  MAX(replication_checked_at)
FROM assets WHERE user_id = $1`, userID).Scan(
		&st.TotalAssets, &st.ProtectedAssets, &st.UnreplicatedAssets, &st.TrashedAssets,
		&lastCommitted, &lastReplication); err != nil {
		return st, err
	}
	if err := s.pool.QueryRow(ctx, `
SELECT
  COUNT(*) FILTER (WHERE status = 'pending' AND expires_at > now()),
  COUNT(*) FILTER (WHERE status = 'pending' AND expires_at <= now())
FROM upload_sessions WHERE user_id = $1`, userID).Scan(&st.PendingUploads, &st.ExpiredUploads); err != nil {
		return st, err
	}
	if lastCommitted.Valid {
		t := lastCommitted.Time
		st.LastCommittedAt = &t
	}
	if lastReplication.Valid {
		t := lastReplication.Time
		st.LastReplicationAt = &t
	}
	return st, nil
}

// staleSession is an expired-but-pending reservation whose abandoned multipart uploads
// (if any) must be aborted to stop accruing storage charges.
type staleSession struct {
	SessionID    string
	UserID       string
	AssetID      string
	MultipartIDs map[string]string
}

// expireStaleUploadSessions marks pending reservations past their expiry as 'expired'
// and returns them so the caller can abort any orphaned multipart uploads.
func (s *store) expireStaleUploadSessions(ctx context.Context, now time.Time) ([]staleSession, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx)

	rows, err := tx.Query(ctx, `
SELECT id, user_id, asset_id, COALESCE(multipart_ids, '{}'::jsonb)
FROM upload_sessions
WHERE status = 'pending' AND expires_at < $1
FOR UPDATE`, now)
	if err != nil {
		return nil, err
	}
	var stale []staleSession
	var ids []string
	for rows.Next() {
		var st staleSession
		var mpJSON []byte
		if err := rows.Scan(&st.SessionID, &st.UserID, &st.AssetID, &mpJSON); err != nil {
			rows.Close()
			return nil, err
		}
		if err := json.Unmarshal(mpJSON, &st.MultipartIDs); err != nil {
			st.MultipartIDs = nil
		}
		stale = append(stale, st)
		ids = append(ids, st.SessionID)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}
	if len(ids) == 0 {
		return nil, tx.Commit(ctx)
	}
	if _, err := tx.Exec(ctx,
		`UPDATE upload_sessions SET status = 'expired' WHERE id = ANY($1::uuid[])`, ids); err != nil {
		return nil, err
	}
	return stale, tx.Commit(ctx)
}

// ---- export snapshots ----
//
// A snapshot is a point-in-time, immutable selection of a library. It stores only
// metadata: the ciphertext stays in the serving bucket and is fetched through
// snapshot-bound presigned URLs, so a large export stays reproducible even if the
// library changes (or assets are trashed) while it downloads.

type exportSnapshot struct {
	ID         string    `json:"snapshotId"`
	CreatedAt  time.Time `json:"createdAt"`
	ExpiresAt  time.Time `json:"expiresAt"`
	MaxSeq     int64     `json:"maxSeq"`
	AssetCount int64     `json:"assetCount"`
	ByteTotal  int64     `json:"byteTotal"`
}

// createExportSnapshot records the caller's current non-trashed library as a new
// snapshot and returns it. One transaction so the snapshot row, its membership and its
// byte total agree; the membership rows are what make the snapshot immutable.
func (s *store) createExportSnapshot(ctx context.Context, userID string, ttl time.Duration) (exportSnapshot, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return exportSnapshot{}, err
	}
	defer tx.Rollback(ctx)

	var maxSeq int64
	err = tx.QueryRow(ctx, `SELECT COALESCE(seq, 0) FROM user_seqs WHERE user_id = $1`, userID).Scan(&maxSeq)
	if errors.Is(err, pgx.ErrNoRows) {
		maxSeq = 0
	} else if err != nil {
		return exportSnapshot{}, err
	}

	snap := exportSnapshot{ID: uuid.NewString(), MaxSeq: maxSeq}
	if err := tx.QueryRow(ctx, `
INSERT INTO export_snapshots (id, user_id, expires_at, max_seq)
VALUES ($1, $2, $3, $4)
RETURNING created_at, expires_at`, snap.ID, userID, time.Now().Add(ttl), maxSeq).
		Scan(&snap.CreatedAt, &snap.ExpiresAt); err != nil {
		return exportSnapshot{}, err
	}

	if _, err := tx.Exec(ctx, `
INSERT INTO export_snapshot_assets (snapshot_id, asset_id, seq)
SELECT $1, a.id, a.seq FROM assets a
WHERE a.user_id = $2 AND a.deleted = false AND a.seq <= $3`, snap.ID, userID, maxSeq); err != nil {
		return exportSnapshot{}, err
	}

	if err := tx.QueryRow(ctx, `
SELECT COUNT(*), COALESCE(SUM(
  (SELECT COALESCE(SUM(r.ciphertext_size), 0) FROM asset_resources r WHERE r.asset_id = sa.asset_id)
), 0)
FROM export_snapshot_assets sa WHERE sa.snapshot_id = $1`, snap.ID).
		Scan(&snap.AssetCount, &snap.ByteTotal); err != nil {
		return exportSnapshot{}, err
	}
	if _, err := tx.Exec(ctx,
		`UPDATE export_snapshots SET asset_count = $2, byte_total = $3 WHERE id = $1`,
		snap.ID, snap.AssetCount, snap.ByteTotal); err != nil {
		return exportSnapshot{}, err
	}
	return snap, tx.Commit(ctx)
}

func (s *store) listExportSnapshots(ctx context.Context, userID string, limit int) ([]exportSnapshot, error) {
	rows, err := s.pool.Query(ctx, `
SELECT id, created_at, expires_at, max_seq, asset_count, byte_total
FROM export_snapshots WHERE user_id = $1 AND expires_at > now()
ORDER BY created_at DESC LIMIT $2`, userID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []exportSnapshot
	for rows.Next() {
		var snap exportSnapshot
		if err := rows.Scan(&snap.ID, &snap.CreatedAt, &snap.ExpiresAt, &snap.MaxSeq, &snap.AssetCount, &snap.ByteTotal); err != nil {
			return nil, err
		}
		out = append(out, snap)
	}
	return out, rows.Err()
}

func (s *store) getExportSnapshot(ctx context.Context, userID, id string) (exportSnapshot, error) {
	var snap exportSnapshot
	err := s.pool.QueryRow(ctx, `
SELECT id, created_at, expires_at, max_seq, asset_count, byte_total
FROM export_snapshots WHERE id = $1 AND user_id = $2 AND expires_at > now()`, id, userID).
		Scan(&snap.ID, &snap.CreatedAt, &snap.ExpiresAt, &snap.MaxSeq, &snap.AssetCount, &snap.ByteTotal)
	if errors.Is(err, pgx.ErrNoRows) {
		return exportSnapshot{}, errNotFound
	}
	return snap, err
}

// exportPage pages the assets captured in a snapshot, oldest first, so a large export
// is streamed without loading the whole library. Membership (not the live assets table)
// decides inclusion, which is what preserves snapshot immutability.
func (s *store) exportPage(ctx context.Context, userID, snapshotID string, cursor int64, limit int) ([]AssetRow, int64, error) {
	rows, err := s.pool.Query(ctx, `
SELECT`+assetColumns+`
FROM export_snapshot_assets sa
JOIN assets a ON a.id = sa.asset_id
WHERE sa.snapshot_id = $1 AND a.user_id = $2 AND sa.seq > $3
ORDER BY sa.seq LIMIT $4`, snapshotID, userID, cursor, limit)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	out, err := scanAssets(rows)
	if err != nil {
		return nil, 0, err
	}
	next := cursor
	for _, a := range out {
		if a.Seq > next {
			next = a.Seq
		}
	}
	return out, next, nil
}

// resourceForSnapshot resolves an object key for an asset that belongs to the caller's
// snapshot. Unlike resourceFor it ignores the current trashed flag: a snapshot is
// immutable, so an asset trashed after the snapshot was taken is still exportable.
func (s *store) resourceForSnapshot(ctx context.Context, userID, snapshotID, assetID, variant string) (string, error) {
	var key string
	err := s.pool.QueryRow(ctx, `
SELECT r.object_key FROM export_snapshot_assets sa
JOIN assets a ON a.id = sa.asset_id
JOIN asset_resources r ON r.asset_id = a.id AND r.variant = $4
WHERE sa.snapshot_id = $1 AND a.user_id = $2 AND a.id = $3`,
		snapshotID, userID, assetID, variant).Scan(&key)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", errUnauthorized // do not reveal existence across tenants
	}
	return key, err
}

// releaseExpiredExportSnapshots drops snapshots past their retention window (the cascade
// removes membership rows). The exported ciphertext itself is untouched — it is only
// removed through the normal trash/purge path.
func (s *store) releaseExpiredExportSnapshots(ctx context.Context, now time.Time) (int64, error) {
	tag, err := s.pool.Exec(ctx, `DELETE FROM export_snapshots WHERE expires_at < $1`, now)
	if err != nil {
		return 0, err
	}
	return tag.RowsAffected(), nil
}

// ---- vault ----

func (s *store) getVault(ctx context.Context, userID string) (*[]byte, error) {
	var wrapped []byte
	err := s.pool.QueryRow(ctx, `SELECT wrapped FROM vaults WHERE user_id = $1`, userID).Scan(&wrapped)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &wrapped, nil
}

func (s *store) putVault(ctx context.Context, userID string, wrapped []byte) error {
	_, err := s.pool.Exec(ctx, `
INSERT INTO vaults (user_id, wrapped, updated_at) VALUES ($1, $2, now())
ON CONFLICT (user_id) DO UPDATE SET wrapped = EXCLUDED.wrapped, updated_at = now()`, userID, wrapped)
	return err
}

// ---- trash lifecycle ----
//
// Cloud trash semantics per plan: user-visible deletion creates a tombstone with a
// 30-day retention window; the scheduled purge job hard-deletes after that. A local
// (device) deletion NEVER touches cloud state, and trash NEVER deletes the phone's
// copy — these endpoints only affect the cloud catalog.

// trashAsset moves one owned asset to cloud trash and advances the sync seq so the
// tombstone propagates. Idempotent: trashing a trashed asset is a no-op.
func (s *store) trashAsset(ctx context.Context, userID, assetID string) (time.Time, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return time.Time{}, err
	}
	defer tx.Rollback(ctx)

	// deleted_at is NULL for live assets — scan via sql.NullTime
	var deletedAt sql.NullTime
	err = tx.QueryRow(ctx, `SELECT deleted_at FROM assets WHERE id = $1 AND user_id = $2 FOR UPDATE`,
		assetID, userID).Scan(&deletedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return time.Time{}, errUnauthorized // do not reveal other tenants' assets
	}
	if err != nil {
		return time.Time{}, err
	}
	if deletedAt.Valid {
		return deletedAt.Time, nil // already in trash
	}
	seq, err := s.nextSeq(ctx, tx, userID)
	if err != nil {
		return time.Time{}, err
	}
	var out sql.NullTime
	err = tx.QueryRow(ctx, `
UPDATE assets SET deleted = true, deleted_at = now(), seq = $3
WHERE id = $1 AND user_id = $2 RETURNING deleted_at`, assetID, userID, seq).Scan(&out)
	if err != nil {
		return time.Time{}, err
	}
	if err := tx.Commit(ctx); err != nil {
		return time.Time{}, err
	}
	return out.Time, nil
}

// restoreAsset pulls one owned asset back out of trash (seq advances so the
// un-deletion propagates).
func (s *store) restoreAsset(ctx context.Context, userID, assetID string) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)

	var deletedAt sql.NullTime
	err = tx.QueryRow(ctx, `SELECT deleted_at FROM assets WHERE id = $1 AND user_id = $2 FOR UPDATE`,
		assetID, userID).Scan(&deletedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return errUnauthorized
	}
	if err != nil {
		return err
	}
	if !deletedAt.Valid {
		return nil // not in trash
	}
	seq, err := s.nextSeq(ctx, tx, userID)
	if err != nil {
		return err
	}
	tag, err := tx.Exec(ctx, `
UPDATE assets SET deleted = false, deleted_at = NULL, seq = $3
WHERE id = $1 AND user_id = $2`, assetID, userID, seq)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return errUnauthorized
	}
	return tx.Commit(ctx)
}

// purgedAsset is one hard-deleted asset together with its S3 object keys, so the
// caller can attempt object deletion after the catalog row is gone.
type purgedAsset struct {
	AssetID string
	Keys    []string
}

// purgeExpiredTrash hard-deletes assets whose trash retention elapsed and returns
// their object keys. Tombstones and rows vanish; the caller deletes S3 objects
// best-effort (a failed object delete is retried by recording nothing — the object
// becomes an orphan that the reconciliation pass flags).
func (s *store) purgeExpiredTrash(ctx context.Context, before time.Time) ([]purgedAsset, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx)

	rows, err := tx.Query(ctx, `
SELECT a.id, COALESCE((SELECT jsonb_agg(r.object_key) FROM asset_resources r WHERE r.asset_id = a.id), '[]'::jsonb)
FROM assets a WHERE a.deleted = true AND a.deleted_at IS NOT NULL AND a.deleted_at < $1 FOR UPDATE OF a`, before)
	if err != nil {
		return nil, err
	}
	var purged []purgedAsset
	var ids []string
	for rows.Next() {
		var id string
		var keysJSON []byte
		if err := rows.Scan(&id, &keysJSON); err != nil {
			rows.Close()
			return nil, err
		}
		var keys []string
		_ = json.Unmarshal(keysJSON, &keys)
		purged = append(purged, purgedAsset{AssetID: id, Keys: keys})
		ids = append(ids, id)
	}
	rows.Close()
	if len(ids) == 0 {
		return nil, tx.Commit(ctx)
	}
	if _, err := tx.Exec(ctx, `DELETE FROM assets WHERE id = ANY($1::uuid[])`, ids); err != nil {
		return nil, err
	}
	return purged, tx.Commit(ctx)
}

// ---- multipart upload IDs (server-owned; client addresses by assetId+variant) ----

func (s *store) getMultipartIDs(ctx context.Context, userID, assetID string) (map[string]string, error) {
	var ids map[string]string
	err := s.pool.QueryRow(ctx, `
SELECT COALESCE(multipart_ids, '{}'::jsonb) FROM upload_sessions
WHERE asset_id = $1 AND user_id = $2`, assetID, userID).Scan(&ids)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, errNotFound
	}
	return ids, err
}

func (s *store) setMultipartID(ctx context.Context, userID, assetID, variant, uploadID string) error {
	// NOTE: no join with assets — the asset row only appears at completion. The
	// upload_sessions row itself carries ownership (user_id).
	tag, err := s.pool.Exec(ctx, `
UPDATE upload_sessions SET multipart_ids = multipart_ids || $4::jsonb
WHERE asset_id = $2 AND user_id = $1 AND status = 'pending' AND expires_at > now()
  AND ($3 = 'original' OR $3 = 'preview' OR $3 = 'thumbnail' OR $3 = 'original_motion')`,
		userID, assetID, variant, mustJSON(map[string]string{variant: uploadID}))
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return errExpired // unknown session, committed, or expired
	}
	return nil
}

func (s *store) clearMultipartID(ctx context.Context, userID, assetID, variant string) error {
	_, err := s.pool.Exec(ctx, `
UPDATE upload_sessions SET multipart_ids = multipart_ids - $3
WHERE asset_id = $2 AND user_id = $1`,
		userID, assetID, variant)
	return err
}

func mustJSON(v any) []byte {
	b, err := json.Marshal(v)
	if err != nil {
		return []byte("{}")
	}
	return b
}
