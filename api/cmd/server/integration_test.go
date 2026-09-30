package main

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"os"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/minio/minio-go/v7"
)

// DB + S3 paths that unit tests cannot cover, exercised against the compose
// Postgres + MinIO. Skipped unless explicitly enabled so CI needs no infrastructure:
//
//	docker compose up -d db minio
//	cd api && FUSION_INTEGRATION=1 go test -run Integration ./...

func integrationServer(t *testing.T) (*server, Config) {
	t.Helper()
	if os.Getenv("FUSION_INTEGRATION") != "1" {
		t.Skip("set FUSION_INTEGRATION=1 with Postgres+MinIO running to run integration tests")
	}
	ctx := context.Background()
	st, err := newStore(ctx, envOr("DATABASE_URL", "postgres://fusion:fusion@localhost:5432/fusion?sslmode=disable"))
	if err != nil {
		t.Fatalf("store: %v", err)
	}
	t.Cleanup(st.close)
	cfg := Config{
		S3Endpoint:      envOr("S3_ENDPOINT", "localhost:9000"),
		S3AccessKey:     envOr("S3_ACCESS_KEY", "fusion-dev"),
		S3SecretKey:     envOr("S3_SECRET_KEY", "fusion-dev-secret"),
		S3Bucket:        envOr("S3_BUCKET", "fusion-media"),
		S3Region:        "us-east-1",
		S3ReplicaBucket: envOr("S3_REPLICA_BUCKET", "fusion-media-replica"),
		S3ReplicaRegion: "us-east-1",
	}
	media, err := newMediaStore(cfg)
	if err != nil {
		t.Fatalf("media: %v", err)
	}
	if !media.replicaConfigured() {
		t.Fatal("integration test requires a working replica bucket (MinIO)")
	}
	return &server{cfg: cfg, store: st, media: media}, cfg
}

func findTarget(targets []replicaTarget, assetID string) (replicaTarget, bool) {
	for _, t := range targets {
		if t.AssetID == assetID {
			return t, true
		}
	}
	return replicaTarget{}, false
}

