<script setup lang="ts">
/**
 * Import — migrate an existing library. First source: Google Photos Takeout.
 *
 * The user points us at their extracted Takeout folder(s); everything is parsed and
 * encrypted in the browser (the server never sees sidecars, album names or filenames).
 * The scan summary is shown before anything uploads, and the run ends with an integrity
 * report so nothing is dropped silently.
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { useCatalogStore } from '../stores/catalog'
import GpShell from '../components/GpShell.vue'
import PhotoIcon from '../components/PhotoIcon.vue'
import { discoverTakeout, type TakeoutScan } from '../lib/import/takeout'
import { runImport, type ImportProgress } from '../lib/import/engine'
import type { ImportRunResult } from '../lib/import/types'
import { formatBytes } from '../lib/format'

const auth = useAuthStore()
const catalog = useCatalogStore()
const router = useRouter()

const dirInput = ref<HTMLInputElement | null>(null)
const scanning = ref(false)
const scan = ref<TakeoutScan | null>(null)
const running = ref(false)
const progress = ref<ImportProgress[]>([])
const result = ref<ImportRunResult | null>(null)
const showProgress = ref(false)
const error = ref('')
/** The Takeout how-to starts open (it's the first thing a new user needs) and
 * auto-collapses once a scan succeeds, so it never crowds the results. */
const guideOpen = ref(true)

const guideSteps: Array<{ title: string; body: string }> = [
  {
    title: 'Request your export',
    body: 'Open Takeout and choose “Deselect all”, then tick only Google Photos.',
  },
  {
    title: 'Include every album',
    body: 'Under Google Photos, open “All photo albums included” and keep every album ticked — albums arrive as folders.',
  },
  {
    title: 'Choose .zip with 2 GB parts',
    body: 'Smaller parts are far easier to download and unzip in stages.',
  },
  {
    title: 'Download every part',
    body: 'A big library arrives as takeout-…-001.zip, -002.zip, … — you need all of them, and Takeout can take hours or days to prepare.',
  },
  {
    title: 'Unzip them into ONE folder',
    body: 'Extract every part to the same place so they merge into a single “Google Photos” tree, then pick that folder here.',
  },
  {
    title: 'Keep the .json sidecars',
    body: 'Every photo has a matching .json file holding its album, favorite, caption and capture date. Deleting them loses that metadata.',
  },
  {
    title: 'Start with one part, then scale',
    body: 'Import part 001 and verify it before doing the rest. Re-running is always safe: anything already backed up is skipped.',
  },
]

const guideTips: string[] = [
  'Takeout gives you the quality Google Photos already stores. If you used “Storage saver” when uploading there, the export is already compressed and the original cannot be recovered from it.',
  'Keep both the Takeout archives and your Google Photos library until you have verified the import (open a photo → Details → Verify original).',
  'Importing runs in this browser and encrypts before upload — keep this tab open and the machine awake until it finishes.',
]

const uploadedCount = computed(() => progress.value.filter((p) => p.state === 'uploaded').length)
const progressPercent = computed(() =>
  progress.value.length ? Math.round((uploadedCount.value / progress.value.length) * 100) : 0,
)
const failurePreview = computed(() => result.value?.failures.slice(0, 20) ?? [])

const stateLabel: Record<string, string> = {
  hashing: 'Reading…',
  skipped: 'Already backed up',
  queued: 'Queued',
  preparing: 'Preparing…',
  encrypting: 'Encrypting…',
  uploading: 'Uploading…',
  verifying: 'Verifying…',
  uploaded: 'Backed up',
  error: 'Failed',
}

onMounted(() => {
  if (!auth.vaultKey) router.push('/unlock')
})

function pickFolder() {
  dirInput.value?.click()
}

async function onPickFolder(e: Event) {
  const input = e.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''
  if (!files.length) return
  error.value = ''
  result.value = null
  scan.value = null
  scanning.value = true
  try {
    scan.value = await discoverTakeout(files)
    guideOpen.value = false
  } catch (err) {
    error.value = err instanceof Error ? err.message : String(err)
  } finally {
    scanning.value = false
  }
}

