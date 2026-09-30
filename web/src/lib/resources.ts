/**
 * Resource fetch + local decryption.
 *
 * MVP fetches the full ciphertext with a presigned GET (MinIO/dev). The export phase
 * will switch to byte-range requests (verify HTTP 206 + Content-Range, persist ranges,
 * resume at verified secretstream frame boundaries) — the decrypt side already accepts
 * the {header, frames} format that resume will produce.
 *
 * Gallery display uses a small in-memory LRU of decrypted blobs, keyed by
 * accountId/assetId/variant (NEVER by signed URL — per plan, changing signatures would
 * bust the cache for no reason).
 */
import { API, fetchSigned, type ResourceVariant } from './api'
import { decryptPayload, decryptPayloadBytes, unwrapAssetKey } from './crypto'
import { getAsset, type LocalAsset } from './db'
import type { AssetManifest } from './upload'

export interface DecryptedAsset {
  asset: LocalAsset
  manifest: AssetManifest
  vaultKey: Uint8Array
}

export async function decryptManifest(
  asset: { keyEnvelope: string; manifestEnc: { headerB64: string; blobB64: string } },
  vaultKey: Uint8Array,
): Promise<AssetManifest> {
  const key = unwrapAssetKey(asset.keyEnvelope, vaultKey)
  const plain = await decryptPayload(asset.manifestEnc, key)
  return JSON.parse(new TextDecoder().decode(plain)) as AssetManifest
}

const blobCache = new Map<string, string>() // cacheKey -> object URL
const MAX_CACHE = 120
const lruOrder: string[] = []

function cachePut(key: string, blob: Blob): string {
  const url = URL.createObjectURL(blob)
  blobCache.set(key, url)
  lruOrder.push(key)
  while (lruOrder.length > MAX_CACHE) {
    const evict = lruOrder.shift()!
    const u = blobCache.get(evict)
    if (u) URL.revokeObjectURL(u)
    blobCache.delete(evict)
  }
  return url
}

export function cachedUrl(assetId: string, variant: ResourceVariant): string | undefined {
  const key = `${assetId}/${variant}`
  return blobCache.get(key)
}

/** Drop every decrypted blob URL held in memory (account switch / cache reset). */
export function clearResourceCache() {
  for (const url of blobCache.values()) URL.revokeObjectURL(url)
  blobCache.clear()
  lruOrder.length = 0
}

/** Mark an entry as most-recently-used (the tail of `lruOrder` is evicted last). */
function cacheTouch(key: string) {
  const idx = lruOrder.indexOf(key)
  if (idx >= 0) lruOrder.splice(idx, 1)
  lruOrder.push(key)
}

/** Fetch a resource's ciphertext through a freshly-signed URL, decrypt, return plaintext.
 * The stored object is header-prefixed ciphertext (raw bytes), matching the upload. */
export async function fetchAndDecrypt(
  assetId: string,
  variant: ResourceVariant,
  vaultKey: Uint8Array,
): Promise<Uint8Array> {
  const asset = await getAsset(assetId)
  if (!asset || asset.deleted) throw new Error('asset not found locally')
  const res = asset.resources[variant]
  if (!res) throw new Error(`no ${variant} resource for ${assetId}`)
  const http = await fetchSigned(() => API.resourceUrl(assetId, variant))
  if (!http.ok) throw new Error(`resource GET failed: HTTP ${http.status}`)
  const cipher = new Uint8Array(await http.arrayBuffer())
  const assetKey = unwrapAssetKey(asset.keyEnvelope, vaultKey)
  return decryptPayloadBytes(cipher, assetKey)
}

/**
 * Get a displayable blob URL for one resource variant, or null when the asset has no
 * such variant (the caller then shows the explicit "preview unavailable" state).
 * Used by grid tiles (thumbnail) and the full-screen viewer (preview).
 */
export async function getResourceUrl(
  asset: LocalAsset,
  variant: ResourceVariant,
  vaultKey: Uint8Array,
): Promise<string | null> {
  if (!asset.resources[variant]) return null
  const cacheKey = `${asset.assetId}/${variant}`
  const hit = blobCache.get(cacheKey)
  if (hit) {
    cacheTouch(cacheKey)
    return hit
  }
  const key = unwrapAssetKey(asset.keyEnvelope, vaultKey)
  const http = await fetchSigned(() => API.resourceUrl(asset.assetId, variant))
  if (!http.ok) throw new Error(`resource GET failed: HTTP ${http.status}`)
  const cipher = new Uint8Array(await http.arrayBuffer())
  const plain = await decryptPayloadBytes(cipher, key)
  const mime = variant === 'original' ? 'application/octet-stream' : 'image/jpeg'
  return cachePut(cacheKey, new Blob([plain], { type: mime }))
}

/** Tile-sized image: the thumbnail if the upload produced one, else the preview. */
export async function getDisplayUrl(
  asset: LocalAsset,
  vaultKey: Uint8Array,
): Promise<string | null> {
  const variant: ResourceVariant = asset.resources.thumbnail ? 'thumbnail' : 'preview'
  return getResourceUrl(asset, variant, vaultKey)
}

/** Download the original: decrypt and hand the browser a blob with the stored filename. */
export async function downloadOriginal(asset: LocalAsset, vaultKey: Uint8Array) {
  const bytes = await fetchAndDecrypt(asset.assetId, 'original', vaultKey)
  const manifest = await decryptManifest(asset, vaultKey)
  const blob = new Blob([bytes], { type: manifest.mimeType || 'application/octet-stream' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = manifest.fileName || `${asset.assetId}.bin`
  a.click()
  URL.revokeObjectURL(url)
}

/** Round-trip verification helper (acceptance gate: byte-identical originals). */
export async function verifyRoundTrip(asset: LocalAsset, vaultKey: Uint8Array): Promise<boolean> {
  const { sha256Hex } = await import('./crypto')
  const bytes = await fetchAndDecrypt(asset.assetId, 'original', vaultKey)
  const digest = await sha256Hex(bytes)
  return digest === asset.originalSha256
}

