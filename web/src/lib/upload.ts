/**
 * Upload pipeline: prepare -> encrypt -> reserve -> transfer -> complete.
 * States: Discovered -> Preparing -> Uploading -> Verifying -> Uploaded.
 *
 * Transfer strategy (per plan): small resources use a retryable single presigned
 * PUT; resources above MULTIPART_THRESHOLD use multipart with independent,
 * individually-retryable parts. Interrupted multipart uploads keep a durable
 * local record (IndexedDB) plus staged ciphertext (OPFS), so a later run can
 * resume with only the unfinished parts retransmitting. Without OPFS the upload
 * still works; cross-reload resume just isn't available (browser limitation,
 * disclosed in the UI).
 */
import { API, type ResourceVariant, type UploadItemRequest, type UploadItemPlan } from './api'
import {
  encryptPayload,
  encryptPayloadBytes,
  newAssetKey,
  randomKeyB64,
  sha256Hex,
  unwrapAssetKey,
  wrapAssetKey,
} from './crypto'
import { extractExif, generateDerivatives } from './images'
import {
  deleteTransfer,
  getTransfer,
  putAssets,
  putTransfer,
  type AssetCompanion,
  type AssetSource,
  type LocalAsset,
  type PendingTransfer,
} from './db'
import { stageDelete, stageGet, stagePut } from './opfs'

export interface AssetManifest {
  fileName: string
  mimeType: string
  capturedAt: string | null
  capturedTzOffsetSeconds: number | null
  latitude: number | null
  longitude: number | null
  width: number | null
  height: number | null
  previewAvailable: boolean
  quality: 'original'
  originalSha256: string
  originalSize: number
  clientAssetRef: string
  crypto: { payload: 'secretstream-xchacha20poly1305'; v: 1 }

  // ---- migration / provenance (encrypted; the server never sees these) ----
  /** where the asset came from; absent on legacy manifests → treat as 'upload' */
  source?: AssetSource
  /** import job this asset belongs to (for resume/bookkeeping) */
  importJobId?: string
  /** original filename recorded by the source (Takeout `title`) */
  originalFileName?: string
  caption?: string
  favorite?: boolean
  archived?: boolean
  albums?: string[]
  people?: string[]
  /** live/motion companion resource (e.g. the video half of a Live Photo) */
  companion?: AssetCompanion | null
}

export type UploadItemState =
  | 'preparing'
  | 'encrypting'
  | 'uploading'
  | 'verifying'
  | 'uploaded'
  | 'skipped'
  | 'error'

export interface UploadProgress {
  fileName: string
  state: UploadItemState
  error?: string
}

/**
 * Optional import hooks. When absent, uploadFiles behaves exactly as before (a plain
 * manual upload). Importers supply per-file manifest overlays (e.g. Google Photos
 * Takeout sidecar metadata) plus provenance for the whole call.
 */
export interface UploadOptions {
  /** per-file manifest overlay keyed by the File instance */
  overlays?: Map<File, Partial<AssetManifest>>
  /**
   * Live/Motion Photo companion (the video half) keyed by the still's File. When
   * present it is encrypted with the same asset key and uploaded as `original_motion`,
   * making the asset a compound one (the server refuses to complete "half a Live Photo").
   */
  companions?: Map<File, File>
  /** provenance recorded on every manifest in this call */
  source?: AssetSource
  /** import job these files belong to */
  jobId?: string
}

/** Resources above this size go multipart (plan: single-PUT small, multipart large). */
export const MULTIPART_THRESHOLD = 32 * 1024 * 1024
/** Target part size (plan: start with 8–16 MiB parts). */
export const MULTIPART_PART_SIZE = 8 * 1024 * 1024
const PART_RETRIES = 3

/** byte length of a raw ciphertext payload (what actually lands in S3) */
export function byteSize(p: Uint8Array): number {
  return p.length
}

