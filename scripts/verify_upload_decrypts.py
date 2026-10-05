#!/usr/bin/env python3
"""Independently decrypt what a client uploaded — without reusing any project code.

This exists to make a claim the unit tests cannot: that the ciphertext sitting in the bucket is
readable by an *independent* implementation. It re-derives the wire format from the documented
contract using PyNaCl only, so a bug shared by the web and Android clients (or by their shared
vector file) cannot hide here.

What it does:
  1. email-OTP sign-in (dev mode echoes the code),
  2. unwraps the vault key from `GET /v1/vault` using the passphrase (Argon2id MODERATE),
  3. reads `GET /v1/sync/changes`, unwraps each asset key from its keyEnvelope,
  4. decrypts the encrypted manifest and prints the metadata the server never sees,
  5. downloads the original from S3 via a presigned URL, decrypts it with secretstream,
  6. checks SHA-256 and length against what the manifest claims, and — when a local file is
     given — that the decrypted bytes are byte-identical to it.

Usage:
    python3 scripts/verify_upload_decrypts.py <email> <passphrase> [local-file-to-compare]
    python3 scripts/verify_upload_decrypts.py <email> <passphrase> --device-dir DIR

Every asset in the catalog is checked; `--device-dir` matches each one to the original of the
same file name in that directory (e.g. a copy pulled off the phone), which is how a whole
device's library can be verified rather than just the newest upload.

Requires PyNaCl (`pip install pynacl`). Requires the API and its S3 backend to be reachable.
"""

import argparse
import base64
import hashlib
import json
import os
import sys
import urllib.request

from nacl import pwhash
from nacl.bindings import (
    crypto_aead_xchacha20poly1305_ietf_decrypt,
    crypto_secretstream_xchacha20poly1305_init_pull,
    crypto_secretstream_xchacha20poly1305_pull,
    crypto_secretstream_xchacha20poly1305_state,
    crypto_secretstream_xchacha20poly1305_TAG_FINAL,
)

BASE = "http://localhost:8080"
NONCE_BYTES = 24
TAG_BYTES = 17  # secretstream ABYTES

# The display-derivative contract, mirrored from both clients (web/src/lib/images.ts and the
# Android DerivativeGenerator): a preview must fit in 1600px on its long edge, a thumbnail in 512.
PREVIEW_MAX_PX = 1600
THUMBNAIL_MAX_PX = 512


def jpeg_size(data):
    """(width, height) from the first SOF marker — JPEG carries no dimensions in its header."""
    if not data.startswith(b"\xff\xd8"):
        raise ValueError("not a JPEG (no SOI marker)")
    i = 2
    while i < len(data) - 9:
        if data[i] != 0xFF:
            i += 1
            continue
        marker = data[i + 1]
        if marker in (0xD8, 0x01) or 0xD0 <= marker <= 0xD7:
            i += 2
            continue
        length = int.from_bytes(data[i + 2:i + 4], "big")
        if 0xC0 <= marker <= 0xCF and marker not in (0xC4, 0xC8, 0xCC):
            return (
                int.from_bytes(data[i + 7:i + 9], "big"),
                int.from_bytes(data[i + 5:i + 7], "big"),
            )
        i += 2 + length
    raise ValueError("no SOF marker found")



def api(path, data=None, token=None, method=None):
    verb = method or ("POST" if data is not None else "GET")
    req = urllib.request.Request(BASE + path, method=verb)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    body = json.dumps(data).encode() if data is not None else None
    if body:
        req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, body) as res:
        return json.load(res)


def b64d(text):
    return base64.b64decode(text)


def aead_open(envelope_b64, key):
    """base64(nonce(24) || ct||tag) -> plaintext (XChaCha20-Poly1305-IETF)."""
    blob = b64d(envelope_b64)
    nonce, ct = blob[:NONCE_BYTES], blob[NONCE_BYTES:]
    return crypto_aead_xchacha20poly1305_ietf_decrypt(ct, None, nonce, key)


def stream_open(header_bytes, frame_bytes, key):
    """header(24) || secretstream frame -> plaintext.

    Today's format writes exactly one FINAL frame (see `Crypto.encryptPayload`), so the frame
    boundary is "everything after the header". A multi-frame payload would need the frames
    walked one at a time.
    """
    state = crypto_secretstream_xchacha20poly1305_state()
    crypto_secretstream_xchacha20poly1305_init_pull(state, header_bytes, key)
    message, tag = crypto_secretstream_xchacha20poly1305_pull(state, frame_bytes)
    if tag != crypto_secretstream_xchacha20poly1305_TAG_FINAL:
        raise SystemExit("payload did not end with a FINAL frame")
    return message


def unwrap_vault_key(wrapped, passphrase):
    kek = pwhash.argon2id.kdf(
        32,
        passphrase.encode(),
        b64d(wrapped["kdfSalt"]),
        opslimit=pwhash.argon2id.OPSLIMIT_MODERATE,
        memlimit=pwhash.argon2id.MEMLIMIT_MODERATE,
    )
    return aead_open(wrapped["wrapped"], kek)


