package main

import (
	"context"
	"errors"
	"fmt"
	"log"
	"net/http"
	"net/url"
	"strconv"
	"time"

	"github.com/minio/minio-go/v7"
	"github.com/minio/minio-go/v7/pkg/credentials"
)

// errReplicaUnconfigured means no independent replica is wired up, so the service
// must not report any asset as remotely protected.
var errReplicaUnconfigured = errors.New("no replica bucket configured")

// mediaStore issues narrowly scoped presigned access to the private bucket.
// Object keys are always chosen by the server: {userPrefix}/{assetId}/{variant} —
// a client never selects an arbitrary key (per plan).
type mediaStore struct {
	// internalClient is used for server-side operations (stat/verify) and speaks to
	// MinIO/S3 over the private network.
	internalClient *minio.Client
	// publicClient signs URLs against the endpoint that CLIENTS can reach (identical
	// credentials; SigV4 is validated against whatever Host MinIO actually receives).
	publicClient *minio.Client
	bucket       string
	// replica is the independent second copy (a cross-region / cross-account bucket
	// in production). Nil when replication is not configured, in which case no asset
	// may be reported as protected.
	replica       *minio.Client
	replicaBucket string
}

func newMinioClient(cfg Config, endpoint string) (*minio.Client, error) {
	return minio.New(endpoint, &minio.Options{
		Creds:  credentials.NewStaticV4(cfg.S3AccessKey, cfg.S3SecretKey, ""),
		Secure: cfg.S3UseSSL,
		Region: cfg.S3Region,
	})
}

func newMediaStore(cfg Config) (*mediaStore, error) {
	internalClient, err := newMinioClient(cfg, cfg.S3Endpoint)
	if err != nil {
		return nil, err
	}
	publicEndpoint := cfg.S3Endpoint
	if cfg.S3PublicEndpoint != "" {
		publicEndpoint = cfg.S3PublicEndpoint
	}
	publicClient, err := newMinioClient(cfg, publicEndpoint)
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := internalClient.MakeBucket(ctx, cfg.S3Bucket, minio.MakeBucketOptions{}); err != nil {
		exists, _ := internalClient.BucketExists(ctx, cfg.S3Bucket)
		if !exists {
			return nil, fmt.Errorf("ensure bucket: %w", err)
		}
	}
	m := &mediaStore{internalClient: internalClient, publicClient: publicClient, bucket: cfg.S3Bucket}

	// Optional independent replica (a cross-region / cross-account bucket in prod).
	if cfg.S3ReplicaBucket != "" {
		endpoint := cfg.S3ReplicaEndpoint
		if endpoint == "" {
			endpoint = cfg.S3Endpoint
		}
		access, secret := cfg.S3ReplicaAccessKey, cfg.S3ReplicaSecretKey
		if access == "" {
			access = cfg.S3AccessKey
		}
		if secret == "" {
			secret = cfg.S3SecretKey
		}
		replicaClient, err := minio.New(endpoint, &minio.Options{
			Creds:  credentials.NewStaticV4(access, secret, ""),
			Secure: cfg.S3UseSSL,
			Region: cfg.S3ReplicaRegion,
		})
		if err != nil {
			return nil, fmt.Errorf("replica client: %w", err)
		}
		// Dev convenience: create the bucket if absent. A replica that is unreachable
		// or not writable must NOT take down the serving path — log and degrade to "no
		// replica", so the service simply never claims remote protection.
		if err := replicaClient.MakeBucket(ctx, cfg.S3ReplicaBucket, minio.MakeBucketOptions{Region: cfg.S3ReplicaRegion}); err != nil {
			if exists, _ := replicaClient.BucketExists(ctx, cfg.S3ReplicaBucket); !exists {
				log.Printf("replica: bucket %q unavailable (%v) — replication disabled; no asset will be reported as protected", cfg.S3ReplicaBucket, err)
			} else {
				m.replica = replicaClient
				m.replicaBucket = cfg.S3ReplicaBucket
			}
		} else {
			m.replica = replicaClient
			m.replicaBucket = cfg.S3ReplicaBucket
		}
	}
	return m, nil
}

const signedURLTTL = 15 * time.Minute

func objectKey(userID, assetID, variant string) string {
	// userID prefix keeps owner data under a server-controlled prefix; the assetID is
	// a server-generated UUID. Filenames and capture dates never appear in keys.
	return fmt.Sprintf("%s/%s/%s", userID, assetID, variant)
}

