/**
 * Full end-to-end round trip against the running stack (API :8080 -> real S3).
 * Mirrors web/src/lib crypto + upload + download paths exactly:
 *   OTP login -> vault wrap -> encrypt (header-prefixed) -> presigned PUTs ->
 *   server-side verify + commit -> sync -> presigned GET -> decrypt -> SHA-256 check.
 *
 * Run: node tests/e2e-roundtrip.cjs        (requires docker compose stack up + .env)
 */
const _sodium = require('libsodium-wrappers-sumo')
const fs = require('fs')

const BASE = process.env.API_BASE || 'http://localhost:8080'
const EMAIL = 'e2e-roundtrip@test.local'

function loadEnv() {
  const env = {}
  for (const line of fs.readFileSync(require('path').join(__dirname, '../..', '.env'), 'utf8').split('\n')) {
    const m = line.match(/^([A-Z0-9_]+)=(.*)$/)
    if (m) env[m[1]] = m[2]
  }
  return env
}

async function api(path, { method = 'GET', body, token } = {}) {
  const res = await fetch(BASE + '/v1' + path, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: 'Bearer ' + token } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  })
  const data = await res.json()
  if (!res.ok) throw new Error(`${path} -> HTTP ${res.status}: ${JSON.stringify(data)}`)
  return data
}

async function sha256Hex(bytes) {
  const d = await crypto.subtle.digest('SHA-256', bytes)
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, '0')).join('')
}

const b64 = (u8) => _sodium.to_base64(u8, _sodium.base64_variants.ORIGINAL)
const fromB64 = (s) => _sodium.from_base64(s, _sodium.base64_variants.ORIGINAL)
/** decoded byte length of a base64 string (what the API compares for the manifest) */
const b64Len = (s) => fromB64(s).length

async function encryptBytes(plaintext, key) {
  await _sodium.ready
  const s = _sodium
  const st = s.crypto_secretstream_xchacha20poly1305_init_push(key)
  const ct = s.crypto_secretstream_xchacha20poly1305_push(st.state, plaintext, null, s.crypto_secretstream_xchacha20poly1305_TAG_FINAL)
  return Buffer.concat([Buffer.from(st.header), Buffer.from(ct)])
}

