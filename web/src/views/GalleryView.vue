<script setup lang="ts">
/**
 * Photos — the Google Photos main surface: sticky top bar with search, a navigation
 * drawer, and an edge-to-edge grid of square tiles grouped under sticky date headings.
 *
 * Local-first, per the plan: the grid paints from IndexedDB before any network call and
 * the sync is a background incremental cursor pull. Tiles fetch their own thumbnails
 * lazily, so a large library never fans out into thousands of presigned GETs on mount.
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { useCatalogStore } from '../stores/catalog'
import { uploadFiles, type UploadProgress } from '../lib/upload'
import { downloadOriginal } from '../lib/resources'
import { API } from '../lib/api'
import { listTransfers, markRestored, markTrashed, type LocalAsset } from '../lib/db'
import { dayKey, dayLabel } from '../lib/format'
import { persistStorage } from '../lib/storage'
import GpShell from '../components/GpShell.vue'
import PhotoTile from '../components/PhotoTile.vue'
import PhotoViewer from '../components/PhotoViewer.vue'
import SnackBar from '../components/SnackBar.vue'
import PhotoIcon from '../components/PhotoIcon.vue'

const auth = useAuthStore()
const catalog = useCatalogStore()
const router = useRouter()

const query = ref('')
const selected = ref(new Set<string>())
const viewerIndex = ref<number | null>(null)
const uploading = ref(false)
const showProgress = ref(false)
const progress = ref<UploadProgress[]>([])
const pendingTransfers = ref(0)
const fileInput = ref<HTMLInputElement | null>(null)

interface Snack {
  id: number
  message: string
  actionLabel: string
  action?: () => void
}
const snack = ref<Snack | null>(null)
let snackId = 0

/** The router guard guarantees an unlocked vault on this route. */
const vaultKey = computed(() => auth.vaultKey as Uint8Array)

// ---------------------------------------------------------------- catalog ----

const filtered = computed(() => {
  const needle = query.value.trim().toLowerCase()
  const all = catalog.visibleAssets
  if (!needle) return all
  return all.filter(
    (a) => a.fileName.toLowerCase().includes(needle) || (a.capturedAt ?? '').includes(needle),
  )
})

interface DayGroup {
  key: string
  label: string
  assets: LocalAsset[]
}

/** visibleAssets is already newest-first, so equal day keys are always adjacent. */
const groups = computed<DayGroup[]>(() => {
  const out: DayGroup[] = []
  for (const asset of filtered.value) {
    const key = dayKey(asset.capturedAt)
    const last = out[out.length - 1]
    if (last && last.key === key) {
      last.assets.push(asset)
      continue
    }
    out.push({ key, label: dayLabel(asset.capturedAt), assets: [asset] })
  }
  return out
})

onMounted(async () => {
  if (!auth.vaultKey) {
    router.push('/unlock')
    return
  }
  try {
    // Cheap authenticated round-trip: rejects a stale/revoked session before we paint.
    await API.getVault()
  } catch {
    await auth.logout()
    router.push('/login')
    return
  }
  await catalog.loadLocal()
  void refreshPendingTransfers()
  void catalog.sync(auth.vaultKey)
  void catalog.refreshBackupStatus()
  // Ask the browser to keep the catalog (best effort; Safari/Chrome may still evict).
  void persistStorage()
})

/** Session died mid-run: clear it and send the user back through login. Nothing is lost
 * — the catalog and any export progress are server-backed and resumable. */
async function reauth() {
  await auth.logout()
  router.push('/login')
}

// --------------------------------------------------------------- selection ---

const selecting = computed(() => selected.value.size > 0)
const allSelected = computed(
  () => filtered.value.length > 0 && selected.value.size === filtered.value.length,
)

function toggleSelect(assetId: string) {
  const next = new Set(selected.value)
  if (next.has(assetId)) next.delete(assetId)
  else next.add(assetId)
  selected.value = next
}

