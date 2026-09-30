//! Fusion Photos — native desktop host for the web client.
//!
//! The desktop app is deliberately THIN: the entire import engine, crypto and upload
//! pipeline are the same TypeScript that ships in the browser client (bundled from
//! `web/dist`). Rust only provides what a browser cannot:
//!
//!   * random-access reads of multi-GB Takeout ZIP parts without loading them
//!   * recursive listing of a TB-scale extracted Takeout tree
//!   * streaming SHA-256 (single pass — a 200 MB RAW never enters the webview)
//!   * durable ciphertext staging on disk (immune to webview storage eviction)
//!   * OS keychain storage for the vault key material
//!
//! SECURITY: the vault key/passphrase never leaves this process, and nothing here is
//! logged. The API base URL is the only value injected into the webview; media bytes and
//! entry names are only ever passed through the read commands the client asks for.

use std::fs;
use std::io::{Read, Seek, SeekFrom};
use std::path::PathBuf;

use sha2::{Digest, Sha256};
use tauri::{Manager, WebviewWindow};

/// Recursively list files under `root` as paths relative to it (sorted). Directories are
/// skipped and symlinks are not followed; separators are normalised to `/`.
#[tauri::command]
fn list_files_recursive(root: String) -> Result<Vec<String>, String> {
    let base = PathBuf::from(&root);
    let mut out = Vec::new();
    let mut stack = vec![base.clone()];
    while let Some(dir) = stack.pop() {
        let entries = fs::read_dir(&dir).map_err(|e| format!("read_dir {}: {e}", dir.display()))?;
        for entry in entries {
            let entry = entry.map_err(|e| e.to_string())?;
            let path = entry.path();
            let meta = entry.metadata().map_err(|e| e.to_string())?;
            if meta.is_dir() {
                stack.push(path);
            } else if meta.is_file() {
                let rel = path.strip_prefix(&base).unwrap_or(&path);
                out.push(rel.to_string_lossy().replace('\\', "/"));
            }
        }
    }
    out.sort();
    Ok(out)
}

/// File size in bytes — used to bound a ByteSource before any range read.
#[tauri::command]
fn stat_size(path: String) -> Result<u64, String> {
    fs::metadata(&path).map(|m| m.len()).map_err(|e| e.to_string())
}

/// Read `length` bytes at `offset`. Bounded so a bad client request cannot allocate
/// unbounded memory; short reads only happen at EOF.
#[tauri::command]
fn read_file_range(path: String, offset: u64, length: u64) -> Result<Vec<u8>, String> {
    const MAX_READ: u64 = 64 * 1024 * 1024;
    if length > MAX_READ {
        return Err(format!("read too large: {length} > {MAX_READ}"));
    }
    let mut f = fs::File::open(&path).map_err(|e| e.to_string())?;
    f.seek(SeekFrom::Start(offset)).map_err(|e| e.to_string())?;
    let mut buf = vec![0u8; length as usize];
    let mut filled = 0usize;
    while filled < buf.len() {
        let n = f.read(&mut buf[filled..]).map_err(|e| e.to_string())?;
        if n == 0 {
            break;
        }
        filled += n;
    }
    buf.truncate(filled);
    Ok(buf)
}

/// Single-pass streamed SHA-256 (hex) of a file.
#[tauri::command]
fn sha256_file(path: String) -> Result<String, String> {
    let mut f = fs::File::open(&path).map_err(|e| e.to_string())?;
    let mut hasher = Sha256::new();
    let mut buf = vec![0u8; 1 << 20];
    loop {
        let n = f.read(&mut buf).map_err(|e| e.to_string())?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    Ok(hex::encode(hasher.finalize()))
}

/// Keep only filesystem-safe characters; keys come from the client (asset ids/variants).
fn sanitize(key: &str) -> String {
    key.chars()
        .map(|c| if c.is_ascii_alphanumeric() || c == '-' || c == '_' || c == '.' { c } else { '_' })
        .collect()
}

fn staging_dir(app: &tauri::AppHandle) -> Result<PathBuf, String> {
    let dir = app
        .path()
        .app_data_dir()
        .map_err(|e| e.to_string())?
        .join("staging");
    fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    Ok(dir)
}

/// Stage ciphertext on disk so an interrupted upload survives webview storage eviction.
#[tauri::command]
fn staging_put(app: tauri::AppHandle, key: String, bytes: Vec<u8>) -> Result<(), String> {
    let dir = staging_dir(&app)?;
    fs::write(dir.join(sanitize(&key)), bytes).map_err(|e| e.to_string())
}

#[tauri::command]
fn staging_get(app: tauri::AppHandle, key: String) -> Result<Vec<u8>, String> {
    let dir = staging_dir(&app)?;
    fs::read(dir.join(sanitize(&key))).map_err(|e| e.to_string())
}

#[tauri::command]
fn staging_delete(app: tauri::AppHandle, key: String) -> Result<(), String> {
    let dir = staging_dir(&app)?;
    match fs::remove_file(dir.join(sanitize(&key))) {
        Ok(()) => Ok(()),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(e) => Err(e.to_string()),
    }
}

const KEYCHAIN_SERVICE: &str = "app.fusion.photos.desktop";

/// Store a secret (e.g. the recovery key, or a wrapped vault key) in the OS keychain.
#[tauri::command]
fn keychain_set(account: String, secret: String) -> Result<(), String> {
    keyring::Entry::new(KEYCHAIN_SERVICE, &account)
        .map_err(|e| e.to_string())?
        .set_password(&secret)
        .map_err(|e| e.to_string())
}

#[tauri::command]
fn keychain_get(account: String) -> Result<Option<String>, String> {
    match keyring::Entry::new(KEYCHAIN_SERVICE, &account)
        .map_err(|e| e.to_string())?
        .get_password()
    {
        Ok(v) => Ok(Some(v)),
        Err(keyring::Error::NoEntry) => Ok(None),
        Err(e) => Err(e.to_string()),
    }
}

#[tauri::command]
fn keychain_delete(account: String) -> Result<(), String> {
    match keyring::Entry::new(KEYCHAIN_SERVICE, &account)
        .map_err(|e| e.to_string())?
        .delete_credential()
    {
        Ok(()) => Ok(()),
        Err(keyring::Error::NoEntry) => Ok(()),
        Err(e) => Err(e.to_string()),
    }
}

/// Point the bundled web client at the hosted API (a packaged app has no dev proxy).
/// Reads `FUSION_API_BASE`; the client falls back to same-origin when it is unset.
fn inject_api_base(win: &WebviewWindow) {
    let base = std::env::var("FUSION_API_BASE").unwrap_or_default();
    let escaped = serde_json::to_string(&base).unwrap_or_else(|_| "\"\"".to_string());
    let _ = win.eval(format!("window.__FUSION_API_BASE__ = {escaped};"));
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .setup(|app| {
            if let Some(win) = app.get_webview_window("main") {
                inject_api_base(&win);
            }
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            list_files_recursive,
            stat_size,
            read_file_range,
            sha256_file,
            staging_put,
            staging_get,
            staging_delete,
            keychain_set,
            keychain_get,
            keychain_delete,
        ])
        .run(tauri::generate_context!())
        .expect("error while running Fusion Photos desktop");
}

