<script setup lang="ts">
/**
 * App shell: sticky top bar + navigation drawer + content column. Views that need a
 * different bar (selection mode) replace it through the `header` slot, exactly like
 * Google Photos swaps the whole app bar when a selection is active.
 */
import { ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import AppHeader from './AppHeader.vue'
import NavRail from './NavRail.vue'

withDefaults(
  defineProps<{
    searchable?: boolean
    query?: string
  }>(),
  { searchable: true, query: '' },
)

const emit = defineEmits<{
  (e: 'update:query', value: string): void
}>()

const navOpen = ref(false)
const route = useRoute()

// The drawer is an overlay on narrow screens — close it after every navigation.
watch(
  () => route.path,
  () => {
    navOpen.value = false
  },
)
</script>

<template>
  <div class="gp-shell" :class="{ 'nav-open': navOpen }">
    <slot name="header">
      <AppHeader
        :searchable="searchable"
        :query="query"
        menu
        @update:query="emit('update:query', $event)"
        @toggle-nav="navOpen = !navOpen"
      >
        <template #actions>
          <slot name="actions" />
        </template>
      </AppHeader>
    </slot>

    <div class="gp-nav-scrim" @click="navOpen = false" />

    <div class="gp-body">
      <NavRail />
      <main class="gp-content">
        <slot />
      </main>
    </div>
  </div>
</template>
