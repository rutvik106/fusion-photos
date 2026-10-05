#!/usr/bin/env python3
"""A tiny stand-in API, so the Android client can be driven on a device without Docker.

**This is a test harness, not the service.** It exists because the real backend (API + Postgres
+ MinIO) needs Docker Desktop, and a device run of the *client* should not be hostage to that.
The real server was verified separately; this stub only has to be faithful about the wire shapes
the client depends on, so client-side behaviour (foreground service, progress notification,
queue states) can be observed on a real device.

What it implements, mirroring `api/cmd/server`:
  POST /v1/auth/otp/request      -> devCode (no email is sent; fixed code 123456)
  POST /v1/auth/otp/verify       -> sessionToken, userId, hasVault
  GET  /v1/me                    -> userId, email
  GET  /v1/vault                 -> a *real* Argon2id + XChaCha20-Poly1305 envelope, built with
                                    PyNaCl for the passphrase given on the command line
  PUT  /v1/vault                 -> stores what the client enrols
  POST /v1/upload-session        -> assetId, object keys, presigned PUT URLs (pointing here)
  POST /v1/upload-session/complete
  POST /v1/upload-session/{id}/multipart/{init,status,parts,complete,abort}
  GET  /v1/sync/changes          -> committed assets
  PUT  /put/{key}                -> "S3": stores ciphertext
  PUT  /part/{upload}/{number}   -> "S3": stores one multipart part

It deliberately does NOT validate like the real server (no size checks, no tenant isolation, no
quota). Do not read anything into its leniency.

Usage:
    python3 scripts/stub_api.py [--port 8081] [--passphrase correct-horse-battery-staple]
"""

import argparse
import base64
import hashlib
import json
import os
import threading
import time
import urllib.parse
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from nacl import pwhash
from nacl.bindings import crypto_aead_xchacha20poly1305_ietf_encrypt

PORT = 8081
PASSPHRASE = "correct-horse-battery-staple"
# The host used in the "presigned" URLs handed back to the client. 10.0.2.2 is the host as seen
# from an emulator; a physical device needs something it can actually reach — either the machine's
# LAN address, or 127.0.0.1 with `adb reverse tcp:8080 tcp:8080`. Override with --host.
HOST = "10.0.2.2"
# Optional per-object delay. A local stub is far too fast to observe progress notifications on
# a real device (a 12 MiB upload finishes in well under a second), so a slow-put lets a device
# run see the foreground service and its progress for as long as the test needs.
SLOW_PUT_SECONDS = 0.0

STATE = {"assets": {}, "sessions": {}, "objects": {}, "uploads": {}, "parts": {}, "vault": None}
LOCK = threading.Lock()
VAULT_BLOB = None
# Kept only so `--debug` can hand it to the independent verifier. The real service never sees a
# vault key in the clear; this is a local harness convenience and is why it is opt-in.
VAULT_KEY = None
DEBUG = False


def b64(data: bytes) -> str:
    return base64.b64encode(data).decode()


