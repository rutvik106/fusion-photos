/**
 * Import engine (web path): dedupe → batch → reuse the upload pipeline → persist job.
 *
 * Reliability, in priority order:
 *  - local dedupe by plaintext SHA-256 against the existing catalog (the digest stays
 *    on-device; it is only ever stored inside the encrypted manifest)
 *  - server-sized batches (validateUploadItems accepts 1..50 items per session)
 *  - durable ImportJob state so a reload/crash can resume and report exactly what
 *    happened (the plan's "integrity report")
 *
 * This deliberately reuses uploadFiles() — no second, divergent upload path.
 */
import { API } from '../api'
import { sha256Hex } from '../crypto'
import { extractExif } from '../images'
import {
  getAllAssets,
  getImportJob,
  markTrashed,
  putImportJob,
  type ImportJob,
  type ImportJobItem,
} from '../db'
import { uploadFiles, type AssetManifest, type UploadItemState } from '../upload'
import { overlayFromTakeout } from './mapping'
import type { ImportItem, ImportRunResult, ManifestOverlay } from './types'

/** validateUploadItems on the server accepts 1..50 items per upload session. */
export const MAX_BATCH = 50

export type ImportItemState = UploadItemState | 'hashing' | 'skipped' | 'queued'

export interface ImportProgress {
  fileName: string
  state: ImportItemState
  error?: string
}

export interface RunImportOptions {
  jobId: string
  items: ImportItem[]
  vaultKey: Uint8Array
  userId: string
  onProgress: (p: ImportProgress) => void
}

/** Overlay → AssetManifest fields (drops `trashed`, which is server state, not manifest). */
function overlayToManifest(o: ManifestOverlay): Partial<AssetManifest> {
  const { trashed: _trashed, ...rest } = o
  void _trashed
  return rest
}

async function persistJob(
  jobId: string,
  items: ImportJobItem[],
  source: ImportJob['source'],
): Promise<void> {
  const now = Date.now()
  const existing = await getImportJob(jobId)
  const job: ImportJob = {
    jobId,
    source,
    createdAt: existing?.createdAt ?? now,
    updatedAt: now,
    items,
    counts: {
      total: items.length,
      committed: items.filter((i) => i.state === 'committed').length,
      skipped: items.filter((i) => i.state === 'skipped').length,
      error: items.filter((i) => i.state === 'error').length,
    },
  }
  await putImportJob(job)
}

export async function runImport(opts: RunImportOptions): Promise<ImportRunResult> {
  const { jobId, items, vaultKey, userId, onProgress } = opts

  const existing = await getAllAssets()
  const have = new Set(existing.map((a) => a.originalSha256).filter(Boolean))

  const failures: Array<{ fileName: string; error: string }> = []
  const jobItems: ImportJobItem[] = []
  const overlays = new Map<File, Partial<AssetManifest>>()
  const trashedDigests = new Set<string>()
  const pending: ImportItem[] = []
  // Live Photo companions (still File -> motion File) and a File->jobItem index used to
  // reconcile the server's idempotent "skipped" reports back into durable job state.
  const companions = new Map<File, File>()
  const jobItemByFile = new Map<File, ImportJobItem>()
  const skippedFiles = new Set<File>()
  for (const item of items) {
    if (item.companionFile) companions.set(item.file, item.companionFile)
  }

  // 1) Hash for local dedupe, extract EXIF, and build the Takeout overlay.
  for (const item of items) {
    onProgress({ fileName: item.file.name, state: 'hashing' })
    let digest = ''
    try {
      digest = await sha256Hex(new Uint8Array(await item.file.arrayBuffer()))
    } catch (e) {
      const error = e instanceof Error ? e.message : String(e)
      failures.push({ fileName: item.file.name, error })
      const ji: ImportJobItem = { digest: '', fileName: item.file.name, state: 'error', error }
      jobItems.push(ji)
      jobItemByFile.set(item.file, ji)
      onProgress({ fileName: item.file.name, state: 'error', error })
      continue
    }

    if (digest && have.has(digest)) {
      const ji: ImportJobItem = { digest, fileName: item.file.name, state: 'skipped' }
      jobItems.push(ji)
      jobItemByFile.set(item.file, ji)
      onProgress({ fileName: item.file.name, state: 'skipped' })
      continue
    }

    const exif = await extractExif(item.file)
    const overlay = overlayFromTakeout({
      sidecar: item.sidecar,
      exif,
      fileName: item.file.name,
      albumPath: item.albumPath,
      companionMime: item.companionMime ?? null,
    })
    if (overlay.trashed) trashedDigests.add(digest)
    overlays.set(item.file, overlayToManifest(overlay))
    pending.push(item)
    const ji: ImportJobItem = { digest, fileName: item.file.name, state: 'pending' }
    jobItems.push(ji)
    jobItemByFile.set(item.file, ji)
  }

  await persistJob(jobId, jobItems, 'takeout')

  // 2) Upload in server-sized batches. uploadFiles() re-derives derivatives and handles
  //    per-file retry/resume; it reports failures through onProgress.
  let committed = 0
  let bytes = 0
  const committedDigests = new Set<string>()
  const committedAssetIds = new Map<string, string>() // digest -> assetId

  for (let i = 0; i < pending.length; i += MAX_BATCH) {
    const batch = pending.slice(i, i + MAX_BATCH)
    const files = batch.map((b) => b.file)
    for (const f of files) onProgress({ fileName: f.name, state: 'queued' })

    // The server reports by file name; map back to the File within this batch.
    const byName = new Map(files.map((f) => [f.name, f]))

    const done = await uploadFiles(
      files,
      vaultKey,
      userId,
      (p) => {
        if (p.state === 'error') failures.push({ fileName: p.fileName, error: p.error ?? 'unknown' })
        // An idempotent replay: the server already has this client ref committed, so it
        // was not re-uploaded — count it as skipped, not failed.
        if (p.state === 'skipped') {
          const f = byName.get(p.fileName)
          if (f) skippedFiles.add(f)
        }
        onProgress(p)
      },
      { overlays, companions, source: 'takeout', jobId },
    )

    committed += done.length
    for (const a of done) {
      committedDigests.add(a.originalSha256)
      committedAssetIds.set(a.originalSha256, a.assetId)
      bytes += a.resources.original?.ciphertextSize ?? 0
    }
    for (const ji of jobItems) {
      if (ji.state === 'pending' && committedDigests.has(ji.digest)) {
        ji.state = 'committed'
        ji.assetId = committedAssetIds.get(ji.digest)
      }
    }
    for (const [file, ji] of jobItemByFile) {
      if (ji.state === 'pending' && skippedFiles.has(file)) ji.state = 'skipped'
    }
    await persistJob(jobId, jobItems, 'takeout')
  }

  // Any item still pending never made it through the pipeline -> mark it failed so the
  // report is complete (uploadFiles swallows per-file errors into onProgress).
  for (const ji of jobItems) {
    if (ji.state === 'pending') ji.state = 'error'
  }

  // 3) Honour the Takeout `trashed` flag: reserve+upload happened, now move to cloud trash.
  for (const digest of trashedDigests) {
    const assetId = committedAssetIds.get(digest)
    if (!assetId) continue
    try {
      await API.trashAsset(assetId)
      await markTrashed(assetId, new Date().toISOString())
    } catch (e) {
      failures.push({ fileName: '(trashed item)', error: e instanceof Error ? e.message : String(e) })
    }
  }

  await persistJob(jobId, jobItems, 'takeout')

  return {
    committed,
    skipped: jobItems.filter((i) => i.state === 'skipped').length,
    failed: failures.length,
    bytes,
    failures,
  }
}
