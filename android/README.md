# Fusion Photos — Android client

Status: **crypto verified on device; sign-in, vault unlock, camera-roll discovery, encrypted
upload (single-PUT *and* resumable multipart), and a durable WorkManager queue that survives a
process kill all work end to end. A user-started backup runs as a foreground service with a live
progress notification, and its on-device run is verified — including an independent decryption of
the bytes it uploaded (see [A backup the user starts is visible](#a-backup-the-user-starts-is-visible)).
It also installs and runs on a physical Android 10 phone (API 29, arm64-v8a): self-check 22/22,
sign-in and vault unlock both work there (see [Testing on a device](#testing-on-a-device-against-a-local-backend)).**

## What the app does today

1. **Encryption self-check** — on every launch the app runs the shared 22-check vector suite
   with the phone's own libsodium and prints the result on screen ("22/22 passed"). If the
   device ever fails to reproduce the web client's bytes, that is visible immediately.
2. **Sign in** — email OTP against the real API (`POST /v1/auth/otp/request`, then
   `/v1/auth/otp/verify`). The code the user types is emailed; in dev the server echoes it.
3. **Unlock** — *create* a vault (first device: vault key wrapped under Argon2id + a recovery
   key, recovery key shown once) or *unlock* it (returning device, including legacy
   pre-recovery-key blobs). The key is held for the session **and** enrolled in the Android
   Keystore, which is what lets a background worker use it after the process is gone; see
   [Background backup](#background-backup-how-it-works-and-what-it-costs).
4. **Discover** — read the camera roll from MediaStore after an explicit runtime permission
   request, filtered to the formats the plan promises (JPEG, HEIC/HEIF, PNG, RAW/DNG).
5. **Queue** — "Queue N photo(s) for backup" writes one durable row per photo (state
   `PENDING`) and hands the job to WorkManager, constrained on *network connected* and
   *battery not low*. The enqueue is deliberately cheap: the idempotency ref comes from
   MediaStore metadata, not from hashing every original, so pressing it with a full camera
   roll does not read gigabytes of untouched files. Because it starts a long job, it also asks
   for notification permission and runs **visibly** (below).
6. **Back up** — a worker drains the queue: read the original, encrypt it under a fresh
   per-asset key (`header(24) || secretstream frames`), wrap that key under the vault key,
   encrypt the manifest (file name, date, digest), reserve object keys, transfer the ciphertext
   straight to S3 with presigned URLs, and commit. Then it marks the row `UPLOADED` with the
   asset id. Small originals go in one PUT; large ones go **multipart and resumable** (below).
   Alongside the original it uploads a **preview (≤1600 px) and thumbnail (≤512 px)**, encrypted
   under the same asset key, so a gallery can draw a photo without fetching the original — see
   [Previews and thumbnails](#previews-and-thumbnails-a-backup-worth-looking-at).

Failure handling follows the plan: exponential backoff with jitter (30 s doubling to a 6 h
ceiling), a dead-letter state after 5 attempts that is kept for inspection rather than
deleted, and an explicit answer for "the object uploaded but the commit failed" — the retry
finds the ref already committed, uploads nothing, and records the asset. A photo that has
simply *gone* (permission revoked, file deleted) is dead-lettered immediately, because
retrying cannot fix it.

Still missing on the upload path, and deliberately not claimed: the original is held in memory
while it is encrypted (plus one part at a time), so streaming it is the remaining memory work.

### Previews and thumbnails: a backup worth looking at

A backup that stores only originals is unreadable in practice — a gallery tile cannot fetch and
decrypt a 20 MB original per photo — so the web client draws tiles from a **thumbnail** (≤512 px)
and the viewer from a **preview** (≤1600 px). Until this increment the Android client uploaded
only the original, so a library backed up from the phone showed the web client's explicit
"preview unavailable" tile: the photos were there, decryptable and byte-exact, with nothing small
to draw. Both derivatives are now generated locally before upload and encrypted under the same
asset key as the original. The sizes and JPEG quality (0.82) mirror `web/src/lib/images.ts`,
because both clients write the same manifest and a reader must not have to know which client
produced a library.

Two details that are easy to get wrong, and are pinned by tests:

- **Orientation.** These are what people actually look at, so a sideways thumbnail is worse than
  none. `ImageDecoder` (API 28+) bakes the EXIF orientation in and reports the oriented size;
  below that the rotation comes from MediaStore's `ORIENTATION` column, because `BitmapFactory`
  ignores EXIF. MediaStore reports *degrees* (0/90/180/270), not the EXIF 1-8 tag encoding —
  reading it as EXIF would rotate upright photos.
- **Memory.** A 12 MP photo is ~48 MB decoded, so decoding is bounded to preview size up front
  (target size / sample size) and the thumbnail is scaled from that bitmap instead of decoding the
  file twice.

Derivatives are **best effort**: a photo this device cannot decode is still backed up, with
`previewAvailable: false` in the manifest — never dropped, never silently different. Verified on
the Nokia against the real API:

```
preview   1200x1600    77659 bytes        thumbnail   384x512    14651 bytes
preview     500x500    44961 bytes        thumbnail   500x500    44961 bytes   (already small)
```

`scripts/verify_upload_decrypts.py` now decrypts the derivatives as well — which doubles as proof
that one keyEnvelope really covers every resource of an asset — and checks each is a valid JPEG
inside its size limit; macOS `sips` agrees on the dimensions independently.

### A backup the user starts is visible

The plan asks for "the appropriate user-visible transfer mechanism for long manual jobs", so a
manual run is promoted to a **foreground service** with a progress notification, while the
periodic heartbeat stays invisible (a permanent notification for unrequested work would be
rude). Concretely:

- `BackupWorker` calls `setForeground(...)` when its input says `manual=true`, and re-posts the
  same `ForegroundInfo` on every progress tick, which updates the notification in place.
- The service is typed `dataSync` — the correct type for uploading the user's own data, and
  required by Android 14+. WorkManager's own service is given the type by a manifest merge
  rather than by declaring a competing service.
- `POST_NOTIFICATIONS` is requested when the user starts a backup, and a refusal does **not**
  block the work: the queue runs anyway, the screen says progress will not be visible, and the
  foreground notification is posted regardless (Android requires a foreground service to be
  visible).
- The wording ("Preparing…", "2 uploaded · 3 still to go") is built by pure functions and
  unit-tested, so it cannot drift into something misleading.

**What is verified so far, and what is not:**

| Check | Status |
|---|---|
| Progress/finished wording and the progress bar | unit-tested (6 cases) |
| Merged manifest in the built APK: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`, and `foregroundServiceType=0x1` (dataSync) on `SystemForegroundService` | verified with `aapt2 dump xmltree` on the APK |
| Notification channel created at process start | verified on the device (`mId='fusion-backup'`, importance LOW) |
| Notification permission prompt, foreground service and progress notification during a real backup | **verified on a device** — see below |
| The bytes that path uploaded | independently decrypted and byte-compared against the device originals — see below |

That last part of the increment was blocked for a while: driving it needs a backend, and Docker
Desktop would not start without GUI interaction. The answer was not to keep waiting but to take
the backend out of the loop — [`scripts/stub_api.py`](../scripts/stub_api.py) is a small harness
that speaks the same wire shapes (OTP, vault, upload-session, multipart, presigned PUTs) and
hands out a **real** Argon2id + XChaCha20-Poly1305 vault envelope, so the client under test is
the real one. It is explicitly *not* the service and validates nothing, so it proves client
behaviour only; the same upload path was verified against the real API in earlier increments.

What the device run showed (Pixel_9a, app backgrounded with `KEYCODE_HOME` the moment the backup
started):

| Observation | Evidence |
|---|---|
| Foreground service up for the whole job | `dumpsys activity services` reported `isForeground=true` for ~13 s, then the service stopped; `ActivityManager: Stop FGS timeout` names `SystemForegroundService` |
| The progress notification is real and live | `dumpsys notification --noredact` read "Backing up photos" with the text advancing `Preparing…` → `1 uploaded · 6 still to go` → … → `6 uploaded · 1 still to go`, and the shade screenshot shows the same |
| It is cleaned up afterwards | the progress notification (`id=4101`) is gone once the worker returns; the finished one reads "Backup finished / 7 uploaded" |
| A refusal to grant notifications is handled | before granting, the screen plainly said "Notifications are off, so a backup you start cannot show progress — it still runs, but Android may defer it" |
| The queue ends in a sane state | `7 uploaded · 0 failed`, and the server-side log shows exactly 7 sessions, 7 object PUTs, 7 completions |

Running it against a clean stub also demonstrated the idempotency path on the device: re-queueing
the same seven photos produced **7 session lookups and zero PUTs** ("0 uploaded, 7 already there")
— the second run cost no bytes.

Finally, [`scripts/verify_stub_run.py`](../scripts/verify_stub_run.py) reads what the harness
received and decrypts it with PyNaCl alone — no project code. For all seven assets it recovered
the asset key from `keyEnvelope` under the vault key, decrypted the manifest and the object, and
found the plaintext **byte-identical to the file on the device**, with the ciphertext exactly
+41 bytes (24-byte header + 17-byte tag) and the declared `ciphertextSize` matching the stored
object. So "the notification said done" and "the bytes are right" are two separate claims, and
both were checked.

### Running that device check without a backend

The harness and the verifier are both in `scripts/`, and neither is part of the app:

```bash
python3 scripts/stub_api.py --port 8080 --slow-put 2.0 --debug
#   in the app: Server URL = http://10.0.2.2:8080   passphrase = correct-horse-battery-staple
#   OTP code is fixed at 123456
# then, once a backup has run:
adb pull /sdcard/DCIM/Camera /tmp/device_photos
python3 scripts/verify_stub_run.py --expect 7 --device-dir /tmp/device_photos
```

`--slow-put` exists because a local harness is far too fast to watch: a 12 MiB upload finishes in
well under a second, so the first attempt at this run looked like nothing had happened until
`logcat` showed the foreground service having come and gone. Two seconds per object makes the
progress notification observable. `--debug` exposes `GET /_debug/state` and
`/_debug/object?key=…` (and prints the vault key), which is what lets the verifier decrypt the
run; it is off by default because none of that belongs in a service.

The files used were pushed with `adb push`, so MediaStore has no capture date for them and the
encrypted manifest records `capturedAt: null`. That is the manifest doing its job — "absent"
rather than an invented timestamp — and the verifier prints the field so the distinction is
visible.

### The device check found a real bug: location was being stripped

This is what the byte comparison is *for*. Backing up the phone's own camera roll and diffing the
decrypted result against the file on the phone showed one photo differing from the original in
**34 bytes of 1,989,500**. The ciphertext was internally consistent (the manifest digest matched
the decrypted plaintext, which is why nothing else caught it) but those bytes were the **GPS IFD**:

```
DIFF GPS 0x0001 GPSLatitudeRef   device=4e ('N')      uploaded=00
DIFF GPS 0x0002 GPSLatitude      device=17/1, 1/1 …    uploaded=0/1, 0/1 …
DIFF GPS 0x0003 GPSLongitudeRef  device=45 ('E')      uploaded=00
DIFF GPS 0x001d GPSDateStamp     device="2025:10:19"  uploaded=zeros
```

The cause is the platform, not the crypto: **Android 10+ MediaProvider zeroes a media file's
location metadata for apps that do not hold `ACCESS_MEDIA_LOCATION`.** The tag structure survives
with zeroed values, so every photo still *looks* fine and the app's own docstring ("the pristine
file — no EXIF stripping") was quietly false. On Android 10+ a backup was storing every photo
with its location blanked.

The fix is in `MediaBytes` and the manifest: declare `ACCESS_MEDIA_LOCATION`, request it alongside
photo access (only where the platform has it), and read through
`MediaStore.setRequireOriginal(uri)` — which asks MediaProvider for the unredacted file and needs
that permission. If the user declines, the read falls back to the redacted one rather than failing
the upload, and the screen says plainly that location will be missing. `MediaPermissionsTest`
pins the SDK rules and the opt-out condition (4 cases).

After the fix, the same two photos verify with `bytes_match=yes` and `identical bytes: True` —
including the GPS tags that were being lost.


Above 32 MiB of ciphertext the client switches to a multipart upload: `multipart/init` returns
every part's URL, the parts are PUT individually, and `multipart/complete` assembles them. The
slicing is the server's own rule (`ceil(size/parts)`, remainder in the last part), pinned on
both sides by tests.

The resume design is worth stating because it is *smaller* than it looks: **the client stores
nothing about parts.** A retry re-runs `POST /v1/upload-session` with the same client ref, and
the server hands back the same asset and the same multipart upload; the client then asks
`multipart/status` which parts S3 already holds and re-sends only the rest. So a process kill
in the middle of a 4 GB upload costs the parts that were in flight, not the whole file.

One subtlety that bit the tests first: the 32 MiB threshold applies to the **ciphertext**, which
is the plaintext plus 41 bytes of header and tag. So a 40 MiB photo is a 40 MiB + 41 B payload
and splits into six parts, not five. The tests now derive expectations from the reserved size
instead of assuming a tidy number.

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
| backup | **`1 backed up · 1 photo(s) processed`** — the photo was encrypted locally and uploaded |
| backup again | **`0 backed up, 1 already present`**, and the catalog still held exactly **1** asset (no duplicate) |

### The upload is verified by an independent implementation

`scripts/verify_upload_decrypts.py` does not reuse a line of project code: it re-implements the
wire format with PyNaCl and reads back what the phone stored. Run against that upload it
printed:

```
vault key unwrapped from the passphrase (Argon2id): 59833c23d5eb011e...
asset key unwrapped from keyEnvelope: 0c440730ed3e3444...
decrypted manifest (the server never sees these):
    fileName: IMG_0001.png
    mimeType: image/png
    quality: original
    source: upload
    originalSize: 237842
    originalSha256: 216d0e29906d0165571f97e059387f5e58f0b4c16e79cde2f5f1237d95fad99f
    clientAssetRef: android:21:216d0e29906d0165
downloaded ciphertext: 237883 bytes
decrypted plaintext: 237842 bytes
  sha256: 216d0e29906d0165571f97e059387f5e58f0b4c16e79cde2f5f1237d95fad99f
local file: /tmp/app_screen3.png 237842 bytes
  byte-identical to the local original
VERIFIED
```

That is the whole chain proven from the outside: Argon2id → vault key → asset key →
encrypted manifest → the object in S3, ending in bytes identical to the original that was on
the phone, with exactly the documented +41 bytes of ciphertext overhead (24-byte header +
17-byte tag). The server, meanwhile, only ever held sizes and ciphertext.

Running it for real earned its keep again: the first pass showed `Vault key: not held` right
after creating a vault. `VaultManager.create()` had published the wrapped envelopes but
**dropped the vault key**, so the app held no key and could not have encrypted a single
upload. It now returns the key, and `VaultManagerTest` fails if that ever regresses.


## Background backup: how it works, and what it costs

The plan asks for WorkManager with network/battery/retry constraints and jobs that "recover
across restart", while keeping the vault key in "device secure storage". Those two demands pull
in opposite directions, so the trade-off is made explicit rather than hidden:

- **The key is enrolled in the Android Keystore** — the wrapping key is generated inside the
  Keystore and never leaves it; only AES-GCM ciphertext is written to private storage. No
  user-authentication requirement is set, because the point is to work while the phone sits
  locked in a pocket.
- **What that buys:** backups continue after the app process dies, which is the entire point of
  a backup app.
- **What it costs:** while enrolled, an attacker who holds the *unlocked* device and can run
  code inside this app's sandbox can reach the key. That was not true while the key was
  memory-only. Sign-out clears it, `allowBackup=false` keeps it out of device backups, and a
  factory reset or "clear data" removes it — the passphrase or recovery key remains the only
  way in afterwards.
- **If the Keystore is unusable**, nothing pretends to be durable: `KeyVault.save` reports
  failure and the screen says background backup is waiting for an unlock.

The queue is one SQLite table with explicit states (`PENDING`, `IN_FLIGHT`, `UPLOADED`,
`FAILED`, `DEAD`). The claim is a conditional `UPDATE` inside a transaction, so two workers
cannot take the same row, and every run starts by returning `IN_FLIGHT` rows to `PENDING` —
which is how a process killed mid-upload resumes.

### Observed on the device, not just asserted

| What was done | What the device did |
|---|---|
| queue while the network is off | `WM-GreedyScheduler: Constraints not met: Cancelling work` — the worker never ran, and the screen showed `1 pending` |
| re-queue the same photo | `1 queued, 1 already known` — the row was not reset |
| `am kill` the process, then restore the network (**no app launch**) | the system started a fresh process by itself and ran the worker: `Data {uploaded : 1, processed : 1, remaining : 0}`, `Worker result SUCCESS` |
| check the result independently | the background-uploaded photo decrypts byte-identical to the local original |
| queue a **40 MiB** original | `Queue: 1 in flight · 4 uploaded` — the four known photos resolved instantly as already committed, and the large one went up in six parts over ~35 s to `0 in flight · 5 uploaded` |
| check the assembled object independently | `downloaded ciphertext: 41943081` (= the 40 MiB original + 41 bytes), decrypts **byte-identical** to the local file |
| wipe the local queue (the test run uninstalls the app), then re-queue | all four already-uploaded photos were re-resolved as `committed` with **zero** re-uploaded bytes — the server's idempotency carries the state when the client's does not |

Two honest footnotes from that session:

- **`am force-stop` is not the same as a kill.** After a force-stop the app sits in Android's
  *stopped* state and JobScheduler will not start it — nothing ran until the app was opened
  again (whereupon the queue resumed and finished, nothing lost). That is the documented
  behaviour the plan says to respect, and the UI never claims otherwise.
- A force-stopped app therefore needs one user action to resume. Anything else would mean
  fighting the OS.

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
./gradlew :app:testDebugUnitTest          # 55 checks of the app's own logic (no device)
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
- **the upload pipeline** (`PhotoUploaderTest`) — runs a real upload against a fake server,
  then **decrypts what was sent**: the object must decode back to the original bytes, the
  manifest must be readable metadata while staying opaque to the server, the declared
  `ciphertextSize` / manifest size must match the server's own validation, an idempotent replay
  must upload nothing, a failed PUT must not commit, and the client ref must be stable for the
  same bytes.
- **the durable queue** (`BackupRunnerTest`) — real libsodium, a fake server and a fake store,
  driving every state: a row left `IN_FLIGHT` by a killed process is picked up again, a failed
  transfer waits out its backoff, repeated failures dead-letter rather than retry forever, a
  missing vault key stops the run *without claiming anything*, losing the key mid-run stops
  cleanly between items, an unreadable original is dead-lettered immediately, re-queueing the
  camera roll never resurrects finished work, a commit that failed **after** the object was
  stored converges on the retry without uploading twice, and a permanent failure (an expired
  session) dead-letters at once instead of burning five retries.
- **large originals** (`MultipartUploadTest`) — real encryption, then it inspects exactly which
  parts were sent: the parts must cover every ciphertext byte with the server's slicing, a
  resumed upload must send **only** the missing parts and must not re-initialise the upload, a
  part the server reports with the wrong size must be re-sent, and an expired session or a
  wrong assembled size must fail permanently rather than loop.
- **what the user is told** (`BackupNotificationsTest`) — the progress line claims nothing
  before work starts, reports both what is done and what is left, the bar stays in 0-100, a
  locked vault reads as something the user must act on, the finished summary accounts for
  uploads, already-present items, failures, retries and what is still queued, and a clean run
  does not invent problems.

The self-check screen has run green on `Pixel_9a` (arm64-v8a, SDK 37, a 16 KB page-size
image):

```
testsuite AndroidSodiumVectorTest  tests="1" failures="0" errors="0" skipped="0"
```

`android/local.properties` (pointing at an SDK) is gitignored. CI runs the two JVM suites
(`:crypto:test`, `:app:testDebugUnitTest`); the instrumentation test is run manually.

To check the *stored* result from outside the app (needs the API + S3 reachable and PyNaCl):

```bash
python3 scripts/verify_upload_decrypts.py <email> <passphrase> [local-file-to-compare]
```

### Testing on a device against a local backend

Two host addresses matter, and they are different:

| What | Used by | From an emulator | From a physical device |
|---|---|---|---|
| API (`:8080`) | this app, via the Server URL field | `http://10.0.2.2:8080` | `http://127.0.0.1:8080` with `adb reverse tcp:8080 tcp:8080`, or your machine's LAN IP |
| S3 presigned URLs (`:9000`) | the upload/download PUT+GET | needs `adb reverse tcp:9000 tcp:9000` | same, or set `S3_PUBLIC_ENDPOINT` to a reachable host |

The presigned URL host is baked in by the server (`S3_PUBLIC_ENDPOINT`, `localhost:9000` in
compose), so on an emulator the object transfer fails with a connection refused until the port
is reversed onto the host.

Cleartext is fail-closed in release builds: `res/xml/network_security_config.xml` permits plain
HTTP for `10.0.2.2`/`localhost`/`127.0.0.1` only, so a misconfigured base URL fails instead of
leaking a session token. Debug builds relax that (`src/debug/res/xml/network_security_config.xml`,
a resource overlay that release does not include), because a physical device has no `10.0.2.2`
and may need to reach a LAN address. The sign-in hint says which policy is in force. Verified by
reading the merged resources of each variant:

```
app/build/intermediates/packaged_res/release/…/xml/network_security_config.xml
    <domain-config cleartextTrafficPermitted="true"> 10.0.2.2 / localhost / 127.0.0.1
app/build/intermediates/packaged_res/debug/…/xml/network_security_config.xml
    <base-config cleartextTrafficPermitted="true" />
```

**A worked example, on a real phone** (Nokia 6.1 Plus, Android 10 / API 29, arm64-v8a) against
the stub harness — this is the recipe that was actually run, and it needs no LAN access at all:

```bash
python3 scripts/stub_api.py --port 8080 --host 127.0.0.1 --debug   # --host makes the
                                                                   # presigned URLs 127.0.0.1
adb -s <serial> reverse tcp:8080 tcp:8080
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
# in the app: Server URL = http://127.0.0.1:8080, then sign in and unlock
```

Two things that only show up on a physical device, both hit during that run:

- **`input tap` can land on the IME.** Some controls (the Verify button) sit under the keyboard
  on a shorter screen, so the tap presses a key instead of the button and the request is never
  sent. Send `keyevent 4` and check `dumpsys input_method` reports `mInputShown=false` first.
- **Argon2id is noticeably slower** on a mid-range phone than on a desktop or emulator, so the
  unlock screen needs longer before it settles.

The `--host` flag exists for exactly this: without it the harness hands back `10.0.2.2` URLs,
which are unreachable from a phone even though sign-in itself works.

#### Untethered: Wi-Fi or Tailscale

You do not need the cable. Both work, and the trap is the same in each case: **the app's Server
URL only covers the control plane — the object PUT/GET host is chosen by the *server***, so
sign-in can succeed while every upload fails.

| | API host (app's Server URL) | Object host (set on the server) |
|---|---|---|
| Same Wi-Fi | `http://<mac-lan-ip>:8080` | stub: `--host <mac-lan-ip>` · real API: `S3_PUBLIC_ENDPOINT=<mac-lan-ip>:9000` |
| Tailscale | `http://100.x.y.z:8080` (this Mac: `100.76.35.112`) | stub: `--host 100.x.y.z` · real API: `S3_PUBLIC_ENDPOINT=100.x.y.z:9000` |

Verified on the Nokia (Android 10), on the same Wi-Fi, with no `adb reverse` in play:

```
from the phone: curl http://192.168.31.43:8080/v1/me      -> 200
from the phone: curl http://100.76.35.112:8080/v1/me      -> 000   (no Tailscale app installed)
# then, through the app itself: sign in -> verify -> unlock, all over the LAN address
```

Both the harness and compose already listen on all interfaces, so no binding changes are needed;
only the two addresses above. Reaching a **`100.x` (CGNAT) address needs the phone on the
tailnet**, i.e. Tailscale installed and signed in on the device.

Test with `curl` *and* with the app: `curl` bypasses the app's network-security policy, so only
the in-app run proves cleartext to that host is actually permitted. On a **release** build it is
not — a tailnet address is neither `10.0.2.2`, `localhost` nor `127.0.0.1`, so release-grade
testing over Tailscale wants HTTPS (`tailscale serve` issues a real certificate) or an explicit
entry in `network_security_config.xml`.

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

- The foreground service for long user-started backups is built, unit-tested and now **verified on
  a device** (permission prompt → progress notification → completion), with the uploaded bytes
  independently decrypted. The run used the local harness rather than the real API because Docker
  was down, so the service's behaviour is proven while the *harness* proves nothing about the
  server; see the table above. The periodic heartbeat is deliberately ordinary background work,
  so the OS may still defer *it*.
- Preview/thumbnail derivatives are generated on the phone, so the gallery draws a tile without
  downloading an original; a photo is still held in memory while it is encrypted (plus one
  multipart part at a time), and streaming that is the remaining memory work.
- No scheduled reconciliation between local and remote state, and no "free up space" cleanup.
- **An expired upload session (24 h) is a dead end for that client ref.** The server reuses the
  existing session row for a known ref and refuses to complete an expired one, so the client
  cannot re-reserve; it reports this as a permanent failure and dead-letters the row with the
  reason rather than retrying. A server-side "re-reserve a new session for the same ref" path is
  the real fix, and is not built.
- No in-app gallery of the remote library, no delete/restore.
- No passkeys or phone OTP (email OTP only), matching the web client's current state.
- The idempotency ref changed in this increment (`android:<id>:<size>:<modified>`), so a
  library uploaded under the older digest-based ref would be uploaded once more. A real
  migration hazard to resolve before any public release.
- RELRO alignment of the three prebuilt native libraries (see above).
- The connected (instrumentation) test is run manually; CI runs the two JVM suites.
- Only `arm64-v8a` has been exercised on device; the APK ships every ABI the AAR provides.

