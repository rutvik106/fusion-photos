/**
 * Minimal, dependency-free ZIP central-directory reader.
 *
 * Google Takeout ships a library as MANY independent `.zip` parts (not a spanned
 * archive), so each part can be read on its own. We only need the *directory* — entry
 * names, sizes, and where each entry's bytes begin — so the importer can stream one
 * entry out of a 50 GB part without extracting or loading the archive.
 *
 * Standard and ZIP64 end-of-central-directory records are supported (50 GB parts exceed
 * the 4 GiB field widths). Pure: exercised by tests/takeout-zip.test.cjs.
 */
import type { ByteSource } from './byteSource'

const EOCD_SIG = 0x06054b50
const ZIP64_EOCD_SIG = 0x06064b50
const ZIP64_LOCATOR_SIG = 0x07064b50
const CEN_SIG = 0x02014b50
const LOCAL_SIG = 0x04034b50
const ZIP64_EXTRA_ID = 0x0001

const EOCD_LEN = 22
const MAX_COMMENT = 0xffff
const U16_MAX = 0xffff
const U32_MAX = 0xffffffff

/** 0 = stored (no compression), 8 = deflate. */
export const ZIP_STORED = 0
export const ZIP_DEFLATE = 8

export interface ZipEntry {
  name: string
  method: number
  compressedSize: number
  uncompressedSize: number
  /** offset of the LOCAL file header; entry data begins after it */
  localHeaderOffset: number
  crc32: number
}

export interface ZipDirectory {
  entries: ZipEntry[]
  /** true when ZIP64 records were used */
  zip64: boolean
}

function u16(d: Uint8Array, o: number): number {
  return d[o] | (d[o + 1] << 8)
}

function u32(d: Uint8Array, o: number): number {
  return (d[o] | (d[o + 1] << 8) | (d[o + 2] << 16) | (d[o + 3] << 24)) >>> 0
}

/** JS numbers are exact to 2^53 — plenty for any zip size/offset. */
function u64(d: Uint8Array, o: number): number {
  return u32(d, o + 4) * 0x100000000 + u32(d, o)
}

/** Read the whole central directory of a zip part. */
export async function readZipDirectory(src: ByteSource): Promise<ZipDirectory> {
  const tailLen = Math.min(src.size, MAX_COMMENT + EOCD_LEN)
  const tail = await src.read(src.size - tailLen, tailLen)

  let eocd = -1
  for (let i = tail.length - EOCD_LEN; i >= 0; i--) {
    if (u32(tail, i) === EOCD_SIG) {
      eocd = i
      break
    }
  }
  if (eocd < 0) throw new Error('not a zip: end-of-central-directory not found')

  let entryCount = u16(tail, eocd + 10)
  let cenSize = u32(tail, eocd + 12)
  let cenOffset = u32(tail, eocd + 16)
  let zip64 = false

  if (entryCount === U16_MAX || cenSize === U32_MAX || cenOffset === U32_MAX) {
    const loc = eocd - 20
    if (loc < 0 || u32(tail, loc) !== ZIP64_LOCATOR_SIG) throw new Error('zip64 locator missing')
    const z64Offset = u64(tail, loc + 8)
    const rec = await src.read(z64Offset, 56)
    if (u32(rec, 0) !== ZIP64_EOCD_SIG) throw new Error('zip64 end-of-central-directory missing')
    entryCount = u64(rec, 32)
    cenSize = u64(rec, 40)
    cenOffset = u64(rec, 48)
    zip64 = true
  }

  const cen = await src.read(cenOffset, cenSize)
  const entries: ZipEntry[] = []
  let p = 0
  while (p + 46 <= cen.length && u32(cen, p) === CEN_SIG) {
    const method = u16(cen, p + 10)
    const crc32 = u32(cen, p + 16)
    const nameLen = u16(cen, p + 28)
    const extraLen = u16(cen, p + 30)
    const commentLen = u16(cen, p + 32)

    let compressedSize = u32(cen, p + 20)
    let uncompressedSize = u32(cen, p + 24)
    let localHeaderOffset = u32(cen, p + 42)

    const name = new TextDecoder().decode(cen.subarray(p + 46, p + 46 + nameLen))

    // ZIP64 extended information extra field (id 0x0001), fields present only when
    // the corresponding 32-bit value is the 0xFFFFFFFF sentinel, in this fixed order.
    const extraStart = p + 46 + nameLen
    const extraEnd = extraStart + extraLen
    let e = extraStart
    while (e + 4 <= extraEnd) {
      const id = u16(cen, e)
      const len = u16(cen, e + 2)
      if (id === ZIP64_EXTRA_ID) {
        let f = e + 4
        if (uncompressedSize === U32_MAX) {
          uncompressedSize = u64(cen, f)
          f += 8
        }
        if (compressedSize === U32_MAX) {
          compressedSize = u64(cen, f)
          f += 8
        }
        if (localHeaderOffset === U32_MAX) {
          localHeaderOffset = u64(cen, f)
          f += 8
        }
      }
      e += 4 + len
    }

    entries.push({ name, method, compressedSize, uncompressedSize, localHeaderOffset, crc32 })
    p = extraEnd + commentLen
  }

  return { entries, zip64 }
}

/** Byte offset where an entry's data begins (after its local header + name + extra). */
export async function zipEntryDataOffset(src: ByteSource, entry: ZipEntry): Promise<number> {
  const lh = await src.read(entry.localHeaderOffset, 30)
  if (u32(lh, 0) !== LOCAL_SIG) throw new Error(`bad local file header for ${entry.name}`)
  return entry.localHeaderOffset + 30 + u16(lh, 26) + u16(lh, 28)
}

/**
 * Read one entry's raw stored bytes (compressed for deflate entries). Stored entries are
 * the common case for Takeout photos; callers inflate deflate entries themselves so the
 * decompressor stays a host choice.
 */
export async function readZipEntry(src: ByteSource, entry: ZipEntry): Promise<Uint8Array> {
  const offset = await zipEntryDataOffset(src, entry)
  return src.read(offset, entry.compressedSize)
}
