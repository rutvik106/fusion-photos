<script setup lang="ts">
/**
 * Full-screen photo viewer, in the Google Photos layout: back arrow + date in the app
 * bar, action cluster on the right, the image centred with side chevrons, and a details
 * panel that slides in from the right.
 *
 * The gallery passes the *visible* (filtered, date-ordered) list and the current index,
 * so keyboard navigation and the chevrons walk exactly what the user sees.
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { decryptManifest, downloadOriginal, getResourceUrl, verifyRoundTrip } from '../lib/resources'
import type { LocalAsset } from '../lib/db'
import type { AssetManifest } from '../lib/upload'
import { dayLabel, formatBytes, fullDateLabel, timeLabel } from '../lib/format'
import PhotoIcon from './PhotoIcon.vue'

const props = defineProps<{
  assets: LocalAsset[]
  index: number
  vaultKey: Uint8Array
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'navigate', index: number): void
  (e: 'trash', asset: LocalAsset): void
}>()

const current = computed<LocalAsset | null>(() => props.assets[props.index] ?? null)
const hasPrev = computed(() => props.index > 0)
const hasNext = computed(() => props.index < props.assets.length - 1)

const url = ref<string | null>(null)
const state = ref<'loading' | 'ready' | 'empty' | 'error'>('loading')
const manifest = ref<AssetManifest | null>(null)
const zoom = ref(1)
const showInfo = ref(false)
const busy = ref(false)
const verdict = ref<'idle' | 'ok' | 'fail'>('idle')

let previousOverflow = ''

const subtitle = computed(() => {
  const asset = current.value
  if (!asset) return ''
  const time = timeLabel(asset.capturedAt)
  return time ? `${time} · ${asset.fileName}` : asset.fileName
})

const capturedLabel = computed(() => fullDateLabel(current.value?.capturedAt ?? null))
const dimensions = computed(() => {
  const m = manifest.value
  return m?.width && m?.height ? `${m.width} × ${m.height}` : '—'
})

/** Warm the LRU for the neighbouring photos so arrow-key browsing feels instant. */
function prefetch(offset: number) {
  const asset = props.assets[props.index + offset]
  if (!asset) return
  const variant = asset.resources.preview ? 'preview' : 'thumbnail'
  void getResourceUrl(asset, variant, props.vaultKey).catch(() => undefined)
}

async function load() {
  const asset = current.value
  url.value = null
  manifest.value = null
  verdict.value = 'idle'
  zoom.value = 1
  if (!asset) return

  state.value = 'loading'
  try {
    // The viewer prefers the larger preview derivative; thumbnail-only uploads still open.
    const variant = asset.resources.preview ? 'preview' : 'thumbnail'
    const displayUrl = await getResourceUrl(asset, variant, props.vaultKey)
    if (displayUrl) {
      url.value = displayUrl
      state.value = 'ready'
      prefetch(-1)
      prefetch(1)
    } else {
      state.value = 'empty'
    }
  } catch {
    state.value = 'error'
  }

  try {
    manifest.value = await decryptManifest(asset, props.vaultKey)
  } catch {
    manifest.value = null
  }
}

function go(delta: number) {
  const next = props.index + delta
  if (next < 0 || next >= props.assets.length) return
  emit('navigate', next)
}

function zoomBy(delta: number) {
  zoom.value = Math.min(3, Math.max(1, zoom.value + delta))
}

async function onDownload() {
  const asset = current.value
  if (!asset) return
  busy.value = true
  try {
    await downloadOriginal(asset, props.vaultKey)
  } finally {
    busy.value = false
  }
}

async function onVerify() {
  const asset = current.value
  if (!asset) return
  busy.value = true
  verdict.value = 'idle'
  try {
    verdict.value = (await verifyRoundTrip(asset, props.vaultKey)) ? 'ok' : 'fail'
  } catch {
    verdict.value = 'fail'
  } finally {
    busy.value = false
  }
}

function onTrash() {
  if (current.value) emit('trash', current.value)
}

function onKey(e: KeyboardEvent) {
  if (e.metaKey || e.ctrlKey || e.altKey) return
  if (e.key === 'Escape') emit('close')
  else if (e.key === 'ArrowLeft') go(-1)
  else if (e.key === 'ArrowRight') go(1)
  else if (e.key === 'i' || e.key === 'I') showInfo.value = !showInfo.value
}

onMounted(() => {
  previousOverflow = document.body.style.overflow
  document.body.style.overflow = 'hidden'
  window.addEventListener('keydown', onKey)
  void load()
})

onBeforeUnmount(() => {
  document.body.style.overflow = previousOverflow
  window.removeEventListener('keydown', onKey)
})

watch(
  () => props.index,
  () => void load(),
)
</script>

