package main

import (
	"errors"
	"math"
	"strings"
	"testing"
	"time"
)

func validItem() uploadItemReq {
	return uploadItemReq{
		OriginalSize:  1000,
		AssetIDClient: "ref-1",
		EncryptedSizes: map[string]int64{
			"original":  1100,
			"preview":   0,
			"thumbnail": 0,
			"manifest":  64,
		},
	}
}

func TestValidateUploadItemOriginalOnly(t *testing.T) {
	reserved, err := validateUploadItem(validItem())
	if err != nil {
		t.Fatalf("expected ok, got %v", err)
	}
	if reserved != 1100+64 {
		t.Fatalf("reserved=%d want %d", reserved, 1100+64)
	}
}

func TestValidateUploadItemWithPreviewThumbnail(t *testing.T) {
	it := validItem()
	it.EncryptedSizes["preview"] = 200
	it.EncryptedSizes["thumbnail"] = 50
	reserved, err := validateUploadItem(it)
	if err != nil {
		t.Fatalf("expected ok, got %v", err)
	}
	want := int64(1100 + 200 + 50 + 64)
	if reserved != want {
		t.Fatalf("reserved=%d want %d", reserved, want)
	}
}

func TestValidateUploadItemMissingOriginal(t *testing.T) {
	it := validItem()
	it.EncryptedSizes["original"] = 0
	if _, err := validateUploadItem(it); !errors.Is(err, errNoOriginal) {
		t.Fatalf("want errNoOriginal, got %v", err)
	}
}

func TestValidateUploadItemMissingManifest(t *testing.T) {
	it := validItem()
	it.EncryptedSizes["manifest"] = 0
	if _, err := validateUploadItem(it); !errors.Is(err, errNoManifest) {
		t.Fatalf("want errNoManifest, got %v", err)
	}
}

func TestValidateUploadItemUnknownVariant(t *testing.T) {
	it := validItem()
	it.EncryptedSizes["raw"] = 10
	if _, err := validateUploadItem(it); !errors.Is(err, errBadVariant) {
		t.Fatalf("want errBadVariant, got %v", err)
	}
}

func TestValidateUploadItemMotionCompanion(t *testing.T) {
	it := validItem()
	it.EncryptedSizes["original_motion"] = 500
	reserved, err := validateUploadItem(it)
	if err != nil {
		t.Fatalf("motion companion must be accepted, got %v", err)
	}
	if want := int64(1100 + 500 + 64); reserved != want {
		t.Fatalf("reserved=%d want %d (companion is charged)", reserved, want)
	}
}

func TestValidateUploadItemMotionRequiresOriginal(t *testing.T) {
	it := validItem()
	it.EncryptedSizes["original"] = 0
	it.EncryptedSizes["original_motion"] = 500
	// missing original is the first rule that fires
	if _, err := validateUploadItem(it); !errors.Is(err, errNoOriginal) {
		t.Fatalf("want errNoOriginal, got %v", err)
	}
}

func TestValidateUploadItemClientRefTooLong(t *testing.T) {
	it := validItem()
	it.AssetIDClient = strings.Repeat("a", maxClientRefLen+1)
	if _, err := validateUploadItem(it); !errors.Is(err, errBadClientRef) {
		t.Fatalf("want errBadClientRef, got %v", err)
	}
	it.AssetIDClient = strings.Repeat("a", maxClientRefLen)
	if _, err := validateUploadItem(it); err != nil {
		t.Fatalf("max-length ref must be accepted, got %v", err)
	}
}

func TestValidateUploadItemNegativeSize(t *testing.T) {
	it := validItem()
	it.EncryptedSizes["preview"] = -5
	if _, err := validateUploadItem(it); !errors.Is(err, errNegativeSize) {
		t.Fatalf("want errNegativeSize, got %v", err)
	}
}

func TestValidateUploadItemNonPositiveOriginalSize(t *testing.T) {
	it := validItem()
	it.OriginalSize = 0
	if _, err := validateUploadItem(it); !errors.Is(err, errNonPositiveOrig) {
		t.Fatalf("want errNonPositiveOrig, got %v", err)
	}
}

func TestValidateUploadItemMissingAssetRef(t *testing.T) {
	it := validItem()
	it.AssetIDClient = ""
	if _, err := validateUploadItem(it); !errors.Is(err, errMissingAssetRef) {
		t.Fatalf("want errMissingAssetRef, got %v", err)
	}
}

func TestValidateUploadItemOverflow(t *testing.T) {
	it := validItem()
	it.EncryptedSizes["original"] = 1 << 62
	it.EncryptedSizes["preview"] = 1 << 62
	if _, err := validateUploadItem(it); !errors.Is(err, errSizeOverflow) {
		t.Fatalf("want errSizeOverflow, got %v", err)
	}
}

