<script setup lang="ts">
/**
 * One square grid tile. It fetches (and decrypts) its own thumbnail only once it scrolls
 * near the viewport: the local-first catalog can hold thousands of rows, so the grid must
 * never fire a presigned GET per row on mount.
 *
 * `mode` picks the affordance: 'select' (gallery hover checkbox), 'trash' (restore
 * button + purge caption). Trashed tiles stay non-interactive because the viewer's
 * download path rejects assets that are already in the trash.
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { getDisplayUrl } from '../lib/resources'
import type { LocalAsset } from '../lib/db'
import PhotoIcon from './PhotoIcon.vue'

const props = withDefaults(
  defineProps<{
    asset: LocalAsset
    vaultKey: Uint8Array
    selected?: boolean
    mode?: 'select' | 'trash'
    /** Optional caption (trash: purge date). */
    meta?: string
    /** Trash mode: the restore call for this row is in flight. */
    busy?: boolean
  }>(),
  { selected: false, mode: 'select', meta: '', busy: false },
)

const emit = defineEmits<{
  (e: 'open'): void
  (e: 'toggle-select'): void
  (e: 'restore'): void
}>()

const root = ref<HTMLElement | null>(null)
const url = ref<string | null>(null)
const state = ref<'idle' | 'loading' | 'ready' | 'empty' | 'error'>('idle')
/** Why this tile failed — surfaced as a tooltip so "Missing resource" is never opaque. */
const errorText = ref('')
let observer: IntersectionObserver | null = null

const interactive = computed(() => props.mode !== 'trash')
const encrypted = computed(() => props.asset.fileName === '(encrypted)')

async function load() {
  if (state.value !== 'idle') return
  state.value = 'loading'
  try {
    const displayUrl = await getDisplayUrl(props.asset, props.vaultKey)
    if (displayUrl) {
      url.value = displayUrl
      state.value = 'ready'
      return
    }
    // null = this asset genuinely has no thumbnail or preview resource: the plan's
    // explicit "preview unavailable" state (the original is still backed up).
    state.value = 'empty'
  } catch (e) {
    // fetch/decrypt failure (object missing from the backend, or a resource that is not
    // ours) — show the "missing resource" state instead of retry-looping, but keep the
    // reason on the element's tooltip so the failure is diagnosable.
    errorText.value = e instanceof Error ? e.message : String(e)
    state.value = 'error'
  }
}

onMounted(() => {
  if (typeof IntersectionObserver === 'undefined') {
    void load()
    return
  }
  observer = new IntersectionObserver(
    (entries) => {
      if (!entries.some((entry) => entry.isIntersecting)) return
      void load()
      observer?.disconnect()
      observer = null
    },
    { rootMargin: '600px' },
  )
  if (root.value) observer.observe(root.value)
})

onBeforeUnmount(() => {
  observer?.disconnect()
  observer = null
})
</script>

<template>
  <div
    ref="root"
    class="gp-tile"
    :class="{ selected, 'gp-tile-static': mode === 'trash' }"
    :role="interactive ? 'button' : undefined"
    :tabindex="interactive ? 0 : undefined"
    :aria-label="interactive ? asset.fileName : undefined"
    @click="interactive ? emit('open') : undefined"
    @keydown.enter="interactive && emit('open')"
    @keydown.space.prevent="interactive && emit('open')"
  >
    <img v-if="url" :src="url" :alt="asset.fileName" loading="lazy" decoding="async" />

    <div
      v-else
      class="gp-tile-ph"
      :class="{ loading: state === 'idle' || state === 'loading' }"
      :title="errorText || undefined"
    >
      <template v-if="state === 'error'">
        <PhotoIcon name="warning" :size="26" />
        <span>Missing resource</span>
      </template>
      <template v-else-if="state === 'empty'">
        <PhotoIcon :name="encrypted ? 'lock' : 'image_off'" :size="26" />
        <span>{{ encrypted ? 'Encrypted' : 'No preview' }}</span>
      </template>
      <template v-else>
        <PhotoIcon name="photos" :size="28" />
      </template>
    </div>

    <button
      v-if="mode === 'select'"
      class="gp-tile-select"
      type="button"
      :aria-label="selected ? `Deselect ${asset.fileName}` : `Select ${asset.fileName}`"
      :aria-pressed="selected"
      @click.stop="emit('toggle-select')"
    />

    <div v-if="mode === 'trash'" class="gp-tile-actions">
      <button type="button" :disabled="busy" @click.stop="emit('restore')">
        <PhotoIcon name="restore" :size="15" />
        {{ busy ? 'Restoring…' : 'Restore' }}
      </button>
    </div>

    <div v-if="mode === 'trash' && meta" class="gp-tile-caption">{{ meta }}</div>
  </div>
</template>
