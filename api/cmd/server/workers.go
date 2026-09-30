package main

import (
	"context"
	"log"
	"time"
)

// replicationBatch bounds how many assets one tick verifies, so one pass cannot scan
// an unbounded library or hammer the replica endpoint.
const replicationBatch = 200

// remoteProtected reports whether every resource of an asset was confirmed present in
// the replica. An asset with zero resources can never be protected — there would be
// nothing to recover.
func remoteProtected(verified, total int) bool {
	return total > 0 && verified == total
}

// verifyAssetReplication checks every resource of one asset in the replica bucket and
// records the outcome (per-resource evidence + asset status). It returns true when the
// asset is now fully protected. Extracted from the worker loop so it is directly
// exercisable by the DB+S3 integration test.
func (s *server) verifyAssetReplication(ctx context.Context, t replicaTarget) (bool, error) {
	verified := make(map[string]int64, len(t.Resources))
	for variant, size := range t.Resources {
		if err := s.media.verifyReplica(ctx, objectKey(t.UserID, t.AssetID, variant), size); err != nil {
			break // partial pass: keep the evidence so far, retry next tick
		}
		verified[variant] = size
	}
	complete := remoteProtected(len(verified), len(t.Resources))
	if err := s.store.recordReplication(ctx, t.AssetID, verified, complete); err != nil {
		return false, err
	}
	return complete, nil
}

// ---- scheduled reconciliation ----
//
// Expires abandoned upload reservations and aborts their orphaned multipart uploads so
// incomplete uploads stop accruing storage charges (plan § backup workflow: set an
// incomplete-upload expiry, reconcile with the backend). Expired pending rows were
// already ignored by the quota ledger; this moves them out of 'pending' and reclaims
// the S3 parts.

func (s *server) startReconcileWorker(ctx context.Context) {
	log.Printf("reconcile: running every %s", s.cfg.ReconcileInterval)
	go func() {
		ticker := time.NewTicker(s.cfg.ReconcileInterval)
		defer ticker.Stop()
		run := func() {
			if err := s.store.pruneExpiredAuth(ctx, time.Now()); err != nil {
				log.Printf("reconcile: auth cleanup failed: %v", err)
			}
			if n, err := s.store.releaseExpiredExportSnapshots(ctx, time.Now()); err != nil {
				log.Printf("reconcile: export snapshot cleanup failed: %v", err)
			} else if n > 0 {
				log.Printf("reconcile: released %d expired export snapshot(s)", n)
			}
			stale, err := s.store.expireStaleUploadSessions(ctx, time.Now())
			if err != nil {
				log.Printf("reconcile: expire sessions failed: %v", err)
				return
			}
			aborted := 0
			for _, st := range stale {
				for variant, uploadID := range st.MultipartIDs {
					if uploadID == "" {
						continue
					}
					key := objectKey(st.UserID, st.AssetID, variant)
					if err := s.media.abortMultipart(ctx, key, uploadID); err != nil {
						log.Printf("reconcile: abort multipart failed for %s: %v", key, err)
						continue
					}
					aborted++
				}
			}
			if len(stale) > 0 {
				log.Printf("reconcile: expired %d stale upload session(s), aborted %d multipart upload(s)", len(stale), aborted)
			}
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

// ---- replication verification ----
//
// This is what makes "Protected remotely" honest: an asset is marked replicated only
// after every one of its resources has been observed in the independent replica bucket
// at the expected ciphertext size. With no replica configured nothing is ever marked
// protected, and the UI must say so.

func (s *server) startReplicationWorker(ctx context.Context) {
	if !s.media.replicaConfigured() {
		log.Printf("replication: no replica bucket configured — no asset will be reported as protected")
		return
	}
	log.Printf("replication: verifying against replica bucket %q every %s", s.media.replicaBucket, s.cfg.ReplicationInterval)
	go func() {
		ticker := time.NewTicker(s.cfg.ReplicationInterval)
		defer ticker.Stop()
		run := func() {
			targets, err := s.store.assetsNeedingReplication(ctx, replicationBatch)
			if err != nil {
				log.Printf("replication: list failed: %v", err)
				return
			}
			protected := 0
			for _, t := range targets {
				complete, err := s.verifyAssetReplication(ctx, t)
				if err != nil {
					log.Printf("replication: record failed for %s: %v", t.AssetID, err)
					continue
				}
				if complete {
					protected++
				}
			}
			if len(targets) > 0 {
				log.Printf("replication: checked %d asset(s), %d now protected", len(targets), protected)
			}
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