func TestValidateUploadItemsCount(t *testing.T) {
	if _, err := validateUploadItems(nil, 1<<40); !errors.Is(err, errTooManyItems) {
		t.Fatalf("empty want errTooManyItems, got %v", err)
	}
	tooMany := make([]uploadItemReq, 51)
	for i := range tooMany {
		tooMany[i] = validItem()
		tooMany[i].AssetIDClient = "ref"
	}
	if _, err := validateUploadItems(tooMany, 1<<40); !errors.Is(err, errTooManyItems) {
		t.Fatalf("51 want errTooManyItems, got %v", err)
	}
}

func TestValidateUploadItemsDuplicateRef(t *testing.T) {
	a, b := validItem(), validItem()
	a.AssetIDClient = "dup"
	b.AssetIDClient = "dup"
	if _, err := validateUploadItems([]uploadItemReq{a, b}, 1<<40); !errors.Is(err, errDuplicateRef) {
		t.Fatalf("want errDuplicateRef, got %v", err)
	}
}

func TestValidateUploadItemsQuota(t *testing.T) {
	it := validItem()
	if _, err := validateUploadItems([]uploadItemReq{it}, 100); !errors.Is(err, errQuotaExceeded) {
		t.Fatalf("want errQuotaExceeded, got %v", err)
	}
}

// ---- completion ----

func newSession(now time.Time) *sessionRow {
	return &sessionRow{
		ID:      "sess-1",
		AssetID: "asset-1",
		UserID:  "user-1",
		ExpectedSizes: map[string]int64{
			"original":  1100,
			"preview":   200,
			"thumbnail": 0,
			"manifest":  64,
		},
		ReservedBytes: 1100 + 200 + 64,
		ExpiresAt:     now.Add(time.Hour),
		Status:        "pending",
	}
}

func b64(t *testing.T, n int) string {
	t.Helper()
	b := make([]byte, n)
	for i := range b {
		b[i] = 'x'
	}
	return enc(b)
}

func enc(b []byte) string {
	const tab = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
	out := make([]byte, 0, ((len(b)+2)/3)*4)
	for i := 0; i < len(b); i += 3 {
		var c uint32
		cnt := 0
		for j := 0; j < 3 && i+j < len(b); j++ {
			c |= uint32(b[i+j]) << (16 - 8*j)
			cnt++
		}
		out = append(out, tab[(c>>18)&63], tab[(c>>12)&63])
		if cnt > 1 {
			out = append(out, tab[(c>>6)&63])
		} else {
			out = append(out, '=')
		}
		if cnt > 2 {
			out = append(out, tab[c&63])
		} else {
			out = append(out, '=')
		}
	}
	return string(out)
}

func completionResources(sess *sessionRow) []completeResource {
	var rs []completeResource
	for _, v := range []string{"original", "preview", "thumbnail", "original_motion"} {
		sz := sess.ExpectedSizes[v]
		if sz <= 0 {
			continue
		}
		rs = append(rs, completeResource{
			Variant:        v,
			ObjectKey:      objectKey(sess.UserID, sess.AssetID, v),
			CiphertextSize: sz,
		})
	}
	return rs
}

// A Live Photo is a compound asset: if the reservation declared a motion companion,
// completion must include it — half a Live Photo can never be marked complete.
func TestValidateCompletionMotionCompanionRequired(t *testing.T) {
	now := time.Now()
	sess := newSession(now)
	sess.ExpectedSizes["original_motion"] = 500
	sess.ReservedBytes += 500

	if err := validateCompletion(sess.UserID, sess, manifestFor(sess), completionResources(sess), now); err != nil {
		t.Fatalf("expected ok with companion, got %v", err)
	}

	var without []completeResource
	for _, r := range completionResources(sess) {
		if r.Variant != "original_motion" {
			without = append(without, r)
		}
	}
	if err := validateCompletion(sess.UserID, sess, manifestFor(sess), without, now); !errors.Is(err, errVariantSet) {
		t.Fatalf("want errVariantSet without companion, got %v", err)
	}
}

// A companion that was not reserved must be rejected (it is not part of the set).
func TestValidateCompletionUnexpectedCompanion(t *testing.T) {
	now := time.Now()
	sess := newSession(now) // no original_motion reserved
	rs := append(completionResources(sess), completeResource{
		Variant: "original_motion", ObjectKey: objectKey(sess.UserID, sess.AssetID, "original_motion"), CiphertextSize: 500,
	})
	if err := validateCompletion(sess.UserID, sess, manifestFor(sess), rs, now); !errors.Is(err, errWrongSize) {
		t.Fatalf("want errWrongSize for unreserved companion, got %v", err)
	}
}

