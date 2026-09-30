<script setup lang="ts">
/**
 * Google Photos-style top app bar: product mark + wordmark, the pill "Search your
 * photos" field, and the right-hand action cluster. The pinwheel geometry and hex
 * colors are the official 59×59 Google Photos mark (kept for visual fidelity — swap in
 * the product's own mark before shipping).
 */
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { useCatalogStore } from '../stores/catalog'
import PhotoIcon from './PhotoIcon.vue'
import SnackBar from './SnackBar.vue'
import BackupStatusPanel from './BackupStatusPanel.vue'

withDefaults(
  defineProps<{
    /** Render the "Search your photos" pill. */
    searchable?: boolean
    /** Current query (v-model:query). */
    query?: string
    /** Render the hamburger that opens the nav drawer on narrow screens. */
    menu?: boolean
  }>(),
  { searchable: true, query: '', menu: true },
)

const emit = defineEmits<{
  (e: 'update:query', value: string): void
  (e: 'toggle-nav'): void
}>()

const auth = useAuthStore()
const catalog = useCatalogStore()
const router = useRouter()
const menuOpen = ref(false)
const statusOpen = ref(false)
const notice = ref<{ id: number; message: string } | null>(null)
let noticeId = 0

/**
 * Whether the backup shield needs attention: the server picture when we have it,
 * otherwise the locally computed count. Never claims protection that isn't there.
 */
const needsAttention = computed(() => {
  const s = catalog.backupStatus
  if (s) return !s.replicaConfigured || s.unreplicatedAssets > 0
  return catalog.unprotectedCount > 0
})

function onInput(event: Event) {
  emit('update:query', (event.target as HTMLInputElement).value)
}

/** Honest placeholder for Google Photos chrome that lands in a later phase. */
function notImplemented(what: string) {
  menuOpen.value = false
  noticeId += 1
  notice.value = { id: noticeId, message: `${what} arrives in a later phase.` }
}

async function logout() {
  menuOpen.value = false
  await auth.logout()
  router.push('/login')
}
</script>

<template>
  <header class="gp-topbar">
    <button
      v-if="menu"
      class="gp-icon-btn gp-nav-toggle"
      type="button"
      aria-label="Main menu"
      @click="emit('toggle-nav')"
    >
      <PhotoIcon name="menu" />
    </button>

    <RouterLink to="/gallery" class="gp-brand" aria-label="Fusion Photos — home">
      <svg class="gp-logo" viewBox="0 0 59 59" aria-hidden="true">
        <path
          d="M14.75 13.41c8.146 0 14.75 6.603 14.75 14.75v1.34H1.34C.6 29.5 0 28.9 0 28.16c0-8.147 6.604-14.75 14.75-14.75z"
          fill="#FBBC04"
        />
        <path
          d="M45.59 14.75c0 8.146-6.603 14.75-14.75 14.75H29.5V1.34C29.5.6 30.1 0 30.84 0c8.147 0 14.75 6.604 14.75 14.75z"
          fill="#EA4335"
        />
        <path
          d="M44.25 45.59c-8.146 0-14.75-6.603-14.75-14.75V29.5h28.16c.74 0 1.34.6 1.34 1.34 0 8.147-6.604 14.75-14.75 14.75z"
          fill="#4285F4"
        />
        <path
          d="M13.41 44.25c0-8.146 6.603-14.75 14.75-14.75h1.34v28.16c0 .74-.6 1.34-1.34 1.34-8.147 0-14.75-6.604-14.75-14.75z"
          fill="#34A853"
        />
      </svg>
      <span class="gp-brand-text">Fusion Photos</span>
    </RouterLink>

    <div v-if="searchable" class="gp-search">
      <PhotoIcon name="search" :size="20" />
      <input
        type="search"
        placeholder="Search your photos"
        aria-label="Search your photos"
        :value="query"
        @input="onInput"
      />
    </div>

    <div class="gp-topbar-actions">
      <!-- page-specific actions first (e.g. Upload), then the global Google chrome -->
      <slot name="actions" />

      <div class="gp-status-wrap">
        <button
          class="gp-icon-btn"
          type="button"
          aria-label="Backup status"
          aria-haspopup="dialog"
          :aria-expanded="statusOpen"
          @click="statusOpen = !statusOpen"
        >
          <PhotoIcon name="shield" />
          <span v-if="needsAttention" class="gp-status-dot" aria-hidden="true" />
        </button>
        <div v-if="statusOpen" class="gp-menu-scrim" @click="statusOpen = false" />
        <BackupStatusPanel v-if="statusOpen" @close="statusOpen = false" />
      </div>

      <button
        class="gp-icon-btn"
        type="button"
        aria-label="Settings"
        @click="notImplemented('Settings')"
      >
        <PhotoIcon name="settings" />
      </button>
      <button
        class="gp-icon-btn"
        type="button"
        aria-label="Google apps"
        @click="notImplemented('App switching')"
      >
        <PhotoIcon name="apps" />
      </button>

      <div class="gp-menu-wrap">
        <button
          class="gp-icon-btn"
          type="button"
          aria-label="Account"
          aria-haspopup="menu"
          :aria-expanded="menuOpen"
          @click="menuOpen = !menuOpen"
        >
          <span class="gp-avatar"><PhotoIcon name="person" :size="20" /></span>
        </button>
        <div v-if="menuOpen" class="gp-menu-scrim" @click="menuOpen = false" />
        <div v-if="menuOpen" class="gp-menu" role="menu">
          <div class="gp-menu-head">Signed in · end-to-end encrypted</div>
          <button class="gp-menu-item" type="button" role="menuitem" @click="logout">
            <PhotoIcon name="arrow_back" :size="18" />
            Sign out
          </button>
        </div>
      </div>
    </div>

    <SnackBar v-if="notice" :key="notice.id" :message="notice.message" @dismiss="notice = null" />
  </header>
</template>
