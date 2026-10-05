# Fusion Photos

Private photo archive with end-to-end encryption — implementing
[`photo-backup-app-plan.md`](./photo-backup-app-plan.md), starting with the **web
surface + local backend** (Phase 1 foundation).

## What exists today (foundation slice)

| Piece | Tech | Status |
|---|---|---|
| Web client | Vue 3 + TypeScript + Vite, Pinia, IndexedDB | Working |
| UI | Google Photos design language — app bar, nav drawer, date-grouped grid, selection bar, full-screen viewer, light/dark | Working |
| E2EE | libsodium (sumo): Argon2id passphrase→KEK, XChaCha20-Poly1305 vault/asset keys, secretstream payloads | Working |
| Recovery key | Second key envelope (recovery-key-wrapped vault key); one-time display at creation, unlock via key on any device, late enrollment | Working |
| API | Go, control-plane only (auth, vault, upload sessions w/ strict validation, sync, presigned access, trash lifecycle, purge worker) | Working |
| Media store | Real AWS S3 (dev default: MinIO via compose) | Working |
| Remote protection | An asset is marked `protected` only after **every** resource is verified present in the independent replica bucket | Working (set `S3_REPLICA_BUCKET`) |
| Backup status | `GET /v1/backup/status` — total / protected / unreplicated / pending counts, last commit & last replication check | Working |
| Backup status UI | Header shield panel (protected / awaiting copy / last backup, **Refresh**) + a per-photo **Protection** row in Details | Working |
| Complete export | Immutable snapshot → resumable client-side export: real files, versioned manifest, integrity report, Live Photo companions, file-level resume | Working (browser) |
| Browser limits | Best-effort persistent storage, eviction detection + auto re-sync, expired-URL re-signing, session-expiry recovery, one-file-at-a-time fallback | Working |
| Reconciliation | Hourly worker: expires abandoned uploads, aborts their orphaned multipart uploads, prunes auth tables | Working |
| Multipart uploads | ≥32 MiB originals: server-owned multipart IDs, presigned per-part PUTs, per-part retry, resume via OPFS staging + ListParts reconcile | Working |
| Trash lifecycle | 30-day cloud trash → scheduled purge worker (6h) with best-effort S3 object deletion | Working |
| Google Photos Takeout import | `/import` wizard: folder scan → sidecar/album/Live-Photo pairing → local SHA-256 dedupe → batched reuse of the upload pipeline → integrity report | Working |
| Live Photo companions | Motion half stored as an `original_motion` resource on the same compound asset; the server refuses to complete "half a Live Photo" | Working |
| Idempotent reservations | Opaque `client_ref` per attempt + unique `(user_id, client_ref)`: retries reuse the same asset and never double-charge quota | Working |
| Auth | Email OTP — crypto-random codes, HMAC-peppered at rest, SES delivery, per-email/IP rate limits, attempt cap | Working |
| Gallery | Local-first: IndexedDB catalog paints instantly, incremental cursor sync in background | Working |
| Upload | Local thumbnails/previews → encrypt → presigned PUT (single or multipart) → server-side verify → catalog commit | Working |
| Round-trip verify | Per-asset SHA-256 check (acceptance gate: byte-identical originals) | Working |
| CI | GitHub Actions: gofmt/vet/test + vue-tsc/build + crypto test | Working |

## Prerequisites

- Node.js ≥ 20 (`node --version`)
- Go ≥ 1.24 (`go version`)
- Docker Desktop (`docker compose version`)

## Run it

```bash
# 1. Infrastructure + API (Postgres, MinIO, Go API on :8080)
docker compose up --build

# 2. Web client (separate terminal, :5173, proxies /v1 to the API)
cd web && npm install && npm run dev
```

### …without Docker

Docker is convenient but not required — the API migrates its own schema on boot and creates its
own bucket, so all it needs is a Postgres and an S3-compatible store. This is how it was run
while Docker Desktop was unavailable, and it serves the same web client:

