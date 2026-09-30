/**
 * OPFS (Origin Private File System) staging for in-flight multipart uploads.
 *
 * Per plan (§web caching): browsers can evict site data under storage pressure, so
 * staged ciphertext is a BEST-EFFORT resume source, never the system of record.
 * When staging exists, an interrupted multipart upload resumes from the staged
 * ciphertext (no re-encryption, only missing parts retransmit — the plan's
 * "at most an unfinished chunk may need retransmission"). Without OPFS, uploads
 * still work; resume-after-reload just isn't possible for that transfer.
 */

const STAGING_DIR = 'upload-staging'

function hasOPFS(): boolean {
  return typeof navigator !== 'undefined' && 'storage' in navigator && 'getDirectory' in navigator.storage
}

/** Handle for one staged resource: staging/{assetId}/{variant} */
function stagedPath(assetId: string, variant: string): string {
  // assetId/variant are server-issued UUIDs/known words — filesystem-safe
  return `${assetId}/${variant}`
}

export async function stagePut(assetId: string, variant: string, bytes: Uint8Array): Promise<boolean> {
  if (!hasOPFS()) return false
  try {
    const root = await navigator.storage.getDirectory()
    const dir = await root.getDirectoryHandle(STAGING_DIR, { create: true })
    const parts = stagedPath(assetId, variant).split('/')
    let handle = dir
    for (let i = 0; i < parts.length - 1; i++) {
      handle = await handle.getDirectoryHandle(parts[i], { create: true })
    }
    const file = await handle.getFileHandle(parts[parts.length - 1], { create: true })
    const writable = await file.createWritable()
    await writable.write(bytes)
    await writable.close()
    return true
  } catch {
    return false
  }
}

export async function stageGet(assetId: string, variant: string): Promise<Uint8Array | null> {
  if (!hasOPFS()) return null
  try {
    const root = await navigator.storage.getDirectory()
    const dir = await root.getDirectoryHandle(STAGING_DIR, { create: false })
    const parts = stagedPath(assetId, variant).split('/')
    let handle = dir
    for (let i = 0; i < parts.length - 1; i++) {
      handle = await handle.getDirectoryHandle(parts[i], { create: false })
    }
    const file = await handle.getFileHandle(parts[parts.length - 1], { create: false })
    const blob = await file.getFile()
    return new Uint8Array(await blob.arrayBuffer())
  } catch {
    return null
  }
}

export async function stageDelete(assetId: string, variant: string): Promise<void> {
  if (!hasOPFS()) return
  try {
    const root = await navigator.storage.getDirectory()
    const dir = await root.getDirectoryHandle(STAGING_DIR, { create: false })
    const asset = await dir.getDirectoryHandle(assetId, { create: false })
    await asset.removeEntry(variant)
  } catch {
    /* already gone */
  }
}

/** Wipe all staged ciphertext (logout). */
export async function stageWipeAll(): Promise<void> {
  if (!hasOPFS()) return
  try {
    const root = await navigator.storage.getDirectory()
    await root.removeEntry(STAGING_DIR, { recursive: true })
  } catch {
    /* already gone */
  }
}