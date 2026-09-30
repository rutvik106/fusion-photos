package main

import (
	"strings"
	"testing"
)

func TestComputePartSlicesEvenDivision(t *testing.T) {
	// 12 MiB in 2 parts of exactly 6 MiB
	slices, err := computePartSlices(12*1024*1024, 2)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(slices) != 2 {
		t.Fatalf("want 2 slices, got %d", len(slices))
	}
	if slices[0].Number != 1 || slices[0].Offset != 0 || slices[0].Length != 6*1024*1024 {
		t.Fatalf("bad first slice: %+v", slices[0])
	}
	if slices[1].Number != 2 || slices[1].Offset != 6*1024*1024 || slices[1].Length != 6*1024*1024 {
		t.Fatalf("bad second slice: %+v", slices[1])
	}
}

func TestComputePartSlicesRemainderInLast(t *testing.T) {
	// 17 MiB in 3 parts: even slicing = ceil(17825792/3) with the remainder in the last
	size := int64(17 * 1024 * 1024)
	slices, err := computePartSlices(size, 3)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	partLen := (size + 2) / 3 // 5941931
	want := []int64{partLen, partLen, size - 2*partLen}
	var off int64
	var total int64
	for i, s := range slices {
		if s.Length != want[i] {
			t.Fatalf("part %d length = %d, want %d", s.Number, s.Length, want[i])
		}
		if s.Offset != off {
			t.Fatalf("part %d offset = %d, want %d (contiguous)", s.Number, s.Offset, off)
		}
		off += s.Length
		total += s.Length
	}
	if total != size {
		t.Fatalf("slices cover %d bytes, want %d", total, size)
	}
}

func TestComputePartSlicesSinglePartAnySize(t *testing.T) {
	// Single part: no minimum-size rule applies (S3 allows any size for 1 part)
	slices, err := computePartSlices(17, 1)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(slices) != 1 || slices[0].Length != 17 {
		t.Fatalf("want one 17-byte part, got %+v", slices)
	}
}

func TestComputePartSlicesMinPartSizeEnforced(t *testing.T) {
	// ceil(17MiB/4) = 4.25 MiB < 5 MiB -> reject
	if _, err := computePartSlices(17*1024*1024, 4); err == nil || !strings.Contains(err.Error(), "partCount") {
		t.Fatalf("want min-part-size rejection, got %v", err)
	}
	// exact boundary: ceil(20MiB/4) = 5 MiB -> allowed
	if _, err := computePartSlices(20*1024*1024, 4); err != nil {
		t.Fatalf("5 MiB parts must be allowed, got %v", err)
	}
}

func TestComputePartSlicesBounds(t *testing.T) {
	if _, err := computePartSlices(10*1024*1024, 0); err != errBadPartCount {
		t.Fatalf("want errBadPartCount for 0, got %v", err)
	}
	if _, err := computePartSlices(10*1024*1024, maxMultipartParts+1); err != errBadPartCount {
		t.Fatalf("want errBadPartCount over max, got %v", err)
	}
	if _, err := computePartSlices(0, 1); err != errBadSize {
		t.Fatalf("want errBadSize for zero size, got %v", err)
	}
	if _, err := computePartSlices(-1, 1); err != errBadSize {
		t.Fatalf("want errBadSize for negative size, got %v", err)
	}
}

func TestResolveMultipartPartsValid(t *testing.T) {
	size := int64(12 * 1024 * 1024)
	uploaded := map[int]uploadedPart{
		1: {ETag: "\"etag1\"", Size: 6 * 1024 * 1024},
		2: {ETag: "\"etag2\"", Size: 6 * 1024 * 1024},
	}
	res, err := resolveMultipartParts(size, []clientPart{{Number: 1}, {Number: 2}}, uploaded)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	// server-observed etags are authoritative; client etags are ignored
	if res[0].ETag != "\"etag1\"" || res[1].ETag != "\"etag2\"" {
		t.Fatalf("want server etags, got %+v", res)
	}
}

func TestResolveMultipartPartsClientEtagIgnored(t *testing.T) {
	size := int64(6 * 1024 * 1024)
	uploaded := map[int]uploadedPart{1: {ETag: "\"from-s3\"", Size: size}}
	res, err := resolveMultipartParts(size, []clientPart{{Number: 1, ETag: "\"client-claim\""}}, uploaded)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if res[0].ETag != "\"from-s3\"" {
		t.Fatalf("client etag must not win, got %q", res[0].ETag)
	}
}

func TestResolveMultipartPartsRejections(t *testing.T) {
	size := int64(12 * 1024 * 1024)
	full := map[int]uploadedPart{
		1: {ETag: "\"e1\"", Size: 6 * 1024 * 1024},
		2: {ETag: "\"e2\"", Size: 6 * 1024 * 1024},
	}
	cases := []struct {
		name    string
		client  []clientPart
		upload  map[int]uploadedPart
		wantErr string
	}{
		{"missing claim", []clientPart{{Number: 1}}, full, "size"},
		{"unuploaded part", []clientPart{{Number: 1}, {Number: 2}},
			map[int]uploadedPart{1: {ETag: "\"e1\"", Size: 6 * 1024 * 1024}}, "not uploaded"},
		{"wrong part size", []clientPart{{Number: 1}, {Number: 2}},
			map[int]uploadedPart{1: {ETag: "\"e1\"", Size: 6*1024*1024 + 1}, 2: {ETag: "\"e2\"", Size: 6 * 1024 * 1024}}, "size"},
		{"duplicate claim", []clientPart{{Number: 1}, {Number: 1}}, full, "expected number"},
		{"wrong number order", []clientPart{{Number: 2}, {Number: 1}}, full, "expected number"},
		{"empty claim", nil, full, "parts do not match"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			_, err := resolveMultipartParts(size, c.client, c.upload)
			if err == nil || !strings.Contains(err.Error(), c.wantErr) {
				t.Fatalf("want error containing %q, got %v", c.wantErr, err)
			}
		})
	}
}
