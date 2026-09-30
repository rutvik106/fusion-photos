package main

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
)

// ---- asset catalog ----

type Resource struct {
	Variant        string `json:"variant"`
	ObjectKey      string `json:"objectKey"`
	CiphertextSize int64  `json:"ciphertextSize"`
	PlaintextSHA   string `json:"plaintextSha256"`
}

type AssetRow struct {
	ID          string              `json:"assetId"`
	Seq         int64               `json:"seq"`
	KeyEnvelope string              `json:"keyEnvelope"`
	Manifest    map[string]any      `json:"manifest"`
	Resources   map[string]Resource `json:"resources"`
	Deleted     bool                `json:"deleted"`
	// DeletedAt is when the asset moved to cloud trash (null when not trashed);
	// clients show a purge countdown from trashWindow.
	DeletedAt *time.Time `json:"deletedAt"`
	// Protected is true only when every resource has been verified present in the
	// independent replica (see the replication worker). Clients must NOT treat an asset
	// as safe to remove from a device until this is true.
	Protected bool `json:"protected"`
}

func (s *store) nextSeq(ctx context.Context, tx pgx.Tx, userID string) (int64, error) {
	var seq int64
	err := tx.QueryRow(ctx, `
INSERT INTO user_seqs (user_id, seq) VALUES ($1, 1)
ON CONFLICT (user_id) DO UPDATE SET seq = user_seqs.seq + 1
RETURNING seq`, userID).Scan(&seq)
	return seq, err
}

// commitAssetWithSession inserts the asset + resources and advances quota
// atomically, gated by the owner-bound upload session. It locks the session
// row FOR UPDATE, re-checks pending/unexpired (or returns the existing seq for
// an idempotent committed repeat without recharging quota), then commits the
// asset and flips the session to committed. manifest is stored as opaque JSONB
// (the encrypted manifest envelope); plaintext_sha256 is never persisted for
// new uploads (the legacy nullable column is left at NULL).
func (s *store) commitAssetWithSession(ctx context.Context, userID, assetID, keyEnvelope string, manifest map[string]any, resources []Resource, sess *sessionRow) (int64, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback(ctx)

	var status string
	var expiresAt time.Time
	if err := tx.QueryRow(ctx,
		`SELECT status, expires_at FROM upload_sessions WHERE id = $1 AND user_id = $2 FOR UPDATE`,
		sess.ID, userID).Scan(&status, &expiresAt); err != nil {
		return 0, err
	}

	// Idempotent repeat: a committed session already has the asset row.
	if status == "committed" {
		var seq int64
		if err := tx.QueryRow(ctx, `SELECT seq FROM assets WHERE id = $1 AND user_id = $2`, assetID, userID).Scan(&seq); err != nil {
			return 0, err
		}
		return seq, tx.Commit(ctx)
	}
	if status != "pending" || time.Now().After(expiresAt) {
		return 0, errExpired
	}

	// Guard against a concurrent commit that already inserted the asset.
	var existing int64
	err = tx.QueryRow(ctx, `SELECT seq FROM assets WHERE id = $1 AND user_id = $2`, assetID, userID).Scan(&existing)
	if err == nil {
		if _, err := tx.Exec(ctx, `UPDATE upload_sessions SET status = 'committed' WHERE id = $1`, sess.ID); err != nil {
			return 0, err
		}
		if err := tx.Commit(ctx); err != nil {
			return 0, err
		}
		return existing, nil
	}
	if !errors.Is(err, pgx.ErrNoRows) {
		return 0, err
	}

	seq, err := s.nextSeq(ctx, tx, userID)
	if err != nil {
		return 0, err
	}
	if _, err := tx.Exec(ctx, `
INSERT INTO assets (id, user_id, seq, key_envelope, manifest) VALUES ($1, $2, $3, $4, $5)`,
		assetID, userID, seq, keyEnvelope, manifest); err != nil {
		return 0, err
	}

	for _, r := range resources {
		if _, err := tx.Exec(ctx, `
INSERT INTO asset_resources (asset_id, variant, object_key, ciphertext_size)
VALUES ($1, $2, $3, $4)`,
			assetID, r.Variant, r.ObjectKey, r.CiphertextSize); err != nil {
			return 0, err
		}
	}

	storedDelta, objectDelta := commitQuotaCharge(sess, resources)
	if _, err := tx.Exec(ctx, `
INSERT INTO quota_usage (user_id, stored_bytes, object_count) VALUES ($1, $2, $3)
ON CONFLICT (user_id) DO UPDATE SET
	stored_bytes = quota_usage.stored_bytes + EXCLUDED.stored_bytes,
	object_count = quota_usage.object_count + EXCLUDED.object_count`,
		userID, storedDelta, objectDelta); err != nil {
		return 0, err
	}

	if _, err := tx.Exec(ctx, `UPDATE upload_sessions SET status = 'committed' WHERE id = $1`, sess.ID); err != nil {
		return 0, err
	}

	if err := tx.Commit(ctx); err != nil {
		return 0, err
	}
	return seq, nil
}