func (m *mediaStore) presignPut(key string) (string, error) {
	u, err := m.publicClient.PresignedPutObject(context.Background(), m.bucket, key, signedURLTTL)
	if err != nil {
		return "", err
	}
	return u.String(), nil
}

func (m *mediaStore) presignGet(key string) (string, error) {
	u, err := m.publicClient.PresignedGetObject(context.Background(), m.bucket, key, signedURLTTL, url.Values{})
	if err != nil {
		return "", err
	}
	return u.String(), nil
}

// statObject verifies an upload actually landed with the expected size before the
// catalog commit (server-side verification step of the backup workflow).
func (m *mediaStore) statObject(ctx context.Context, key string) (int64, error) {
	info, err := m.internalClient.StatObject(ctx, m.bucket, key, minio.StatObjectOptions{})
	if err != nil {
		return 0, err
	}
	return info.Size, nil
}

// ---- multipart upload management ----
//
// Large resources are uploaded as independent parts directly from the client via
// presigned part URLs; the server owns the multipart upload ID end-to-end (the
// client never sees it — it addresses uploads by assetId+variant).

func (m *mediaStore) core() minio.Core { return minio.Core{Client: m.internalClient} }

// createUploadID initiates a multipart upload for one resource object.
func (m *mediaStore) createUploadID(ctx context.Context, key string) (string, error) {
	return m.core().NewMultipartUpload(ctx, m.bucket, key, minio.PutObjectOptions{})
}

// presignPartPut signs a single UploadPart request. uploadId/partNumber are part
// of the signed canonical request, so a leaked URL is bound to exactly one part.
func (m *mediaStore) presignPartPut(ctx context.Context, key, uploadID string, partNumber int) (string, error) {
	u, err := m.publicClient.Presign(ctx, http.MethodPut, m.bucket, key, signedURLTTL, url.Values{
		"uploadId":   []string{uploadID},
		"partNumber": []string{strconv.Itoa(partNumber)},
	})
	if err != nil {
		return "", err
	}
	return u.String(), nil
}

// listUploadParts returns all uploaded parts (number, etag, size) for resume
// reconciliation — the plan's "reconcile with S3 before resuming".
func (m *mediaStore) listUploadParts(ctx context.Context, key, uploadID string) ([]minio.ObjectPart, error) {
	var out []minio.ObjectPart
	marker := 0
	for {
		res, err := m.core().ListObjectParts(ctx, m.bucket, key, uploadID, marker, 1000)
		if err != nil {
			return nil, err
		}
		out = append(out, res.ObjectParts...)
		if !res.IsTruncated {
			return out, nil
		}
		marker = res.NextPartNumberMarker
	}
}

// completeMultipart commits the parts; returns the final object size for
// verification against the reserved ciphertext size. The complete-response size
// is unreliable on some backends (S3 omits it), so the assembled object is
// stat'ed — the same source of truth as the single-PUT path.
func (m *mediaStore) completeMultipart(ctx context.Context, key, uploadID string, parts []minio.CompletePart) (int64, error) {
	if _, err := m.core().CompleteMultipartUpload(ctx, m.bucket, key, uploadID, parts, minio.PutObjectOptions{}); err != nil {
		return 0, err
	}
	return m.statObject(ctx, key)
}

func (m *mediaStore) abortMultipart(ctx context.Context, key, uploadID string) error {
	return m.core().AbortMultipartUpload(ctx, m.bucket, key, uploadID)
}

// deleteObject removes one object (garbage collection / trash purge). The
// application role needs s3:DeleteObject for this; callers log and continue on
// failure (purge re-runs).
func (m *mediaStore) deleteObject(ctx context.Context, key string) error {
	return m.internalClient.RemoveObject(ctx, m.bucket, key, minio.RemoveObjectOptions{})
}

// ---- independent replica verification ----
//
// "Protected remotely" is only ever claimed after every resource of an asset has been
// observed in the independent replica bucket with the expected ciphertext size. The
// serving bucket alone can never justify that claim.

func (m *mediaStore) replicaConfigured() bool {
	return m.replica != nil && m.replicaBucket != ""
}

// verifyReplica confirms one object exists in the replica bucket at the expected size.
func (m *mediaStore) verifyReplica(ctx context.Context, key string, wantSize int64) error {
	if !m.replicaConfigured() {
		return errReplicaUnconfigured
	}
	info, err := m.replica.StatObject(ctx, m.replicaBucket, key, minio.StatObjectOptions{})
	if err != nil {
		return err
	}
	if info.Size != wantSize {
		return fmt.Errorf("replica size mismatch for %s: got %d want %d", key, info.Size, wantSize)
	}
	return nil
}