```bash
# Postgres (Homebrew) — a role/db matching compose's DATABASE_URL
createdb fusion && psql -d fusion -c "CREATE ROLE fusion LOGIN SUPERUSER PASSWORD 'fusion'"

# MinIO (Homebrew)
MINIO_ROOT_USER=fusion-dev MINIO_ROOT_PASSWORD=fusion-dev-secret \
MINIO_API_CORS_ALLOW_ORIGIN=http://localhost:5173 \
  minio server --address :9000 --console-address :9001 /tmp/fusion-minio

# API
cd api && DATABASE_URL='postgres://fusion:fusion@localhost:5432/fusion?sslmode=disable' \
  S3_ENDPOINT=localhost:9000 S3_PUBLIC_ENDPOINT=localhost:9000 \
  S3_ACCESS_KEY=fusion-dev S3_SECRET_KEY=fusion-dev-secret S3_BUCKET=fusion-media \
  DEV_MODE=true go run ./cmd/server

# Web client
cd web && npm run dev
```

Two details worth keeping: `S3_PUBLIC_ENDPOINT` is the host baked into presigned URLs (so it must
be reachable *from the client*), and MinIO needs `MINIO_API_CORS_ALLOW_ORIGIN` for the browser,
which fetches ciphertext directly and decrypts it locally.

Open http://localhost:5173:

1. Enter an email → **Send sign-in code** (dev mode shows the code on screen).
2. First login: choose a **vault passphrase (≥12 chars)** — this derives your key.
   It never leaves the device; losing it and every unlocked device means losing the
   library (server-side reset is impossible by design).
3. Back up photos with the **upload button in the top bar**. Files are encrypted in the
   browser before upload; the server stores only ciphertext. Progress shows in a card
   anchored bottom-right, and a failed/interrupted multipart upload is resumable.
4. Click any tile to open the full-screen viewer (arrow keys navigate, `i` toggles
   Details, Esc closes). Details holds the metadata plus **Verify original**, which
   re-downloads the original and checks its SHA-256 digest against the source copy —
   the plan's original-fidelity gate, in miniature. Select tiles with the hover circle
   (or the checkbox) to get the batch toolbar: download and move-to-trash with **Undo**.

MinIO console (dev): http://localhost:9001 (`fusion-dev` / `fusion-dev-secret`) —
all objects are opaque ciphertext under `{userId}/{assetId}/{variant}` keys.

### API smoke test

A backend-only end-to-end test (no browser needed) exercises OTP login → vault →
upload session → presigned PUTs → server-side verification → catalog commit →
incremental sync → authorized download → idempotent repeat → tenant isolation:

```bash
python3 scripts/smoke_upload.py   # requires the compose stack running
```

### Crypto + full round-trip tests

```bash
cd web
node tests/crypto-roundtrip.test.cjs   # vault-key wrap/unwrap + payload wire format
node tests/e2e-roundtrip.cjs           # full client-simulation against API + real S3:
                                       # upload -> presigned GET -> decrypt -> SHA-256
                                       # byte-identical check (the original-fidelity gate)
```

### Integration tests (DB + S3: replication & reconciliation)

The replication-verification and reconciliation paths need Postgres + MinIO. Run them
inside the compose network (a host Postgres can shadow port 5432):

```bash
docker compose up -d db minio
./scripts/test_integration.sh          # FUSION_INTEGRATION=1 go test -run Integration
```

They prove that an asset is **not** protected before its object reaches the replica and
**is** protected after, that the sync payload and backup status agree, and that an
abandoned upload is expired so a completion attempt fails closed. CI runs them too.

## Architecture notes (per plan)

- **Media never passes through the API server** — clients PUT/GET ciphertext via
  15-minute presigned URLs to server-chosen keys.
- **Server sees only**: opaque IDs, ciphertext sizes, encrypted key envelopes,
  encrypted manifests, monotonic sync sequence numbers.
- **Auth ≠ decryption** — the OTP session proves account access; the vault key exists
  only in memory after the passphrase unlock.
- **Local-first gallery** — render from IndexedDB before any network call; sync uses
  an incremental per-user cursor, never full re-listing.
- **Preview-unavailable state** — HEIC/RAW the browser can't decode still gets backed
  up as an original; the UI shows an explicit no-preview tile, never a silent drop.

