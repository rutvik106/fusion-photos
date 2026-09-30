# Fusion Photos — Android client

Status: **crypto verified on device; sign-in, vault unlock and camera-roll discovery work.
The durable upload queue is next.**

## What the app does today

1. **Encryption self-check** — on every launch the app runs the shared 22-check vector suite
   with the phone's own libsodium and prints the result on screen ("22/22 passed"). If the
   device ever fails to reproduce the web client's bytes, that is visible immediately.
2. **Sign in** — email OTP against the real API (`POST /v1/auth/otp/request`, then
   `/v1/auth/otp/verify`). The code the user types is emailed; in dev the server echoes it.
3. **Unlock** — *create* a vault (first device: vault key wrapped under Argon2id + a recovery
   key, recovery key shown once) or *unlock* it (returning device, including legacy
   pre-recovery-key blobs). The vault key lives in memory only — never on disk.
4. **Discover** — read the camera roll from MediaStore after an explicit runtime permission
   request, filtered to the formats the plan promises (JPEG, HEIC/HEIF, PNG, RAW/DNG).

Uploading the discovered photos is deliberately **not** wired up yet; the next increment adds
the durable WorkManager queue, so nothing is claimed that the app cannot yet do.

## Verified end to end, on a device, against the real API

The whole chain was driven on a Pixel_9a emulator (arm64-v8a, SDK 37, 16 KB page-size image)
talking to the local Go API, by scripting the UI and reading the resulting screen:

| Step | Observed |
|---|---|
| launch | `Encryption self-check: 22/22 passed` |
| send code | the app called `/v1/auth/otp/request`; the screen showed `Dev code: 493008` (the dev-mode code the server returns) |
| verify | `/v1/auth/otp/verify` accepted it and the app moved on |
| create vault | Argon2id ran, the vault was `PUT` to the server; signing in again then said **`Unlock your vault`** — i.e. the server really stored it |
| unlock | `Vault key: unlocked in memory` |
| permission | the app requested photo access; the system dialog appeared |
| discovery | with `DCIM/Camera/IMG_0001.png` and `Pictures/not_camera.png` both on the device, the app reported **`1 photo(s) in the camera roll`** and listed only `IMG_0001.png` (232 KiB, `image/png`) |

Running it for real earned its keep: the first pass showed `Vault key: not held` right after
creating a vault. `VaultManager.create()` had published the wrapped envelopes but **dropped
the vault key**, so the app held no key and could not have encrypted a single upload. It now
returns the key, and `VaultManagerTest` fails if that ever regresses.


## Why this starts with crypto, not UI

A native client must re-implement the end-to-end encryption wire format. The project plan
is explicit: *"do not invent nonce management or a proprietary cipher"*, and
*"shared contracts … crypto format and test vectors"*. So the first deliverable is a
Kotlin implementation that is **provably byte-compatible with the web client**, and only
then the Android UI/discovery/upload on top of it.

## Module layout

```
android/
  crypto/        pure JVM module: the E2EE wire format + the shared vector suite
  app/           Android application: Compose shell, AndroidSodium adapter, on-device self-check
```

`:crypto` has no Android dependency on purpose: its tests run on the host JVM (fast, and
CI needs no SDK or emulator), and the Android app consumes it unchanged. The one adapter,
`LibSodiumAdapter`, takes the shared `com.goterl.lazysodium.Sodium` API as `compileOnly`,
so the app passes `SodiumAndroid` and the JVM tests pass `SodiumJava` — identical code.

## The cross-client contract

`../shared/crypto-vectors.json` is the single source of truth for the wire format. Both
clients are checked against the same file:

| Client | Verified by |
|---|---|
| Web (TypeScript) | `web/tests/crypto-vectors.test.cjs` (`npm run test:crypto`) |
| Android (Kotlin) | `android/crypto/src/test/.../CryptoVectorsTest.kt` |

`web/tests/gen-crypto-vectors.cjs` regenerates the file, but CI only ever *verifies* it, so
the format cannot drift silently. The vectors pin:

- Argon2id MODERATE (ops 3, mem 256 MiB, Argon2id13) → the KEK, and the derived key itself
- vault key wrap: `base64(nonce(24) || AEAD_tagged_ciphertext)`
- asset key envelope: `base64(nonce(24) || ct)` — 96 base64 chars
- recovery-key wrap (no KDF)
- resource payloads: `header(24) || secretstream frames` (one FINAL frame)
- encrypted manifests: header and ciphertext as two base64 fields

The Kotlin tests assert both **decrypt** (it can read web-produced bytes) and **encrypt**
(its output is byte-identical for a fixed nonce) — the direction that would otherwise
silently corrupt uploads.