/** byte length of a base64 blob */
export function b64Len(b64: string): number {
  const padding = b64.endsWith('==') ? 2 : b64.endsWith('=') ? 1 : 0
  return Math.floor((b64.length * 3) / 4) - padding
}

/** total ciphertext size of a manifest envelope (header + blob) */
export function manifestByteSize(p: { headerB64: string; blobB64: string }): number {
  return b64Len(p.headerB64) + b64Len(p.blobB64)
}

/** Mirror of the server's even slicing (ceil(size/n), remainder in the last part). */
export function computeSlices(size: number, partCount: number): Array<{ number: number; offset: number; length: number }> {
  const partLen = Math.ceil(size / partCount)
  const out: Array<{ number: number; offset: number; length: number }> = []
  let off = 0
  for (let n = 1; n <= partCount && off < size; n++) {
    const length = Math.min(partLen, size - off)
    out.push({ number: n, offset: off, length })
    off += length
  }
  return out
}

function transferKey(digest: string, variant: ResourceVariant): string {
  return `${digest}/${variant}`
}

/** Retry helper: exponential backoff with jitter (plan §reliability). */
async function withRetries<T>(fn: () => Promise<T>, what: string, attempts = PART_RETRIES): Promise<T> {
  let lastErr: unknown
  for (let i = 0; i < attempts; i++) {
    try {
      return await fn()
    } catch (e) {
      lastErr = e
      if (i < attempts - 1) {
        const backoff = 500 * 2 ** i + Math.random() * 250
        await new Promise((r) => setTimeout(r, backoff))
      }
    }
  }
  throw new Error(`${what} failed after ${attempts} attempts: ${lastErr instanceof Error ? lastErr.message : String(lastErr)}`)
}

interface PreparedItem {
  file: File
  item: UploadItemRequest
  digest: string
  keyEnvelope: string
  manifest: AssetManifest
  manifestEnc: { headerB64: string; blobB64: string }
  /** header-prefixed ciphertext, exactly what gets PUT to S3 */
  payloads: Partial<Record<ResourceVariant, Uint8Array>>
}

