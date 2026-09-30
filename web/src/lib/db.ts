/**
 * Local catalog (IndexedDB). The gallery ALWAYS renders from this store first — the
 * network sync is a background incremental cursor pull (per plan: never re-list S3 or
 * download the whole catalog on launch).
 */
import { openDB, type IDBPDatabase } from 'idb'
import type { ResourceVariant } from './api'

/** Where an asset came from — set for imported libraries, 'upload' for direct uploads. */
export type AssetSource = 'takeout' | 'apple' | 'folder' | 'device' | 'upload'

/**
 * A live/motion companion resource attached to a photo (e.g. the video half of an
 * iPhone Live Photo). Modelled on the asset so "half a Live Photo" can never be
 * marked complete.
 */
export interface AssetCompanion {
  motionMime: string
  originalSize: number
}

/** One item tracked within an import job. The digest is local-only and never uploaded. */
export interface ImportJobItem {
  /** plaintext SHA-256 — the local dedupe key */
  digest: string
  fileName: string
  /** server-issued asset id, once the item has been reserved */
  assetId?: string
  state: 'pending' | 'reserved' | 'transferred' | 'committed' | 'skipped' | 'error'
  error?: string
}

/**
 * Durable record of a (possibly multi-hour) import run so it can resume after a
 * reload/crash without re-uploading or double-counting already-committed items.
 */
export interface ImportJob {
  jobId: string
  source: AssetSource
  createdAt: number
  updatedAt: number
  items: ImportJobItem[]
  counts: { total: number; committed: number; skipped: number; error: number }
}

export interface LocalAsset {
  assetId: string
  seq: number
  keyEnvelope: string
  manifestEnc: { headerB64: string; blobB64: string }
  resources: Record<ResourceVariant, { objectKey: string; ciphertextSize: number }>
  deleted: boolean
  /** RFC3339 when the asset moved to cloud trash (purge countdown), if trashed */
  deletedAt: string | null
  /**
   * True only when the server verified every resource in the independent replica.
   * Optional so pre-existing local databases keep working (treated as not protected).
   */
  protected?: boolean
  /** decrypted-from-manifest convenience fields for gallery sort/filter (local only) */
  fileName: string
  capturedAt: string | null
  mimeType: string
  originalSha256: string
  downloaded: boolean

  // ---- migration / provenance (decrypted from the encrypted manifest; optional so
  //      legacy assets and pre-import databases keep working unchanged) ----
  source?: AssetSource
  importJobId?: string
  /** original filename recorded by the source (Takeout `title`); fileName may be sanitized */
  originalFileName?: string
  caption?: string
  favorite?: boolean
  archived?: boolean
  albums?: string[]
  people?: string[]
  companion?: AssetCompanion | null
}

/** One in-flight multipart transfer, kept so an interrupted upload can resume. */
export interface PendingTransfer {
  /** key: `${plaintextSha256}/${variant}` — resume on re-selecting the same file */
  key: string
  plaintextSha256: string
  assetId: string
  variant: ResourceVariant
  ciphertextSize: number
  partCount: number
  /** the pending asset's wrapped key — resume reuses the SAME asset key */
  keyEnvelope: string
  startedAt: number
  updatedAt: number
}

/** Durable per-file progress for one resumable export, so a reload continues instead. */
export interface ExportJobState {
  snapshotId: string
  completed: string[]
  failed: Record<string, string>
  bytesWritten: number
  updatedAt: number
}

let dbPromise: Promise<IDBPDatabase> | null = null

function getDb(): Promise<IDBPDatabase> {
  if (!dbPromise) {
    dbPromise = openDB('fusion-photos', 3, {
      upgrade(db, _oldVersion, _oldVn, _tx) {
        if (!db.objectStoreNames.contains('assets')) {
          const store = db.createObjectStore('assets', { keyPath: 'assetId' })
          store.createIndex('capturedAt', 'capturedAt')
        }
        if (!db.objectStoreNames.contains('meta')) {
          db.createObjectStore('meta', { keyPath: 'key' })
        }
        if (!db.objectStoreNames.contains('transfers')) {
          db.createObjectStore('transfers', { keyPath: 'key' })
        }
        // v3: durable import-job state (Takeout / Apple Photos / folder migration).
        if (!db.objectStoreNames.contains('importJobs')) {
          db.createObjectStore('importJobs', { keyPath: 'jobId' })
        }
      },
    })
  }
  return dbPromise
}