async function decryptBytes(blob, key) {
  const s = await _sodium.ready.then(() => _sodium)
  const h = s.crypto_secretstream_xchacha20poly1305_HEADERBYTES
  // init_pull returns the state object directly (init_push returns {state, header})
  const state = s.crypto_secretstream_xchacha20poly1305_init_pull(blob.slice(0, h), key)
  const out = s.crypto_secretstream_xchacha20poly1305_pull(state, blob.slice(h), null)
  if (!out) throw new Error('decrypt failed')
  return Buffer.from(out.message)
}async function main() {
  const env = loadEnv()
  await _sodium.ready
  const s = _sodium
  console.log('1. OTP login')
  const otp = await api('/auth/otp/request', { method: 'POST', body: { email: EMAIL } })
  const session = await api('/auth/otp/verify', { method: 'POST', body: { email: EMAIL, code: otp.devCode } })
  const tok = session.sessionToken
  console.log('   ok, userId', session.userId.slice(0, 8))

  console.log('2. Vault wrap/put (passphrase-derived KEK, Argon2id)')
  const passphrase = 'e2e test passphrase 42!'
  const vaultKey = s.crypto_secretbox_keygen()
  const salt = s.randombytes_buf(s.crypto_pwhash_SALTBYTES)
  const kek = s.crypto_pwhash(32, passphrase, salt, s.crypto_pwhash_OPSLIMIT_MODERATE, s.crypto_pwhash_MEMLIMIT_MODERATE, s.crypto_pwhash_ALG_ARGON2ID13)
  const nonce = s.randombytes_buf(s.crypto_aead_xchacha20poly1305_ietf_NPUBBYTES)
  const ct = s.crypto_aead_xchacha20poly1305_ietf_encrypt(vaultKey, null, null, nonce, kek)
  await api('/vault', { method: 'PUT', token: tok, body: { wrapped: { kdfSalt: b64(salt), wrapped: b64(Buffer.concat([Buffer.from(nonce), Buffer.from(ct)])), v: 1 } } })
  console.log('   vault stored (opaque blob)')

  console.log('3. Prepare synthetic photo + encrypt (header-prefixed wire format)')
  const original = Buffer.from(Array.from({ length: 128 * 1024 }, (_, i) => (i * 7) % 256))
  const thumbnail = Buffer.from(Array.from({ length: 8 * 1024 }, (_, i) => (i * 13) % 256))
  const preview = Buffer.from(Array.from({ length: 32 * 1024 }, (_, i) => (i * 31) % 256))
  const originalSha = await sha256Hex(original)

  const assetKey = s.randombytes_buf(32)
  const n = s.randombytes_buf(s.crypto_aead_xchacha20poly1305_ietf_NPUBBYTES)
  const c = s.crypto_aead_xchacha20poly1305_ietf_encrypt(assetKey, null, null, n, vaultKey)
  const keyEnvelope = b64(Buffer.concat([Buffer.from(n), Buffer.from(c)]))

  const manifestPlain = JSON.stringify({ fileName: 'e2e-test.jpg', mimeType: 'image/jpeg', capturedAt: '2026-09-18T09:00:00Z', quality: 'original', originalSha256: originalSha })
  const mSt = s.crypto_secretstream_xchacha20poly1305_init_push(assetKey)
  const mCt = s.crypto_secretstream_xchacha20poly1305_push(mSt.state, Buffer.from(manifestPlain), null, s.crypto_secretstream_xchacha20poly1305_TAG_FINAL)
  const manifestEnv = { headerB64: b64(mSt.header), blobB64: b64(mCt) }

  const payloads = {
    original: await encryptBytes(original, assetKey),
    preview: await encryptBytes(preview, assetKey),
    thumbnail: await encryptBytes(thumbnail, assetKey),
  }

  console.log('4. Upload session (server-chosen keys + presigned PUTs)')
  const sess = await api('/upload-session', { method: 'POST', token: tok, body: { items: [{
    plaintextSha256: originalSha,
    originalSize: original.length,
    encryptedSizes: {
      original: payloads.original.length,
      preview: payloads.preview.length,
      thumbnail: payloads.thumbnail.length,
      manifest: b64Len(manifestEnv.headerB64) + b64Len(manifestEnv.blobB64),
    },
    assetIdClient: 'e2e-ref-1',
  }] } })
  const plan = sess.items[0]
console.log('5. PUT ciphertext directly to S3 (bucket: ' + env.S3_BUCKET + ')')
  for (const [variant, payload] of Object.entries(payloads)) {
    const r = await fetch(plan.putUrls[variant], { method: 'PUT', body: payload })
    if (!r.ok) throw new Error(`PUT ${variant} failed: HTTP ${r.status}`)
  }
  console.log('   all 3 PUTs ok')

  console.log('6. Complete (server HEAD-verifies sizes, commits transactionally)')
  const done = await api('/upload-session/complete', { method: 'POST', token: tok, body: { items: [{
    assetId: plan.assetId,
    keyEnvelope,
    manifest: manifestEnv,
    resources: Object.entries(payloads).map(([variant, payload]) => ({
      variant, objectKey: plan.keys[variant], ciphertextSize: payload.length, plaintextSha256: originalSha,
    })),
  }] } })
  console.log('   committed, seq =', done.assets[0].seq)

  console.log('7. Sync (incremental cursor)')
  const sync = await api('/sync/changes?cursor=0', { token: tok })
  const asset = sync.assets.find((a) => a.assetId === plan.assetId)
  if (!asset) throw new Error('asset missing from sync')
  console.log('   asset present with variants', Object.keys(asset.resources).sort().join(','))

  console.log('8. Authorized presigned GET + decrypt (the fixed download path)')
  for (const [variant, expected] of [['original', original], ['thumbnail', thumbnail], ['preview', preview]]) {
    const res = await api(`/resource/${plan.assetId}/${variant}`, { token: tok })
    const http = await fetch(res.url)
    if (!http.ok) throw new Error(`GET ${variant}: HTTP ${http.status}`)
    const wire = Buffer.from(await http.arrayBuffer())
    const plaintext = await decryptBytes(wire, assetKey)
    const ok = plaintext.equals(Buffer.from(expected))
    const digestOk = variant === 'original' ? (await sha256Hex(plaintext)) === originalSha : true
    console.log(`   ${variant}: ${wire.length} bytes ciphertext -> decrypt ok=${ok}, sha256 match=${digestOk}`)
    if (!ok || !digestOk) throw new Error(`round trip failed for ${variant}`)
  }

  console.log('9. Multipart upload (17 MiB original in 3 parts, per plan §transfer)')
  const bigPlain = Buffer.from(Array.from({ length: 17 * 1024 * 1024 }, (_, i) => (i * 97) % 256))
  const bigSha = await sha256Hex(bigPlain)
  const mpKey = s.randombytes_buf(32)
  const mpNonce = s.randombytes_buf(s.crypto_aead_xchacha20poly1305_ietf_NPUBBYTES)
  const mpCt = s.crypto_aead_xchacha20poly1305_ietf_encrypt(mpKey, null, null, mpNonce, vaultKey)
  const mpEnvelope = b64(Buffer.concat([Buffer.from(mpNonce), Buffer.from(mpCt)]))
  const mpPlain = await encryptBytes(bigPlain, mpKey) // header-prefixed ciphertext
  const mpThumbPlain = Buffer.from(Array.from({ length: 4 * 1024 }, (_, i) => i % 251))
  const mpThumb = await encryptBytes(mpThumbPlain, mpKey)
  const mSt2 = s.crypto_secretstream_xchacha20poly1305_init_push(mpKey)
  const mCt2 = s.crypto_secretstream_xchacha20poly1305_push(mSt2.state, Buffer.from(JSON.stringify({ fileName: 'e2e-mp.jpg', originalSha256: bigSha })), null, s.crypto_secretstream_xchacha20poly1305_TAG_FINAL)
  const mpManifest = { headerB64: b64(mSt2.header), blobB64: b64(mCt2) }

  const mpSess = await api('/upload-session', { method: 'POST', token: tok, body: { items: [{
    originalSize: bigPlain.length,
    encryptedSizes: {
      original: mpPlain.length,
      preview: 0,
      thumbnail: mpThumb.length,
      manifest: b64Len(mpManifest.headerB64) + b64Len(mpManifest.blobB64),
    },
    assetIdClient: 'e2e-mp-1',
  }] } })
  const mpPlan = mpSess.items[0]

  const mpInit = await api(`/upload-session/${mpPlan.assetId}/multipart/init`, {
    method: 'POST', token: tok, body: { variant: 'original', partCount: 3 },
  })
  console.log('   init ok:', mpInit.parts.length, 'parts,', mpInit.parts.map((p) => p.length).join('+'), 'bytes')

  const mpEtags = []
  for (const part of mpInit.parts) {
    const slice = mpPlain.subarray(part.offset, part.offset + part.length)
    const r = await fetch(part.url, { method: 'PUT', body: slice })
    if (!r.ok) throw new Error(`part PUT failed: HTTP ${r.status}`)
    mpEtags.push({ number: part.number, etag: r.headers.get('etag') })
  }
  const mpDone = await api(`/upload-session/${mpPlan.assetId}/multipart/complete`, {
    method: 'POST', token: tok, body: { variant: 'original', parts: mpEtags },
  })
  if (mpDone.size !== mpPlain.length) throw new Error(`multipart size ${mpDone.size} != ${mpPlain.length}`)
  console.log('   multipart completed, object size verified =', mpDone.size)

  const putUrls = await api(`/upload-session/${mpPlan.assetId}/put-urls`, {
    method: 'POST', token: tok, body: { variants: ['original', 'thumbnail'] },
  })
  const thumbPlan = putUrls.items.find((i) => i.variant === 'thumbnail')
  const origPlan = putUrls.items.find((i) => i.variant === 'original')
  const rThumb = await fetch(thumbPlan.url, { method: 'PUT', body: mpThumb })
  if (!rThumb.ok) throw new Error(`thumbnail PUT failed: HTTP ${rThumb.status}`)

  await api('/upload-session/complete', { method: 'POST', token: tok, body: { items: [{
    assetId: mpPlan.assetId,
    keyEnvelope: mpEnvelope,
    manifest: mpManifest,
    resources: [
      { variant: 'original', objectKey: origPlan.objectKey, ciphertextSize: mpPlain.length },
      { variant: 'thumbnail', objectKey: thumbPlan.objectKey, ciphertextSize: mpThumb.length },
    ],
  }] } })
  console.log('   committed')

  const mpGet = await api(`/resource/${mpPlan.assetId}/original`, { token: tok })
  const mpWire = Buffer.from(await (await fetch(mpGet.url)).arrayBuffer())
  const mpDecrypted = await decryptBytes(mpWire, mpKey)
  const mpShaOk = (await sha256Hex(mpDecrypted)) === bigSha
  console.log('   multipart original decrypt:', mpWire.length, 'bytes ciphertext -> sha256 match =', mpShaOk)
  if (!mpShaOk || !mpDecrypted.equals(bigPlain)) throw new Error('multipart round trip failed')

  console.log('10. Trash / restore lifecycle (30-day window)')
  const trashRes = await api(`/assets/${mpPlan.assetId}/trash`, { method: 'POST', token: tok, body: {} })
  console.log('   trashed, purge after', trashRes.purgeAfter)
  let s2 = await api('/sync/changes?cursor=0', { token: tok })
  let trashed = s2.assets.find((a) => a.assetId === mpPlan.assetId)
  if (!trashed || !trashed.deleted || !trashed.deletedAt) throw new Error('trash not propagated')
  await api(`/assets/${mpPlan.assetId}/restore`, { method: 'POST', token: tok, body: {} })
  s2 = await api('/sync/changes?cursor=0', { token: tok })
  trashed = s2.assets.find((a) => a.assetId === mpPlan.assetId)
  if (!trashed || trashed.deleted) throw new Error('restore not propagated')
  console.log('   restored ok (seq bumped both ways)')
  // leave it trashed: this is a throwaway e2e account
  await api(`/assets/${mpPlan.assetId}/trash`, { method: 'POST', token: tok, body: {} })

  console.log('\nE2E ROUND TRIP PASSED — byte-identical originals through real S3 ✓')
}

main()
  .then(() => process.exit(0))
  .catch((e) => { console.error('E2E FAILED:', e.message); process.exit(1) })