/**
 * Resumable, client-side library export.
 *
 * The server only records an immutable snapshot and signs snapshot-scoped URLs; every
 * decryption, file naming and integrity check happens here, so the export is usable
 * without this app and the server never sees plaintext.
 *
 * Resume model: per-FILE progress is persisted in IndexedDB, so a reload, crash or
 * browser restart continues where it stopped instead of re-downloading. Byte-range
 * resume *inside* a file belongs to the native desktop downloader (see desktop/).
 */
import { API, fetchSigned, type ResourceVariant, type SyncAsset } from './api'
import { decryptManifest } from './resources'
import { deleteMeta, getMeta, setMeta, type ExportJobState } from './db'
import { decryptPayloadBytes, sha256Hex, unwrapAssetKey } from './crypto'
import type { AssetManifest } from './upload'

/** File System Access API entry point (a proposal, so not in lib.dom). */
declare global {
  interface Window {
    showDirectoryPicker?: (opts?: {
      mode?: 'read' | 'readwrite'
    }) => Promise<FileSystemDirectoryHandle>
  }
}

export function directoryPickerSupported(): boolean {
  return typeof window !== 'undefined' && typeof window.showDirectoryPicker === 'function'
}

/** A Live Photo's motion half gets a sibling name next to its still. */
export function companionName(photoName: string): string {
  const dot = photoName.lastIndexOf('.')
  return dot > 0 ? `${photoName.slice(0, dot)}.mov` : `${photoName}.mov`
}

export interface ExportEntry {
  assetId: string
  seq: number
  fileName: string
  mimeType: string
  capturedAt: string | null
  quality: string
  originalSha256: string
  originalSize: number
  keyEnvelope: string
  resources: Record<string, { objectKey: string; ciphertextSize: number }>
  hasCompanion: boolean
  /** Set when the encrypted manifest could not be decrypted — never a silent drop. */
  error?: string
}

async function decryptEntryManifest(a: SyncAsset, vaultKey: Uint8Array): Promise<AssetManifest> {
  return decryptManifest({ keyEnvelope: a.keyEnvelope, manifestEnc: a.manifest }, vaultKey)
}

/**
 * Page a snapshot and decrypt each manifest locally to build the export manifest. A
 * manifest that cannot be decrypted becomes an explicit error entry rather than a silent
 * omission (the plan forbids dropping a resource quietly).
 */
export async function collectEntries(
  snapshotId: string,
  vaultKey: Uint8Array,
  onProgress?: (fetched: number) => void,
): Promise<ExportEntry[]> {
  const out: ExportEntry[] = []
  let cursor = 0
  for (let page = 0; page < 10_000; page++) {
    const res = await API.exportAssets(snapshotId, cursor)
    if (res.assets.length === 0) break
    for (const a of res.assets) {
      const base = {
        assetId: a.assetId,
        seq: a.seq,
        keyEnvelope: a.keyEnvelope,
        resources: a.resources as ExportEntry['resources'],
      }
      try {
        const m = await decryptEntryManifest(a, vaultKey)
        out.push({
          ...base,
          fileName: m.fileName || `${a.assetId}.bin`,
          mimeType: m.mimeType,
          capturedAt: m.capturedAt,
          quality: m.quality,
          originalSha256: m.originalSha256,
          originalSize: m.originalSize,
          hasCompanion: Boolean(m.companion),
        })
      } catch (err) {
        out.push({
          ...base,
          fileName: `(undecryptable) ${a.assetId}`,
          mimeType: '',
          capturedAt: null,
          quality: 'unknown',
          originalSha256: '',
          originalSize: 0,
          hasCompanion: false,
          error: err instanceof Error ? err.message : String(err),
        })
      }
    }
    cursor = res.cursor
    onProgress?.(out.length)
  }
  return out
}