export async function putAssets(assets: LocalAsset[]) {
  const db = await getDb()
  const tx = db.transaction('assets', 'readwrite')
  for (const a of assets) tx.store.put(a)
  await tx.done
}

export async function getAllAssets(): Promise<LocalAsset[]> {
  const db = await getDb()
  return db.getAll('assets') as Promise<LocalAsset[]>
}

export async function getAsset(assetId: string): Promise<LocalAsset | undefined> {
  const db = await getDb()
  return db.get('assets', assetId) as Promise<LocalAsset | undefined>
}

export async function markDownloaded(assetId: string) {
  const db = await getDb()
  const a = await getAsset(assetId)
  if (a) {
    a.downloaded = true
    await db.put('assets', a)
  }
}

export async function putAsset(asset: LocalAsset) {
  const db = await getDb()
  await db.put('assets', asset)
}

/** Record a trash move locally so the gallery drops the tile before the next sync. */
export async function markTrashed(assetId: string, deletedAt: string): Promise<LocalAsset | undefined> {
  const asset = await getAsset(assetId)
  if (!asset) return undefined
  asset.deleted = true
  asset.deletedAt = deletedAt
  await putAsset(asset)
  return asset
}

/** Undo a trash move locally (mirrors the server restore). */
export async function markRestored(assetId: string): Promise<LocalAsset | undefined> {
  const asset = await getAsset(assetId)
  if (!asset) return undefined
  asset.deleted = false
  asset.deletedAt = null
  await putAsset(asset)
  return asset
}

export async function getCursor(): Promise<number> {
  const db = await getDb()
  const row = await db.get('meta', 'syncCursor')
  return (row?.value as number) ?? 0
}

export async function setCursor(cursor: number) {
  const db = await getDb()
  await db.put('meta', { key: 'syncCursor', value: cursor })
}

// ---- catalog ownership (account scoping) ----
//
// The local catalog is a cache, but it must never outlive the account it belongs to.
// The owner id is stored next to it so a different login can drop it immediately.

export async function getCatalogOwner(): Promise<string | null> {
  const owner = await getMeta<string>('catalogUserId')
  return owner ?? null
}

export async function setCatalogOwner(userId: string): Promise<void> {
  await setMeta('catalogUserId', userId)
}

/** Generic meta accessor — the `meta` store is a key/value table (export state, etc.). */
export async function getMeta<T>(key: string): Promise<T | undefined> {
  const db = await getDb()
  const row = await db.get('meta', key)
  return row?.value as T | undefined
}

export async function setMeta(key: string, value: unknown): Promise<void> {
  const db = await getDb()
  await db.put('meta', { key, value })
}

export async function deleteMeta(key: string): Promise<void> {
  const db = await getDb()
  await db.delete('meta', key)
}

/** Wipe all account-local data on logout (per plan: logout wipes caches and key access). */
export async function wipeLocalCatalog() {
  const db = await getDb()
  await db.clear('assets')
  await db.clear('meta')
  await db.clear('transfers')
  await db.clear('importJobs')
}

// ---- pending transfer records (multipart resume) ----

export async function getTransfer(key: string): Promise<PendingTransfer | undefined> {
  const db = await getDb()
  return db.get('transfers', key) as Promise<PendingTransfer | undefined>
}

export async function putTransfer(t: PendingTransfer) {
  const db = await getDb()
  await db.put('transfers', t)
}

export async function deleteTransfer(key: string) {
  const db = await getDb()
  await db.delete('transfers', key)
}

export async function listTransfers(): Promise<PendingTransfer[]> {
  const db = await getDb()
  return db.getAll('transfers') as Promise<PendingTransfer[]>
}

// ---- import-job records (Takeout / Apple Photos / folder migration) ----

export async function putImportJob(job: ImportJob) {
  const db = await getDb()
  await db.put('importJobs', job)
}

export async function getImportJob(jobId: string): Promise<ImportJob | undefined> {
  const db = await getDb()
  return db.get('importJobs', jobId) as Promise<ImportJob | undefined>
}

export async function listImportJobs(): Promise<ImportJob[]> {
  const db = await getDb()
  return db.getAll('importJobs') as Promise<ImportJob[]>
}

export async function deleteImportJob(jobId: string) {
  const db = await getDb()
  await db.delete('importJobs', jobId)
}
