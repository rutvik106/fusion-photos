#!/usr/bin/env python3
"""Independently decrypt what the device uploaded to the *stub* API.

The stub stores the completion payload (keyEnvelope, manifest, resources) and the object bytes it
received, so a run can be verified end to end without trusting anything the app printed: pull the
originals off the device, decrypt what the server holds, and compare.

Crypto is done with PyNaCl only, using the same helpers as `verify_upload_decrypts.py`, so a bug
shared by the clients cannot hide here either.

Usage:
    python3 scripts/verify_stub_run.py [--base http://localhost:8080] [--device-dir DIR]

Requires the stub to have been started with --debug, and (for the byte-identity check) each
manifest fileName to exist in --device-dir.

Exit code is non-zero if any asset fails a check.
"""

import argparse
import base64
import hashlib
import json
import os
import sys
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from verify_upload_decrypts import NONCE_BYTES, TAG_BYTES, aead_open, b64d, stream_open  # noqa: E402

BASE = "http://localhost:8080"


def get_json(path):
    with urllib.request.urlopen(BASE + path) as res:
        return json.load(res)


def get_bytes(path):
    with urllib.request.urlopen(BASE + path) as res:
        return res.read()


def resource_of(asset, variant="original"):
    resources = asset["resources"]
    if isinstance(resources, dict):
        return resources[variant]
    return next(r for r in resources if r["variant"] == variant)


def verify_asset(asset, vault_key, device_dir):
    problems = []
    name = "?"
    asset_key = aead_open(asset["keyEnvelope"], vault_key)

    blob = asset["manifest"]
    manifest = json.loads(
        stream_open(b64d(blob["headerB64"]), b64d(blob["blobB64"]), asset_key)
    )
    name = manifest.get("fileName", "?")

    original = resource_of(asset)
    key = original["objectKey"]
    cipher = get_bytes("/_debug/object?key=" + urllib.parse.quote(key))
    plain = stream_open(cipher[:NONCE_BYTES], cipher[NONCE_BYTES:], asset_key)

    if hashlib.sha256(plain).hexdigest() != manifest["originalSha256"]:
        problems.append("plaintext digest does not match the digest inside the manifest")
    if len(plain) != manifest["originalSize"]:
        problems.append("plaintext length does not match the manifest")
    if len(cipher) != len(plain) + NONCE_BYTES + TAG_BYTES:
        problems.append("ciphertext overhead is not exactly +%d" % (NONCE_BYTES + TAG_BYTES))
    if original["ciphertextSize"] != len(cipher):
        problems.append("the declared ciphertextSize does not match the stored object")
    if manifest.get("crypto", {}).get("payload") != "secretstream-xchacha20poly1305":
        problems.append("the manifest does not name the expected payload cipher")

    device_path = os.path.join(device_dir, name) if device_dir else None
    if device_path and os.path.exists(device_path):
        with open(device_path, "rb") as handle:
            local = handle.read()
        if local != plain:
            problems.append("decrypted bytes differ from %s on the device" % name)
        elif len(local) != manifest["originalSize"]:
            problems.append("the device file is not the size the manifest claims")
    elif device_dir:
        problems.append("no local copy of %s to compare against" % name)

    return name, manifest, len(plain), len(cipher), problems


def main():
    global BASE
    parser = argparse.ArgumentParser(description="Verify a stub-API device run (see module docs)")
    parser.add_argument("--base", default=BASE)
    parser.add_argument("--device-dir", default=None, help="directory holding the originals")
    parser.add_argument("--expect", type=int, default=None, help="expected number of assets")
    args = parser.parse_args()

    BASE = args.base

    state = get_json("/_debug/state")
    vault_key = b64d(state["vaultKeyB64"])
    assets = state["assets"]
    print("assets recorded by the stub: %d" % len(assets))
    print("objects stored by the stub:  %d" % len(state["objects"]))
    print("vault key (from the stub):   %s…" % vault_key.hex()[:16])

    if args.expect is not None and len(assets) != args.expect:
        print("FAILED: expected %d assets, the stub recorded %d" % (args.expect, len(assets)))
        return 1
    if not assets:
        print("nothing to verify — run a backup first")
        return 1

    failures = []
    for index, asset in enumerate(sorted(assets, key=lambda a: a.get("seq", 0)), start=1):
        name, manifest, plain_len, cipher_len, problems = verify_asset(
            asset, vault_key, args.device_dir
        )
        marker = "ok  " if not problems else "FAIL"
        print(
            "%s %d. %-16s %8d bytes plaintext -> %8d ciphertext  (%s)"
            % (marker, index, name, plain_len, cipher_len, manifest.get("clientAssetRef", "?")[:40])
        )
        print(
            "        manifest: capturedAt=%s mime=%s previewAvailable=%s quality=%s"
            % (
                manifest.get("capturedAt"),
                manifest.get("mimeType"),
                manifest.get("previewAvailable"),
                manifest.get("quality"),
            )
        )
        for problem in problems:
            print("        - %s" % problem)
        failures.extend("%s: %s" % (name, p) for p in problems)

    if failures:
        print("\nFAILED:")
        for problem in failures:
            print("  -", problem)
        return 1

    print(
        "\nVERIFIED: an independent implementation decrypted every asset the stub received, and "
        "the bytes are byte-identical to the originals on the device."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
