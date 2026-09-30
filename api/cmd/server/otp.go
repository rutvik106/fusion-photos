package main

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"math/big"
	"net"
	"net/http"
	"strings"
	"time"
)

// Email-OTP hardening primitives. Everything here is pure (no DB, no network) so
// it can be exercised by standard-library tests without infrastructure, matching
// the validation.go convention. The DB-backed pieces live in store.go.

// Defaults for the OTP lifecycle and abuse limits. All are overridable via env
// (see loadConfig) so a deployment can tighten or relax them without a rebuild.
const (
	defaultOTPTTL            = 10 * time.Minute
	defaultOTPMaxAttempts    = 5
	defaultOTPPerEmailLimit  = 5
	defaultOTPPerEmailWindow = 15 * time.Minute
	defaultOTPPerIPLimit     = 200
	defaultOTPPerIPWindow    = time.Hour
	otpCodeDigits            = 6
)

// otpResult is the outcome of verifying a submitted code.
type otpResult int

const (
	// otpInvalid covers every rejection the caller should treat identically
	// (unknown email, expired code, wrong code) so the response never reveals
	// which case occurred.
	otpInvalid otpResult = iota
	otpOK
	// otpTooManyAttempts means the code was guessed at enough times that it has
	// been invalidated; the user must request a fresh one.
	otpTooManyAttempts
)

// newOTPCode returns a cryptographically random, zero-padded numeric code.
// crypto/rand is mandatory: math/rand is deterministic and would let an attacker
// predict future codes from observed ones.
func newOTPCode() (string, error) {
	max := big.NewInt(1)
	for i := 0; i < otpCodeDigits; i++ {
		max.Mul(max, big.NewInt(10))
	}
	n, err := rand.Int(rand.Reader, max)
	if err != nil {
		return "", err
	}
	return fmt.Sprintf("%0*d", otpCodeDigits, n.Int64()), nil
}

// hashOTP binds a code to its destination email under a server-held pepper with
// HMAC-SHA256, so a leaked otp_codes table cannot be brute-forced offline (the
// code space is only 10^6). Keeping the pepper outside the database is the point.
func hashOTP(pepper, email, code string) string {
	mac := hmac.New(sha256.New, []byte(pepper))
	mac.Write([]byte(email))
	mac.Write([]byte{0})
	mac.Write([]byte(strings.TrimSpace(code)))
	return hex.EncodeToString(mac.Sum(nil))
}

// normalizeEmail lower-cases and trims an address and applies a deliberately
// strict syntactic check: exactly one "@", a non-empty local part, a dotted
// domain, and no whitespace. This is a format gate, not proof of deliverability.
func normalizeEmail(raw string) (string, bool) {
	e := strings.ToLower(strings.TrimSpace(raw))
	if strings.ContainsAny(e, " \t\r\n") {
		return "", false
	}
	at := strings.IndexByte(e, '@')
	if at <= 0 || at == len(e)-1 || strings.IndexByte(e[at+1:], '@') >= 0 {
		return "", false
	}
	domain := e[at+1:]
	if !strings.Contains(domain, ".") || strings.HasSuffix(domain, ".") {
		return "", false
	}
	return e, true
}

// otpThrottleKey namespaces a throttle bucket: "email:<addr>" or "ip:<addr>", so
// the same literal value in two namespaces can never collide.
func otpThrottleKey(kind, id string) string {
	return strings.ToLower(strings.TrimSpace(kind)) + ":" + strings.ToLower(strings.TrimSpace(id))
}

// admitThrottle reports whether a request already counted in its window
// (newCount) is still within limit. Counting continues past the limit, so a
// caller that keeps hammering keeps seeing false until the window rolls over.
func admitThrottle(newCount, limit int) bool {
	if limit <= 0 {
		return false
	}
	return newCount <= limit
}

// clientIP extracts the caller's address for IP-based throttling. It prefers the
// first X-Forwarded-For hop (set by the load balancer / reverse proxy) and falls
// back to the socket peer. A spoofed XFF can only loosen the sender's own bucket,
// never tighten another user's, so the per-email bucket stays authoritative.
func clientIP(r *http.Request) string {
	if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
		if first := strings.TrimSpace(strings.Split(xff, ",")[0]); first != "" {
			return first
		}
	}
	host, _, err := net.SplitHostPort(strings.TrimSpace(r.RemoteAddr))
	if err != nil {
		return strings.TrimSpace(r.RemoteAddr)
	}
	return host
}
