<script setup lang="ts">
/**
 * Export — retrieve the whole library as normal files.
 *
 * An export is a point-in-time snapshot plus a resumable, client-side download: the
 * server records the immutable snapshot and signs snapshot-scoped URLs, while
 * decryption, file naming, integrity checking and the manifest/report happen on this
 * device. A reload resumes; only unfinished files retransmit.
 */
import { computed, onMounted, ref } from 'vue'
import { useAuthStore } from '../stores/auth'
import GpShell from '../components/GpShell.vue'
import PhotoIcon from '../components/PhotoIcon.vue'
import { API, type ExportSnapshot } from '../lib/api'
import { getMeta, type ExportJobState } from '../lib/db'
import {
  directoryPickerSupported,
  runExport,
  type ExportProgress,
  type ExportReport,
} from '../lib/export'
import { formatBytes, fullDateLabel } from '../lib/format'

const auth = useAuthStore()

const snapshot = ref<ExportSnapshot | null>(null)
const retentionDays = ref(0)
const dir = ref<FileSystemDirectoryHandle | null>(null)
const running = ref(false)
const collecting = ref(false)
const progress = ref<ExportProgress | null>(null)
const report = ref<ExportReport | null>(null)
const error = ref('')
const resumable = ref(false)
const control = { cancelled: false }

const dirSupported = directoryPickerSupported()
const percent = computed(() => {
  const p = progress.value
  if (!p || p.total === 0) return 0
  return Math.round((p.done / p.total) * 100)
})

function vaultKey(): Uint8Array {
  if (!auth.vaultKey) throw new Error('vault is locked')
  return auth.vaultKey
}

async function select(s: ExportSnapshot) {
  snapshot.value = s
  report.value = null
  progress.value = null
  const state = await getMeta<ExportJobState>(`exportState:${s.snapshotId}`)
  resumable.value = Boolean(state && state.completed.length > 0)
}

async function refresh() {
  try {
    const res = await API.listExportSnapshots()
    retentionDays.value = Math.round(res.retentionSeconds / 86400)
    if (!snapshot.value && res.snapshots.length > 0) await select(res.snapshots[0])
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}

async function prepare() {
  error.value = ''
  collecting.value = true
  try {
    const s = await API.createExportSnapshot()
    await refresh()
    await select(s)
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    collecting.value = false
  }
}

async function chooseFolder() {
  error.value = ''
  try {
    if (!window.showDirectoryPicker) throw new Error('this browser cannot choose a folder')
    dir.value = await window.showDirectoryPicker({ mode: 'readwrite' })
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}

async function start() {
  if (!snapshot.value) return
  error.value = ''
  report.value = null
  control.cancelled = false
  running.value = true
  try {
    report.value = await runExport({
      snapshotId: snapshot.value.snapshotId,
      vaultKey: vaultKey(),
      dir: dir.value ?? undefined,
      control,
      onProgress: (p) => {
        progress.value = p
      },
    })
    resumable.value = false
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    running.value = false
  }
}

function cancel() {
  control.cancelled = true
}

onMounted(refresh)
</script>

<template>
  <GpShell :searchable="false">
    <section class="gp-export">
      <h1>Export your library</h1>
      <p class="lede">
        Download every backed-up photo as normal files, decrypted on this device. The
        export is a point-in-time snapshot, so it stays consistent even if you add or
        trash photos while it runs — and it resumes if you close the tab.
      </p>

      <div class="gp-export-actions">
        <button class="gp-btn" type="button" :disabled="running || collecting" @click="prepare">
          {{ collecting ? 'Preparing…' : 'Prepare a new snapshot' }}
        </button>
        <button
          v-if="dirSupported"
          class="gp-btn text"
          type="button"
          :disabled="running"
          @click="chooseFolder"
        >
          {{ dir ? 'Change folder…' : 'Choose folder…' }}
        </button>
        <button v-if="snapshot && !running" class="gp-btn" type="button" @click="start">
          {{ resumable ? 'Resume export' : 'Start export' }}
        </button>
        <button v-if="running" class="gp-btn text" type="button" @click="cancel">Stop</button>
      </div>

      <p v-if="error" class="gp-error">{{ error }}</p>

      <section v-if="snapshot" class="gp-export-card">
        <h2>Snapshot</h2>
        <div class="gp-meta-row">
          <span class="k">Photos</span><span class="v">{{ snapshot.assetCount }}</span>
        </div>
        <div class="gp-meta-row">
          <span class="k">Total size</span><span class="v">{{ formatBytes(snapshot.byteTotal) }}</span>
        </div>
        <div class="gp-meta-row">
          <span class="k">Taken</span><span class="v">{{ fullDateLabel(snapshot.createdAt) }}</span>
        </div>
        <div class="gp-meta-row">
          <span class="k">Available until</span>
          <span class="v">{{ fullDateLabel(snapshot.expiresAt) }}</span>
        </div>
        <div class="gp-meta-row">
          <span class="k">Destination</span>
          <span class="v">{{ dir ? dir.name : 'Browser downloads' }}</span>
        </div>
      </section>

      <div v-if="!dirSupported" class="gp-export-note">
        <PhotoIcon name="info" :size="18" />
        <span>
          This browser cannot write to a folder, so each file is offered as a separate
          download. Chrome or Edge can write straight into a chosen folder; the desktop app
          is the reliable path for very large libraries.
        </span>
      </div>
      <div v-else-if="!dir" class="gp-export-note">
        <PhotoIcon name="info" :size="18" />
        <span>Choose a folder to get real files plus the manifest and integrity report.</span>
      </div>

      <p v-if="retentionDays" class="lede">
        Snapshots stay available for {{ retentionDays }} days, so an interrupted export can
        be resumed.
      </p>

      <section v-if="progress" class="gp-upload-card" aria-live="polite">
        <div class="gp-upload-head">
          <span v-if="running" class="gp-spinner" />
          <span>{{ running ? 'Exporting…' : 'Export progress' }}</span>
          <span class="gp-day-count">{{ progress.done }}/{{ progress.total }}</span>
        </div>
        <div class="gp-upload-bar"><span :style="{ width: `${percent}%` }" /></div>
        <div class="gp-upload-row">
          <span class="name">{{ progress.current }}</span>
          <span class="state">
            {{ formatBytes(progress.bytes) }}{{ progress.failed ? ` · ${progress.failed} failed` : '' }}
          </span>
        </div>
      </section>

      <section v-if="report" class="gp-export-card" aria-live="polite">
        <h2>Integrity report</h2>
        <div class="gp-meta-row">
          <span class="k">Exported</span><span class="v">{{ report.exported }}</span>
        </div>
        <div class="gp-meta-row">
          <span class="k">Already complete</span><span class="v">{{ report.skipped }}</span>
        </div>
        <div class="gp-meta-row">
          <span class="k">Failed</span><span class="v">{{ report.failed.length }}</span>
        </div>
        <div class="gp-meta-row">
          <span class="k">Bytes written</span>
          <span class="v">{{ formatBytes(report.bytesWritten) }}</span>
        </div>
        <ul v-if="report.failed.length" class="gp-export-failures">
          <li v-for="f in report.failed.slice(0, 20)" :key="f.assetId">
            {{ f.fileName }} — {{ f.error }}
          </li>
        </ul>
        <p v-else class="lede">Every file decrypted and matched its recorded SHA-256 digest.</p>
      </section>
    </section>
  </GpShell>
</template>
