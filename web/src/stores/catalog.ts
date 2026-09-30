/**
 * Catalog store: gallery state + background incremental sync.
 * Renders from IndexedDB first, then pulls server changes with the sync cursor.
 */
import { defineStore } from 'pinia'
import {
  getAllAssets,
  getCatalogOwner,
  getCursor,
  putAssets,
  setCatalogOwner,
  setCursor,
  wipeLocalCatalog,
  type LocalAsset,
} from '../lib/db'
import { API, ApiError, getUserId, setUserId, type BackupStatus, type SyncAsset } from '../lib/api'
import { catalogBelongsToOtherAccount, hadCatalog, markCatalogPresent } from '../lib/storage'
import { clearResourceCache, decryptManifest } from '../lib/resources'
import { stageWipeAll } from '../lib/opfs'
import type { AssetManifest } from '../lib/upload'

function toLocal(a: SyncAsset, m: AssetManifest): LocalAsset {
  return {
    assetId: a.assetId,
    seq: a.seq,
    keyEnvelope: a.keyEnvelope,
    manifestEnc: a.manifest,
    resources: a.resources,
    deleted: a.deleted,
    deletedAt: a.deletedAt,
    protected: a.protected,
    fileName: m.fileName,
    capturedAt: m.capturedAt,
    mimeType: m.mimeType,
    originalSha256: m.originalSha256,
    downloaded: false,
    // migration/provenance fields ride inside the encrypted manifest; surface them
    // locally so album/favorite/archived filters work without a re-import
    source: m.source ?? 'upload',
    importJobId: m.importJobId,
    originalFileName: m.originalFileName,
    caption: m.caption,
    favorite: m.favorite ?? false,
    archived: m.archived ?? false,
    albums: m.albums ?? [],
    people: m.people ?? [],
    companion: m.companion ?? null,
  }
}

export const useCatalogStore = defineStore('catalog', {
  state: () => ({
    assets: [] as LocalAsset[],
    syncing: false,
    lastSyncedAt: null as number | null,
    syncError: '' as string,
    loaded: false,
    /** Why the local catalog was rebuilt: browser eviction, or a different account. */
    cacheReset: null as 'evicted' | 'account' | null,
    /** True when an authenticated call returned 401 mid-session (expired/revoked). */
    sessionExpired: false,
    /** Authoritative server-side picture; null until first fetched. */
    backupStatus: null as BackupStatus | null,
    statusError: '' as string,
  }),
  getters: {
    /**
     * Visible (non-trashed) assets NOT yet verified in the replica. Computed locally so
     * the gallery can warn immediately, before/without the status endpoint. An asset
     * with no `protected` field (pre-existing local DB) counts as unprotected.
     */
    unprotectedCount(state): number {
      return state.assets.filter((a) => !a.deleted && a.protected !== true).length
    },
    visibleAssets(state): LocalAsset[] {
      return state.assets
        .filter((a) => !a.deleted)
        .sort((a, b) => {
          if (a.capturedAt && b.capturedAt) return b.capturedAt.localeCompare(a.capturedAt)
          if (a.capturedAt) return -1
          if (b.capturedAt) return 1
          return b.seq - a.seq
        })
    },
    deletedAssets(state): LocalAsset[] {
      return state.assets
        .filter((a) => a.deleted)
        .sort((a, b) => (b.deletedAt ?? '').localeCompare(a.deletedAt ?? ''))
    },
  },
  actions: {
    /**
     * Paint the gallery from local storage immediately (no network), scoped to the
     * signed-in account.
     *
     * The catalog is only a cache, so when it belongs to a different account — or
     * predates account scoping — it is dropped and rebuilt from the server. Reusing it
     * would display another account's photos (and every fetch would 401): the plan is
     * explicit that switching accounts must never expose another account's photos.
     */
    async loadLocal(userId?: string) {
      let uid = userId ?? getUserId() ?? ''
      // Sessions that predate the client-side marker (or a cleared localStorage) still
      // know who they are: ask the server, so scoping is authoritative rather than
      // dependent on local state.
      if (!uid) {
        try {
          uid = (await API.me()).userId
          setUserId(uid)
        } catch {
          /* offline or expired — skip the check rather than destroying a cache blindly */
        }
      }
      const existing = await getAllAssets()
      const owner = await getCatalogOwner()
      const foreign = catalogBelongsToOtherAccount(uid, owner, existing.length)

      if (foreign) {
        await wipeLocalCatalog()
        // Staged ciphertext for a partial upload also belongs to the previous account.
        await stageWipeAll()
        clearResourceCache()
        this.assets = []
        this.cacheReset = existing.length > 0 ? 'account' : null
        this.lastSyncedAt = null
        this.syncError = ''
        this.sessionExpired = false
      } else {
        this.assets = existing
        // The browser may have evicted IndexedDB since the last visit: say so rather
        // than pretending the library is empty — the sync re-downloads it.
        this.cacheReset = this.assets.length === 0 && hadCatalog() ? 'evicted' : null
        if (this.assets.length > 0) markCatalogPresent()
      }
      if (uid) await setCatalogOwner(uid)
      this.loaded = true
    },
    /** Background incremental pull from the server cursor. */
    async sync(vaultKey: Uint8Array) {
      if (this.syncing) return
      this.syncing = true
      this.syncError = ''
      try {
        let cursor = await getCursor()
        // loop until the server reports no more pages
        for (let i = 0; i < 100; i++) {
          const res = await API.sync(cursor)
          if (res.assets.length === 0) {
            await setCursor(res.cursor)
            cursor = res.cursor
            break
          }
          const locals: LocalAsset[] = []
          for (const a of res.assets) {
            // Even trashed assets keep their encrypted manifest, so the trash view
            // can show file names; decrypt first, then mark state.
            let m: AssetManifest
            try {
              m = await decryptManifest({ keyEnvelope: a.keyEnvelope, manifestEnc: a.manifest }, vaultKey)
            } catch {
              m = {
                fileName: '(encrypted)',
                mimeType: '',
                capturedAt: null,
                capturedTzOffsetSeconds: null,
                latitude: null,
                longitude: null,
                width: null,
                height: null,
                previewAvailable: false,
                quality: 'original',
                originalSha256: '',
                originalSize: 0,
                clientAssetRef: '',
                crypto: { payload: 'secretstream-xchacha20poly1305', v: 1 },
              }
            }
            locals.push(toLocal(a, m))
          }
          await putAssets(locals)
          cursor = res.cursor
          await setCursor(cursor)
        }
        this.assets = await getAllAssets()
        if (this.assets.length > 0) {
          markCatalogPresent()
          this.cacheReset = null
        }
        this.lastSyncedAt = Date.now()
      } catch (e) {
        if (e instanceof ApiError && e.status === 401) {
          this.sessionExpired = true
          this.syncError = 'Your session expired — sign in again to resume syncing.'
        } else {
          this.syncError = e instanceof Error ? e.message : String(e)
        }
      } finally {
        this.syncing = false
      }
    },
    /**
     * Fetch the server's authoritative backup/protection counts. Failure is
     * non-fatal: the panel falls back to the locally computed picture.
     */
    async refreshBackupStatus() {
      this.statusError = ''
      try {
        this.backupStatus = await API.backupStatus()
      } catch (e) {
        this.statusError = e instanceof Error ? e.message : String(e)
      }
    },
  },
})
