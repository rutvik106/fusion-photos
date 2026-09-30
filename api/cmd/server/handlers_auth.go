package main

import (
	"encoding/json"
	"log"
	"net/http"
	"strings"
	"time"
)

type server struct {
	cfg    Config
	store  *store
	media  *mediaStore
	mail   mailer
	pepper string // HMAC pepper for OTP code hashes; never persisted
}

func (s *server) authed(next func(http.ResponseWriter, *http.Request, string)) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		bearer := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
		if bearer == "" || bearer == r.Header.Get("Authorization") {
			writeErr(w, http.StatusUnauthorized, "unauthorized", "missing bearer token")
			return
		}
		userID, err := s.store.userForToken(r.Context(), bearer)
		if err != nil {
			writeErr(w, http.StatusUnauthorized, "unauthorized", "invalid or expired session")
			return
		}
		next(w, r, userID)
	}
}

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}

func writeErr(w http.ResponseWriter, status int, code, msg string) {
	writeJSON(w, status, map[string]string{"code": code, "error": msg})
}

func (s *server) handleHealth(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"ok": "true"})
}

// GET /v1/me — which account does this session belong to?
//
// The client persists the account id to scope its local catalog (so signing in as a
// different account can never display the previous account's photos). Asking the server
// makes that authoritative, including for sessions that predate the client-side marker.
func (s *server) handleMe(w http.ResponseWriter, r *http.Request, userID string) {
	email, err := s.store.emailFor(r.Context(), userID)
	if err != nil {
		writeErr(w, http.StatusUnauthorized, "unauthorized", "unknown account")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"userId": userID, "email": email})
}

// ---- OTP auth ----

// handleOtpRequest issues a single-use sign-in code. Abuse controls run before the
// code is generated or sent: a per-IP bucket guards the endpoint broadly and a
// per-email bucket limits how often one address can be mailed (SES spend is a
// billing risk, per the plan). The code is stored only as a peppered hash and is
// echoed back solely in DevMode; it is never logged.
func (s *server) handleOtpRequest(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Email string `json:"email"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4<<10)).Decode(&body); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_request", "valid email required")
		return
	}
	email, ok := normalizeEmail(body.Email)
	if !ok {
		writeErr(w, http.StatusBadRequest, "bad_request", "valid email required")
		return
	}

	now := time.Now()
	if ok, err := s.store.bumpThrottle(r.Context(), otpThrottleKey("ip", clientIP(r)), s.cfg.OTPPerIPWindow, s.cfg.OTPPerIPLimit, now); err != nil {
		writeErr(w, http.StatusInternalServerError, "db", err.Error())
		return
	} else if !ok {
		writeErr(w, http.StatusTooManyRequests, "rate_limited", "too many requests from this address; try again later")
		return
	}
	if ok, err := s.store.bumpThrottle(r.Context(), otpThrottleKey("email", email), s.cfg.OTPPerEmailWindow, s.cfg.OTPPerEmailLimit, now); err != nil {
		writeErr(w, http.StatusInternalServerError, "db", err.Error())
		return
	} else if !ok {
		writeErr(w, http.StatusTooManyRequests, "rate_limited", "too many codes requested; try again later")
		return
	}

	code, err := newOTPCode()
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "otp", "could not generate code")
		return
	}
	if err := s.store.putOTP(r.Context(), email, hashOTP(s.pepper, email, code), now.Add(s.cfg.OTPTTL)); err != nil {
		writeErr(w, http.StatusInternalServerError, "db", err.Error())
		return
	}
	if err := s.mail.sendOTP(r.Context(), email, code, s.cfg.OTPTTL); err != nil {
		log.Printf("otp: send failed for %s: %v", email, err)
		writeErr(w, http.StatusBadGateway, "mail", "could not send sign-in code; try again")
		return
	}

	resp := map[string]any{"ok": true}
	// DEV ONLY: in production the code leaves via SES and is never echoed back.
	if s.cfg.DevMode {
		resp["devCode"] = code
	}
	writeJSON(w, http.StatusOK, resp)
}

// handleOtpVerify consumes a code and mints a session. Wrong, expired and unknown
// all map to the same 401 so the endpoint does not confirm which address has a
// pending code; too many wrong guesses maps to 429.
func (s *server) handleOtpVerify(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Email string `json:"email"`
		Code  string `json:"code"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4<<10)).Decode(&body); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_request", "invalid body")
		return
	}
	email, ok := normalizeEmail(body.Email)
	if !ok || strings.TrimSpace(body.Code) == "" {
		writeErr(w, http.StatusBadRequest, "bad_request", "email and code required")
		return
	}

	res, err := s.store.verifyOTP(r.Context(), email, hashOTP(s.pepper, email, body.Code), time.Now(), s.cfg.OTPMaxAttempts)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "db", err.Error())
		return
	}
	if res == otpTooManyAttempts {
		writeErr(w, http.StatusTooManyRequests, "too_many_attempts", "too many incorrect codes; request a new one")
		return
	}
	if res != otpOK {
		writeErr(w, http.StatusUnauthorized, "bad_code", "code invalid or expired")
		return
	}

	// Only now is a user row created: unverified addresses never enter the catalog.
	userID, err := s.store.ensureUser(r.Context(), email)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "db", err.Error())
		return
	}
	token, err := s.store.createSession(r.Context(), userID, s.cfg.SessionExpiry)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "db", err.Error())
		return
	}
	vault, err := s.store.getVault(r.Context(), userID)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "db", err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"sessionToken": token,
		"userId":       userID,
		"hasVault":     vault != nil,
	})
}

// ---- vault ----

func (s *server) handleVaultGet(w http.ResponseWriter, r *http.Request, userID string) {
	vault, err := s.store.getVault(r.Context(), userID)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "db", err.Error())
		return
	}
	if vault == nil {
		writeJSON(w, http.StatusOK, map[string]any{"wrapped": nil})
		return
	}
	var wrapped any
	if err := json.Unmarshal(*vault, &wrapped); err != nil {
		writeErr(w, http.StatusInternalServerError, "db", err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"wrapped": wrapped})
}

func (s *server) handleVaultPut(w http.ResponseWriter, r *http.Request, userID string) {
	var body struct {
		Wrapped json.RawMessage `json:"wrapped"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil || len(body.Wrapped) == 0 {
		writeErr(w, http.StatusBadRequest, "bad_request", "wrapped vault blob required")
		return
	}
	if err := s.store.putVault(r.Context(), userID, body.Wrapped); err != nil {
		writeErr(w, http.StatusInternalServerError, "db", err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}