async function start() {
  if (!scan.value || !auth.vaultKey) return
  error.value = ''
  result.value = null
  running.value = true
  showProgress.value = true
  progress.value = scan.value.items.map((i) => ({ fileName: i.file.name, state: 'hashing' }))
  try {
    result.value = await runImport({
      jobId: crypto.randomUUID(),
      items: scan.value.items,
      vaultKey: auth.vaultKey,
      userId: auth.userId,
      onProgress: (p) => {
        const idx = progress.value.findIndex((x) => x.fileName === p.fileName)
        if (idx >= 0) progress.value[idx] = p
      },
    })
    await catalog.loadLocal()
    await catalog.sync(auth.vaultKey)
  } catch (err) {
    error.value = err instanceof Error ? err.message : String(err)
  } finally {
    running.value = false
  }
}

function reset() {
  scan.value = null
  result.value = null
  progress.value = []
  showProgress.value = false
  error.value = ''
}
</script>

<template>
  <GpShell :searchable="false">
    <template #actions>
      <button
        class="gp-icon-btn"
        type="button"
        aria-label="Choose a Takeout folder"
        :disabled="running || scanning"
        @click="pickFolder"
      >
        <PhotoIcon name="upload" />
      </button>
    </template>

    <input
      ref="dirInput"
      type="file"
      webkitdirectory
      multiple
      hidden
      :disabled="running || scanning"
      @change="onPickFolder"
    />

    <section class="gp-import">
      <div class="gp-import-intro">
        <PhotoIcon name="shield" :size="40" />
        <h1>Import from Google Photos</h1>
        <p>
          Bring your whole library across from a Google Takeout archive. Everything is read and
          encrypted on this device — the server never sees your filenames, albums or locations.
        </p>
      </div>

      <section class="gp-import-guide">
        <button
          class="gp-import-guide-head"
          type="button"
          :aria-expanded="guideOpen"
          aria-controls="takeout-guide"
          @click="guideOpen = !guideOpen"
        >
          <PhotoIcon name="info" :size="18" />
          <span>How to get your archive</span>
          <PhotoIcon class="gp-import-chev" :class="{ open: guideOpen }" name="chevron_right" :size="18" />
        </button>

        <div v-if="guideOpen" id="takeout-guide" class="gp-import-guide-body">
          <ol>
            <li v-for="(step, i) in guideSteps" :key="i">
              <strong>{{ step.title }}</strong>
              <span>{{ step.body }}</span>
            </li>
          </ol>

          <ul class="gp-import-guide-tips">
            <li v-for="(tip, i) in guideTips" :key="i">
              <PhotoIcon name="warning" :size="16" />
              <span>{{ tip }}</span>
            </li>
          </ul>

          <a
            class="gp-import-guide-link"
            href="https://takeout.google.com"
            target="_blank"
            rel="noopener noreferrer"
          >
            <PhotoIcon name="download" :size="16" />
            Open takeout.google.com
          </a>
        </div>
      </section>

      <div class="gp-import-actions">
        <button class="gp-btn" type="button" :disabled="running || scanning" @click="pickFolder">
          {{ scanning ? 'Scanning…' : 'Choose Takeout folder' }}
        </button>
        <button v-if="scan" class="gp-btn text" type="button" :disabled="running" @click="reset">
          Choose a different folder
        </button>
      </div>

      <p v-if="error" class="gp-error">{{ error }}</p>

      <section v-if="scan" class="gp-import-summary">
        <h2>Found in this folder</h2>
        <ul>
          <li><strong>{{ scan.report.mediaCount }}</strong> photos &amp; videos</li>
          <li><strong>{{ scan.report.albumCount }}</strong> albums</li>
          <li v-if="scan.report.livePhotoPairs">
            <strong>{{ scan.report.livePhotoPairs }}</strong> Live Photos (motion kept as a paired
            companion resource)
          </li>
          <li v-if="scan.report.mediaWithoutSidecar">
            <strong>{{ scan.report.mediaWithoutSidecar }}</strong> without a metadata sidecar (EXIF
            used instead)
          </li>
          <li v-if="scan.report.unmatchedSidecars.length" class="warn">
            <strong>{{ scan.report.unmatchedSidecars.length }}</strong> sidecar files could not be
            paired
          </li>
          <li v-if="scan.report.unsupported.length" class="warn">
            <strong>{{ scan.report.unsupported.length }}</strong> unsupported files will be skipped
          </li>
        </ul>
        <button
          class="gp-btn"
          type="button"
          :disabled="running || scan.items.length === 0"
          @click="start"
        >
          {{ running ? 'Importing…' : `Back up ${scan.items.length} items` }}
        </button>
      </section>

      <section v-if="result" class="gp-import-report" aria-live="polite">
        <h2>Import report</h2>
        <ul>
          <li><strong>{{ result.committed }}</strong> backed up ({{ formatBytes(result.bytes) }})</li>
          <li><strong>{{ result.skipped }}</strong> already in your library (skipped)</li>
          <li :class="{ warn: result.failed }"><strong>{{ result.failed }}</strong> failed</li>
        </ul>
        <ul v-if="failurePreview.length" class="gp-import-failures">
          <li v-for="(f, i) in failurePreview" :key="i">{{ f.fileName }} — {{ f.error }}</li>
        </ul>
        <button class="gp-btn text" type="button" @click="router.push('/gallery')">
          View your photos
        </button>
      </section>

      <section v-if="showProgress && progress.length" class="gp-upload-card" aria-live="polite">
        <div class="gp-upload-head">
          <span v-if="running" class="gp-spinner" />
          <PhotoIcon v-else name="check" :size="18" />
          <span>
            {{
              running
                ? `Importing ${progress.length - uploadedCount} of ${progress.length}…`
                : `Imported ${uploadedCount} of ${progress.length}`
            }}
          </span>
          <button
            class="gp-icon-btn"
            type="button"
            aria-label="Dismiss progress"
            @click="showProgress = false"
          >
            <PhotoIcon name="close" :size="18" />
          </button>
        </div>
        <div class="gp-upload-rows">
          <div
            v-for="(p, i) in progress.slice(0, 200)"
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
    </section>
  </GpShell>