## Deliberate Phase-1 limitations (tracked for later phases)

- Presigned PUTs carry no enforced content-length, checksum, or object-version
  constraints — the server verifies size via a post-PUT `StatObject`, but a
  malicious client can still upload arbitrary bytes to a key it was issued.
- Cross-region replication is **implemented and verifiable** but must be wired up: set
  `S3_REPLICA_BUCKET` (and the `S3_REPLICA_*` credentials for a cross-account copy) and
  provision the AWS side with `infra/`. Until then the service reports **nothing** as
  "Protected remotely" — a committed asset is "Uploaded" (stored in one backend), which
  is honest, not protective. Object Lock is defined in `infra/` but off by default.
- Quota is an application-level reservation ledger (reserved bytes per pending
  upload session + stored bytes per committed asset). A reconciliation worker now
  expires abandoned pending sessions and aborts their orphaned multipart uploads.
- The server ignores the legacy client-supplied `plaintextSha256` field and does
  not persist it for new uploads; the original SHA-256 lives only inside the
  encrypted manifest and is checked client-side. The legacy nullable DB column
  is retained so existing assets keep syncing.
- Presigned PUT per resource; the import web path reads one folder at a time and
  re-hashes/re-derives per file. A single-pass TB-scale desktop importer is next.
- Live Photo motion companions are stored and completed atomically, but the viewer
  does not yet play them back.
- Export is a snapshot-based, resumable, **client-side** job (see "Complete export"
  below). Byte-range resume *inside* a single file — the hundreds-of-GB case — belongs
  to the desktop downloader and is not built yet.
- Trusted-device enrollment and passkeys are not built. The recovery key (shown once at
  vault creation, enrollable later) and the vault passphrase are the recovery paths.
- OTP delivery is wired for SES (`SES_ENABLED=true` + `SES_FROM`); dev still echoes
  `devCode`. Phone OTP, passkeys and managed fraud controls remain later phases.
- Phone OTP, cleanup ("free up space"), Storage Saver: later phases.

## Android client (in progress)

The native Android client **started with crypto, not UI**: a native client must re-implement
the E2EE wire format, and the plan forbids inventing nonce management. So the first
deliverable was a Kotlin implementation that is **provably byte-compatible with the web
client**, checked against a single shared file:

- `shared/crypto-vectors.json` — the cross-client contract (Argon2id→KEK, vault/asset key
  wraps, recovery wrap, secretstream payloads, manifest envelopes).
- `web/tests/crypto-vectors.test.cjs` verifies the web client against it (`npm run test:crypto`).
- `android/crypto` verifies the Kotlin client against the same file — including that its
  **encrypt** output is byte-identical for a fixed nonce, which is the direction that would
  otherwise silently corrupt uploads.

```bash
cd android
./gradlew :crypto:test            # 22 vector checks on the host — no Android SDK or emulator
./gradlew :app:testDebugUnitTest  # 55 checks of the app's own logic (no device needed)
./gradlew :app:assembleDebug      # the app module
```

The app's first screen is the **self-check**: it runs the same 22-check suite with
`SodiumAndroid` (native libsodium) and prints the results, so the phone's implementation is
verifiable on real hardware. Beyond that the client now signs in (email OTP against the real
API), creates or unlocks the vault (vault key in memory only), and discovers the camera roll
from MediaStore after an explicit permission request. It has run green on `Pixel_9a`
(arm64-v8a, SDK 37):

```
testsuite AndroidSodiumVectorTest  tests="1" failures="0" errors="0" skipped="0"
```

The `:app` unit tests pin the parts that would otherwise only fail on a phone: upload slicing
(expectations copied from the **server's** own tests, so the two can't disagree about which
bytes are part *n*), the vault wire shapes (including the legacy flat envelope), the
supported-media rules, the vault round trip (create → unlock → recover, against a real
libsodium on the JVM) and the upload pipeline — which uploads to a fake server and then
**decrypts what it sent**, checking the digest, the manifest and the sizes the server verifies.

The whole chain has also been driven **on a device against the real API** — sign in with an
emailed OTP, create the vault over the wire, unlock it, grant photo access, discover the
camera roll, encrypt and upload a photo, then upload it again to confirm the idempotent
replay. And the result is checked from outside the project entirely:

```bash
python3 scripts/verify_upload_decrypts.py android-e2e@example.com 'correct-horse-battery-staple' photo.png
# → unwraps the vault key with Argon2id, the asset key from keyEnvelope, decrypts the
#   encrypted manifest and the object in S3 — byte-identical to the original on the phone.
```

That independent check (PyNaCl, none of this repo's code) is what makes the E2EE claim
testable rather than a matter of trust. Running it for real also caught a genuine bug:
`VaultManager.create()` returned only the recovery key, so the app held no vault key and could
not have encrypted anything. Fixed and pinned by a test. Details in
[`android/README.md`](./android/README.md).

When the local backend is unavailable (Docker down, say), a device run can be driven without it:
`scripts/stub_api.py` is a harness that speaks the same wire shapes — and hands the app a real
Argon2id vault envelope — while `scripts/verify_stub_run.py` decrypts what the harness received
and compares it with the originals pulled off the device.

```bash
python3 scripts/stub_api.py --port 8080 --slow-put 2.0 --debug   # app: Server URL http://10.0.2.2:8080
python3 scripts/verify_stub_run.py --expect 7 --device-dir /tmp/device_photos
# → "decrypted every asset the stub received ... byte-identical to the originals on the device"
```

It proves the *client*, not the service: the harness validates nothing (no sizes, no tenancy, no
quota), which is exactly why the same path is also exercised against the real API.

Backups also run **with the app closed**: the queue is a durable SQLite table handed to
WorkManager (network + battery constraints, exponential backoff with jitter, a dead-letter
state, and the "object stored but commit failed" case handled by the idempotency ref). The
vault key is enrolled in the Android Keystore so a worker can decrypt after the process is
gone. On the device, killing the app process and restoring the network produced a **fresh
process running the worker on its own** (`Worker result SUCCESS`, `uploaded : 1`) with no app
launch — and that background-uploaded photo still decrypted byte-identical. The trade-off is
stated plainly rather than buried: an enrolled key is reachable by code inside this app's
sandbox on an unlocked device, which was not true while the key was memory-only.

Large originals (over 32 MiB of ciphertext) go **multipart and resumable**: `multipart/init`,
per-part PUTs, `multipart/complete`. Resume needs no client-side part bookkeeping at all — the
upload session is keyed by the client ref, so a retry lands on the same asset and the same
multipart upload, and `multipart/status` says which parts S3 already holds. A 40 MiB original
was pushed through that path on the device in six parts; the assembled object came back as
41,943,081 bytes (the original + 41) and decrypted byte-identical.

A backup the user starts deliberately runs as a **foreground service** (`dataSync`) with a
progress notification, so a long job is visible and not treated as deferrable background work;
the periodic heartbeat stays invisible. That is unit-tested, the merged manifest is verified in
the built APK, and the run itself is now **verified on a device**: backgrounded mid-job,
`dumpsys` showed `isForeground=true` for the whole transfer while the notification advanced
`Preparing…` → `1 uploaded · 6 still to go` → … , ending at "Backup finished / 7 uploaded". The
bytes that path produced were then decrypted by an independent implementation and matched the
originals byte for byte. Docker was down, so the device run used `scripts/stub_api.py` (a
harness speaking the same wire shapes, not the service) — see
[`android/README.md`](./android/README.md) for the evidence and what the harness does *not* prove.

Verifying on a real 16 KB page-size image paid off: it caught `lazysodium-android:5.1.0`
shipping a `libsodium.so` whose LOAD segment was only 4 KB aligned, which cannot be mapped
with 16 KB pages at all. That is fixed by 5.2.0 (the on-device vector test still passes);
`scripts/check_16kb_alignment.py` checks any APK for this class of bug and doubles as a CI
gate. The remaining RELRO alignment of three prebuilt libraries needs an upstream rebuild —
recorded as an open item in [`android/README.md`](./android/README.md).

CI runs the two JVM suites (`:crypto:test`, `:app:testDebugUnitTest`, the latter with an
Android SDK but no emulator); the connected test is run with
`ANDROID_SERIAL=… ./gradlew :app:connectedDebugAndroidTest`. The durable WorkManager upload
queue and the device gallery are the next increment. See
[`android/README.md`](./android/README.md).

## Switching media storage to real AWS S3

1. `cp .env.example .env` and fill in the AWS block (region, bucket, IAM access key pair).
2. `docker compose up -d --force-recreate api` — the API now presigns against real S3.
3. The bucket needs a CORS rule (browser PUTs ciphertext directly): see the
   AWS-setup section in chat/docs — allow `GET/PUT` from `http://localhost:5173`.

`.env` is gitignored — credentials never enter the repo.

> **Purge worker note:** the 30-day trash purge deletes S3 objects via
> `s3:DeleteObject` — add that action to the `fusion-photos-api-dev` IAM policy
> (Step 3 of the AWS setup) when you enable real S3, otherwise objects linger as
> orphans after their catalog rows are purged.

## Enabling real email OTP (SES)

Dev mode returns the code as `devCode` and never emails it. For a real deployment:

1. Verify a sender domain (or address) in Amazon SES and set `SES_FROM` to it.
2. Set `SES_ENABLED=true` and `SES_REGION`; the API uses the default AWS
   credential chain, so grant its role `ses:SendEmail` (no static keys in the app).
3. Set `OTP_PEPPER` to a random 32-byte value (`openssl rand -hex 32`). Codes are
   stored only as HMAC-SHA256 hashes; an empty pepper generates an ephemeral one at
   boot (dev-only convenience) that invalidates pending codes on restart.
4. Tune the abuse limits if needed (`OTP_PER_EMAIL_LIMIT`/`_WINDOW`,
   `OTP_PER_IP_LIMIT`/`_WINDOW`, `OTP_MAX_ATTEMPTS`, `OTP_TTL`). Throttle buckets
   are keyed `email:<addr>` and `ip:<addr>` (first `X-Forwarded-For` hop); make
   sure your proxy overwrites that header.

`scripts/smoke_upload.py` asserts the wrong-code and 429 throttle paths in addition
to the full upload/round-trip flow.

## Complete export

`/export` (and the `POST /v1/export/snapshot` API) retrieves the whole library as
normal files:

1. **Immutable snapshot** — the server records the current non-trashed library
   (`export_snapshot_assets`) with its byte total and a **7-day retention** window.
   Because membership is recorded, trashing a photo *after* the snapshot does not
   remove it from a running export (snapshot immutability, enforced by
   `resourceForSnapshot`).
2. **Local manifest** — the client pages the snapshot, decrypts each manifest and
   builds a versioned `fusion-photos-export-manifest.json`. Only metadata goes in it:
   no provider secrets or credentials.
3. **Resumable download** — each original (plus the `original_motion` half of a Live
   Photo, never dropped) is fetched through a snapshot-scoped presigned URL, decrypted
   locally, and its SHA-256 checked against the recorded digest before it is written.
   Per-file progress is persisted in IndexedDB, so a reload continues instead of
   re-downloading.
4. **Integrity report** — `fusion-photos-export-report.json` lists exported/skipped/
   failed counts and every failure.

Chrome/Edge write real files into a chosen folder (File System Access API); Safari and
Firefox fall back to per-file downloads. The export is usable without this app.

Tests: `TestExportSnapshotIntegration` proves snapshot immutability and tenant
isolation; `scripts/smoke_upload.py` exercises the endpoints live.

## Browser limits (Safari & Chrome)

The plan's "browser limits" gate is handled explicitly rather than assumed away:

- **Persistent storage is requested** (`navigator.storage.persist()`) and its real state
  is reported in the backup panel as *Persistent* vs *best effort — the browser may clear
  it*. The app never promises permanent offline storage.
- **Eviction is detected, not hidden.** If IndexedDB looked present on a previous visit
  and is now empty, the gallery says *"the browser cleared this device's local cache"* and
  the background sync re-downloads the catalog. It never shows an emptied library as if
  the photos were gone.
- **Expired presigned URLs are re-signed once** (`fetchSigned`). A presigned URL is a
  short-lived bearer token; when S3/MinIO answers 400/403/404, the client mints a fresh
  URL and retries exactly once. This is what lets a long export outlive the 15-minute
  signing window. Covered by `web/tests/signed-url-retry.test.cjs` (`npm run test:lib`).
- **Session expiry recovers** — a mid-run 401 raises an explicit *"sign in again"* notice;
  the catalog and export progress are server-backed and resumable, so nothing is lost.
- **The fallback export path is bounded** — one file at a time with a small gap, and the
  UI tells the user to keep the tab open, rather than firing hundreds of downloads at once.
- **The catalog is account-scoped.** The owning account id is recorded beside the cached
  library and confirmed via `GET /v1/me`, so signing in as a different account drops the
  previous account's cache and re-syncs. It can never display another account's photos
  (which would render as *Missing resource*, since every fetch is correctly refused).
  Covered by `web/tests/catalog-scope.test.cjs`.

## Repository layout

```
web/                 Vue 3 + TS client
  src/components/    shell + reusable UI (header, nav drawer, tile, viewer, icons)
  src/lib/crypto.ts  E2EE primitives (vault/asset keys, secretstream)
  src/lib/api.ts     typed API client + presigned-URL media access
  src/lib/db.ts      IndexedDB catalog (local-first gallery)
  src/lib/format.ts  presentation-only formatters (date headings, sizes, purge dates)
  src/lib/upload.ts  prepare → encrypt → reserve → transfer → complete
  src/lib/resources.ts decrypt-on-fetch, LRU blob cache, round-trip verify
  src/style.css      the Google Photos design system (tokens + components)
  src/views/         Login (OTP), Unlock (vault), Gallery (Photos), Trash, placeholders
api/                 Go control plane
  cmd/server/        main, store (Postgres), media (S3), handlers, auth (otp.go, mailer.go)
                     workers.go (reconciliation + replication), handlers_backup.go,
                     handlers_export.go (immutable export snapshots)
web/                 Vue 3 + TS client
  src/lib/export.ts  resumable client-side export engine (manifest, verify, resume)
  src/views/ExportView.vue  the /export wizard
desktop/             Tauri native host for TB-scale Takeout import (thin Rust layer)
android/             Kotlin client: :crypto (E2EE wire format, vector-verified) + :app (next)
shared/              crypto-vectors.json — the cross-client E2EE contract
infra/               Terraform: S3 versioning/replication, Aurora + cross-region replica,
                     IAM, Secrets Manager, budgets (see infra/README.md)
docker-compose.yml   Postgres + MinIO + API
```

## UI — Google Photos design language

The web client is built to read like Google Photos while staying honest about what is
implemented:

- **Chrome**: 64px top app bar (product mark, pill *Search your photos*, upload +
  account menu), 240px navigation drawer that becomes an overlay drawer under 900px,
  and a selection app bar that replaces the top bar when tiles are selected.
- **Grid**: edge-to-edge square tiles with 2px gaps, grouped under sticky date headings
  (*Today* / *Yesterday* / weekday / full date), lazy-loaded through an
  `IntersectionObserver` so a large library never fires one presigned GET per row.
- **Viewer**: full-screen backdrop, date header, right-hand action cluster (details,
  zoom, download, delete), side chevrons, keyboard navigation and a Details panel with
  the decrypted manifest metadata plus the round-trip verify action.
- **Theme**: light by default (Google's default) with a `prefers-color-scheme: dark`
  palette, the Google brand hexes, and a Google Sans → Roboto → system font stack.
  No web fonts or icon fonts: the CSP is `default-src 'self'`, so every icon in
  `PhotoIcon.vue` is hand-authored inline SVG.
- **Backup status**: the shield in the top bar opens an honest panel — with no replica
  configured it says so plainly rather than implying durability; the dot appears only
  when something is not yet verified in a second region. The viewer's Details panel
  repeats the per-photo `Protection` line.
- **Not-yet-built Google Photos features** (Explore, Sharing, Library, albums, editor,
  settings) are reachable but never faked: they either render a Google-style empty
  state or explain that they arrive in a later phase.