// An asset must never be reported as protected until its object is actually present
// in the independent replica — and then (and only then) it flips.
func TestReplicationVerificationIntegration(t *testing.T) {
	srv, cfg := integrationServer(t)
	ctx := context.Background()

	userID, err := srv.store.ensureUser(ctx, fmt.Sprintf("repl+%d@test.local", time.Now().UnixNano()))
	if err != nil {
		t.Fatalf("user: %v", err)
	}
	assetID := uuid.NewString()
	key := objectKey(userID, assetID, "original")
	payload := []byte("ciphertext-sentinel-bytes")

	// Object exists in the SERVING bucket only.
	if _, err := srv.media.internalClient.PutObject(ctx, cfg.S3Bucket, key,
		bytes.NewReader(payload), int64(len(payload)), minio.PutObjectOptions{}); err != nil {
		t.Fatalf("put serving object: %v", err)
	}
	_ = srv.media.replica.RemoveObject(ctx, cfg.S3ReplicaBucket, key, minio.RemoveObjectOptions{})

	// Catalog the asset + its resource directly.
	tx, err := srv.store.pool.Begin(ctx)
	if err != nil {
		t.Fatalf("tx: %v", err)
	}
	seq, err := srv.store.nextSeq(ctx, tx, userID)
	if err != nil {
		t.Fatalf("seq: %v", err)
	}
	if _, err := tx.Exec(ctx,
		`INSERT INTO assets (id, user_id, seq, key_envelope, manifest) VALUES ($1,$2,$3,$4,$5)`,
		assetID, userID, seq, "env-test", map[string]any{}); err != nil {
		t.Fatalf("insert asset: %v", err)
	}
	if _, err := tx.Exec(ctx,
		`INSERT INTO asset_resources (asset_id, variant, object_key, ciphertext_size) VALUES ($1,$2,$3,$4)`,
		assetID, "original", key, len(payload)); err != nil {
		t.Fatalf("insert resource: %v", err)
	}
	if err := tx.Commit(ctx); err != nil {
		t.Fatalf("commit: %v", err)
	}

	targets, err := srv.store.assetsNeedingReplication(ctx, 1000)
	if err != nil {
		t.Fatalf("targets: %v", err)
	}
	target, ok := findTarget(targets, assetID)
	if !ok {
		t.Fatalf("asset %s missing from replication targets", assetID)
	}

	// Not mirrored yet -> must stay unprotected.
	protected, err := srv.verifyAssetReplication(ctx, target)
	if err != nil {
		t.Fatalf("verify (unmirrored): %v", err)
	}
	if protected {
		t.Fatal("asset must NOT be protected before the replica holds its resources")
	}

	// Mirror the object into the replica (stand-in for S3 Replication in dev).
	if _, err := srv.media.replica.CopyObject(ctx,
		minio.CopyDestOptions{Bucket: cfg.S3ReplicaBucket, Object: key},
		minio.CopySrcOptions{Bucket: cfg.S3Bucket, Object: key}); err != nil {
		t.Fatalf("copy to replica: %v", err)
	}

	protected, err = srv.verifyAssetReplication(ctx, target)
	if err != nil {
		t.Fatalf("verify (mirrored): %v", err)
	}
	if !protected {
		t.Fatal("asset must be protected once its resource is verified in the replica")
	}

	// The sync payload must now advertise protected=true.
	rows, _, err := srv.store.assetsSince(ctx, userID, seq-1, 10)
	if err != nil {
		t.Fatalf("sync: %v", err)
	}
	if len(rows) == 0 || !rows[0].Protected {
		t.Fatalf("sync payload must report protected=true, got %+v", rows)
	}

	st, err := srv.store.backupStatus(ctx, userID)
	if err != nil {
		t.Fatalf("status: %v", err)
	}
	if st.ProtectedAssets < 1 || st.TotalAssets < 1 {
		t.Fatalf("backup status protected=%d total=%d want >=1", st.ProtectedAssets, st.TotalAssets)
	}
}

// Abandoned upload reservations must be moved out of 'pending' so the reconciler can
// release them, and a completion attempt against one must fail closed.
func TestExpireStaleUploadSessionsIntegration(t *testing.T) {
	srv, _ := integrationServer(t)
	ctx := context.Background()

	userID, err := srv.store.ensureUser(ctx, fmt.Sprintf("stale+%d@test.local", time.Now().UnixNano()))
	if err != nil {
		t.Fatalf("user: %v", err)
	}
	assetID := uuid.NewString()
	sessionID := uuid.NewString()
	if _, err := srv.store.pool.Exec(ctx, `
INSERT INTO upload_sessions (id, user_id, asset_id, client_ref, expected_sizes, reserved_bytes, expires_at, status)
VALUES ($1, $2, $3, 'stale-ref', '{}'::jsonb, 10, now() - interval '1 hour', 'pending')`,
		sessionID, userID, assetID); err != nil {
		t.Fatalf("insert session: %v", err)
	}

	stale, err := srv.store.expireStaleUploadSessions(ctx, time.Now())
	if err != nil {
		t.Fatalf("expire: %v", err)
	}
	found := false
	for _, s := range stale {
		if s.SessionID == sessionID {
			found = true
		}
	}
	if !found {
		t.Fatal("expired session was not returned for multipart cleanup")
	}

	var status string
	if err := srv.store.pool.QueryRow(ctx,
		`SELECT status FROM upload_sessions WHERE id = $1`, sessionID).Scan(&status); err != nil {
		t.Fatalf("read status: %v", err)
	}
	if status != "expired" {
		t.Fatalf("status=%q want expired", status)
	}
	if _, err := srv.store.getSessionForCompletion(ctx, assetID, userID); !errors.Is(err, errExpired) {
		t.Fatalf("completion against an expired session must fail closed, got %v", err)
	}
}

