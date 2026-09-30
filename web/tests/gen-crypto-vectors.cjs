/**
 * Generate the language-neutral crypto vectors shared by every client (web, Android,
 * desktop): ../shared/crypto-vectors.json
 *
 *   cd web && node tests/gen-crypto-vectors.cjs
 *
 * Every input is a fixed hex constant, so the deterministic parts are reproducible.
 * The ONE non-reproducible value is the secretstream header (libsodium generates it at
 * random); it is therefore generated once, pinned in the file, and only ever decrypted
 * afterwards. CI runs the *verifier* (crypto-vectors.test.cjs), never this script, so
 * the vectors cannot drift silently.
 */
const fs = require('node:fs')
const path = require('node:path')
const _sodium = require('libsodium-wrappers-sumo')

const fromHex = (h) => Uint8Array.from(Buffer.from(h, 'hex'))
const concat = (...parts) => {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0))
  let off = 0
  for (const p of parts) {
    out.set(p, off)
    off += p.length
  }
  return out
}

async function main() {
  const s = _sodium
  await s.ready
  const b64 = (b) => s.to_base64(b, s.base64_variants.ORIGINAL)

  // ---- fixed inputs ----
  const PASSPHRASE = 'correct horse battery staple 42!'
  const SALT = fromHex('000102030405060708090a0b0c0d0e0f') // 16 (pwhash_SALTBYTES)
  const VAULT_KEY = fromHex('101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f')
  const VAULT_NONCE = fromHex('202122232425262728292a2b2c2d2e2f3031323334353637')
  const ASSET_KEY = fromHex('303132333435363738393a3b3c3d3e3f404142434445464748494a4b4c4d4e4f')
  const ASSET_NONCE = fromHex('404142434445464748494a4b4c4d4e4f5051525354555657')
  const RECOVERY_KEY = fromHex('505152535455565758595a5b5c5d5e5f606162636465666768696a6b6c6d6e6f')
  const RECOVERY_NONCE = fromHex('606162636465666768696a6b6c6d6e6f7071727374757677')
  const PLAINTEXT = new TextEncoder().encode('fusion-photos crypto vector payload - keep byte-exact')

  const OPSLIMIT = s.crypto_pwhash_OPSLIMIT_MODERATE
  const MEMLIMIT = s.crypto_pwhash_MEMLIMIT_MODERATE
  const ALG = s.crypto_pwhash_ALG_ARGON2ID13

  // ---- derive + wrap, exactly as src/lib/crypto.ts does ----
  const kek = s.crypto_pwhash(32, PASSPHRASE, SALT, OPSLIMIT, MEMLIMIT, ALG)
  const vaultCt = s.crypto_aead_xchacha20poly1305_ietf_encrypt(VAULT_KEY, null, null, VAULT_NONCE, kek)
  const assetCt = s.crypto_aead_xchacha20poly1305_ietf_encrypt(ASSET_KEY, null, null, ASSET_NONCE, VAULT_KEY)
  const recoveryCt = s.crypto_aead_xchacha20poly1305_ietf_encrypt(VAULT_KEY, null, null, RECOVERY_NONCE, RECOVERY_KEY)

  const stream = s.crypto_secretstream_xchacha20poly1305_init_push(ASSET_KEY)
  const streamCt = s.crypto_secretstream_xchacha20poly1305_push(
    stream.state,
    PLAINTEXT,
    null,
    s.crypto_secretstream_xchacha20poly1305_TAG_FINAL,
  )

  const vectors = {
    format: 'fusion-photos-crypto-vectors',
    version: 1,
    notes: [
      'Wire format shared by all clients. Do not edit by hand.',
      'wrapped/envelope values are base64(nonce(24) || AEAD_ciphertext_including_tag).',
      'payload.wireB64 is base64(header(24) || secretstream frames) - the resource object format.',
      'payload.headerB64/blobB64 are the manifest envelope format (both stored server-side).',
      'The secretstream header is random in libsodium; it is pinned here and only decrypted.',
    ],
    sodiumVersion: s.sodium_version_string(),
    constants: {
      pwhashSaltBytes: s.crypto_pwhash_SALTBYTES,
      pwhashOpsLimitModerate: OPSLIMIT,
      pwhashMemLimitModerate: MEMLIMIT,
      pwhashAlgArgon2id13: ALG,
      aeadNonceBytes: s.crypto_aead_xchacha20poly1305_ietf_NPUBBYTES,
      aeadKeyBytes: s.crypto_aead_xchacha20poly1305_ietf_KEYBYTES,
      streamHeaderBytes: s.crypto_secretstream_xchacha20poly1305_HEADERBYTES,
      streamTagFinal: s.crypto_secretstream_xchacha20poly1305_TAG_FINAL,
    },
    argon2id: {
      passphrase: PASSPHRASE,
      saltB64: b64(SALT),
      keyLen: 32,
      kekB64: b64(kek),
    },
    vaultKeyWrap: {
      vaultKeyB64: b64(VAULT_KEY),
      nonceB64: b64(VAULT_NONCE),
      wrappedB64: b64(concat(VAULT_NONCE, vaultCt)),
    },
    assetKeyWrap: {
      assetKeyB64: b64(ASSET_KEY),
      vaultKeyB64: b64(VAULT_KEY),
      nonceB64: b64(ASSET_NONCE),
      envelopeB64: b64(concat(ASSET_NONCE, assetCt)),
    },
    recoveryWrap: {
      vaultKeyB64: b64(VAULT_KEY),
      recoveryKeyB64: b64(RECOVERY_KEY),
      nonceB64: b64(RECOVERY_NONCE),
      envelopeB64: b64(concat(RECOVERY_NONCE, recoveryCt)),
    },
    payload: {
      assetKeyB64: b64(ASSET_KEY),
      plaintextB64: b64(PLAINTEXT),
      headerB64: b64(stream.header),
      blobB64: b64(streamCt),
      wireB64: b64(concat(stream.header, streamCt)),
    },
  }

  const outPath = path.join(__dirname, '..', '..', 'shared', 'crypto-vectors.json')
  fs.mkdirSync(path.dirname(outPath), { recursive: true })
  fs.writeFileSync(outPath, JSON.stringify(vectors, null, 2) + '\n')
  console.log('wrote', outPath)
  console.log('sodium', vectors.sodiumVersion)
}

main().catch((e) => {
  console.error(e)
  process.exit(1)
})