function clearSelection() {
  selected.value = new Set()
}

function selectAll() {
  selected.value = new Set(filtered.value.map((a) => a.assetId))
}

function selectedAssets(): LocalAsset[] {
  return filtered.value.filter((a) => selected.value.has(a.assetId))
}

// ------------------------------------------------------------------ viewer ---

const viewerAsset = computed(() =>
  viewerIndex.value === null ? null : (filtered.value[viewerIndex.value] ?? null),
)
const viewerIndexSafe = computed(() => viewerIndex.value ?? 0)

function openViewer(asset: LocalAsset) {
  const idx = filtered.value.findIndex((a) => a.assetId === asset.assetId)
  if (idx >= 0) viewerIndex.value = idx
}

function closeViewer() {
  viewerIndex.value = null
}

// ------------------------------------------------------------------ upload ---

const uploadedCount = computed(() => progress.value.filter((p) => p.state === 'uploaded').length)
const progressPercent = computed(() =>
  progress.value.length ? Math.round((uploadedCount.value / progress.value.length) * 100) : 0,
)

const stateLabel: Record<string, string> = {
  preparing: 'Preparing…',
  encrypting: 'Encrypting…',
  uploading: 'Uploading…',
  verifying: 'Verifying…',
  uploaded: 'Backed up',
  skipped: 'Already backed up',
  error: 'Failed',
}

function pickFiles() {
  fileInput.value?.click()
}

async function onPickFiles(e: Event) {
  const input = e.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''
  if (!files.length || !auth.vaultKey) return

  uploading.value = true
  showProgress.value = true
  progress.value = files.map((f) => ({ fileName: f.name, state: 'preparing' as const }))
  try {
    await uploadFiles(files, auth.vaultKey, auth.userId, (p) => {
      const idx = progress.value.findIndex((x) => x.fileName === p.fileName)
      if (idx >= 0) progress.value[idx] = p
    })
    await catalog.loadLocal()
    await catalog.sync(auth.vaultKey)
    await refreshPendingTransfers()
    const failed = progress.value.filter((p) => p.state === 'error').length
    if (failed) {
      const total = progress.value.length
      showSnack(`${failed} of ${total} upload${total > 1 ? 's' : ''} failed.`)
    } else {
      window.setTimeout(() => {
        if (!uploading.value) showProgress.value = false
      }, 4000)
    }
  } finally {
    uploading.value = false
  }
}

/** Durable transfer records: interrupted multipart uploads waiting for a resume. */
async function refreshPendingTransfers() {
  pendingTransfers.value = (await listTransfers()).length
}

// ------------------------------------------------------------------- trash ---

async function trashAssets(assets: LocalAsset[]) {
  if (!assets.length) return
  const moved: LocalAsset[] = []
  for (const asset of assets) {
    try {
      await API.trashAsset(asset.assetId)
      const updated = await markTrashed(asset.assetId, new Date().toISOString())
      if (updated) moved.push(updated)
    } catch (e) {
      showSnack(e instanceof Error ? e.message : String(e))
    }
  }
  if (!moved.length) return

  clearSelection()
  await catalog.loadLocal()
  syncViewerAfterRemoval(moved)
  showSnack(
    moved.length === 1
      ? `"${moved[0].fileName}" moved to trash.`
      : `${moved.length} items moved to trash.`,
    'Undo',
    () => void undoTrash(moved),
  )
}

async function undoTrash(assets: LocalAsset[]) {
  for (const asset of assets) {
    try {
      await API.restoreAsset(asset.assetId)
      await markRestored(asset.assetId)
    } catch (e) {
      showSnack(e instanceof Error ? e.message : String(e))
    }
  }
  await catalog.loadLocal()
  showSnack('Restored to your library.')
}