// assetColumns is the shared projection for any query returning AssetRow values, so
// the incremental sync and the export-manifest paging stay byte-for-byte consistent.
//
// The resource map is built with EXPLICIT camelCase keys: `jsonb_object_agg(r.variant, r)`
// would serialise the raw columns (object_key, ciphertext_size) and silently deserialise
// into zero values, which is exactly the kind of quiet data loss this project avoids.
const assetColumns = `
a.id, a.seq, a.key_envelope, a.manifest, a.deleted,
a.deleted_at IS NOT NULL, COALESCE(a.deleted_at, 'epoch'::timestamptz),
(a.replication_status = 'replicated'),
COALESCE((SELECT jsonb_object_agg(r.variant, jsonb_build_object(
    'variant', r.variant,
    'objectKey', r.object_key,
    'ciphertextSize', r.ciphertext_size,
    'plaintextSha256', COALESCE(r.plaintext_sha256, '')))
  FROM asset_resources r WHERE r.asset_id = a.id), '{}'::jsonb)`

// scanAssets reads AssetRow values from a query that selected assetColumns.
func scanAssets(rows pgx.Rows) ([]AssetRow, error) {
	var out []AssetRow
	for rows.Next() {
		var a AssetRow
		var resources map[string]Resource
		var hasDeletedAt bool
		var deletedAt time.Time
		if err := rows.Scan(&a.ID, &a.Seq, &a.KeyEnvelope, &a.Manifest, &a.Deleted,
			&hasDeletedAt, &deletedAt, &a.Protected, &resources); err != nil {
			return nil, err
		}
		if hasDeletedAt {
			t := deletedAt
			a.DeletedAt = &t
		}
		a.Resources = resources
		out = append(out, a)
	}
	return out, rows.Err()
}

func (s *store) assetsSince(ctx context.Context, userID string, cursor int64, limit int) ([]AssetRow, int64, error) {
	rows, err := s.pool.Query(ctx, `
SELECT`+assetColumns+`
FROM assets a WHERE a.user_id = $1 AND a.seq > $2 ORDER BY a.seq LIMIT $3`,
		userID, cursor, limit)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	out, err := scanAssets(rows)
	if err != nil {
		return nil, 0, err
	}
	maxSeq := cursor
	for _, a := range out {
		if a.Seq > maxSeq {
			maxSeq = a.Seq
		}
	}
	return out, maxSeq, nil
}

// ---- resource lookup (ownership enforcement point) ----

func (s *store) resourceFor(ctx context.Context, userID, assetID, variant string) (objectKey string, err error) {
	err = s.pool.QueryRow(ctx, `
SELECT r.object_key FROM assets a
JOIN asset_resources r ON r.asset_id = a.id AND r.variant = $3
WHERE a.id = $1 AND a.user_id = $2 AND a.deleted = false`,
		assetID, userID, variant).Scan(&objectKey)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", errUnauthorized // do not reveal existence across tenants
	}
	return objectKey, err
}

func (s *store) resourceKeysFor(ctx context.Context, assetID string) (map[string]string, error) {
	rows, err := s.pool.Query(ctx, `SELECT variant, object_key FROM asset_resources WHERE asset_id = $1`, assetID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	m := map[string]string{}
	for rows.Next() {
		var v, k string
		if err := rows.Scan(&v, &k); err != nil {
			return nil, err
		}
		m[v] = k
	}
	return m, rows.Err()
}

// ---- misc ----

func randomToken(n int) string {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

type quotaError struct{ msg string }

func (e quotaError) Error() string { return e.msg }

var errQuotaExceeded = quotaError{"quota exceeded"}
