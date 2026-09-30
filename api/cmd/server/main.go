// Package main: Fusion Photos API (control plane only).
//
// Media never flows through this server: clients PUT/GET ciphertext directly to/from
// S3 (MinIO in dev) using short-lived presigned URLs issued here after an ownership
// check. The server sees only opaque ciphertext lengths, object keys it chose itself,
// and encrypted blobs.
package main

import (
	"context"
	"log"
	"net/http"
	"os"
	"strconv"
	"time"
)

type Config struct {
	Addr        string
	DatabaseURL string
	S3Endpoint  string
	// S3PublicEndpoint is the S3 hostname CLIENTS reach for presigned transfers
	// (e.g. localhost:9000 in dev compose); S3Endpoint is the server-internal one.
	S3PublicEndpoint string
	S3AccessKey      string
	S3SecretKey      string
	S3Bucket         string
	S3UseSSL         bool
	S3Region         string
	DevMode          bool
	QuotaBytes       int64
	SessionExpiry    time.Duration
	// Auth / OTP. The lifecycle default and pure semantics live in otp.go; these are
	// the env-tunable deployment knobs.
	OTPTTL            time.Duration
	OTPMaxAttempts    int
	OTPPerEmailLimit  int
	OTPPerEmailWindow time.Duration
	OTPPerIPLimit     int
	OTPPerIPWindow    time.Duration
	OTPPepper         string
	SESEnabled        bool
	SESRegion         string
	SESFrom           string
	// Independent replica (cross-region / cross-account in production). When
	// S3ReplicaBucket is empty the service makes no remote-protection claim.
	S3ReplicaBucket    string
	S3ReplicaEndpoint  string
	S3ReplicaAccessKey string
	S3ReplicaSecretKey string
	S3ReplicaRegion    string
	// Scheduled workers.
	ReplicationInterval time.Duration
	ReconcileInterval   time.Duration
}

func loadConfig() Config {
	return Config{
		Addr:             envOr("ADDR", ":8080"),
		DatabaseURL:      envOr("DATABASE_URL", "postgres://fusion:fusion@localhost:5432/fusion?sslmode=disable"),
		S3Endpoint:       envOr("S3_ENDPOINT", "localhost:9000"),
		S3PublicEndpoint: envOr("S3_PUBLIC_ENDPOINT", ""),
		S3AccessKey:      envOr("S3_ACCESS_KEY", "fusion-dev"),
		S3SecretKey:      envOr("S3_SECRET_KEY", "fusion-dev-secret"),
		S3Bucket:         envOr("S3_BUCKET", "fusion-media"),
		S3UseSSL:         envOr("S3_USE_SSL", "false") == "true",
		S3Region:         envOr("S3_REGION", "us-east-1"),
		DevMode:          envOr("DEV_MODE", "true") == "true",
		QuotaBytes:       envInt64("QUOTA_BYTES", 2*1024*1024*1024), // 2 GiB dev quota
		SessionExpiry:    30 * 24 * time.Hour,

		OTPTTL:            envDuration("OTP_TTL", defaultOTPTTL),
		OTPMaxAttempts:    envInt("OTP_MAX_ATTEMPTS", defaultOTPMaxAttempts),
		OTPPerEmailLimit:  envInt("OTP_PER_EMAIL_LIMIT", defaultOTPPerEmailLimit),
		OTPPerEmailWindow: envDuration("OTP_PER_EMAIL_WINDOW", defaultOTPPerEmailWindow),
		OTPPerIPLimit:     envInt("OTP_PER_IP_LIMIT", defaultOTPPerIPLimit),
		OTPPerIPWindow:    envDuration("OTP_PER_IP_WINDOW", defaultOTPPerIPWindow),
		// OTP_PEPPER keys the HMAC that hashes codes at rest. Leave empty in dev
		// (an ephemeral pepper is generated at boot); ALWAYS set it in production so
		// codes survive a restart and are useless if the database leaks.
		OTPPepper:  os.Getenv("OTP_PEPPER"),
		SESEnabled: envOr("SES_ENABLED", "false") == "true",
		SESRegion:  envOr("SES_REGION", "us-east-1"),
		SESFrom:    os.Getenv("SES_FROM"),

		// Replica credentials default to the primary ones (dev / same-account); set
		// the S3_REPLICA_* vars explicitly for a cross-account disaster copy.
		S3ReplicaBucket:    envOr("S3_REPLICA_BUCKET", ""),
		S3ReplicaEndpoint:  envOr("S3_REPLICA_ENDPOINT", ""),
		S3ReplicaAccessKey: os.Getenv("S3_REPLICA_ACCESS_KEY"),
		S3ReplicaSecretKey: os.Getenv("S3_REPLICA_SECRET_KEY"),
		S3ReplicaRegion:    envOr("S3_REPLICA_REGION", envOr("S3_REGION", "us-east-1")),

		ReplicationInterval: envDuration("REPLICATION_INTERVAL", 15*time.Minute),
		ReconcileInterval:   envDuration("RECONCILE_INTERVAL", time.Hour),
	}
}

func envInt(key string, def int) int {
	if v := os.Getenv(key); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			return n
		}
	}
	return def
}

