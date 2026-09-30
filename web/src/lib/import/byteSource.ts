/**
 * Byte-range source abstraction for large, on-disk archives.
 *
 * A browser can only read a Blob it was handed; a native (Tauri) host can open a 50 GB
 * Takeout ZIP part and read arbitrary ranges. Both are exposed through this tiny
 * interface, so the ZIP reader and the import engine stay host-agnostic — and unit
 * testable with an in-memory buffer (tests/takeout-zip.test.cjs).
 */
export interface ByteSource {
  /** total size in bytes */
  size: number
  /** read [offset, offset+length); may return fewer bytes only at EOF */
  read(offset: number, length: number): Promise<Uint8Array>
}

/** ByteSource over a Uint8Array (tests, small in-memory archives). */
export function bufferSource(bytes: Uint8Array): ByteSource {
  return {
    size: bytes.length,
    async read(offset, length) {
      return bytes.subarray(offset, offset + length)
    },
  }
}

/** ByteSource over a browser Blob/File via `Blob.slice` (no full read into memory). */
export function blobSource(blob: Blob): ByteSource {
  return {
    size: blob.size,
    async read(offset, length) {
      const end = Math.min(offset + length, blob.size)
      const buf = await blob.slice(offset, Math.max(offset, end)).arrayBuffer()
      return new Uint8Array(buf)
    },
  }
}
