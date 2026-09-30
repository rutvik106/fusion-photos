/**
 * End-to-end encryption primitives (web client).
 *
 * Design (v1, per plan):
 *  - Vault key: random 32 bytes, generated on first device. Wrapped with a KEK derived
 *    from the user's passphrase (Argon2id via sodium pwhash) and stored server-side as
 *    an opaque blob. The server never sees the passphrase or plaintext vault key.
 *  - Per-asset data key: random 32 bytes, wrapped with the vault key (XChaCha20-Poly1305
 *    AEAD envelope). Uploaded with the asset; also cached locally for offline unlock.
 *  - Resource payloads (original / preview / thumbnail / encrypted manifest) are
 *    encrypted with secretstream (XChaCha20-Poly1305 stream AEAD) using the asset data
 *    key. Sodium handles internal framing, which is the boundary format we will use for
 *    chunked/resumable downloads in the export phase.
 *
 * NOTE: recovery key + trusted-device enrollment are Phase 2. For the foundation slice
 * the passphrase-wrapped vault key IS the recovery mechanism. Documented limitation.
 *
 * We use libsodium-wrappers-SUMO: the standard build omits crypto_pwhash (Argon2),
 * which our vault key derivation requires.
 */
import _sodium from 'libsodium-wrappers-sumo'

export const VAULT_KEY_BYTES = 32
export const ASSET_KEY_BYTES = 32

let sodiumReady: Promise<typeof _sodium> | null = null
export function sodium() {
  if (!sodiumReady) {
    sodiumReady = _sodium.ready.then(() => _sodium)
  }
  return sodiumReady
}

// ---------- base64 / byte helpers ----------

export function toB64(bytes: Uint8Array): string {
  return _sodium.to_base64(bytes, _sodium.base64_variants.ORIGINAL)
}

export function fromB64(s: string): Uint8Array {
  return _sodium.from_base64(s, _sodium.base64_variants.ORIGINAL)
}

function concat(...parts: Uint8Array[]): Uint8Array {
  const total = parts.reduce((n, p) => n + p.length, 0)
  const out = new Uint8Array(total)
  let off = 0
  for (const p of parts) {
    out.set(p, off)
    off += p.length
  }
  return out
}

// ---------- vault key ----------

export interface WrappedVault {
  /** base64 argon2 salt */
  kdfSalt: string
  /** base64 nonce(24) || AEAD ciphertext || tag(16) */
  wrapped: string
  /** crypto format version, for future migration */
  v: number
}

export function newVaultKey(): Promise<Uint8Array> {
  return sodium().then((s) => s.crypto_secretbox_keygen())
}

// ---------- KDF via web worker ----------
//
// Argon2id (Moderate: ~256 MiB, multi-second) must not run on the main thread — it
// freezes the whole UI. The worker does the heavy lifting; if Workers are unavailable
// or fail to start, we fall back to the main thread (blocking but functional).

let kdfWorker: Worker | null = null
let kdfSeq = 0
const kdfPending = new Map<number, { resolve: (k: Uint8Array) => void; reject: (e: Error) => void }>()

function getKdfWorker(): Worker | null {
  if (kdfWorker) return kdfWorker
  if (typeof Worker === 'undefined') return null
  try {
    kdfWorker = new Worker(new URL('./kdf.worker.ts', import.meta.url), { type: 'module' })
    kdfWorker.onmessage = (e: MessageEvent<{ id: number; kekB64?: string; error?: string }>) => {
      const p = kdfPending.get(e.data.id)
      if (!p) return
      kdfPending.delete(e.data.id)
      if (e.data.error || !e.data.kekB64) p.reject(new Error(e.data.error ?? 'KDF failed'))
      else p.resolve(fromB64(e.data.kekB64))
    }
    kdfWorker.onerror = (e) => {
      // worker failed to load/crashed: fail pending, disable for future calls
      const err = new Error(`KDF worker error: ${e.message || 'unknown'}`)
      for (const [, p] of kdfPending) p.reject(err)
      kdfPending.clear()
      kdfWorker = null
    }
    return kdfWorker
  } catch {
    return null
  }
}

/** Derive a key-encryption key from the passphrase. Moderate params on purpose.
 * Runs in a worker so the UI stays responsive; native apps can raise the limits. */
