# Fusion Photos — desktop importer

A thin native host for the **existing** web client, built for TB-scale Google Photos
Takeout migration: libraries that a browser cannot reliably import (storage eviction, tab
suspension, no streaming from disk).

> **Status: scaffold.** The shared TypeScript core below is implemented and unit-tested
> (`npm run test:import` in `web/`). The Rust layer here requires a Rust + Tauri toolchain
> and has **not** been compiled in CI, because the repo's CI only installs Node and Go.

## Why this design: reuse the client, don't fork it

Crypto, upload and import logic live **once**, in `web/src/lib/**`, and the desktop app
serves the same built client (`web/dist`). Re-implementing the E2EE wire format in Rust
would mean duplicating nonce management and stream framing — exactly what the project plan
warns against. Rust therefore only does what a browser cannot:

| Native command | Why it must be native |
|---|---|
| `read_file_range(path, offset, length)` | random-access reads inside a 50 GB Takeout ZIP part without loading it |
| `list_files_recursive(root)` | enumerate a multi-hundred-GB extracted Takeout tree |
| `stat_size(path)` | bound a byte source before reading |
| `sha256_file(path)` | single-pass streaming hash — a 200 MB RAW never enters the webview |
| `staging_put/get/delete` | durable ciphertext staging on disk (webview storage can be evicted) |
| `keychain_get/set/delete` | OS keychain (macOS Keychain / Windows Credential Manager / Secret Service) |

The client-side counterparts — `web/src/lib/import/byteSource.ts`,
`zipCentralDirectory.ts`, `tauriByteSource.ts` — are **host-agnostic and fully tested**:

```bash
cd web && npm run test:import      # 28 checks: sidecars, mapping, ZIP (incl. ZIP64), native source
```

`tauriFileSource()` reads ranges through `window.__TAURI__.core.invoke`, so the same ZIP
reader and the same import engine run unchanged in the browser and the desktop app.

## How it fits together

```
desktop/src-tauri/         Rust: commands above + window + API-base injection
web/dist/                  the SAME web client (built once, served by the desktop app)
web/src/lib/import/
  byteSource.ts            ByteSource abstraction (bufferSource / blobSource)
  tauriByteSource.ts       ByteSource over native read_file_range
  zipCentralDirectory.ts   standard + ZIP64 central-directory reader
  takeout.ts, engine.ts    discovery + import engine (shared)
```

The vault key is never persisted by Rust; Keychain storage is for the recovery key /
wrapped envelope only. The API base is injected into the webview as
`window.__FUSION_API_BASE__` (a packaged app has no Vite dev proxy).

## Build & run

```bash
# 1) build the shared client
cd web && npm install && npm run build

# 2) run the desktop app (requires Rust + the Tauri CLI)
cd ../desktop/src-tauri
cargo install tauri-cli --version '^2'   # once
FUSION_API_BASE=https://api.example.com cargo tauri dev
FUSION_API_BASE=https://api.example.com cargo tauri build
```

### Required packaging step: widen the CSP

The web client ships a deliberate strict CSP meta tag
(`web/index.html`, `connect-src 'self' http://localhost:8080 … https://*.amazonaws.com`).
A packaged desktop app talks to a remote API and presigned S3 URLs, so the packaged
`web/dist/index.html` must add those origins:

```bash
# example: add the API origin to connect-src after building web/
python3 - <<'PY'
import re, pathlib
p = pathlib.Path('web/dist/index.html')
html = p.read_text()
html = html.replace("connect-src 'self'", "connect-src 'self' https://api.example.com")
p.write_text(html)
PY
```

Both the meta CSP and Tauri's own `app.security.csp` apply; keep the intersection as
tight as the deployed API allows.

## Not done yet (honest list)

- Not compiled/tested in CI (no Rust toolchain there).
- No bundled installer icons or signing/notarisation.
- The import engine does not yet *use* `read_file_range` for streaming a ZIP part through
  the full upload path — the ZIP reader and byte source are in place and tested, but the
  desktop entry point (a native "choose a .zip part" flow) is the next increment.
- `sha256_file` and `staging_*` are exposed but not yet wired into the engine (which still
  hashes and stages in the webview).
- Takeout source adapters still assume an **extracted folder**; streaming entries straight
  out of `.zip` parts is the follow-up.