export async function uploadFiles(
  files: File[],
  vaultKey: Uint8Array,
  userId: string,
  onProgress: (p: UploadProgress) => void,
  options: UploadOptions = {},
): Promise<LocalAsset[]> {
  const prepared: PreparedItem[] = []
  const done: LocalAsset[] = []

  // Phase 1+2: prepare & encrypt locally (bounded staging — files are processed
  // sequentially so we never hold the whole camera roll in memory).
  for (const file of files) {
    try {
      onProgress({ fileName: file.name, state: 'preparing' })
      const bytes = new Uint8Array(await file.arrayBuffer())
      const digest = await sha256Hex(bytes)

      // Resume first: a previously interrupted multipart original for the SAME
      // file bytes (digest match) finishes from staged ciphertext — only missing
      // parts retransmit (plan: resumable transfer with bounded retransmission).
      const tr = await getTransfer(transferKey(digest, 'original'))
      if (tr) {
        try {
          const asset = await resumeAndCommit(tr, vaultKey, file, bytes, digest, onProgress, options)
          await putAssets([asset])
          done.push(asset)
          continue
        } catch (resumeErr) {
          // Unrecoverable resume (expired session, evicted staging, geometry
          // drift): clean up server-side, drop the record, fall through to fresh.
          try {
            await API.multipartAbort(tr.assetId, 'original')
          } catch { /* best effort */ }
          await deleteTransfer(tr.key)
          await stageDelete(tr.assetId, 'original')
          void resumeErr
        }
      }

      const [derivs, exif] = await Promise.all([generateDerivatives(file), extractExif(file)])

      onProgress({ fileName: file.name, state: 'encrypting' })
      const assetKey = await newAssetKey()
      const keyEnvelope = wrapAssetKey(assetKey, vaultKey)
      const clientAssetRef = await randomKeyB64(16)

      const payloads: Partial<Record<ResourceVariant, Uint8Array>> = {
        original: await encryptPayloadBytes(bytes, assetKey),
      }
      if (derivs.preview) {
        payloads.preview = await encryptPayloadBytes(new Uint8Array(await derivs.preview.arrayBuffer()), assetKey)
      }
      if (derivs.thumbnail) {
        payloads.thumbnail = await encryptPayloadBytes(new Uint8Array(await derivs.thumbnail.arrayBuffer()), assetKey)
      }
      // Live Photo companion: encrypted with the SAME asset key and uploaded as its own
      // resource, so the compound asset commits whole (never half a Live Photo).
      const companion = options.companions?.get(file)
      let companionMeta: AssetCompanion | null = null
      if (companion) {
        payloads.original_motion = await encryptPayloadBytes(
          new Uint8Array(await companion.arrayBuffer()),
          assetKey,
        )
        companionMeta = {
          motionMime: companion.type || 'video/quicktime',
          originalSize: companion.size,
        }
      }

      const manifest: AssetManifest = {
        fileName: file.name,
        mimeType: file.type || 'application/octet-stream',
        capturedAt: exif.capturedAt,
        capturedTzOffsetSeconds: exif.capturedTzOffsetSeconds,
        latitude: exif.latitude,
        longitude: exif.longitude,
        width: derivs.width,
        height: derivs.height,
        previewAvailable: derivs.previewAvailable,
        quality: 'original',
        originalSha256: digest,
        originalSize: bytes.length,
        clientAssetRef,
        crypto: { payload: 'secretstream-xchacha20poly1305', v: 1 },
        source: options.source ?? 'upload',
        importJobId: options.jobId,
      }
      // Import overlays (Takeout sidecars etc.) win over EXIF-derived values — the source
      // is authoritative for capture time/GPS and the overlay already carries the EXIF
      // fallback where the source had none.
      const overlay = options.overlays?.get(file)
      if (overlay) Object.assign(manifest, overlay)
      // A real companion resource wins over a source-declared hint (which lacks the size).
      manifest.companion = companionMeta ?? manifest.companion ?? null
      const manifestEnc = await encryptPayload(
        new TextEncoder().encode(JSON.stringify(manifest)),
        assetKey,
      )

      prepared.push({
        file,
        item: {
          originalSize: bytes.length,
          encryptedSizes: {
            original: byteSize(payloads.original!),
            preview: payloads.preview ? byteSize(payloads.preview) : 0,
            thumbnail: payloads.thumbnail ? byteSize(payloads.thumbnail) : 0,
            manifest: manifestByteSize(manifestEnc),
            original_motion: payloads.original_motion ? byteSize(payloads.original_motion) : 0,
          },
          assetIdClient: clientAssetRef,
        },
        digest,
        keyEnvelope,
        manifest,
        manifestEnc,
        payloads,
      })
    } catch (err) {
      onProgress({
        fileName: file.name,
        state: 'error',
        error: err instanceof Error ? err.message : String(err),
      })
    }
  }

  // Phase 3: reserve quota + get server-chosen keys and presigned PUT URLs.
  if (prepared.length) {
    const session = await API.createUploadSession(prepared.map((p) => p.item))
    const byRef = new Map(prepared.map((p) => [p.item.assetIdClient, p]))

    // Phase 4: transfer directly to S3 (ciphertext only — media never passes
    // through the API server, per plan). Large originals go multipart.
    for (const plan of session.items) {
      const p = byRef.get(plan.assetIdClient)
      if (!p) continue
      // Idempotent replay: this client ref already has a committed asset. Re-uploading
      // would re-encrypt under a new key and overwrite (corrupt) the stored object, so
      // skip it entirely — the catalog sync will surface it.
      if (plan.committed) {
        onProgress({ fileName: p.file.name, state: 'skipped' })
        continue
      }
      try {
        onProgress({ fileName: p.file.name, state: 'uploading' })
        for (const variant of ['original', 'preview', 'thumbnail', 'original_motion'] as ResourceVariant[]) {
          const payload = p.payloads[variant]
          if (!payload) continue
          if (byteSize(payload) > MULTIPART_THRESHOLD) {
            await uploadMultipart(plan, variant, payload, p.digest, p.keyEnvelope, onProgress)
          } else {
            await withRetries(async () => {
              const res = await fetch(plan.putUrls[variant], {
                method: 'PUT',
                // raw header-prefixed ciphertext bytes — exactly what S3 stores
                body: payload,
              })
              if (!res.ok) throw new Error(`HTTP ${res.status}`)
            }, `upload ${variant}`)
          }
        }

        onProgress({ fileName: p.file.name, state: 'verifying' })
        const complete = await API.completeUploadSession({
          items: [
            {
              assetId: plan.assetId,
              keyEnvelope: p.keyEnvelope,
              manifest: p.manifestEnc,
              resources: (Object.keys(p.payloads) as ResourceVariant[]).map((variant) => ({
                variant,
                objectKey: plan.keys[variant],
                ciphertextSize: byteSize(p.payloads[variant]!),
              })),
            },
          ],
        })

        onProgress({ fileName: p.file.name, state: 'uploaded' })
        const local = buildLocalAsset(plan.assetId, complete.assets[0].seq, userId, p.keyEnvelope, p.manifestEnc, p.manifest, p.payloads, plan.keys)
        await putAssets([local])
        done.push(local)
      } catch (err) {
        onProgress({
          fileName: p.file.name,
          state: 'error',
          error: err instanceof Error ? err.message : String(err),
        })
      }
    }
  }
  return done
}

