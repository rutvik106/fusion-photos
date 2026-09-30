/**
 * Verifier for the shared crypto vectors (../shared/crypto-vectors.json).
 *
 * This is the cross-client contract: the web client and the Android client are both
 * checked against the SAME file, so the E2EE wire format cannot drift between them.
 * The web side is verified here in CI; the Kotlin side in android/app's unit tests.
 *
 * Run: node tests/crypto-vectors.test.cjs
 */
const fs = require('node:fs')
const path = require('node:path')
const assert = require('node:assert')
const _sodium = require('libsodium-wrappers-sumo')

const fromB64 = (s, b) => s.from_base64(b, s.base64_variants.ORIGINAL)
const toB64 = (s, b) => s.to_base64(b, s.base64_variants.ORIGINAL)
const concat = (...parts) => {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0))
  let off = 0
  for (const p of parts) {
    out.set(p, off)
    off += p.length
  }
  return out
}
const same = (a, b) => Buffer.from(a).equals(Buffer.from(b))

let count = 0
function check(name, cond) {
  assert.ok(cond, name)
  count += 1
  console.log('  \u2713', name)
}

async function main() {
  const s = _sodium
  await s.ready
  const v = JSON.parse(fs.readFileSync(path.join(__dirname, '..', '..', 'shared', 'crypto-vectors.json'), 'utf8'))
  assert.strictEqual(v.format, 'fusion-photos-crypto-vectors', 'unexpected vectors file')
  console.log(`crypto vectors (sodium ${v.sodiumVersion})`)

  // Constants the Kotlin side must use identically.
  check('salt bytes = 16', v.constants.pwhashSaltBytes === 16)
  check('aead nonce = 24 bytes', v.constants.aeadNonceBytes === 24)
  check('stream header = 24 bytes', v.constants.streamHeaderBytes === 24)

  // Argon2id MODERATE reproduces the pinned KEK.
  const kek = s.crypto_pwhash(
    32,
    v.argon2id.passphrase,
    fromB64(s, v.argon2id.saltB64),
    v.constants.pwhashOpsLimitModerate,
    v.constants.pwhashMemLimitModerate,
    v.constants.pwhashAlgArgon2id13,
  )
  check('argon2id derives the pinned KEK', same(kek, fromB64(s, v.argon2id.kekB64)))

  // vault key wrap/unwrap
  const vw = v.vaultKeyWrap
  const wBlob = fromB64(s, vw.wrappedB64)
  const n = v.constants.aeadNonceBytes
  const unwrappedVault = s.crypto_aead_xchacha20poly1305_ietf_decrypt(
    null, wBlob.slice(n), null, wBlob.slice(0, n), kek,
  )
  check('vault key unwraps with the derived KEK', same(unwrappedVault, fromB64(s, vw.vaultKeyB64)))
  const rewrapped = s.crypto_aead_xchacha20poly1305_ietf_encrypt(
    fromB64(s, vw.vaultKeyB64), null, null, fromB64(s, vw.nonceB64), kek,
  )
  check('vault key re-wraps byte-identically (deterministic vector)',
    same(concat(fromB64(s, vw.nonceB64), rewrapped), wBlob))

  // asset key envelope
  const aw = v.assetKeyWrap
  const aBlob = fromB64(s, aw.envelopeB64)
  const assetKey = s.crypto_aead_xchacha20poly1305_ietf_decrypt(
    null, aBlob.slice(n), null, aBlob.slice(0, n), fromB64(s, aw.vaultKeyB64),
  )
  check('asset key unwraps under the vault key', same(assetKey, fromB64(s, aw.assetKeyB64)))
  check('asset envelope length = 24 + 32 + 16', aBlob.length === 24 + 32 + 16)

  // recovery envelope
  const rw = v.recoveryWrap
  const rBlob = fromB64(s, rw.envelopeB64)
  const recovered = s.crypto_aead_xchacha20poly1305_ietf_decrypt(
    null, rBlob.slice(n), null, rBlob.slice(0, n), fromB64(s, rw.recoveryKeyB64),
  )
  check('vault key unwraps under the recovery key', same(recovered, fromB64(s, rw.vaultKeyB64)))

  // resource payload (header-prefixed secretstream)
  const p = v.payload
  const wire = fromB64(s, p.wireB64)
  const hLen = v.constants.streamHeaderBytes
  const state = s.crypto_secretstream_xchacha20poly1305_init_pull(wire.slice(0, hLen), fromB64(s, p.assetKeyB64))
  const out = s.crypto_secretstream_xchacha20poly1305_pull(state, wire.slice(hLen), null)
  check('resource payload decrypts to the pinned plaintext', same(out.message, fromB64(s, p.plaintextB64)))
  check('wire = header || frames', wire.length === hLen + fromB64(s, p.blobB64).length)

  // manifest envelope: header and blob are separate base64 fields
  const mState = s.crypto_secretstream_xchacha20poly1305_init_pull(fromB64(s, p.headerB64), fromB64(s, p.assetKeyB64))
  const mOut = s.crypto_secretstream_xchacha20poly1305_pull(mState, fromB64(s, p.blobB64), null)
  check('manifest envelope decrypts to the same plaintext', same(mOut.message, fromB64(s, p.plaintextB64)))

  // wrong key must fail closed (libsodium's pull returns falsy rather than throwing)
  let rejected = false
  try {
    const badKey = fromB64(s, vw.vaultKeyB64) // different key from the payload's asset key
    const bad = s.crypto_secretstream_xchacha20poly1305_init_pull(wire.slice(0, hLen), badKey)
    rejected = !s.crypto_secretstream_xchacha20poly1305_pull(bad, wire.slice(hLen), null)
  } catch {
    rejected = true
  }
  check('a wrong key is rejected', rejected)

  console.log(`\ncrypto vectors: ${count} checks passed`)
}

main().catch((e) => {
  console.error(e)
  process.exit(1)
})
