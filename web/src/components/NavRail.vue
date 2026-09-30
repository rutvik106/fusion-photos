<script setup lang="ts">
/**
 * Navigation drawer. Only destinations that exist are listed; Trash shows its live
 * count from the local catalog.
 */
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useCatalogStore } from '../stores/catalog'
import PhotoIcon from './PhotoIcon.vue'

const route = useRoute()
const catalog = useCatalogStore()

const items = [
  { path: '/gallery', label: 'Photos', icon: 'photos' },
  { path: '/explore', label: 'Explore', icon: 'explore' },
  { path: '/sharing', label: 'Sharing', icon: 'people' },
  { path: '/library', label: 'Library', icon: 'library' },
  { path: '/import', label: 'Import', icon: 'upload' },
  { path: '/export', label: 'Export', icon: 'download' },
]

const trashCount = computed(() => catalog.deletedAssets.length)
const trashLabel = computed(() => (trashCount.value ? `Trash (${trashCount.value})` : 'Trash'))
</script>

<template>
  <nav class="gp-navrail" aria-label="Primary">
    <RouterLink
      v-for="item in items"
      :key="item.path"
      :to="item.path"
      class="gp-nav-item"
      :class="{ active: route.path === item.path }"
    >
      <PhotoIcon :name="item.icon" />
      <span>{{ item.label }}</span>
    </RouterLink>

    <div class="gp-nav-sep" />

    <RouterLink to="/trash" class="gp-nav-item" :class="{ active: route.path === '/trash' }">
      <PhotoIcon name="delete" />
      <span>{{ trashLabel }}</span>
    </RouterLink>

    <div class="gp-nav-foot">
      <PhotoIcon name="shield" :size="16" />
      <span>
        End-to-end encrypted — photos are locked with a key that never leaves this device.
      </span>
    </div>
  </nav>
</template>