// An export snapshot is immutable: an asset captured in it stays exportable even after
// it is trashed, while normal resource access (correctly) stops working — and no other
// account can read it.
func TestExportSnapshotIntegration(t *testing.T) {
	srv, _ := integrationServer(t)
	ctx := context.Background()

	userID, err := srv.store.ensureUser(ctx, fmt.Sprintf("export+%d@test.local", time.Now().UnixNano()))
	if err != nil {
		t.Fatalf("user: %v", err)
	}
	assetID := uuid.NewString()

	tx, err := srv.store.pool.Begin(ctx)
	if err != nil {
		t.Fatalf("tx: %v", err)
	}
	seq, err := srv.store.nextSeq(ctx, tx, userID)
	if err != nil {
		t.Fatalf("seq: %v", err)
	}
	if _, err := tx.Exec(ctx,
		`INSERT INTO assets (id, user_id, seq, key_envelope, manifest) VALUES ($1,$2,$3,$4,$5)`,
		assetID, userID, seq, "env", map[string]any{}); err != nil {
		t.Fatalf("asset: %v", err)
	}
	if _, err := tx.Exec(ctx,
		`INSERT INTO asset_resources (asset_id, variant, object_key, ciphertext_size) VALUES ($1,$2,$3,$4)`,
		assetID, "original", objectKey(userID, assetID, "original"), 42); err != nil {
		t.Fatalf("resource: %v", err)
	}
	if err := tx.Commit(ctx); err != nil {
		t.Fatalf("commit: %v", err)
	}

	snap, err := srv.store.createExportSnapshot(ctx, userID, time.Hour)
	if err != nil {
		t.Fatalf("snapshot: %v", err)
	}
	if snap.AssetCount != 1 {
		t.Fatalf("assetCount=%d want 1", snap.AssetCount)
	}
	if snap.ByteTotal != 42 {
		t.Fatalf("byteTotal=%d want 42", snap.ByteTotal)
	}

	page, next, err := srv.store.exportPage(ctx, userID, snap.ID, 0, 100)
	if err != nil {
		t.Fatalf("page: %v", err)
	}
	if len(page) != 1 || page[0].ID != assetID {
		t.Fatalf("export page mismatch: %+v", page)
	}
	if next != snap.MaxSeq {
		t.Fatalf("next cursor=%d want %d", next, snap.MaxSeq)
	}
	if _, err := srv.store.resourceForSnapshot(ctx, userID, snap.ID, assetID, "original"); err != nil {
		t.Fatalf("snapshot resource lookup failed: %v", err)
	}

	// Trash it: normal access stops, the snapshot stays exportable.
	if _, err := srv.store.trashAsset(ctx, userID, assetID); err != nil {
		t.Fatalf("trash: %v", err)
	}
	if _, err := srv.store.resourceFor(ctx, userID, assetID, "original"); !errors.Is(err, errUnauthorized) {
		t.Fatalf("normal access after trash must be denied, got %v", err)
	}
	if _, err := srv.store.resourceForSnapshot(ctx, userID, snap.ID, assetID, "original"); err != nil {
		t.Fatalf("snapshot must survive trashing (immutability), got %v", err)
	}

	// Tenant isolation.
	other, err := srv.store.ensureUser(ctx, fmt.Sprintf("exportother+%d@test.local", time.Now().UnixNano()))
	if err != nil {
		t.Fatalf("other user: %v", err)
	}
	if _, err := srv.store.getExportSnapshot(ctx, other, snap.ID); !errors.Is(err, errNotFound) {
		t.Fatalf("cross-tenant snapshot read must fail, got %v", err)
	}
	if _, err := srv.store.resourceForSnapshot(ctx, other, snap.ID, assetID, "original"); !errors.Is(err, errUnauthorized) {
		t.Fatalf("cross-tenant snapshot resource must fail, got %v", err)
	}
}