/** Keep the open viewer valid after rows disappear from the visible list. */
function syncViewerAfterRemoval(removed: LocalAsset[]) {
  if (viewerIndex.value === null) return
  const viewer = viewerAsset.value
  if (viewer && removed.some((a) => a.assetId === viewer.assetId)) {
    viewerIndex.value = null
    return
  }
  if (viewerIndex.value >= filtered.value.length) {
    viewerIndex.value = filtered.value.length ? filtered.value.length - 1 : null
  }
}

function trashSelected() {
  void trashAssets(selectedAssets())
}

// -------------------------------------------------------------- downloads ----

async function downloadSelected() {
  const items = selectedAssets()
  if (!items.length || !auth.vaultKey) return
  clearSelection()
  let failed = 0
  for (const asset of items) {
    try {
      await downloadOriginal(asset, auth.vaultKey)
    } catch {
      failed += 1
    }
  }
  const total = items.length
  showSnack(
    failed
      ? `${failed} of ${total} download${total > 1 ? 's' : ''} failed.`
      : `Downloaded ${total} original${total > 1 ? 's' : ''}.`,
  )
}

// ----------------------------------------------------------------- chrome ----

function showSnack(message: string, actionLabel = '', action?: () => void) {
  snackId += 1
  snack.value = { id: snackId, message, actionLabel, action }
}

function runSnackAction() {
  const action = snack.value?.action
  snack.value = null
  action?.()
}

/** Honest placeholder for Google Photos features that land in a later phase. */
function notImplemented(what: string) {
  showSnack(`${what} arrives in a later phase.`)
}
</script>

