/**
 * ZIP central-directory reader tests.
 *
 * These build real (byte-exact) ZIP archives in memory — standard and ZIP64 — and parse
 * them with the compiled src/lib/import modules, so the reader is verified without any
 * fixture files or external tooling. Run: npm run test:import
 */
const assert = require('node:assert')
const { readZipDirectory, readZipEntry, zipEntryDataOffset } = require('./.build/zipCentralDirectory.js')
const { bufferSource } = require('./.build/byteSource.js')

let count = 0
function test(name, fn) {
  fn()
  count += 1
  console.log('  \u2713', name)
}

// ---- minimal CRC-32 (IEEE) ----
const CRC_TABLE = (() => {
  const t = new Uint32Array(256)
  for (let n = 0; n < 256; n++) {
    let c = n
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
    t[n] = c >>> 0
  }
  return t
})()

function crc32(bytes) {
  let c = 0xffffffff
  for (let i = 0; i < bytes.length; i++) c = CRC_TABLE[(c ^ bytes[i]) & 0xff] ^ (c >>> 8)
  return (c ^ 0xffffffff) >>> 0
}

/**
 * Build a ZIP with STORED (uncompressed) entries. `zip64: true` emits ZIP64 end records
 * and 0xFFFF/0xFFFFFFFF sentinels in the classic EOCD, exercising the 64-bit path.
 */
function buildZip(files, { zip64 = false, comment = '' } = {}) {
  const enc = new TextEncoder()
  const commentBytes = enc.encode(comment)
  const parts = []
  const central = []
  let offset = 0

  for (const f of files) {
    const nameBytes = enc.encode(f.name)
    const data = enc.encode(f.data)
    const crc = crc32(data)
    const lh = new Uint8Array(30 + nameBytes.length)
    const dv = new DataView(lh.buffer)
    dv.setUint32(0, 0x04034b50, true)
    dv.setUint16(4, 20, true)
    dv.setUint16(6, 0, true)
    dv.setUint16(8, 0, true) // stored
    dv.setUint16(26, nameBytes.length, true)
    dv.setUint32(14, crc, true)
    dv.setUint32(18, data.length, true)
    dv.setUint32(22, data.length, true)
    lh.set(nameBytes, 30)
    parts.push(lh, data)
    central.push({ nameBytes, crc, size: data.length, offset })
    offset += lh.length + data.length
  }

  const cenStart = offset
  for (const c of central) {
    const ch = new Uint8Array(46 + c.nameBytes.length)
    const dv = new DataView(ch.buffer)
    dv.setUint32(0, 0x02014b50, true)
    dv.setUint16(4, 20, true)
    dv.setUint16(6, 20, true)
    dv.setUint16(10, 0, true) // stored
    dv.setUint32(16, c.crc, true)
    dv.setUint32(20, c.size, true)
    dv.setUint32(24, c.size, true)
    dv.setUint16(28, c.nameBytes.length, true)
    dv.setUint32(42, c.offset, true)
    ch.set(c.nameBytes, 46)
    parts.push(ch)
    offset += ch.length
  }
  const cenSize = offset - cenStart

  if (zip64) {
    const z64 = new Uint8Array(56)
    const dv = new DataView(z64.buffer)
    dv.setUint32(0, 0x06064b50, true)
    dv.setUint32(4, 44, true) // size of remainder
    dv.setUint16(12, 45, true) // version made by
    dv.setUint16(14, 45, true) // version needed
    dv.setUint32(16, 0, true)
    dv.setUint32(20, 0, true)
    dv.setUint32(24, central.length, true) // entries on this disk (lo)
    dv.setUint32(32, central.length, true) // total entries (lo)
    dv.setUint32(40, cenSize, true) // central dir size (lo)
    dv.setUint32(48, cenStart, true) // central dir offset (lo)
    parts.push(z64)
    offset += z64.length

    const loc = new Uint8Array(20)
    const lv = new DataView(loc.buffer)
    lv.setUint32(0, 0x07064b50, true)
    lv.setUint32(4, 0, true)
    lv.setUint32(8, offset - z64.length, true) // offset of the zip64 EOCD
    lv.setUint32(16, 1, true)
    parts.push(loc)
    offset += loc.length
  }

  const eocd = new Uint8Array(22 + commentBytes.length)
  const ev = new DataView(eocd.buffer)
  ev.setUint32(0, 0x06054b50, true)
  ev.setUint16(8, zip64 ? 0xffff : central.length, true)
  ev.setUint16(10, zip64 ? 0xffff : central.length, true)
  ev.setUint32(12, zip64 ? 0xffffffff : cenSize, true)
  ev.setUint32(16, zip64 ? 0xffffffff : cenStart, true)
  ev.setUint16(20, commentBytes.length, true)
  eocd.set(commentBytes, 22)
  parts.push(eocd)

  const total = parts.reduce((n, p) => n + p.length, 0)
  const out = new Uint8Array(total)
  let o = 0
  for (const p of parts) {
    out.set(p, o)
    o += p.length
  }
  return out
}