<template>
  <div
    class="gp-viewer"
    role="dialog"
    aria-modal="true"
    :aria-label="current ? current.fileName : 'Photo viewer'"
  >
    <div class="gp-viewer-bar">
      <button class="gp-icon-btn" type="button" aria-label="Back to photos" @click="emit('close')">
        <PhotoIcon name="arrow_back" />
      </button>

      <div class="gp-viewer-title">
        <span class="t1">{{ dayLabel(current?.capturedAt ?? null) }}</span>
        <span class="t2">{{ subtitle }}</span>
      </div>

      <div class="gp-topbar-actions">
        <button
          class="gp-icon-btn"
          :class="{ active: showInfo }"
          type="button"
          aria-label="Details"
          :aria-pressed="showInfo"
          @click="showInfo = !showInfo"
        >
          <PhotoIcon name="info" />
        </button>
        <button
          class="gp-icon-btn"
          type="button"
          aria-label="Zoom out"
          :disabled="zoom === 1"
          @click="zoomBy(-1)"
        >
          <PhotoIcon name="zoom_out" />
        </button>
        <button
          class="gp-icon-btn"
          type="button"
          aria-label="Zoom in"
          :disabled="zoom === 3"
          @click="zoomBy(1)"
        >
          <PhotoIcon name="zoom_in" />
        </button>
        <button
          class="gp-icon-btn"
          type="button"
          aria-label="Download original"
          :disabled="busy || !current"
          @click="onDownload"
        >
          <PhotoIcon name="download" />
        </button>
        <button
          class="gp-icon-btn danger"
          type="button"
          aria-label="Move to trash"
          :disabled="!current"
          @click="onTrash"
        >
          <PhotoIcon name="delete" />
        </button>
      </div>
    </div>

    <div class="gp-viewer-stage" @click.self="emit('close')">
      <img
        v-if="url"
        class="gp-viewer-img"
        :src="url"
        :alt="current?.fileName ?? ''"
        :style="{ transform: `scale(${zoom})` }"
      />

      <div v-else-if="state === 'loading'" class="gp-viewer-state">
        <span class="gp-spinner" />
        <p>Decrypting…</p>
      </div>

      <div v-else-if="state === 'empty'" class="gp-viewer-state">
        <PhotoIcon name="image_off" :size="40" />
        <p>
          No preview derivative exists for this file. The encrypted original is still backed
          up — use Download to retrieve it.
        </p>
      </div>

      <div v-else class="gp-viewer-state">
        <PhotoIcon name="warning" :size="40" />
        <p>This photo could not be loaded from storage.</p>
      </div>

      <div class="gp-viewer-nav prev">
        <button type="button" aria-label="Previous photo" :disabled="!hasPrev" @click="go(-1)">
          <PhotoIcon name="chevron_left" :size="26" />
        </button>
      </div>
      <div class="gp-viewer-nav next">
        <button type="button" aria-label="Next photo" :disabled="!hasNext" @click="go(1)">
          <PhotoIcon name="chevron_right" :size="26" />
        </button>
      </div>
    </div>

    <div class="gp-viewer-foot">
      <PhotoIcon name="lock" :size="14" />
      <span>Decrypted on this device · stored as ciphertext</span>
    </div>

    <aside v-if="showInfo" class="gp-viewer-panel" aria-label="Photo details">
      <div class="gp-viewer-panel-head">
        <span>Details</span>
        <button class="gp-icon-btn" type="button" aria-label="Close details" @click="showInfo = false">
          <PhotoIcon name="close" />
        </button>
      </div>

      <div class="gp-meta-row">
        <span class="k">Name</span>
        <span class="v">{{ current?.fileName ?? '—' }}</span>
      </div>
      <div class="gp-meta-row">
        <span class="k">Taken</span>
        <span class="v">{{ capturedLabel }}</span>
      </div>
      <div class="gp-meta-row">
        <span class="k">Dimensions</span>
        <span class="v">{{ dimensions }}</span>
      </div>
      <div class="gp-meta-row">
        <span class="k">Size</span>
        <span class="v">{{ formatBytes(manifest?.originalSize ?? 0) }}</span>
      </div>
      <div class="gp-meta-row">
        <span class="k">Type</span>
        <span class="v">{{ current?.mimeType || 'unknown' }}</span>
      </div>
      <div class="gp-meta-row">
        <span class="k">SHA-256</span>
        <span class="v">{{ current?.originalSha256 || '—' }}</span>
      </div>
      <div class="gp-meta-row">
        <span class="k">Protection</span>
        <span class="v">
          {{ current?.protected
            ? 'Verified in a second region'
            : 'Uploaded — not yet copied to a second region' }}
        </span>
      </div>

      <div class="gp-verified">
        <PhotoIcon :name="verdict === 'fail' ? 'warning' : 'shield'" :size="18" />
        <span v-if="verdict === 'ok'">
          Verified — the decrypted original hashes identically to the source copy.
        </span>
        <span v-else-if="verdict === 'fail'">
          Verification failed — the downloaded bytes did not match the recorded digest.
        </span>
        <span v-else>
          Stored as ciphertext the server cannot read. Verify to re-download the original
          and compare its SHA-256 digest.
        </span>
      </div>

      <div class="gp-viewer-panel-head">
        <button class="gp-btn text" type="button" :disabled="busy" @click="onVerify">
          {{ busy ? 'Working…' : 'Verify original' }}
        </button>
      </div>
    </aside>
  </div>
</template>