def verify_asset(asset, vault_key, token, compare_path=None, device_dir=None):
    """Decrypt one asset end to end, printing what was found.

    Returns a list of problems; empty means every claim the client made about this asset agrees
    with what the server actually stores.
    """
    failures = []
    asset_key = aead_open(asset["keyEnvelope"], vault_key)
    manifest_blob = asset["manifest"]
    manifest = json.loads(
        stream_open(b64d(manifest_blob["headerB64"]), b64d(manifest_blob["blobB64"]), asset_key)
    )
    name = manifest.get("fileName") or asset["assetId"]
    label = "asset %s (%s)" % (asset.get("seq", "?"), name)

    if manifest.get("crypto", {}).get("payload") != "secretstream-xchacha20poly1305":
        failures.append("%s: the manifest names an unexpected payload cipher" % label)

    resource = api("/v1/resource/%s/original" % asset["assetId"], token=token)
    with urllib.request.urlopen(resource["url"]) as res:
        cipher = res.read()
    plain = stream_open(cipher[:NONCE_BYTES], cipher[NONCE_BYTES:], asset_key)
    digest = hashlib.sha256(plain).hexdigest()

    if digest != manifest["originalSha256"]:
        failures.append("%s: digest mismatch — the manifest does not describe these bytes" % label)
    if len(plain) != manifest["originalSize"]:
        failures.append("%s: length mismatch vs the manifest" % label)

    # /v1/sync returns resources as a map keyed by variant (see scripts/smoke_upload.py).
    resources = asset["resources"]
    original = resources["original"] if isinstance(resources, dict) else next(
        r for r in resources if r["variant"] == "original"
    )
    if original["ciphertextSize"] != len(cipher):
        failures.append("%s: the server's declared ciphertextSize does not match the object" % label)
    overhead = len(plain) + NONCE_BYTES + TAG_BYTES
    if len(cipher) != overhead:
        failures.append(
            "%s: ciphertext overhead is wrong (%d != %d)" % (label, len(cipher), overhead)
        )

    # Display derivatives, when the client produced them: decrypting them here also proves the
    # single keyEnvelope really does cover every resource of the asset.
    for variant, limit in (("preview", PREVIEW_MAX_PX), ("thumbnail", THUMBNAIL_MAX_PX)):
        ref = resources.get(variant) if isinstance(resources, dict) else next(
            (r for r in resources if r["variant"] == variant), None
        )
        if not ref:
            continue
        with urllib.request.urlopen(
            api("/v1/resource/%s/%s" % (asset["assetId"], variant), token=token)["url"]
        ) as res:
            derivative_cipher = res.read()
        derivative = stream_open(
            derivative_cipher[:NONCE_BYTES], derivative_cipher[NONCE_BYTES:], asset_key
        )
        try:
            width, height = jpeg_size(derivative)
        except ValueError as exc:
            failures.append("%s: %s is not a usable image (%s)" % (label, variant, exc))
            continue
        print("        %-9s %4dx%-4d %7d bytes" % (variant, width, height, len(derivative)))
        if max(width, height) > limit:
            failures.append(
                "%s: %s is %dx%d, larger than the %dpx the gallery expects"
                % (label, variant, width, height, limit)
            )

    # Byte identity against the client's own copy, when one is available.
    path = compare_path
    if device_dir:
        candidate = os.path.join(device_dir, name)
        path = candidate if os.path.exists(candidate) else None
    matched = "n/a"
    if path:
        with open(path, "rb") as handle:
            local = handle.read()
        matched = "yes" if local == plain else "no"
        if local != plain:
            failures.append("%s: decrypted bytes differ from %s" % (label, path))
    elif device_dir:
        failures.append("%s: no local copy of %s to compare against" % (label, name))

    marker = "ok  " if not failures else "FAIL"
    print(
        "%s %-30s %9d B plaintext -> %9d B cipher  bytes_match=%s"
        % (marker, name[:30], len(plain), len(cipher), matched)
    )
    print(
        "        mime=%s quality=%s previewAvailable=%s capturedAt=%s"
        % (
            manifest.get("mimeType"),
            manifest.get("quality"),
            manifest.get("previewAvailable"),
            manifest.get("capturedAt"),
        )
    )
    print("        clientAssetRef=%s" % manifest.get("clientAssetRef"))
    return failures


def main():
    parser = argparse.ArgumentParser(description="Independently decrypt what a client uploaded")
    parser.add_argument("email")
    parser.add_argument("passphrase")
    parser.add_argument("local_file", nargs="?", help="one original to compare against")
    parser.add_argument(
        "--device-dir",
        help="directory of originals for a whole device; each asset is matched by file name",
    )
    args = parser.parse_args()

    email, passphrase = args.email, args.passphrase
    compare_path = args.local_file
    device_dir = args.device_dir

    otp = api("/v1/auth/otp/request", {"email": email})
    code = otp.get("devCode")
    if not code:
        print("no devCode returned — this script needs a dev-mode server")
        return 2
    session = api("/v1/auth/otp/verify", {"email": email, "code": code})
    token = session["sessionToken"]
    print("signed in; hasVault:", session["hasVault"])

    wrapped = api("/v1/vault", token=token)["wrapped"]["passphrase"]
    vault_key = unwrap_vault_key(wrapped, passphrase)
    print("vault key unwrapped from the passphrase (Argon2id):", vault_key.hex()[:16] + "...")

    sync = api("/v1/sync/changes?cursor=0", token=token)
    assets = sync["assets"]
    print("assets in the catalog:", len(assets))
    if not assets:
        print("nothing to verify — upload something from a client first")
        return 1

    all_failures = []
    for asset in sorted(assets, key=lambda a: a.get("seq", 0)):
        all_failures.extend(verify_asset(asset, vault_key, token, compare_path, device_dir))

    if all_failures:
        print("\nFAILED:")
        for problem in all_failures:
            print("  -", problem)
        return 1

    print("\nVERIFIED: an independent implementation decrypted every uploaded asset, and every")
    print("size, digest and key the clients claimed agrees with what is actually stored.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