function buildLocalAsset(
  assetId: string,
  seq: number,
  userId: string,
  keyEnvelope: string,
  manifestEnc: { headerB64: string; blobB64: string },
  manifest: AssetManifest,
  payloads: Partial<Record<ResourceVariant, Uint8Array>>,
  keys?: Record<ResourceVariant, string>,
): LocalAsset {
  // Object keys: echo the server-issued plan when available; on resume paths the
  // server hands keys back via /put-urls. The server validates every key anyway.
  const variants = Object.keys(payloads) as ResourceVariant[]
  return {
    assetId,
    seq,
    keyEnvelope,
    manifestEnc,
    resources: Object.fromEntries(
      variants.map((variant) => [
        variant,
        {
          objectKey: keys?.[variant] ?? `${userId}/${assetId}/${variant}`,
          ciphertextSize: byteSize(payloads[variant]!),
        },
      ]),
    ) as LocalAsset['resources'],
    deleted: false,
    deletedAt: null,
    fileName: manifest.fileName,
    capturedAt: manifest.capturedAt,
    mimeType: manifest.mimeType,
    originalSha256: manifest.originalSha256,
    downloaded: true,
    // carry migration/provenance so imported assets are organized locally immediately
    source: manifest.source,
    importJobId: manifest.importJobId,
    originalFileName: manifest.originalFileName,
    caption: manifest.caption,
    favorite: manifest.favorite,
    archived: manifest.archived,
    albums: manifest.albums,
    people: manifest.people,
    companion: manifest.companion ?? null,
  }
}

/** Multipart transfer for one large variant: init -> durable record + OPFS staging
 * -> per-part PUT (each individually retried) -> server-side complete. On failure
 * the record + staging are kept so a later run resumes with only missing parts. */