export async function deriveKek(passphrase: string, salt: Uint8Array): Promise<Uint8Array> {
  const w = getKdfWorker()
  if (!w) {
    const s = await sodium()
    return s.crypto_pwhash(
      32,
      passphrase,
      salt,
      s.crypto_pwhash_OPSLIMIT_MODERATE,
      s.crypto_pwhash_MEMLIMIT_MODERATE,
      s.crypto_pwhash_ALG_ARGON2ID13,
    )
  }
  return new Promise<Uint8Array>((resolve, reject) => {
    const id = ++kdfSeq
    kdfPending.set(id, { resolve, reject })
    w.postMessage({ id, passphrase, saltB64: toB64(salt) })
  })
}

export async function wrapVaultKey(vaultKey: Uint8Array, passphrase: string): Promise<WrappedVault> {
  const s = await sodium()
  const salt = s.randombytes_buf(s.crypto_pwhash_SALTBYTES)
  const kek = await deriveKek(passphrase, salt)
  const nonce = s.randombytes_buf(s.crypto_aead_xchacha20poly1305_ietf_NPUBBYTES)
  const ct = s.crypto_aead_xchacha20poly1305_ietf_encrypt(vaultKey, null, null, nonce, kek)
  return { kdfSalt: toB64(salt), wrapped: toB64(concat(nonce, ct)), v: 1 }
}

export async function unwrapVaultKey(w: WrappedVault, passphrase: string): Promise<Uint8Array> {
  const s = await sodium()
  const salt = fromB64(w.kdfSalt)
  const blob = fromB64(w.wrapped)
  const n = s.crypto_aead_xchacha20poly1305_ietf_NPUBBYTES
  const nonce = blob.slice(0, n)
  const ct = blob.slice(n)
  const kek = await deriveKek(passphrase, salt)
  return s.crypto_aead_xchacha20poly1305_ietf_decrypt(null, ct, null, nonce, kek)
}

// ---------- per-asset data key ----------

export async function newAssetKey(): Promise<Uint8Array> {
  const s = await sodium()
  return s.randombytes_buf(ASSET_KEY_BYTES)
}

/** AEAD-encrypt the asset data key under the vault key. */
export function wrapAssetKey(assetKey: Uint8Array, vaultKey: Uint8Array): string {
  const s = _sodium
  const nonce = s.randombytes_buf(s.crypto_aead_xchacha20poly1305_ietf_NPUBBYTES)
  const ct = s.crypto_aead_xchacha20poly1305_ietf_encrypt(assetKey, null, null, nonce, vaultKey)
  return toB64(concat(nonce, ct))
}

export function unwrapAssetKey(envelopeB64: string, vaultKey: Uint8Array): Uint8Array {
  const s = _sodium
  const blob = fromB64(envelopeB64)
  const n = s.crypto_aead_xchacha20poly1305_ietf_NPUBBYTES
  return s.crypto_aead_xchacha20poly1305_ietf_decrypt(
    null,
    blob.slice(n),
    null,
    blob.slice(0, n),
    vaultKey,
  )
}

// ---------- payload encryption (secretstream) ----------

export interface EncryptedPayload {
  /** stream header, required to decrypt; stored alongside ciphertext */
  headerB64: string
  /** ciphertext frames */
  blobB64: string
}

/**
 * Encrypt a resource payload (original bytes, preview JPEG, thumbnail JPEG).
 * Returns a single self-describing blob: header(24) || ciphertext frames.
 * The header is REQUIRED to decrypt — it must never be stored separately from
 * the payload (that was the download-path bug: headers were silently dropped).
 */
export async function encryptPayloadBytes(
  plaintext: Uint8Array,
  key: Uint8Array,
): Promise<Uint8Array> {
  const s = await sodium()
  const stream = s.crypto_secretstream_xchacha20poly1305_init_push(key)
  const ct = s.crypto_secretstream_xchacha20poly1305_push(
    stream.state,
    plaintext,
    null,
    s.crypto_secretstream_xchacha20poly1305_TAG_FINAL,
  )
  return concat(stream.header, ct)
}

