package main

import (
	"encoding/base64"
	"errors"
	"fmt"
	"math"
	"time"
)

// Pure validation for the upload start/completion flow. These functions take no
// DB or S3 handle so they can be exercised by standard-library tests without
// infrastructure. The HTTP handlers translate the returned errors to statuses.

var allowedVariants = map[string]bool{
	"original":  true,
	"preview":   true,
	"thumbnail": true,
	"manifest":  true,
	// original_motion is the video half of a Live/Motion Photo. It is an S3 resource
	// (like original/preview/thumbnail) and makes the asset a compound one.
	"original_motion": true,
}

// maxClientRefLen bounds the caller-supplied idempotency key (assetIdClient).
const maxClientRefLen = 128

var (
	errTooManyItems     = errors.New("1..50 items required")
	errNonPositiveOrig  = errors.New("originalSize must be positive")
	errMissingAssetRef  = errors.New("assetIdClient required")
	errDuplicateRef     = errors.New("duplicate assetIdClient")
	errBadVariant       = errors.New("unknown variant")
	errNegativeSize     = errors.New("negative size")
	errSizeOverflow     = errors.New("size overflow")
	errNoOriginal       = errors.New("original ciphertext size required and must be positive")
	errNoManifest       = errors.New("manifest ciphertext size required and must be positive")
	errBadClientRef     = errors.New("assetIdClient too long")
	errCompanionNoOrig  = errors.New("original_motion requires a positive original")
	errNotFound         = errors.New("session not found")
	errExpired          = errors.New("session expired")
	errAlreadyCommitted = errors.New("session already committed")
	errVariantSet       = errors.New("variant set mismatch")
	errDuplicateVariant = errors.New("duplicate variant")
	errWrongKey         = errors.New("object key mismatch")
	errWrongSize        = errors.New("ciphertext size mismatch")
	errMissingManifest  = errors.New("manifest required")
	errBadManifestB64   = errors.New("manifest base64 invalid")
	errManifestSize     = errors.New("manifest decoded size mismatch")
)

type manifestBlob struct {
	HeaderB64 string `json:"headerB64"`
	BlobB64   string `json:"blobB64"`
}

type sessionRow struct {
	ID            string
	AssetID       string
	UserID        string
	ExpectedSizes map[string]int64
	ReservedBytes int64
	ExpiresAt     time.Time
	Status        string
}

// validateUploadItem validates one start-request item and returns its reserved
// byte total (the sum of declared ciphertext sizes, computed with overflow
// checks). original and manifest must be present and positive; preview and
// thumbnail may be zero but never negative; only the four reserved variant
// keys are accepted.
func validateUploadItem(it uploadItemReq) (int64, error) {
	if it.OriginalSize <= 0 {
		return 0, errNonPositiveOrig
	}
	if it.AssetIDClient == "" {
		return 0, errMissingAssetRef
	}
	if len(it.AssetIDClient) > maxClientRefLen {
		return 0, errBadClientRef
	}
	var sum int64
	for v, sz := range it.EncryptedSizes {
		if !allowedVariants[v] {
			return 0, fmt.Errorf("%w: %q", errBadVariant, v)
		}
		if sz < 0 {
			return 0, fmt.Errorf("%w: %q", errNegativeSize, v)
		}
		if sum > math.MaxInt64-sz {
			return 0, errSizeOverflow
		}
		sum += sz
	}
	if it.EncryptedSizes["original"] <= 0 {
		return 0, errNoOriginal
	}
	if it.EncryptedSizes["manifest"] <= 0 {
		return 0, errNoManifest
	}
	// A motion companion only ever attaches to an original photo (never on its own).
	if it.EncryptedSizes["original_motion"] > 0 && it.EncryptedSizes["original"] <= 0 {
		return 0, errCompanionNoOrig
	}
	return sum, nil
}