/** Filesystem-safe, deterministic, duplicate-free name (stable across resumes). */
function safeName(raw: string, seq: number, used: Set<string>): string {
  let base = (raw || `asset-${seq}`)
    .replace(/[\\/:*?"<>|\u0000-\u001f]/g, '_')
    .replace(/^\.+/, '')
    .trim()
  if (!base) base = `asset-${seq}`
  const dot = base.lastIndexOf('.')
  const stem = dot > 0 ? base.slice(0, dot) : base
  const ext = dot > 0 ? base.slice(dot) : ''
  let candidate = base
  for (let n = 1; used.has(candidate); n++) {
    candidate = `${stem} (${seq}${n > 1 ? `-${n}` : ''})${ext}`
  }
  used.add(candidate)
  used.add(companionName(candidate))
  return candidate
}

export interface ExportProgress {
  total: number
  done: number
  failed: number
  bytes: number
  current: string
}

export interface ExportFailure {
  assetId: string
  fileName: string
  error: string
}

export interface ExportReport {
  snapshotId: string
  finishedAt: string
  assetCount: number
  exported: number
  skipped: number
  failed: ExportFailure[]
  bytesWritten: number
  mode: 'directory' | 'downloads'
}

export interface ExportOptions {
  snapshotId: string
  vaultKey: Uint8Array
  /** Destination directory when the File System Access API is available. */
  dir?: FileSystemDirectoryHandle
  onProgress?: (p: ExportProgress) => void
  /** Set `cancelled = true` to stop after the current file (progress is kept). */
  control?: { cancelled: boolean }
}

async function fetchDecryptVariant(
  assetId: string,
  variant: ResourceVariant,
  snapshotId: string,
  assetKey: Uint8Array,
): Promise<Uint8Array> {
  const http = await fetchSigned(() => API.resourceUrl(assetId, variant, snapshotId))
  if (!http.ok) throw new Error(`GET ${variant} failed: HTTP ${http.status}`)
  const cipher = new Uint8Array(await http.arrayBuffer())
  return decryptPayloadBytes(cipher, assetKey)
}

async function writeBytes(
  dir: FileSystemDirectoryHandle | undefined,
  name: string,
  bytes: Uint8Array,
  mime: string,
): Promise<void> {
  if (dir) {
    const handle = await dir.getFileHandle(name, { create: true })
    const writable = await handle.createWritable()
    await writable.write(bytes as unknown as BufferSource)
    await writable.close()
    return
  }
  // No directory access (Safari/Firefox): hand each file to the browser's downloads.
  const blob = new Blob([bytes as unknown as BlobPart], {
    type: mime || 'application/octet-stream',
  })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = name
  a.click()
  URL.revokeObjectURL(url)
  await new Promise((r) => setTimeout(r, 150)) // let the browser queue the download
}

async function writeJson(dir: FileSystemDirectoryHandle | undefined, name: string, data: unknown) {
  await writeBytes(dir, name, new TextEncoder().encode(JSON.stringify(data, null, 2)), 'application/json')
}

async function exportOne(
  e: ExportEntry,
  name: string,
  snapshotId: string,
  vaultKey: Uint8Array,
  dir?: FileSystemDirectoryHandle,
): Promise<number> {
  const assetKey = unwrapAssetKey(e.keyEnvelope, vaultKey)
  const plain = await fetchDecryptVariant(e.assetId, 'original', snapshotId, assetKey)
  if (e.originalSha256) {
    const digest = await sha256Hex(plain)
    if (digest !== e.originalSha256) {
      throw new Error(
        `integrity check failed (expected ${e.originalSha256.slice(0, 12)}…, got ${digest.slice(0, 12)}…)`,
      )
    }
  }
  await writeBytes(dir, name, plain, e.mimeType)
  let written = plain.length
  // Never silently drop the motion half of a Live Photo.
  if (e.hasCompanion && e.resources.original_motion) {
    const motion = await fetchDecryptVariant(e.assetId, 'original_motion', snapshotId, assetKey)
    await writeBytes(dir, companionName(name), motion, 'video/quicktime')
    written += motion.length
  }
  return written
}

function manifestOf(snapshotId: string, entries: ExportEntry[], names: Map<string, string>) {
  return {
    format: 'fusion-photos-export',
    version: 1,
    snapshotId,
    createdAt: new Date().toISOString(),
    assetCount: entries.length,
    // Deliberately metadata only: no provider secrets or authentication credentials.
    assets: entries.map((e) => ({
      assetId: e.assetId,
      file: names.get(e.assetId),
      fileName: e.fileName,
      mimeType: e.mimeType,
      capturedAt: e.capturedAt,
      quality: e.quality,
      bytes: e.originalSize,
      sha256: e.originalSha256,
      hasMotionCompanion: e.hasCompanion,
    })),
  }
}

/**
 * Run (or resume) an export of one snapshot. Completed files are skipped, so calling
 * this again after an interruption continues rather than restarting. A versioned
 * manifest and an integrity report are always written next to the photos.
 */
export async function runExport(opts: ExportOptions): Promise<ExportReport> {
  const { snapshotId, vaultKey, dir, onProgress, control } = opts
  const stateKey = `exportState:${snapshotId}`
  const prior = await getMeta<ExportJobState>(stateKey)
  const completed = new Set(prior?.completed ?? [])
  const failedMap: Record<string, string> = { ...(prior?.failed ?? {}) }
  let bytesWritten = prior?.bytesWritten ?? 0

  const entries = await collectEntries(snapshotId, vaultKey)
  const used = new Set<string>()
  const names = new Map<string, string>()
  for (const e of entries) names.set(e.assetId, safeName(e.fileName, e.seq, used))

  let done = 0
  let skipped = 0
  const failures: ExportFailure[] = []
  for (const e of entries) {
    if (control?.cancelled) break
    const name = names.get(e.assetId) ?? `asset-${e.seq}`
    if (!e.error && completed.has(e.assetId)) {
      done++
      skipped++
      continue
    }
    try {
      if (e.error) throw new Error(e.error)
      bytesWritten += await exportOne(e, name, snapshotId, vaultKey, dir)
      completed.add(e.assetId)
      delete failedMap[e.assetId]
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err)
      failedMap[e.assetId] = msg
      failures.push({ assetId: e.assetId, fileName: name, error: msg })
    }
    done++
    await setMeta(stateKey, {
      snapshotId,
      completed: [...completed],
      failed: failedMap,
      bytesWritten,
      updatedAt: Date.now(),
    } satisfies ExportJobState)
    onProgress?.({
      total: entries.length,
      done,
      failed: Object.keys(failedMap).length,
      bytes: bytesWritten,
      current: name,
    })
  }

  const report: ExportReport = {
    snapshotId,
    finishedAt: new Date().toISOString(),
    assetCount: entries.length,
    exported: completed.size,
    skipped,
    failed: failures,
    bytesWritten,
    mode: dir ? 'directory' : 'downloads',
  }
  await writeJson(dir, 'fusion-photos-export-manifest.json', manifestOf(snapshotId, entries, names))
  await writeJson(dir, 'fusion-photos-export-report.json', report)
  if (Object.keys(failedMap).length === 0) await deleteMeta(stateKey)
  return report
}