async function uploadMultipart(
  plan: UploadItemPlan,
  variant: ResourceVariant,
  payload: Uint8Array,
  digest: string,
  keyEnvelope: string,
  onProgress: (p: UploadProgress) => void,
) {
  const assetId = plan.assetId
  const size = byteSize(payload)
  const partCount = Math.max(1, Math.ceil(size / MULTIPART_PART_SIZE))
  const init = await API.multipartInit(assetId, variant, partCount)
  await putTransfer({
    key: transferKey(digest, variant),
    plaintextSha256: digest,
    assetId,
    variant,
    ciphertextSize: size,
    partCount,
    keyEnvelope,
    startedAt: Date.now(),
    updatedAt: Date.now(),
  })
  // Stage the exact ciphertext so a reload can resume without re-encryption
  // (re-encryption produces fresh nonces -> parts would not be byte-compatible).
  const staged = await stagePut(assetId, variant, payload)
  void staged // best-effort; absence only disables cross-reload resume
  onProgress({ fileName: `${variant} (${partCount} parts)`, state: 'uploading' })
  const etags = new Map<number, string>()
  for (const part of init.parts) {
    const slice = payload.subarray(part.offset, part.offset + part.length)
    await withRetries(async () => {
      const res = await fetch(part.url, { method: 'PUT', body: slice })
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      const etag = res.headers.get('etag')
      if (!etag) throw new Error('missing ETag response header')
      etags.set(part.number, etag)
    }, `part ${part.number}`)
  }
  await finishMultipart(assetId, variant, size, etags)
}

/** Complete a multipart upload with the collected etags and verify the size. */
async function finishMultipart(
  assetId: string,
  variant: ResourceVariant,
  expectedSize: number,
  etags: Map<number, string>,
) {
  const res = await API.multipartComplete(
    assetId,
    variant,
    [...etags].map(([number, etag]) => ({ number, etag })),
  )
  if (res.size !== expectedSize) throw new Error(`completed size ${res.size} != reserved ${expectedSize}`)
}
/** Resume an interrupted multipart original from OPFS-staged ciphertext: reconcile
 * with the server (ListParts is authoritative), retransmit only missing parts,
 * complete, then run the normal catalog commit with fresh derivatives under the
 * SAME asset key (the original parts were encrypted with it). */