/** Async-aware test helper. */
async function test(name, fn) {
  await fn()
  count += 1
  console.log('  \u2713', name)
}

const FILES = [
  { name: 'Google Photos/IMG_1.jpg', data: 'first-photo-bytes' },
  { name: 'Google Photos/IMG_1.jpg.json', data: '{"title":"IMG_1.jpg"}' },
  { name: 'Google Photos/Photos from 2023/IMG_2.heic', data: 'second-photo' },
]

;(async () => {
  console.log('zip: standard archive')
  const zip = buildZip(FILES)
  const dir = await readZipDirectory(bufferSource(zip))

  await test('all entries are listed in order', () => {
    assert.deepStrictEqual(
      dir.entries.map((e) => e.name),
      FILES.map((f) => f.name),
    )
    assert.strictEqual(dir.zip64, false)
  })
  await test('stored entries report method/sizes correctly', () => {
    for (let i = 0; i < FILES.length; i++) {
      const e = dir.entries[i]
      assert.strictEqual(e.method, 0)
      assert.strictEqual(e.compressedSize, FILES[i].data.length)
      assert.strictEqual(e.uncompressedSize, FILES[i].data.length)
    }
  })
  await test('entry data streams back byte-for-byte at the right offset', async () => {
    for (let i = 0; i < FILES.length; i++) {
      const data = await readZipEntry(bufferSource(zip), dir.entries[i])
      assert.strictEqual(new TextDecoder().decode(data), FILES[i].data)
      const off = await zipEntryDataOffset(bufferSource(zip), dir.entries[i])
      assert.strictEqual(off, dir.entries[i].localHeaderOffset + 30 + FILES[i].name.length)
    }
  })

  console.log('zip: archive comment')
  await test('EOCD is still found with a trailing comment', async () => {
    const commented = buildZip(FILES, { comment: 'Takeout export comment' })
    const d = await readZipDirectory(bufferSource(commented))
    assert.strictEqual(d.entries.length, FILES.length)
  })

  console.log('zip: ZIP64')
  await test('ZIP64 end records are parsed and flagged', async () => {
    const z64 = buildZip(FILES, { zip64: true })
    const d = await readZipDirectory(bufferSource(z64))
    assert.strictEqual(d.zip64, true)
    assert.deepStrictEqual(
      d.entries.map((e) => e.name),
      FILES.map((f) => f.name),
    )
    const data = await readZipEntry(bufferSource(z64), d.entries[2])
    assert.strictEqual(new TextDecoder().decode(data), FILES[2].data)
  })

  console.log('zip: rejection')
  await test('a non-zip buffer is rejected', async () => {
    await assert.rejects(() => readZipDirectory(bufferSource(new Uint8Array(100))))
  })

  console.log('zip: native byte source')
  await test('tauriFileSource reads ranges through the bridge', async () => {
    const { tauriFileSource } = require('./.build/tauriByteSource.js')
    const backing = buildZip(FILES)
    const calls = []
    const bridge = {
      invoke: async (cmd, args) => {
        calls.push([cmd, args.path, args.offset, args.length])
        return Array.from(backing.subarray(args.offset, args.offset + args.length))
      },
    }
    const src = tauriFileSource(bridge, '/tmp/takeout-001.zip', backing.length)
    assert.strictEqual(src.size, backing.length)
    const dir = await readZipDirectory(src)
    assert.deepStrictEqual(
      dir.entries.map((e) => e.name),
      FILES.map((f) => f.name),
    )
    assert.ok(calls.every((c) => c[0] === 'read_file_range'))
  })

  console.log(`\ntakeout zip: ${count} checks passed`)
})().catch((err) => {
  console.error(err)
  process.exit(1)
})

