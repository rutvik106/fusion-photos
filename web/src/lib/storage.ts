/**
 * Browser storage limits (the plan's "browser limits" gate).
 *
 * Safari and Chrome can evict site data under storage pressure or inactivity, so the app
 * must (a) ASK for persistent storage, (b) notice when a catalog was evicted and re-sync
 * rather than silently show an empty library, and (c) never promise permanent offline
 * storage. These capabilities live here so the promise stays consistent everywhere.
 */

/** Ask the browser to keep this origin's data (best effort; may prompt or be ignored). */
export async function persistStorage(): Promise<boolean> {
  try {
    if (!navigator.storage?.persist) return false
    if (await navigator.storage.persisted()) return true
    return await navigator.storage.persist()
  } catch {
    return false
  }
}

export async function isStoragePersisted(): Promise<boolean> {
  try {
    return Boolean(await navigator.storage?.persisted?.())
  } catch {
    return false
  }
}

export interface StorageEstimate {
  usage: number
  quota: number
}

export async function storageEstimate(): Promise<StorageEstimate | null> {
  try {
    const est = await navigator.storage?.estimate?.()
    if (!est || typeof est.usage !== 'number' || typeof est.quota !== 'number') return null
    return { usage: est.usage, quota: est.quota }
  } catch {
    return null
  }
}

/** Marker used to notice that IndexedDB was evicted while we believed we had a catalog. */
const HAD_CATALOG_KEY = 'fp.hadCatalog'

export function markCatalogPresent(): void {
  try {
    localStorage.setItem(HAD_CATALOG_KEY, '1')
  } catch {
    /* private mode / storage disabled — the marker is an optimization, not state */
  }
}

export function hadCatalog(): boolean {
  try {
    return localStorage.getItem(HAD_CATALOG_KEY) === '1'
  } catch {
    return false
  }
}

export function clearCatalogMarker(): void {
  try {
    localStorage.removeItem(HAD_CATALOG_KEY)
  } catch {
    /* ignore */
  }
}

/**
 * Whether the cached catalog must be dropped because it belongs to another account.
 *
 * Pure (no storage access) so it can be unit-tested. It is also true for a pre-existing
 * catalog with **no recorded owner** — that is how account scoping ships safely onto a
 * device that cached photos before ownership was recorded: the catalog is a cache, so
 * rebuilding it from the server is cheap, while showing another account's photos is not
 * acceptable.
 */
export function catalogBelongsToOtherAccount(
  currentUserId: string,
  owner: string | null,
  cachedCount: number,
): boolean {
  if (!currentUserId) return false // unknown account: never destroy a cache blindly
  if (owner === null) return cachedCount > 0
  return owner !== currentUserId
}