<template>
  <GpShell :query="query" @update:query="query = $event">
    <!-- Selection mode replaces the entire app bar, exactly like Google Photos. -->
    <template v-if="selecting" #header>
      <header class="gp-selection-bar">
        <button class="gp-icon-btn" type="button" aria-label="Exit selection" @click="clearSelection">
          <PhotoIcon name="close" />
        </button>
        <span class="gp-selection-count">{{ selected.size }} selected</span>
        <button
          class="gp-btn text"
          type="button"
          @click="allSelected ? clearSelection() : selectAll()"
        >
          {{ allSelected ? 'Deselect all' : 'Select all' }}
        </button>
        <button
          class="gp-icon-btn"
          type="button"
          aria-label="Share"
          @click="notImplemented('Sharing with people')"
        >
          <PhotoIcon name="share" />
        </button>
        <button
          class="gp-icon-btn"
          type="button"
          aria-label="Add to album"
          @click="notImplemented('Albums')"
        >
          <PhotoIcon name="add_box" />
        </button>
        <button
          class="gp-icon-btn"
          type="button"
          aria-label="Edit"
          @click="notImplemented('The photo editor')"
        >
          <PhotoIcon name="edit" />
        </button>
        <button
          class="gp-icon-btn"
          type="button"
          aria-label="Download selected originals"
          @click="downloadSelected"
        >
          <PhotoIcon name="download" />
        </button>
        <button
          class="gp-icon-btn"
          type="button"
          aria-label="Move selected to trash"
          @click="trashSelected"
        >
          <PhotoIcon name="delete" />
        </button>
      </header>
    </template>

    <template #actions>
      <span v-if="catalog.syncing" class="gp-spinner" title="Syncing…" aria-label="Syncing" />
      <button
        class="gp-icon-btn"
        type="button"
        aria-label="Upload photos"
        :disabled="uploading"
        @click="pickFiles"
      >
        <PhotoIcon name="upload" />
      </button>
    </template>

    <input
      ref="fileInput"
      type="file"
      multiple
      accept="image/*,.dng,.heic,.heif"
      hidden
      :disabled="uploading"
      @change="onPickFiles"
    />

    <section v-if="pendingTransfers > 0" class="gp-notice">
      <PhotoIcon name="warning" :size="18" />
      <span>
        {{ pendingTransfers }} upload{{ pendingTransfers > 1 ? 's were' : ' was' }} interrupted —
        re-select the same file{{ pendingTransfers > 1 ? 's' : '' }} to resume; only the
        unfinished parts retransmit.
      </span>
    </section>

    <section v-if="catalog.cacheReset" class="gp-notice">
      <PhotoIcon name="warning" :size="18" />
      <span v-if="catalog.cacheReset === 'account'">
        You're signed in as a different account, so this device's cached library was
        cleared — loading this account's photos now.
      </span>
      <span v-else>
        The browser cleared this device's local cache. Your photos are safe on the server —
        re-downloading the catalog now.
      </span>
    </section>

    <section v-if="catalog.sessionExpired" class="gp-notice error">
      <PhotoIcon name="warning" :size="18" />
      <span>
        Your session expired.
        <a href="#" @click.prevent="reauth">Sign in again</a> to resume syncing — nothing
        was lost.
      </span>
    </section>

    <section v-if="catalog.syncError" class="gp-notice error">
      <PhotoIcon name="warning" :size="18" />
      <span>Could not sync with the server: {{ catalog.syncError }}</span>
    </section>

    <section class="gp-grid-area">
      <template v-if="filtered.length">
        <section v-for="group in groups" :key="group.key">
          <h2 class="gp-day-header">
            {{ group.label }}
            <span class="gp-day-count">{{ group.assets.length }}</span>
          </h2>
          <div class="gp-grid">
            <PhotoTile
              v-for="asset in group.assets"
              :key="asset.assetId"
              :asset="asset"
              :vault-key="vaultKey"
              :selected="selected.has(asset.assetId)"
              mode="select"
              @open="openViewer(asset)"
              @toggle-select="toggleSelect(asset.assetId)"
            />
          </div>
        </section>
      </template>

      <div v-else-if="catalog.loaded" class="gp-empty">
        <PhotoIcon :name="query ? 'search' : 'photos'" :size="44" />
        <h2>{{ query ? `No photos match “${query}”` : 'No photos yet' }}</h2>
        <p v-if="query">Try a different file name or date.</p>
        <p v-else>
          Every photo is encrypted on this device before it leaves the browser. Use the upload
          button in the top bar to back up your first ones.
        </p>
      </div>
    </section>
    <section v-if="showProgress && progress.length" class="gp-upload-card" aria-live="polite">
      <div class="gp-upload-head">
        <span v-if="uploading" class="gp-spinner" />
        <PhotoIcon v-else name="check" :size="18" />
        <span>
          {{
            uploading
              ? `Backing up ${progress.length - uploadedCount} of ${progress.length}…`
              : `Backed up ${uploadedCount} of ${progress.length}`
          }}
        </span>
        <button
          class="gp-icon-btn"
          type="button"
          aria-label="Dismiss upload progress"
          @click="showProgress = false"
        >
          <PhotoIcon name="close" :size="18" />
        </button>
      </div>
      <div class="gp-upload-rows">
        <div
          v-for="(p, i) in progress"
          :key="`${p.fileName}-${i}`"
          class="gp-upload-row"
          :class="{ done: p.state === 'uploaded', error: p.state === 'error' }"
        >
          <span class="name" :title="p.fileName">{{ p.fileName }}</span>
          <span class="state">{{ stateLabel[p.state] ?? p.state }}</span>
        </div>
      </div>
      <div class="gp-upload-bar"><span :style="{ width: `${progressPercent}%` }" /></div>
    </section>

    <PhotoViewer
      v-if="viewerIndex !== null && viewerAsset"
      :assets="filtered"
      :index="viewerIndexSafe"
      :vault-key="vaultKey"
      @close="closeViewer"
      @navigate="viewerIndex = $event"
      @trash="trashAssets([$event])"
    />

    <SnackBar
      v-if="snack"
      :key="snack.id"
      :message="snack.message"
      :action-label="snack.actionLabel"
      @action="runSnackAction"
      @dismiss="snack = null"
    />
  </GpShell>
</template>
