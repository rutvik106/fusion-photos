<script setup lang="ts">
/**
 * Trash — 30-day cloud trash, styled like the rest of the shell: the same date-heading
 * grid, with a restore affordance on each tile. Permanent deletion is owned by the
 * server's purge worker, so there is deliberately no "delete forever" control here.
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { useCatalogStore } from '../stores/catalog'
import { API } from '../lib/api'
import { markRestored, type LocalAsset } from '../lib/db'
import { purgeLabel } from '../lib/format'
import GpShell from '../components/GpShell.vue'
import PhotoTile from '../components/PhotoTile.vue'
import SnackBar from '../components/SnackBar.vue'
import PhotoIcon from '../components/PhotoIcon.vue'

const auth = useAuthStore()
const catalog = useCatalogStore()
const router = useRouter()

const query = ref('')
const busy = ref('')
const snack = ref<{ id: number; message: string } | null>(null)
let snackId = 0

/** The router guard guarantees an unlocked vault on this route. */
const vaultKey = computed(() => auth.vaultKey as Uint8Array)

const filtered = computed(() => {
  const needle = query.value.trim().toLowerCase()
  const all = catalog.deletedAssets
  if (!needle) return all
  return all.filter((a) => a.fileName.toLowerCase().includes(needle))
})

onMounted(async () => {
  if (!auth.vaultKey) {
    router.push('/unlock')
    return
  }
  await catalog.loadLocal()
  void catalog.sync(auth.vaultKey)
})

async function restore(asset: LocalAsset) {
  if (busy.value) return
  busy.value = asset.assetId
  try {
    await API.restoreAsset(asset.assetId)
    await markRestored(asset.assetId)
    await catalog.loadLocal()
    showSnack(`"${asset.fileName}" restored to your library.`)
  } catch (e) {
    showSnack(e instanceof Error ? e.message : String(e))
  } finally {
    busy.value = ''
  }
}

function showSnack(message: string) {
  snackId += 1
  snack.value = { id: snackId, message }
}
</script>

<template>
  <GpShell :query="query" @update:query="query = $event">
    <section class="gp-trash-note">
      <PhotoIcon name="warning" :size="18" />
      <span>
        Items are permanently deleted 30 days after they are moved to trash. Restoring an item
        puts it back in your library.
      </span>
    </section>

    <section class="gp-grid-area">
      <template v-if="filtered.length">
        <h2 class="gp-day-header">
          Trash
          <span class="gp-day-count">{{ filtered.length }}</span>
        </h2>
        <div class="gp-grid">
          <PhotoTile
            v-for="asset in filtered"
            :key="asset.assetId"
            :asset="asset"
            :vault-key="vaultKey"
            mode="trash"
            :busy="busy === asset.assetId"
            :meta="`Permanently deleted ${purgeLabel(asset.deletedAt)}`"
            @restore="restore(asset)"
          />
        </div>
      </template>

      <div v-else-if="catalog.loaded" class="gp-empty">
        <PhotoIcon name="delete" :size="44" />
        <h2>{{ query ? 'No trash items match your search' : 'Trash is empty' }}</h2>
        <p v-if="query">Try a different file name.</p>
        <p v-else>Photos you delete appear here for 30 days before they are removed for good.</p>
      </div>
    </section>

    <SnackBar v-if="snack" :key="snack.id" :message="snack.message" @dismiss="snack = null" />
  </GpShell>
</template>
