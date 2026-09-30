/**
 * Browser-limits tests: `fetchSigned` must re-sign exactly once when a short-lived
 * presigned URL is rejected, and must NOT re-sign for success or genuine server errors.
 *
 * This is the client-side half of the plan's "expired access" gate — it is what lets a
 * long export (or a large preview) survive the 15-minute signing window.
 *
 * Run: npm run test:lib
 */
const assert = require('node:assert')
const { fetchSigned } = require('./.build/api.js')

let count = 0
function pass(name) {
  count += 1
  console.log('  \u2713', name)
}

/** Stub global.fetch to answer with the given statuses in order. */
function stubFetch(statuses) {
  let n = 0
  const urls = []
  global.fetch = async (url) => {
    const status = statuses[Math.min(n, statuses.length - 1)]
    urls.push(url)
    n += 1
    return { ok: status < 400, status, url }
  }
  return { urls, calls: () => n }
}

let signCount = 0
const signer = () => {
  signCount += 1
  return Promise.resolve({ url: `https://s3.invalid/object?sig=${signCount}` })
}

;(async () => {
  console.log('browser limits: signed-URL retry')

  {
    const f = stubFetch([403, 200])
    signCount = 0
    const res = await fetchSigned(signer)
    assert.strictEqual(res.status, 200, 'expired signature must succeed after re-signing')
    assert.strictEqual(f.calls(), 2, 'must retry exactly once')
    assert.notStrictEqual(f.urls[0], f.urls[1], 'the retry must use a freshly signed URL')
    assert.strictEqual(signCount, 2, 'the signer is called once per attempt')
    pass('403 -> re-sign once -> 200')
  }

  {
    const f = stubFetch([404, 200])
    signCount = 0
    const res = await fetchSigned(signer)
    assert.strictEqual(res.status, 200)
    assert.strictEqual(f.calls(), 2)
    pass('404 (rotated object) -> re-sign once -> 200')
  }

  {
    const f = stubFetch([200])
    signCount = 0
    const res = await fetchSigned(signer)
    assert.strictEqual(res.status, 200)
    assert.strictEqual(f.calls(), 1)
    assert.strictEqual(signCount, 1)
    pass('200 -> single attempt, no re-sign')
  }

  {
    const f = stubFetch([500, 500])
    signCount = 0
    const res = await fetchSigned(signer)
    assert.strictEqual(res.status, 500)
    assert.strictEqual(f.calls(), 1, 'a genuine server error must not be re-signed')
    pass('500 -> returned as-is (no pointless re-sign)')
  }

  {
    const f = stubFetch([403, 403])
    signCount = 0
    const res = await fetchSigned(signer)
    assert.strictEqual(res.status, 403)
    assert.strictEqual(f.calls(), 2, 'gives up after exactly one retry')
    pass('403 twice -> gives up after one retry')
  }

  delete global.fetch
  console.log(`\nsigned-URL retry: ${count} checks passed`)
})().catch((err) => {
  console.error(err)
  process.exit(1)
})