async function resumeAndCommit(
  tr: PendingTransfer,
  vaultKey: Uint8Array,
  file: File,
  bytes: Uint8Array,
  digest: string,
  onProgress: (p: UploadProgress) => void,
  options: UploadOptions = {},
): Promise<LocalAsset> {
  if (digest !== tr.plaintextSha256) throw new Error('digest drift')
  onProgress({ fileName: file.name, state: 'uploading' })
  const status = await API.multipartStatus(tr.assetId, tr.variant)
  if (!status.started) throw new Error('multipart upload no longer exists')
  const slices = computeSlices(tr.ciphertextSize, tr.partCount)
  for (const sp of status.parts) {
    const s = slices.find((x) => x.number === sp.number)
    if (!s || s.length !== sp.size) throw new Error('part geometry drift')
  }
  const staged = await stageGet(tr.assetId, tr.variant)
  if (!staged || staged.length !== tr.ciphertextSize) throw new Error('staged ciphertext unavailable')

  const etags = new Map<number, string>(status.parts.map((p) => [p.number, p.etag]))
  const uploadedNumbers = new Set(status.parts.map((p) => p.number))
  const missing = slices.filter((s) => !uploadedNumbers.has(s.number))
  if (missing.length) {
    const plans = await API.multipartParts(tr.assetId, tr.variant, missing.map((m) => m.number))
    for (const mp of plans.parts) {
      const s = slices.find((x) => x.number === mp.number)!
      const slice = staged.subarray(s.offset, s.offset + s.length)
      await withRetries(async () => {
        const res = await fetch(mp.url, { method: 'PUT', body: slice })
        if (!res.ok) throw new Error(`HTTP ${res.status}`)
        const etag = res.headers.get('etag')
        if (!etag) throw new Error('missing ETag response header')
        etags.set(mp.number, etag)
      }, `part ${mp.number}`)
    }
  }
  await finishMultipart(tr.assetId, tr.variant, tr.ciphertextSize, etags)
  await deleteTransfer(tr.key)
  await stageDelete(tr.assetId, tr.variant)

  // Fresh derivatives + manifest under the SAME asset key (the original parts were
  // encrypted with it; the single keyEnvelope must cover every resource).
  const assetKey = unwrapAssetKey(tr.keyEnvelope, vaultKey)
  onProgress({ fileName: file.name, state: 'encrypting' })
  const [derivs, exif] = await Promise.all([generateDerivatives(file), extractExif(file)])
  const payloads: Partial<Record<ResourceVariant, Uint8Array>> = {}
  if (derivs.preview) {
    payloads.preview = await encryptPayloadBytes(new Uint8Array(await derivs.preview.arrayBuffer()), assetKey)
  }
  if (derivs.thumbnail) {
    payloads.thumbnail = await encryptPayloadBytes(new Uint8Array(await derivs.thumbnail.arrayBuffer()), assetKey)
  }
  // The companion must be re-encrypted and re-uploaded too: the reservation declared it,
  // so completing without it would be rejected (never half a Live Photo).
  const companion = options.companions?.get(file)
  let companionMeta: AssetCompanion | null = null
  if (companion) {
    payloads.original_motion = await encryptPayloadBytes(new Uint8Array(await companion.arrayBuffer()), assetKey)
    companionMeta = { motionMime: companion.type || 'video/quicktime', originalSize: companion.size }
  }

  const manifest: AssetManifest = {
    fileName: file.name,
    mimeType: file.type || 'application/octet-stream',
    capturedAt: exif.capturedAt,
    capturedTzOffsetSeconds: exif.capturedTzOffsetSeconds,
    latitude: exif.latitude,
    longitude: exif.longitude,
    width: derivs.width,
    height: derivs.height,
    previewAvailable: derivs.previewAvailable,
    quality: 'original',
    originalSha256: digest,
    originalSize: bytes.length,
    clientAssetRef: 'resumed',
    crypto: { payload: 'secretstream-xchacha20poly1305', v: 1 },
    source: options.source ?? 'upload',
    importJobId: options.jobId,
  }
  const resumeOverlay = options.overlays?.get(file)
  if (resumeOverlay) Object.assign(manifest, resumeOverlay)
  manifest.companion = companionMeta ?? manifest.companion ?? null
  const manifestEnc = await encryptPayload(new TextEncoder().encode(JSON.stringify(manifest)), assetKey)

  onProgress({ fileName: file.name, state: 'verifying' })
  // Recover server-chosen object keys + presigned PUTs for the derivatives (and the
  // original key, needed for the completion record even though its upload finished).
  const wants = (['original', 'preview', 'thumbnail', 'original_motion'] as ResourceVariant[]).filter(
    (v) => v === 'original' || payloads[v],
  )
  const urls = await API.putUrlsFor(tr.assetId, wants)
  const keyByVariant = Object.fromEntries(urls.items.map((i) => [i.variant, i.objectKey])) as Partial<Record<ResourceVariant, string>>
  const urlByVariant = Object.fromEntries(urls.items.map((i) => [i.variant, i.url])) as Partial<Record<ResourceVariant, string>>
  for (const variant of wants) {
    if (variant === 'original') continue // already completed via multipart
    await withRetries(async () => {
      const res = await fetch(urlByVariant[variant]!, { method: 'PUT', body: payloads[variant]! })
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
    }, `upload ${variant}`)
  }

  const complete = await API.completeUploadSession({
    items: [
      {
        assetId: tr.assetId,
        keyEnvelope: tr.keyEnvelope,
        manifest: manifestEnc,
        resources: wants.map((variant) => ({
          variant,
          objectKey: keyByVariant[variant]!,
          ciphertextSize: variant === 'original' ? tr.ciphertextSize : byteSize(payloads[variant]!),
        })),
      },
    ],
  })
  onProgress({ fileName: file.name, state: 'uploaded' })
  const local = buildLocalAsset(
    tr.assetId,
    complete.assets[0].seq,
    '', // keys come from the server response below
    tr.keyEnvelope,
    manifestEnc,
    manifest,
    payloads,
    keyByVariant as Record<ResourceVariant, string>,
  )
  local.resources.original = { objectKey: keyByVariant.original!, ciphertextSize: tr.ciphertextSize }
  return local
}