func manifestFor(sess *sessionRow) manifestBlob {
	manifestLen := int(sess.ExpectedSizes["manifest"])
	return manifestBlob{HeaderB64: b64ForLen(32), BlobB64: b64ForLen(manifestLen - 32)}
}

func b64ForLen(n int) string {
	return enc(make([]byte, n))
}

func TestValidateCompletionOriginalOnly(t *testing.T) {
	now := time.Now()
	sess := &sessionRow{
		ID: "s", AssetID: "a", UserID: "u",
		ExpectedSizes: map[string]int64{"original": 1100, "preview": 0, "thumbnail": 0, "manifest": 64},
		ExpiresAt:     now.Add(time.Hour), Status: "pending",
	}
	rs := []completeResource{{
		Variant: "original", ObjectKey: objectKey("u", "a", "original"), CiphertextSize: 1100,
	}}
	if err := validateCompletion("u", sess, manifestFor(sess), rs, now); err != nil {
		t.Fatalf("expected ok, got %v", err)
	}
}

func TestValidateCompletionWithPreviewThumbnail(t *testing.T) {
	now := time.Now()
	sess := newSession(now)
	if err := validateCompletion(sess.UserID, sess, manifestFor(sess), completionResources(sess), now); err != nil {
		t.Fatalf("expected ok, got %v", err)
	}
}

func TestValidateCompletionMissingVariant(t *testing.T) {
	now := time.Now()
	sess := newSession(now)
	rs := completionResources(sess)
	rs = rs[:1] // drop preview
	if err := validateCompletion(sess.UserID, sess, manifestFor(sess), rs, now); !errors.Is(err, errVariantSet) {
		t.Fatalf("want errVariantSet, got %v", err)
	}
}

func TestValidateCompletionExtraVariant(t *testing.T) {
	now := time.Now()
	sess := newSession(now)
	rs := completionResources(sess)
	rs = append(rs, completeResource{
		Variant: "thumbnail", ObjectKey: objectKey(sess.UserID, sess.AssetID, "thumbnail"), CiphertextSize: 10,
	})
	if err := validateCompletion(sess.UserID, sess, manifestFor(sess), rs, now); !errors.Is(err, errWrongSize) {
		t.Fatalf("want errWrongSize (thumbnail not reserved), got %v", err)
	}
}

func TestValidateCompletionDuplicateVariant(t *testing.T) {
	now := time.Now()
	sess := newSession(now)
	rs := completionResources(sess)
	rs = append(rs, completeResource{
		Variant: "original", ObjectKey: objectKey(sess.UserID, sess.AssetID, "original"), CiphertextSize: 1100,
	})
	if err := validateCompletion(sess.UserID, sess, manifestFor(sess), rs, now); !errors.Is(err, errDuplicateVariant) {
		t.Fatalf("want errDuplicateVariant, got %v", err)
	}
}

func TestValidateCompletionUnknownVariant(t *testing.T) {
	now := time.Now()
	sess := newSession(now)
	rs := completionResources(sess)
	rs = append(rs, completeResource{Variant: "raw", ObjectKey: "x", CiphertextSize: 1})
	if err := validateCompletion(sess.UserID, sess, manifestFor(sess), rs, now); !errors.Is(err, errBadVariant) {
		t.Fatalf("want errBadVariant, got %v", err)
	}
}

func TestValidateCompletionManifestInResources(t *testing.T) {
	now := time.Now()
	sess := newSession(now)
	rs := completionResources(sess)
	rs = append(rs, completeResource{
		Variant: "manifest", ObjectKey: objectKey(sess.UserID, sess.AssetID, "manifest"), CiphertextSize: 64,
	})
	if err := validateCompletion(sess.UserID, sess, manifestFor(sess), rs, now); !errors.Is(err, errBadVariant) {
		t.Fatalf("want errBadVariant (manifest not an S3 resource), got %v", err)
	}
}

func TestValidateCompletionWrongKey(t *testing.T) {
	now := time.Now()
	sess := newSession(now)
	rs := completionResources(sess)
	rs[0].ObjectKey = "user-OTHER/asset-1/original"
	if err := validateCompletion(sess.UserID, sess, manifestFor(sess), rs, now); !errors.Is(err, errWrongKey) {
		t.Fatalf("want errWrongKey, got %v", err)
	}
}

func TestValidateCompletionWrongSize(t *testing.T) {
	now := time.Now()
	sess := newSession(now)
	rs := completionResources(sess)
	rs[0].CiphertextSize = 9999
	if err := validateCompletion(sess.UserID, sess, manifestFor(sess), rs, now); !errors.Is(err, errWrongSize) {
		t.Fatalf("want errWrongSize, got %v", err)
	}
}

