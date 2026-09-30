<script setup lang="ts">
/**
 * Backup status panel: the honest picture of what is stored and what is safe.
 *
 * Wording rule (per plan): never claim a photo is "protected remotely" unless the
 * server verified it in an independent region. With no replica configured, say so
 * plainly instead of implying durability the deployment does not provide.
 */
import { computed, onMounted, ref } from 'vue'
import { useCatalogStore } from '../stores/catalog'
import { formatBytes, fullDateLabel } from '../lib/format'
import { isStoragePersisted, storageEstimate, type StorageEstimate } from '../lib/storage'
import PhotoIcon from './PhotoIcon.vue'

const catalog = useCatalogStore()
const emit = defineEmits<{ (e: 'close'): void }>()

const status = computed(() => catalog.backupStatus)

/** Three honest states: no replica, some not yet copied, all copied. */
const state = computed(() => {
  const s = status.value
  if (!s) return { tone: 'info', icon: 'info', text: 'Checking backup status…' }
  if (!s.replicaConfigured) {
    return {
      tone: 'warn',
      icon: 'warning',
      text: 'No second-region copy is configured. Photos are stored on the server as ciphertext, but they are not yet replicated to an independent region.',
    }
  }
  if (s.unreplicatedAssets > 0) {
    return {
      tone: 'warn',
      icon: 'warning',
      text: `${s.unreplicatedAssets} photo${s.unreplicatedAssets === 1 ? '' : 's'} not yet verified in the second region. This completes automatically.`,
    }
  }
  if (s.totalAssets > 0) {
    return {
      tone: 'ok',
      icon: 'shield',
      text: `All ${s.totalAssets} photo${s.totalAssets === 1 ? '' : 's'} verified in a second region.`,
    }
  }
  return { tone: 'info', icon: 'info', text: 'Nothing backed up yet.' }
})

const lastBackup = computed(() => {
  const t = status.value?.lastCommittedAt
  return t ? fullDateLabel(t) : '—'
})

// Local-cache honesty: the catalog is best-effort in every browser — say which.
const persisted = ref<boolean | null>(null)
const usage = ref<StorageEstimate | null>(null)

onMounted(async () => {
  void catalog.refreshBackupStatus()
  persisted.value = await isStoragePersisted()
  usage.value = await storageEstimate()
})
</script>

<template>
  <section class="gp-status-panel" aria-label="Backup status">
    <div class="gp-status-head">
      <span>Backup status</span>
      <button class="gp-icon-btn" type="button" aria-label="Close backup status" @click="emit('close')">
        <PhotoIcon name="close" />
      </button>
    </div>

    <div class="gp-status-state" :class="state.tone">
      <PhotoIcon :name="state.icon" :size="18" />
      <span>{{ state.text }}</span>
    </div>

    <div class="gp-meta-row">
      <span class="k">Photos</span>
      <span class="v">{{ status?.totalAssets ?? catalog.visibleAssets.length }}</span>
    </div>
    <div class="gp-meta-row">
      <span class="k">Second-region copy</span>
      <span class="v">{{ status?.protectedAssets ?? 0 }}</span>
    </div>
    <div class="gp-meta-row">
      <span class="k">Awaiting copy</span>
      <span class="v">{{ status?.unreplicatedAssets ?? catalog.unprotectedCount }}</span>
    </div>
    <div class="gp-meta-row">
      <span class="k">In trash</span>
      <span class="v">{{ status?.trashedAssets ?? catalog.deletedAssets.length }}</span>
    </div>
    <div class="gp-meta-row">
      <span class="k">Uploads in progress</span>
      <span class="v">{{ status?.pendingUploads ?? 0 }}</span>
    </div>
    <div class="gp-meta-row">
      <span class="k">Last backup</span>
      <span class="v">{{ lastBackup }}</span>
    </div>
    <div class="gp-meta-row">
      <span class="k">Local cache</span>
      <span class="v">
        {{ persisted ? 'Persistent' : 'Best effort — the browser may clear it' }}
      </span>
    </div>
    <div v-if="usage" class="gp-meta-row">
      <span class="k">Storage used</span>
      <span class="v">{{ formatBytes(usage.usage) }} of {{ formatBytes(usage.quota) }}</span>
    </div>

    <div class="gp-status-foot">
      <PhotoIcon name="lock" :size="14" />
      <span>
        End-to-end encrypted: the server stores ciphertext it cannot read. Removing
        photos in this app never deletes the copies on your devices.
      </span>
    </div>

    <div class="gp-status-actions">
      <button class="gp-btn text" type="button" @click="catalog.refreshBackupStatus()">
        Refresh
      </button>
    </div>
    <p v-if="catalog.statusError" class="gp-status-err">
      Could not load status: {{ catalog.statusError }}
    </p>
  </section>
</template>