// validateUploadItems validates a whole start request: item count, per-item
// shape, unique client refs, and total reservation within quota. Returns the
// total reserved bytes for the request.
func validateUploadItems(items []uploadItemReq, quota int64) (int64, error) {
	if len(items) == 0 || len(items) > 50 {
		return 0, errTooManyItems
	}
	refs := make(map[string]bool, len(items))
	var total int64
	for _, it := range items {
		reserved, err := validateUploadItem(it)
		if err != nil {
			return 0, err
		}
		if refs[it.AssetIDClient] {
			return 0, fmt.Errorf("%w: %q", errDuplicateRef, it.AssetIDClient)
		}
		refs[it.AssetIDClient] = true
		if total > math.MaxInt64-reserved {
			return 0, errSizeOverflow
		}
		total += reserved
	}
	if total > quota {
		return 0, errQuotaExceeded
	}
	return total, nil
}

// expectedResourceVariants returns the nonzero original/preview/thumbnail/motion
// variants reserved for the session (manifest is tracked separately).
func expectedResourceVariants(sess *sessionRow) map[string]int64 {
	out := map[string]int64{}
	for _, v := range []string{"original", "preview", "thumbnail", "original_motion"} {
		out[v] = sess.ExpectedSizes[v]
	}
	return out
}

func countNonzero(m map[string]int64) int {
	n := 0
	for _, v := range m {
		if v > 0 {
			n++
		}
	}
	return n
}

// validateCompletion checks that a completion request matches the reserved
// session exactly: the variant set (no extras, duplicates, or missing), the
// owner-derived object keys, the reserved ciphertext sizes, and the manifest's
// decoded length. now is injected so tests are deterministic. The resource list
// and manifest are validated regardless of status, so an invalid repeat of a
// committed session is still rejected. A committed session then reports
// errAlreadyCommitted so the caller can return the existing seq without
// recharging quota; a pending session must still be within its expiry window.
func validateCompletion(userID string, sess *sessionRow, manifest manifestBlob, resources []completeResource, now time.Time) error {
	expected := expectedResourceVariants(sess)
	seen := map[string]bool{}
	for _, r := range resources {
		if r.Variant == "manifest" || !allowedVariants[r.Variant] {
			return fmt.Errorf("%w: %q", errBadVariant, r.Variant)
		}
		if seen[r.Variant] {
			return fmt.Errorf("%w: %q", errDuplicateVariant, r.Variant)
		}
		seen[r.Variant] = true
		if r.CiphertextSize != expected[r.Variant] {
			return fmt.Errorf("%w: %q", errWrongSize, r.Variant)
		}
		if r.ObjectKey != objectKey(userID, sess.AssetID, r.Variant) {
			return fmt.Errorf("%w: %q", errWrongKey, r.Variant)
		}
	}
	if len(seen) != countNonzero(expected) {
		return errVariantSet
	}
	for v, sz := range expected {
		if sz > 0 && !seen[v] {
			return fmt.Errorf("%w: missing %q", errVariantSet, v)
		}
	}
	// A Live Photo is a compound asset: if the reservation declared a motion companion,
	// completion MUST include it — half a Live Photo can never be marked complete.
	if expected["original_motion"] > 0 && !seen["original_motion"] {
		return fmt.Errorf("%w: missing companion %q", errVariantSet, "original_motion")
	}
	if manifest.HeaderB64 == "" || manifest.BlobB64 == "" {
		return errMissingManifest
	}
	header, err := base64.StdEncoding.DecodeString(manifest.HeaderB64)
	if err != nil {
		return errBadManifestB64
	}
	blob, err := base64.StdEncoding.DecodeString(manifest.BlobB64)
	if err != nil {
		return errBadManifestB64
	}
	if int64(len(header)+len(blob)) != sess.ExpectedSizes["manifest"] {
		return errManifestSize
	}

	if sess.Status == "committed" {
		return errAlreadyCommitted
	}
	if sess.Status != "pending" || now.After(sess.ExpiresAt) {
		return errExpired
	}
	return nil
}

// admitQuota returns true iff the requested reservation fits within the
// remaining quota with no int64 overflow. Each subtraction stays in
// [0, limit] because the chain is checked left-to-right after the
// non-negative guards.
func admitQuota(stored, pending, requested, limit int64) bool {
	return limit >= 0 && stored >= 0 && pending >= 0 && requested >= 0 &&
		stored <= limit && pending <= limit-stored && requested <= limit-stored-pending
}

