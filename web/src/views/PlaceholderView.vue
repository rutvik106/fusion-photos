<script setup lang="ts">
/** Google-style empty state for sections that land in a later phase. */
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import GpShell from '../components/GpShell.vue'
import PhotoIcon from '../components/PhotoIcon.vue'

const route = useRoute()

const pages: Record<string, { title: string; icon: string; copy: string }> = {
  '/explore': {
    title: 'Explore',
    icon: 'explore',
    copy: 'Search your library by place, thing or date. Semantic search and map browse arrive in a later phase.',
  },
  '/sharing': {
    title: 'Sharing',
    icon: 'people',
    copy: 'Shared albums, conversations and partner libraries arrive in a later phase.',
  },
  '/library': {
    title: 'Library',
    icon: 'library',
    copy: 'Albums, Archive and Utilities arrive in a later phase. Everything you have backed up is already in Photos.',
  },
}

const page = computed(
  () => pages[route.path] ?? { title: 'Not found', icon: 'photos', copy: 'Nothing here yet.' },
)
</script>

<template>
  <GpShell :searchable="false">
    <div class="gp-empty">
      <PhotoIcon :name="page.icon" :size="44" />
      <h2>{{ page.title }}</h2>
      <p>{{ page.copy }}</p>
    </div>
  </GpShell>
</template>