## Build & test

```bash
cd android
./gradlew :crypto:test                    # 22 vector checks on the host (no SDK needed)
./gradlew :app:testDebugUnitTest          # 25 checks of the app's own logic (no device)
./gradlew :app:assembleDebug              # builds app/build/outputs/apk/debug/app-debug.apk

# On-device verification (needs a booted emulator/device). ANDROID_SERIAL keeps the test
# off any other attached device:
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest
```

`:app:testDebugUnitTest` covers the logic that would otherwise only fail on a real phone:

- **upload planning** (`UploadPlan`) — single PUT vs multipart, and the exact byte ranges for
  each part. The expected ranges are copied from the **server's** own tests
  (`api/cmd/server/validation_multipart_test.go`) so client and server can never disagree
  about which bytes belong to part *n*. Its numbers mirror `web/src/lib/upload.ts`
  (32 MiB threshold, 8 MiB-granular part count) and the server's rules (≥5 MiB per non-final
  part, ≤1024 parts).
- **vault wire shapes** (`Dtos`) — the structured blob, the legacy flat envelope, a
  recovery-only blob, and an unknown crypto version.
- **supported media** (`SupportedMedia`) — the promised format list including the case where
  Android reports `application/octet-stream` for RAW, plus the DCIM/Screenshots rules.
- **the vault round trip** (`VaultManagerTest`) — create, unlock, recover, and a wrong
  passphrase failing closed. It runs a real libsodium on the JVM (`LibSodiumAdapter` +
  `SodiumJava`), so the Argon2id wraps under test are the real ones; that makes this suite
  seconds rather than milliseconds, which is the honest price of testing real crypto.

The self-check screen has run green on `Pixel_9a` (arm64-v8a, SDK 37, a 16 KB page-size
image):

```
testsuite AndroidSodiumVectorTest  tests="1" failures="0" errors="0" skipped="0"
```

`android/local.properties` (pointing at an SDK) is gitignored. CI runs the two JVM suites
(`:crypto:test`, `:app:testDebugUnitTest`); the instrumentation test is run manually.

## 16 KB page sizes (found on device, partly fixed)

The emulator image used for verification is a **16 KB page-size** system image, so the
platform checks every native library on launch. The first build failed that check and showed
the system's "Android App Compatibility" dialog:

```
This app isn't 16 KB compatible. LOAD segment and RELRO segment alignment checks failed.
  lib/arm64-v8a/libsodium.so : LOAD segment not aligned
  lib/arm64-v8a/libjnidispatch.so : RELRO segment not aligned
  lib/arm64-v8a/libandroidx.graphics.path.so : Unknown error
```

What was wrong, and what was done:

| Library | Problem | Status |
|---|---|---|
| `libsodium.so` | `lazysodium-android:5.1.0` ships `PT_LOAD p_align=4096` — cannot be mapped with 16 KB pages at all | **fixed**: upgraded to `lazysodium-android:5.2.0` (`p_align=16384`); the on-device vector test still passes |
| `libjnidispatch.so` | RELRO starts off a 16 KB boundary (JNA 5.14…5.17, all versions checked, are the same) | needs the library rebuilt; no published version fixes it |
| `libandroidx.graphics.path.so` | same RELRO issue (androidx graphics-path 1.0.1) | needs the library rebuilt |

Verify with the checker that found this (it parses the ELF program headers and the APK's zip
offsets, and exits non-zero on failure):

```bash
python3 scripts/check_16kb_alignment.py android/app/build/outputs/apk/debug/app-debug.apk
```

Current state of this APK: **all three libraries are LOAD-aligned and stored at 16 KB-aligned
offsets inside the APK** (AGP 8.7 zipaligns uncompressed `.so` entries), so they map and run
correctly — the crypto vector test passes on the 16 KB image. What remains is RELRO
alignment, which only a rebuild of those three upstream libraries can fix; until then the
platform keeps the app in "page size compatible mode" and shows the warning. This must be
resolved before a Play release on 16 KB devices, and is recorded as an open item rather than
waved away.

## Not done yet (honest list)

- **No upload queue** — discovery works, but nothing is encrypted or uploaded from the phone
  yet: no WorkManager queue, no multipart/resume, no gallery of the remote library.
- No passkeys or phone OTP (email OTP only), matching the web client's current state.
- RELRO alignment of the three prebuilt native libraries (see above).
- The connected (instrumentation) test is run manually; CI runs the two JVM suites.
- Only `arm64-v8a` has been exercised on device; the APK ships every ABI the AAR provides.