</template>

<style scoped>
.gp-import {
  max-width: 760px;
  margin: 0 auto;
  padding: 24px 16px 64px;
  display: flex;
  flex-direction: column;
  gap: 20px;
}
.gp-import-intro h1 {
  margin: 12px 0 8px;
  font-size: 1.5rem;
}
.gp-import-intro p {
  line-height: 1.5;
  color: var(--gp-text-2);
}
.gp-import-actions {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}
.gp-import-guide {
  border: 1px solid var(--gp-border, #dadce0);
  border-radius: 12px;
  overflow: hidden;
}
.gp-import-guide-head {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 14px 18px;
  background: none;
  border: 0;
  cursor: pointer;
  font: inherit;
  font-weight: 600;
  color: inherit;
  text-align: left;
}
.gp-import-guide-head > span:first-of-type {
  flex: 1;
}
.gp-import-chev {
  transition: transform 0.15s ease;
}
.gp-import-chev.open {
  transform: rotate(90deg);
}
.gp-import-guide-body {
  padding: 0 18px 18px;
}
.gp-import-guide-body ol {
  margin: 0 0 16px;
  padding-left: 22px;
  line-height: 1.6;
}
.gp-import-guide-body ol li {
  margin-bottom: 8px;
}
.gp-import-guide-body ol strong {
  display: block;
}
.gp-import-guide-body ol span {
  color: var(--gp-text-2);
}
.gp-import-guide-tips {
  list-style: none;
  margin: 0 0 14px;
  padding: 12px 14px;
  border-radius: 10px;
  background: var(--gp-surface-2);
}
.gp-import-guide-tips li {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  line-height: 1.5;
  margin-bottom: 8px;
  font-size: 0.9rem;
}
.gp-import-guide-tips li:last-child {
  margin-bottom: 0;
}
.gp-import-guide-link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
}
.gp-import-summary,
.gp-import-report {
  border: 1px solid var(--gp-border, #dadce0);
  border-radius: 12px;
  padding: 16px 20px;
}
.gp-import-summary ul,
.gp-import-report ul {
  margin: 8px 0 16px;
  padding-left: 20px;
  line-height: 1.8;
}
.gp-import-summary li.warn,
.gp-import-report li.warn {
  color: var(--gp-warn, #b06000);
}
.gp-import-failures {
  font-size: 0.85rem;
  max-height: 220px;
  overflow: auto;
}
</style>