def build_vault_blob(passphrase: str) -> dict:
    """A genuine vault envelope: Argon2id MODERATE -> KEK, XChaCha20-Poly1305 wrap."""
    global VAULT_KEY
    salt = os.urandom(16)
    kek = pwhash.argon2id.kdf(
        32,
        passphrase.encode(),
        salt,
        opslimit=pwhash.argon2id.OPSLIMIT_MODERATE,
        memlimit=pwhash.argon2id.MEMLIMIT_MODERATE,
    )
    vault_key = os.urandom(32)
    nonce = os.urandom(24)
    ciphertext = crypto_aead_xchacha20poly1305_ietf_encrypt(vault_key, None, nonce, kek)
    VAULT_KEY = vault_key
    return {"passphrase": {"kdfSalt": b64(salt), "wrapped": b64(nonce + ciphertext), "v": 1}}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        # Quieter than the default, but keep a trace of the flow.
        print("  %s %s" % (self.command, self.path), flush=True)

    def _body(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length == 0:
            return {}
        try:
            return json.loads(self.rfile.read(length))
        except json.JSONDecodeError:
            return {}

    def _read(self):
        return self.rfile.read(int(self.headers.get("Content-Length") or 0))

    def _json(self, payload, status=200):
        data = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _stored(self, payload):
        """A successful object PUT: 200 plus the ETag the client may echo back."""
        self.send_response(200)
        self.send_header("ETag", '"%s"' % hashlib.md5(payload).hexdigest())
        self.send_header("Content-Length", "0")
        self.end_headers()

    def _bytes(self, payload):
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    # ---- routes ----

    def do_GET(self):
        path = self.path.split("?")[0]
        if path == "/v1/vault":
            with LOCK:
                wrapped = STATE["vault"] or VAULT_BLOB
            self._json({"wrapped": wrapped})
        elif path == "/v1/me":
            self._json({"userId": "stub-user", "email": "stub@test.local"})
        elif path == "/v1/sync/changes":
            with LOCK:
                assets = [
                    {
                        "assetId": a["assetId"],
                        "seq": a["seq"],
                        "protected": False,
                        "keyEnvelope": a["keyEnvelope"],
                        "manifest": a["manifest"],
                        "resources": {
                            r["variant"]: {
                                "objectKey": r["objectKey"],
                                "ciphertextSize": r["ciphertextSize"],
                            }
                            for r in a["resources"]
                        },
                    }
                    for a in STATE["assets"].values()
                ]
            self._json({"cursor": len(assets), "assets": assets})
        elif path == "/_debug/state" and DEBUG:
            # Everything the verifier needs to reconstruct the run, including the vault key the
            # client unwrapped. Debug only — see --debug.
            with LOCK:
                self._json({
                    "vaultKeyB64": b64(VAULT_KEY),
                    "assets": list(STATE["assets"].values()),
                    "objects": [
                        {"key": k, "size": len(v), "sha256": hashlib.sha256(v).hexdigest()}
                        for k, v in STATE["objects"].items()
                    ],
                })
        elif path.startswith("/_debug/object") and DEBUG:
            query = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            key = (query.get("key") or [""])[0]
            with LOCK:
                payload = STATE["objects"].get(key)
            if payload is None:
                self._json({"error": "no such object", "key": key}, 404)
            else:
                self._bytes(payload)
        else:
            self._json({"error": "not found"}, 404)

    def do_PUT(self):
        path = self.path.split("?")[0]
        if path == "/v1/vault":
            with LOCK:
                STATE["vault"] = self._body().get("wrapped")
            self._json({"ok": True})
        elif path.startswith("/put/"):
            key = path[len("/put/"):]
            payload = self._read()
            if SLOW_PUT_SECONDS:
                time.sleep(SLOW_PUT_SECONDS)
            with LOCK:
                STATE["objects"][key] = payload
            self._stored(payload)
        elif path.startswith("/part/"):
            upload_id, number = path[len("/part/"):].split("/")
            payload = self._read()
            if SLOW_PUT_SECONDS:
                time.sleep(SLOW_PUT_SECONDS)
            with LOCK:
                STATE["parts"].setdefault(upload_id, {})[int(number)] = payload
            self._stored(payload)
        else:
            self._json({"error": "not found"}, 404)

    def do_POST(self):
        path = self.path.split("?")[0]
        body = self._body()
        if path == "/v1/auth/otp/request":
            self._json({"ok": True, "devCode": "123456"})
        elif path == "/v1/auth/otp/verify":
            self._json({"sessionToken": "stub-token", "userId": "stub-user", "hasVault": True})
        elif path == "/v1/upload-session":
            self._json({"items": self._start_sessions(body)})
        elif path == "/v1/upload-session/complete":
            self._json({"assets": self._complete(body)})
        elif "/multipart/" in path:
            self._multipart(path, body)
        else:
            self._json({"error": "not found"}, 404)

    # ---- upload session ----

    def _start_sessions(self, body):
        plans = []
        with LOCK:
            for item in body.get("items", []):
                ref = item.get("assetIdClient", "")
                existing = STATE["sessions"].get(ref)
                if existing and existing["committed"]:
                    # Idempotent replay, exactly like the real service: no URLs, nothing to do.
                    plans.append({
                        "assetIdClient": ref,
                        "assetId": existing["assetId"],
                        "keys": {},
                        "putUrls": {},
                        "maxSizeBytes": 1 << 40,
                        "committed": True,
                    })
                    continue
                asset_id = existing["assetId"] if existing else str(uuid.uuid4())
                keys, urls = {}, {}
                for variant, size in (item.get("encryptedSizes") or {}).items():
                    # The manifest is a database column in the real service, not an object.
                    if variant == "manifest" or size <= 0:
                        continue
                    key = "%s/%s" % (asset_id, variant)
                    keys[variant] = key
                    urls[variant] = "http://%s:%d/put/%s" % (HOST, PORT, key)
                STATE["sessions"][ref] = {
                    "assetId": asset_id,
                    "committed": False,
                    "sizes": item.get("encryptedSizes") or {},
                }
                plans.append({
                    "assetIdClient": ref,
                    "assetId": asset_id,
                    "keys": keys,
                    "putUrls": urls,
                    "maxSizeBytes": 1 << 40,
                })
        return plans

    def _complete(self, body):
        out = []
        with LOCK:
            for item in body.get("items", []):
                asset_id = item["assetId"]
                seq = len(STATE["assets"]) + 1
                STATE["assets"][asset_id] = {
                    "assetId": asset_id,
                    "seq": seq,
                    "keyEnvelope": item.get("keyEnvelope", ""),
                    "manifest": item.get("manifest", {}),
                    "resources": item.get("resources", []),
                }
                for sess in STATE["sessions"].values():
                    if sess["assetId"] == asset_id:
                        sess["committed"] = True
                out.append({"assetId": asset_id, "seq": seq})
        return out

    # ---- multipart ----

    def _multipart(self, path, body):
        asset_id = path.split("/v1/upload-session/", 1)[1].split("/multipart/", 1)[0]
        action = path.rsplit("/", 1)[-1]
        variant = body.get("variant", "original")
        key = "%s/%s" % (asset_id, variant)
        upload_id = "%s-part" % asset_id
        with LOCK:
            sizes = {}
            for sess in STATE["sessions"].values():
                if sess["assetId"] == asset_id:
                    sizes = sess["sizes"]
        expected = sizes.get(variant, 0)

        if action == "init":
            part_count = int(body.get("partCount", 1))
            part_len = -(-expected // part_count)  # the server's ceil(size / parts)
            parts = []
            for number in range(1, part_count + 1):
                offset = (number - 1) * part_len
                parts.append({
                    "number": number,
                    "offset": offset,
                    "length": min(part_len, expected - offset),
                    "url": "http://%s:%d/part/%s/%d" % (HOST, PORT, upload_id, number),
                })
            with LOCK:
                STATE["uploads"][(asset_id, variant)] = part_count
            self._json({"size": expected, "parts": parts})
        elif action == "status":
            with LOCK:
                started = (asset_id, variant) in STATE["uploads"]
                have = dict(STATE["parts"].get(upload_id, {}))
            self._json({
                "started": started,
                "parts": [
                    {"number": n, "etag": '"%s"' % hashlib.md5(b).hexdigest(), "size": len(b)}
                    for n, b in sorted(have.items())
                ],
            })
        elif action == "parts":
            parts = []
            for number in body.get("numbers", []):
                entry = {
                    "number": number,
                    "url": "http://%s:%d/part/%s/%d" % (HOST, PORT, upload_id, number),
                }
                with LOCK:
                    existing = STATE["parts"].get(upload_id, {}).get(number)
                if existing is not None:
                    entry["size"] = len(existing)  # present => the client may skip it
                parts.append(entry)
            self._json({"parts": parts})
        elif action == "complete":
            with LOCK:
                have = dict(STATE["parts"].get(upload_id, {}))
            assembled = b"".join(have[n] for n in sorted(have))
            with LOCK:
                STATE["objects"][key] = assembled
            print(
                "  assembled %d part(s) into %d bytes for %s" % (len(have), len(assembled), key),
                flush=True,
            )
            self._json({"size": len(assembled)})
        elif action == "abort":
            with LOCK:
                STATE["parts"].pop(upload_id, None)
                STATE["uploads"].pop((asset_id, variant), None)
            self._json({"ok": True})
        else:
            self._json({"error": "not found"}, 404)


def main():
    global PORT, PASSPHRASE, VAULT_BLOB, SLOW_PUT_SECONDS, DEBUG, HOST
    parser = argparse.ArgumentParser(description="Stub API for device testing (see module docs)")
    parser.add_argument("--port", type=int, default=PORT)
    parser.add_argument("--passphrase", default=PASSPHRASE)
    parser.add_argument("--bind", default="0.0.0.0")
    parser.add_argument(
        "--host",
        default=HOST,
        help="host to put in presigned URLs (10.0.2.2 from an emulator; 127.0.0.1 with adb reverse; "
        "this machine's LAN IP; or its Tailscale 100.x address)",
    )
    parser.add_argument(
        "--slow-put",
        type=float,
        default=0.0,
        help="seconds to sleep before answering an object PUT (to observe progress on a device)",
    )
    parser.add_argument(
        "--debug",
        action="store_true",
        help="expose GET /_debug/state and /_debug/object?key= (for independent verification)",
    )
    args = parser.parse_args()
    PORT = args.port
    PASSPHRASE = args.passphrase
    SLOW_PUT_SECONDS = args.slow_put
    DEBUG = args.debug
    HOST = args.host

    print("building the vault envelope (Argon2id MODERATE, this takes a moment)…", flush=True)
    VAULT_BLOB = build_vault_blob(PASSPHRASE)
    print("stub API listening on %s:%d" % (args.bind, PORT))
    print("  in the app: Server URL = http://%s:%d" % (HOST, PORT))
    print("  passphrase  = %s" % PASSPHRASE)
    print("  otp code    = 123456 (fixed)")
    print("  slow put    = %.1fs per object" % SLOW_PUT_SECONDS, flush=True)
    if DEBUG:
        print("  vault key   = %s (debug)" % b64(VAULT_KEY), flush=True)
    ThreadingHTTPServer((args.bind, PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
