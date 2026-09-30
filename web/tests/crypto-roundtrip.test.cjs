// Round-trip test mirroring web/src/lib/crypto.ts exactly (sumo CJS build, same as the
// Vite alias resolves in the browser bundle). Run: node tests/crypto-roundtrip.test.cjs
const _sodium = require('libsodium-wrappers-sumo')

async function main() {
  const s = _sodium
  await s.ready
  console.log('sodium version:', s.sodium_version_string())

  const passphrase = 'correct horse battery staple 42!'
  const wrong = 'a different passphrase entirely'

  // --- replicate wrapVaultKey ---
  const vaultKey = s.crypto_secretbox_keygen()
  const salt = s.randombytes_buf(s.crypto_pwhash_SALTBYTES)
  const kek = await s.crypto_pwhash(
    32, passphrase, salt,
    s.crypto_pwhash_OPSLIMIT_MODERATE,
    s.crypto_pwhash_MEMLIMIT_MODERATE,
    s.crypto_pwhash_ALG_ARGON2ID13,
  )
  const nonce = s.randombytes_buf(s.crypto_aead_xchacha20poly1305_ietf_NPUBBYTES)
  const ct = s.crypto_aead_xchacha20poly1305_ietf_encrypt(vaultKey, null, null, nonce, kek)
  const blob = new Uint8Array(nonce.length + ct.length)
  blob.set(nonce, 0); blob.set(ct, nonce.length)

  const toB64 = (b) => s.to_base64(b, s.base64_variants.ORIGINAL)
  const wrapped = { kdfSalt: toB64(salt), wrapped: toB64(blob), v: 1 }

  // simulate server JSON round trip (JSONB does the same to strings)
  const stored = JSON.parse(JSON.stringify(wrapped))
  console.log('stored blob length:', stored.wrapped.length, 'salt length:', stored.kdfSalt.length)

  // --- replicate unwrapVaultKey ---
  function unwrap(w, pass) {
    const fromB64 = (x) => s.from_base64(x, s.base64_variants.ORIGINAL)
    const salt2 = fromB64(w.kdfSalt)
    const blob2 = fromB64(w.wrapped)
    const n = s.crypto_aead_xchacha20poly1305_ietf_NPUBBYTES
    const kek2 = s.crypto_pwhash(
      32, pass, salt2,
      s.crypto_pwhash_OPSLIMIT_MODERATE,
      s.crypto_pwhash_MEMLIMIT_MODERATE,
      s.crypto_pwhash_ALG_ARGON2ID13,
    )
    return s.crypto_aead_xchacha20poly1305_ietf_decrypt(null, blob2.slice(n), null, blob2.slice(0, n), kek2)
  }

  const unwrapped = unwrap(stored, passphrase)
  console.log('correct passphrase: key matches =', Buffer.from(unwrapped).equals(Buffer.from(vaultKey)))

  // --- resource payload round trip (header-prefixed wire format, like upload.ts) ---
  const plaintext = new Uint8Array(1024 * 64)
  for (let i = 0; i < plaintext.length; i++) plaintext[i] = i % 251
  const assetKey = s.randombytes_buf(32)
  const stream = s.crypto_secretstream_xchacha20poly1305_init_push(assetKey)
  const payloadCt = s.crypto_secretstream_xchacha20poly1305_push(
    stream.state, plaintext, null, s.crypto_secretstream_xchacha20poly1305_TAG_FINAL)
  const wireBlob = new Uint8Array(stream.header.length + payloadCt.length)
  wireBlob.set(stream.header, 0); wireBlob.set(payloadCt, stream.header.length)

  const hLen = s.crypto_secretstream_xchacha20poly1305_HEADERBYTES
  const state = s.crypto_secretstream_xchacha20poly1305_init_pull(wireBlob.slice(0, hLen), assetKey)
  const out = s.crypto_secretstream_xchacha20poly1305_pull(state, wireBlob.slice(hLen), null)
  console.log('payload round trip: plaintext matches =', Buffer.from(out.message).equals(Buffer.from(plaintext)))

  try {
    unwrap(stored, wrong)
    console.log('SECURITY FAIL: wrong passphrase decrypted!')
  } catch (e) {
    console.log('wrong passphrase: rejected as expected (' + e.message + ')')
  }
}

main().catch((e) => { console.error('TEST ERROR:', e); process.exit(1) })
