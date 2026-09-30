package main

import (
	"context"
	"errors"
	"testing"
)

func TestRemoteProtected(t *testing.T) {
	cases := []struct {
		verified, total int
		want            bool
	}{
		{0, 0, false}, // no resources: nothing could be recovered
		{1, 1, true},
		{3, 3, true}, // original + preview + thumbnail + motion, all present
		{2, 3, false},
		{0, 1, false},
	}
	for _, c := range cases {
		if got := remoteProtected(c.verified, c.total); got != c.want {
			t.Errorf("remoteProtected(%d,%d)=%v want %v", c.verified, c.total, got, c.want)
		}
	}
}

// A service with no replica configured must refuse to verify (and therefore must never
// mark anything protected).
func TestVerifyReplicaUnconfigured(t *testing.T) {
	m := &mediaStore{}
	if m.replicaConfigured() {
		t.Fatal("a zero-value mediaStore must not report a configured replica")
	}
	if err := m.verifyReplica(context.Background(), "user/asset/original", 1); !errors.Is(err, errReplicaUnconfigured) {
		t.Fatalf("want errReplicaUnconfigured, got %v", err)
	}
}