func envDuration(key string, def time.Duration) time.Duration {
	if v := os.Getenv(key); v != "" {
		if d, err := time.ParseDuration(v); err == nil {
			return d
		}
	}
	return def
}

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func envInt64(key string, def int64) int64 {
	if v := os.Getenv(key); v != "" {
		if n, err := strconv.ParseInt(v, 10, 64); err == nil {
			return n
		}
	}
	return def
}

func main() {
	cfg := loadConfig()
	ctx := context.Background()

	store, err := newStore(ctx, cfg.DatabaseURL)
	if err != nil {
		log.Fatalf("store: %v", err)
	}
	defer store.close()

	s3, err := newMediaStore(cfg)
	if err != nil {
		log.Fatalf("s3: %v", err)
	}

	// OTP delivery: SES when configured, otherwise a logging no-op (dev).
	mail := mailer(logMailer{})
	if cfg.SESEnabled {
		m, err := newSESMailer(ctx, cfg.SESRegion, cfg.SESFrom)
		if err != nil {
			log.Fatalf("ses: %v", err)
		}
		mail = m
		log.Printf("otp: SES enabled (region=%s from=%s)", cfg.SESRegion, cfg.SESFrom)
	}

	// OTP code-hash pepper. A random ephemeral pepper is fine in dev; production
	// must set OTP_PEPPER so pending codes survive restarts.
	pepper := cfg.OTPPepper
	if pepper == "" {
		pepper = randomToken(32)
		if !cfg.DevMode {
			log.Printf("warning: OTP_PEPPER unset — using an ephemeral pepper; pending codes will not survive a restart")
		}
	}

	srv := &server{
		cfg:    cfg,
		store:  store,
		media:  s3,
		mail:   mail,
		pepper: pepper,
	}

	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", srv.handleHealth)
	mux.HandleFunc("GET /v1/me", srv.authed(srv.handleMe))
	mux.HandleFunc("POST /v1/auth/otp/request", srv.handleOtpRequest)
	mux.HandleFunc("POST /v1/auth/otp/verify", srv.handleOtpVerify)
	mux.HandleFunc("GET /v1/vault", srv.authed(srv.handleVaultGet))
	mux.HandleFunc("PUT /v1/vault", srv.authed(srv.handleVaultPut))
	mux.HandleFunc("POST /v1/upload-session", srv.authed(srv.handleUploadSession))
	mux.HandleFunc("POST /v1/upload-session/complete", srv.authed(srv.handleUploadComplete))
	mux.HandleFunc("POST /v1/upload-session/{assetId}/multipart/init", srv.authed(srv.handleMultipartInit))
	mux.HandleFunc("POST /v1/upload-session/{assetId}/multipart/status", srv.authed(srv.handleMultipartStatus))
	mux.HandleFunc("POST /v1/upload-session/{assetId}/multipart/parts", srv.authed(srv.handleMultipartParts))
	mux.HandleFunc("POST /v1/upload-session/{assetId}/put-urls", srv.authed(srv.handlePutURLs))
	mux.HandleFunc("POST /v1/upload-session/{assetId}/multipart/complete", srv.authed(srv.handleMultipartComplete))
	mux.HandleFunc("POST /v1/upload-session/{assetId}/multipart/abort", srv.authed(srv.handleMultipartAbort))
	mux.HandleFunc("GET /v1/sync/changes", srv.authed(srv.handleSync))
	mux.HandleFunc("GET /v1/backup/status", srv.authed(srv.handleBackupStatus))
	// Export snapshots (immutable, resumable, client-side export).
	mux.HandleFunc("POST /v1/export/snapshot", srv.authed(srv.handleExportCreate))
	mux.HandleFunc("GET /v1/export/snapshots", srv.authed(srv.handleExportList))
	mux.HandleFunc("GET /v1/export/snapshot/{id}", srv.authed(srv.handleExportGet))
	mux.HandleFunc("GET /v1/export/snapshot/{id}/assets", srv.authed(srv.handleExportAssets))
	mux.HandleFunc("GET /v1/resource/{assetId}/{variant}", srv.authed(srv.handleResource))
	mux.HandleFunc("POST /v1/assets/{assetId}/trash", srv.authed(srv.handleAssetTrash))
	mux.HandleFunc("POST /v1/assets/{assetId}/restore", srv.authed(srv.handleAssetRestore))

	// Scheduled lifecycle jobs (modular monolith: no separate worker fleet yet).
	srv.startPurgeWorker(ctx)
	srv.startReconcileWorker(ctx)
	srv.startReplicationWorker(ctx)

	log.Printf("Fusion Photos API listening on %s (dev=%v, s3=%s/%s)", cfg.Addr, cfg.DevMode, cfg.S3Endpoint, cfg.S3Bucket)
	log.Fatal(http.ListenAndServe(cfg.Addr, securityHeaders(mux)))
}

// securityHeaders applies a conservative baseline to every API response.
func securityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := w.Header()
		h.Set("X-Content-Type-Options", "nosniff")
		h.Set("X-Frame-Options", "DENY")
		h.Set("Referrer-Policy", "no-referrer")
		h.Set("Cache-Control", "no-store")
		next.ServeHTTP(w, r)
	})
}