// commitQuotaCharge returns the stored_bytes and object_count deltas to apply
// when a session commits. stored_bytes charges the full reservation (manifest
// + S3 resources); object_count counts only the S3 resources.
func commitQuotaCharge(sess *sessionRow, resources []Resource) (storedDelta, objectDelta int64) {
	return sess.ReservedBytes, int64(len(resources))
}

// ---- multipart part geometry ----

var (
	errBadPartCount = errors.New("partCount out of range")
	errBadSize      = errors.New("size out of range")
	errPartMismatch = errors.New("uploaded parts do not match the session")
)

// minPartSize is the S3 minimum part size for every part except the last.
const minPartSize = 5 * 1024 * 1024

// maxMultipartParts caps presigning per init request (S3's hard limit is 10000;
// presigning thousands of URLs in one request is slow, so we cap lower — clients
// needing more parts simply use larger parts).
const maxMultipartParts = 1024

// partSlice is the deterministic byte range for one part of an object.
type partSlice struct {
	Number int   `json:"number"`
	Offset int64 `json:"offset"`
	Length int64 `json:"length"`
}

// computePartSlices returns the byte ranges for partCount parts of a size-byte
// object (even slicing: every part except the last is ceil(size/n)). Errors when
// any non-last part would violate the S3 minimum part size or count is out of
// bounds. Pure — exercised by validation_test.go.
func computePartSlices(size int64, partCount int) ([]partSlice, error) {
	if partCount < 1 || partCount > maxMultipartParts {
		return nil, errBadPartCount
	}
	if size <= 0 {
		return nil, errBadSize
	}
	partLen := (size + int64(partCount) - 1) / int64(partCount)
	if partCount > 1 && partLen < minPartSize {
		return nil, fmt.Errorf("%w: ceil(%d/%d) = %d < %d", errBadPartCount, size, partCount, partLen, minPartSize)
	}
	slices := make([]partSlice, 0, partCount)
	var off int64
	for i := 1; i <= partCount; i++ {
		l := partLen
		if off+l > size {
			l = size - off
		}
		if l <= 0 {
			break
		}
		slices = append(slices, partSlice{Number: i, Offset: off, Length: l})
		off += l
	}
	if off != size {
		return nil, errBadSize
	}
	return slices, nil
}

// uploadedPart is the server-observed state of one uploaded part (from S3
// ListParts) — authoritative etag and size, passed in pure form for validation.
type uploadedPart struct {
	ETag string
	Size int64
}

// clientPart is the client's claim about one uploaded part.
type clientPart struct {
	Number int    `json:"number"`
	ETag   string `json:"etag"`
}

// resolvedPart is one validated part, ready for CompleteMultipartUpload.
type resolvedPart struct {
	Number int
	ETag   string
}

// resolveMultipartParts validates a completion claim against the reserved
// session size and the server-observed uploaded parts:
//   - client must claim exactly the full even-slice set for the expected size
//   - every claimed part must actually exist on S3 with the expected length
//   - etags are taken from S3 (authoritative); client etags are advisory
//
// Returns the ordered part list for CompleteMultipartUpload. Pure.
func resolveMultipartParts(expectedSize int64, client []clientPart, uploaded map[int]uploadedPart) ([]resolvedPart, error) {
	if len(client) < 1 {
		return nil, errPartMismatch
	}
	slices, err := computePartSlices(expectedSize, len(client))
	if err != nil {
		return nil, err
	}
	out := make([]resolvedPart, 0, len(slices))
	seen := map[int]bool{}
	for i, cp := range client {
		exp := slices[i]
		if cp.Number != exp.Number {
			return nil, fmt.Errorf("%w: part %d expected number %d", errPartMismatch, cp.Number, exp.Number)
		}
		if seen[cp.Number] {
			return nil, fmt.Errorf("%w: duplicate part %d", errPartMismatch, cp.Number)
		}
		seen[cp.Number] = true
		up, ok := uploaded[cp.Number]
		if !ok {
			return nil, fmt.Errorf("%w: part %d not uploaded", errPartMismatch, cp.Number)
		}
		if up.Size != exp.Length {
			return nil, fmt.Errorf("%w: part %d size %d != %d", errPartMismatch, cp.Number, up.Size, exp.Length)
		}
		out = append(out, resolvedPart{Number: cp.Number, ETag: up.ETag})
	}
	return out, nil
}