/** Decrypt a header-prefixed payload produced by encryptPayloadBytes. */
export async function decryptPayloadBytes(
  blob: Uint8Array,
  key: Uint8Array,
): Promise<Uint8Array> {
  const s = await sodium()
  const hLen = s.crypto_secretstream_xchacha20poly1305_HEADERBYTES
  if (blob.length <= hLen) throw new Error('payload too short to decrypt')
  const state = s.crypto_secretstream_xchacha20poly1305_init_pull(blob.slice(0, hLen), key)
  const out = s.crypto_secretstream_xchacha20poly1305_pull(state, blob.slice(hLen), null)
  if (!out) throw new Error('payload decryption failed (corrupt ciphertext or wrong key)')
  return out.message
}

/** Encrypt the asset manifest (kept in the base64 envelope form — both parts are
 * stored together server-side, so the header is not lost). */
export async function encryptPayload(
  plaintext: Uint8Array,
  key: Uint8Array,
): Promise<EncryptedPayload> {
  const s = await sodium()
  const stream = s.crypto_secretstream_xchacha20poly1305_init_push(key)
  const ct = s.crypto_secretstream_xchacha20poly1305_push(
    stream.state,
    plaintext,
    null,
    s.crypto_secretstream_xchacha20poly1305_TAG_FINAL,
  )
  return { headerB64: toB64(stream.header), blobB64: toB64(ct) }
}

export async function decryptPayload(e: EncryptedPayload, key: Uint8Array): Promise<Uint8Array> {
  const s = await sodium()
  const header = fromB64(e.headerB64)
  const ct = fromB64(e.blobB64)
  const state = s.crypto_secretstream_xchacha20poly1305_init_pull(header, key)
  const out = s.crypto_secretstream_xchacha20poly1305_pull(state, ct, null)
  if (!out) throw new Error('payload decryption failed (corrupt ciphertext or wrong key)')
  return out.message
}

// ---------- recovery key ----------
//
// The recovery key is a second, high-entropy copy-safe key: it directly wraps the
// vault key with XChaCha20-Poly1305 (no KDF — the key itself is 256-bit random, so
// there is nothing to slow down; display/handling is the user's responsibility).
// Per plan: losing every authorized device AND the recovery key means unrecoverable
// photos — the UI must say this out loud.

/** Generate a recovery key and return it in a human-pasteable grouped form. */
export async function newRecoveryKey(): Promise<string> {
  const s = await sodium()
  const raw = s.randombytes_buf(32)
  const b = toB64(raw)
  // group into 8-char chunks for readability: aaaaaaaa-bbbbbbbb-...
  return (b.match(/.{1,8}/g) ?? []).join('-')
}

/** Parse a displayed/pasted recovery key back to raw bytes. */
export function parseRecoveryKey(display: string): Uint8Array {
  const compact = display.replace(/[\s-]/g, '')
  const raw = fromB64(compact)
  if (raw.length !== ASSET_KEY_BYTES) {
    throw new Error('Recovery key looks wrong (bad length)')
  }
  return raw
}

/** Wrap the vault key under the recovery key (AEAD, no KDF). */
export function wrapVaultWithRecovery(vaultKey: Uint8Array, recoveryRaw: Uint8Array): WrappedVault {
  const s = _sodium
  const nonce = s.randombytes_buf(s.crypto_aead_xchacha20poly1305_ietf_NPUBBYTES)
  const ct = s.crypto_aead_xchacha20poly1305_ietf_encrypt(vaultKey, null, null, nonce, recoveryRaw)
  // no kdfSalt needed (no KDF); reuse the envelope shape with a marker salt
  return { kdfSalt: '', wrapped: toB64(concat(nonce, ct)), v: 1 }
}

export function unwrapVaultWithRecovery(w: WrappedVault, recoveryRaw: Uint8Array): Uint8Array {
  const s = _sodium
  const blob = fromB64(w.wrapped)
  const n = s.crypto_aead_xchacha20poly1305_ietf_NPUBBYTES
  return s.crypto_aead_xchacha20poly1305_ietf_decrypt(
    null,
    blob.slice(n),
    null,
    blob.slice(0, n),
    recoveryRaw,
  )
}

// ---------- digests ----------

export async function sha256Hex(bytes: Uint8Array): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', bytes as unknown as ArrayBuffer)
  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('')
}

export async function randomKeyB64(bytes = 32): Promise<string> {
  const s = await sodium()
  return toB64(s.randombytes_buf(bytes))
}