func TestValidateCompletionBadManifestBase64(t *testing.T) {
	now := time.Now()
	sess := newSession(now)
	m := manifestFor(sess)
	m.HeaderB64 = "!!!not base64!!!"
	if err := validateCompletion(sess.UserID, sess, m, completionResources(sess), now); !errors.Is(err, errBadManifestB64) {
		t.Fatalf("want errBadManifestB64, got %v", err)
	}
}

func TestValidateCompletionMissingManifest(t *testing.T) {
	now := time.Now()
	sess := newSession(now)
	if err := validateCompletion(sess.UserID, sess, manifestBlob{}, completionResources(sess), now); !errors.Is(err, errMissingManifest) {
		t.Fatalf("want errMissingManifest, got %v", err)
	}
}

func TestValidateCompletionManifestSizeMismatch(t *testing.T) {
	now := time.Now()
	sess := newSession(now)
	m := manifestBlob{HeaderB64: b64ForLen(10), BlobB64: b64ForLen(10)}
	if err := validateCompletion(sess.UserID, sess, m, completionResources(sess), now); !errors.Is(err, errManifestSize) {
		t.Fatalf("want errManifestSize, got %v", err)
	}
}

func TestValidateCompletionExpired(t *testing.T) {
	now := time.Now()
	sess := newSession(now)
	sess.ExpiresAt = now.Add(-time.Minute)
	if err := validateCompletion(sess.UserID, sess, manifestFor(sess), completionResources(sess), now); !errors.Is(err, errExpired) {
		t.Fatalf("want errExpired, got %v", err)
	}
}

func TestValidateCompletionAlreadyCommitted(t *testing.T) {
	now := time.Now()
	sess := newSession(now)
	sess.Status = "committed"
	if err := validateCompletion(sess.UserID, sess, manifestFor(sess), completionResources(sess), now); !errors.Is(err, errAlreadyCommitted) {
		t.Fatalf("want errAlreadyCommitted, got %v", err)
	}
}

// ---- quota admission ----

func TestAdmitQuota(t *testing.T) {
	max := int64(math.MaxInt64)
	cases := []struct {
		name                              string
		stored, pending, requested, limit int64
		want                              bool
	}{
		{"zero ok", 0, 0, 0, 0, true},
		{"exact fit", 100, 200, 300, 600, true},
		{"exceeded by one", 100, 200, 301, 600, false},
		{"pending exact", 100, 500, 0, 600, true},
		{"pending exceeded", 100, 501, 0, 600, false},
		{"stored exact", 600, 0, 0, 600, true},
		{"stored exceeded", 601, 0, 0, 600, false},
		{"requested exact", 0, 0, 600, 600, true},
		{"requested exceeded", 0, 0, 601, 600, false},
		{"negative stored", -1, 0, 0, 100, false},
		{"negative pending", 0, -1, 0, 100, false},
		{"negative requested", 0, 0, -1, 100, false},
		{"negative limit", 0, 0, 0, -1, false},
		{"max requested exact", 0, 0, max, max, true},
		{"max requested exceeded", 0, 0, max, max - 1, false},
		{"three large positives wrap twice", max, max, 3, max, false},
		{"stored+pending wrap then small requested", max, max, 0, max, false},
		{"large operands fit", 1 << 60, 1 << 60, 1 << 60, max, true},
	}
	for _, c := range cases {
		if got := admitQuota(c.stored, c.pending, c.requested, c.limit); got != c.want {
			t.Errorf("%s: admitQuota(%d,%d,%d,%d)=%v want %v", c.name, c.stored, c.pending, c.requested, c.limit, got, c.want)
		}
	}
}

// admitQuota must reject the case where the old `stored+pending+requested`
// sum wrapped twice to a small positive value (falsely admitted before).
func TestAdmitQuotaOldWrapTwiceRejected(t *testing.T) {
	max := int64(math.MaxInt64)
	if admitQuota(max, max, 3, max) {
		t.Fatalf("three large positives must be rejected")
	}
}

// ---- commit quota charge ----

func TestCommitQuotaChargeIncludesManifest(t *testing.T) {
	sess := &sessionRow{ReservedBytes: 1164} // original 1100 + manifest 64
	resources := []Resource{{Variant: "original"}, {Variant: "preview"}}
	stored, obj := commitQuotaCharge(sess, resources)
	if stored != 1164 {
		t.Fatalf("stored=%d want 1164 (must charge manifest bytes)", stored)
	}
	if obj != 2 {
		t.Fatalf("object_count=%d want 2 (S3 resources only)", obj)
	}
}

func TestCommitQuotaChargeNoS3Resources(t *testing.T) {
	sess := &sessionRow{ReservedBytes: 64}
	stored, obj := commitQuotaCharge(sess, nil)
	if stored != 64 {
		t.Fatalf("stored=%d want 64 (reservation still charged)", stored)
	}
	if obj != 0 {
		t.Fatalf("object_count=%d want 0 (no S3 resources)", obj)
	}
}
