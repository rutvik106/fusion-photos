package main

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestNewOTPCodeIsSixDigits(t *testing.T) {
	seen := map[string]bool{}
	for i := 0; i < 200; i++ {
		code, err := newOTPCode()
		if err != nil {
			t.Fatalf("newOTPCode: %v", err)
		}
		if len(code) != otpCodeDigits {
			t.Fatalf("code %q is not %d digits", code, otpCodeDigits)
		}
		for _, r := range code {
			if r < '0' || r > '9' {
				t.Fatalf("code %q contains a non-digit", code)
			}
		}
		seen[code] = true
	}
	// 200 draws from a 10^6 space: fewer than 50 distinct would indicate the code
	// is not coming from a real random source.
	if len(seen) < 50 {
		t.Fatalf("codes look non-random: only %d distinct in 200 draws", len(seen))
	}
}

func TestHashOTPBindsPepperEmailCode(t *testing.T) {
	base := hashOTP("pepper", "a@b.com", "123456")
	if base != hashOTP("pepper", "a@b.com", "123456") {
		t.Fatal("hash must be deterministic for the same inputs")
	}
	if base == hashOTP("other", "a@b.com", "123456") {
		t.Fatal("a different pepper must change the hash")
	}
	if base == hashOTP("pepper", "c@d.com", "123456") {
		t.Fatal("a different email must change the hash")
	}
	if base == hashOTP("pepper", "a@b.com", "654321") {
		t.Fatal("a different code must change the hash")
	}
	if base == "123456" || strings.Contains(base, "123456") {
		t.Fatal("hash must not contain the plaintext code")
	}
	if base != hashOTP("pepper", "a@b.com", " 123456 ") {
		t.Fatal("surrounding whitespace on the code must be ignored")
	}
}

func TestNormalizeEmail(t *testing.T) {
	cases := []struct {
		in   string
		want string
		ok   bool
	}{
		{" A@B.com ", "a@b.com", true},
		{"user@test.local", "user@test.local", true},
		{"user+tag@sub.example.co.uk", "user+tag@sub.example.co.uk", true},
		{"", "", false},
		{"no-at-sign", "", false},
		{"@nolocal.com", "", false},
		{"nolocal@", "", false},
		{"two@@example.com", "", false},
		{"a@b@c.com", "", false},
		{"space in@example.com", "", false},
		{"a@nodot", "", false},
		{"trailing@example.", "", false},
	}
	for _, c := range cases {
		got, ok := normalizeEmail(c.in)
		if ok != c.ok || got != c.want {
			t.Errorf("normalizeEmail(%q)=(%q,%v) want (%q,%v)", c.in, got, ok, c.want, c.ok)
		}
	}
}

func TestAdmitThrottle(t *testing.T) {
	cases := []struct {
		count, limit int
		want         bool
	}{
		{1, 5, true},
		{5, 5, true},
		{6, 5, false},
		{0, 0, false},
		{1, 0, false},
		{1, -1, false},
	}
	for _, c := range cases {
		if got := admitThrottle(c.count, c.limit); got != c.want {
			t.Errorf("admitThrottle(%d,%d)=%v want %v", c.count, c.limit, got, c.want)
		}
	}
}

func TestOTPThrottleKey(t *testing.T) {
	if got := otpThrottleKey("Email", " Foo@Bar.com "); got != "email:foo@bar.com" {
		t.Fatalf("key=%q want email:foo@bar.com", got)
	}
	if otpThrottleKey("ip", "1.2.3.4") == otpThrottleKey("email", "1.2.3.4") {
		t.Fatal("ip and email buckets must be distinct namespaces")
	}
}

func TestClientIP(t *testing.T) {
	r := httptest.NewRequest(http.MethodPost, "/", nil)
	r.RemoteAddr = "10.0.0.9:5555"
	if got := clientIP(r); got != "10.0.0.9" {
		t.Fatalf("RemoteAddr ip=%q want 10.0.0.9", got)
	}
	r.Header.Set("X-Forwarded-For", "203.0.113.7, 10.0.0.1")
	if got := clientIP(r); got != "203.0.113.7" {
		t.Fatalf("XFF ip=%q want 203.0.113.7", got)
	}
	r.Header.Set("X-Forwarded-For", "   ")
	if got := clientIP(r); got != "10.0.0.9" {
		t.Fatalf("blank XFF should fall back to RemoteAddr, got %q", got)
	}
}
