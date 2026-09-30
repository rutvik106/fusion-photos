<script setup lang="ts">
/**
 * Inline SVG icon set (24px grid, 1.7px round caps) drawn by hand so the app needs no
 * icon font or CDN: the page CSP (`default-src 'self'`) forbids external assets, and
 * Google's own font/icon hosts are not reachable from the client. Names follow the
 * Material Symbols vocabulary used across Google products.
 */
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    name: string
    /** 24 (default) or any CSS length string. */
    size?: number | string
  }>(),
  { size: 24 },
)

const dim = computed(() => (typeof props.size === 'number' ? `${props.size}px` : props.size))
</script>

<template>
  <svg
    class="gp-icon"
    :width="dim"
    :height="dim"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.7"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
    focusable="false"
  >
    <template v-if="name === 'search'">
      <circle cx="11" cy="11" r="6.5" />
      <path d="M15.9 15.9 20.5 20.5" />
    </template>

    <template v-else-if="name === 'menu'">
      <path d="M3.5 6.5h17M3.5 12h17M3.5 17.5h17" />
    </template>

    <template v-else-if="name === 'upload'">
      <path d="M12 15.5V4.2m0 0L8.2 8m3.8-3.8L15.8 8" />
      <path d="M4.5 15.5v3A1.5 1.5 0 0 0 6 20h12a1.5 1.5 0 0 0 1.5-1.5v-3" />
    </template>

    <template v-else-if="name === 'settings'">
      <circle cx="12" cy="12" r="6.6" />
      <circle cx="12" cy="12" r="3.1" />
      <path d="M12 2.8v2.8" />
      <path d="M12 2.8v2.8" transform="rotate(45 12 12)" />
      <path d="M12 2.8v2.8" transform="rotate(90 12 12)" />
      <path d="M12 2.8v2.8" transform="rotate(135 12 12)" />
      <path d="M12 2.8v2.8" transform="rotate(180 12 12)" />
      <path d="M12 2.8v2.8" transform="rotate(225 12 12)" />
      <path d="M12 2.8v2.8" transform="rotate(270 12 12)" />
      <path d="M12 2.8v2.8" transform="rotate(315 12 12)" />
    </template>

    <template v-else-if="name === 'apps'">
      <circle v-for="x in 3" :key="`ax${x}`" :cx="4.6 + (x - 1) * 7.4" cy="4.6" r="1.45" fill="currentColor" stroke="none" />
      <circle v-for="x in 3" :key="`ay${x}`" :cx="4.6 + (x - 1) * 7.4" cy="12" r="1.45" fill="currentColor" stroke="none" />
      <circle v-for="x in 3" :key="`az${x}`" :cx="4.6 + (x - 1) * 7.4" cy="19.4" r="1.45" fill="currentColor" stroke="none" />
    </template>

    <template v-else-if="name === 'person'">
      <circle cx="12" cy="8.4" r="3.7" />
      <path d="M4.9 20.2c.5-3.9 3.6-6.3 7.1-6.3s6.6 2.4 7.1 6.3" />
    </template>

    <template v-else-if="name === 'photos'">
      <rect x="3.2" y="4.2" width="17.6" height="15.6" rx="2.4" />
      <circle cx="8.6" cy="9.6" r="1.6" />
      <path d="M4 16.4l4.9-4.6 3.4 3.2 3-2.7 4.7 4.3" />
    </template>

    <template v-else-if="name === 'explore'">
      <circle cx="12" cy="12" r="8.6" />
      <path d="M15.9 8.1l-2.1 5.7-5.7 2.1 2.1-5.7z" />
    </template>

    <template v-else-if="name === 'people'">
      <circle cx="9.2" cy="8.8" r="3.4" />
      <path d="M3.2 19.4c.5-3.4 3.1-5.5 6-5.5s5.5 2.1 6 5.5" />
      <path d="M15.7 5.9a3.4 3.4 0 0 1 0 6.3" />
      <path d="M17.6 14.3c1.7.6 3 2.2 3.4 4.4" />
    </template>

    <template v-else-if="name === 'library'">
      <rect x="4" y="7" width="16" height="13.2" rx="2.2" />
      <path d="M6.6 4.1h10.8M9.2 1.6h5.6" />
    </template>

    <template v-else-if="name === 'delete'">
      <path d="M4.3 6.8h15.4" />
      <path d="M9.4 6.8V5.1A1.3 1.3 0 0 1 10.7 3.8h2.6a1.3 1.3 0 0 1 1.3 1.3v1.7" />
      <path d="M6.6 6.8l.9 12a1.6 1.6 0 0 0 1.6 1.4h5.8a1.6 1.6 0 0 0 1.6-1.4l.9-12" />
      <path d="M10.4 10.6v5.8M13.6 10.6v5.8" />
    </template>

    <template v-else-if="name === 'close'">
      <path d="M5.6 5.6l12.8 12.8M18.4 5.6 5.6 18.4" />
    </template>

    <template v-else-if="name === 'check'">
      <path d="M4.8 12.6l4.8 4.8L19.4 7" />
    </template>

    <template v-else-if="name === 'arrow_back'">
      <path d="M20 12H4.6M11.2 5.4 4.6 12l6.6 6.6" />
    </template>

    <template v-else-if="name === 'chevron_left'">
      <path d="M14.6 5.6 8.2 12l6.4 6.4" />
    </template>

    <template v-else-if="name === 'chevron_right'">
      <path d="M9.4 5.6 15.8 12l-6.4 6.4" />
    </template>

    <template v-else-if="name === 'download'">
      <path d="M12 3.6v10.8m0 0 4.1-4.1M12 14.4 7.9 10.3" />
      <path d="M4.6 16.6v2.4A1.4 1.4 0 0 0 6 20.4h12a1.4 1.4 0 0 0 1.4-1.4v-2.4" />
    </template>

    <template v-else-if="name === 'restore'">
      <path d="M19.4 12a7.4 7.4 0 1 1-2.2-5.2" />
      <path d="M19.7 3.4v4.4h-4.4" />
      <path d="M12 8.6V12l2.6 1.6" />
    </template>

    <template v-else-if="name === 'info'">
      <circle cx="12" cy="12" r="8.5" />
      <path d="M12 11.2v5.4" />
      <circle cx="12" cy="7.9" r="1.05" fill="currentColor" stroke="none" />
    </template>

    <template v-else-if="name === 'lock'">
      <rect x="4.9" y="10.3" width="14.2" height="9.7" rx="2.1" />
      <path d="M8.1 10.3V7.9a3.9 3.9 0 0 1 7.8 0v2.4" />
      <path d="M12 14.2v1.9" />
    </template>

    <template v-else-if="name === 'shield'">
      <path d="M12 2.9 4.9 5.7v5.5c0 4.3 2.9 8.2 7.1 9.4 4.2-1.2 7.1-5.1 7.1-9.4V5.7z" />
      <path d="M9 11.9l2.2 2.2 4-4.2" />
    </template>

    <template v-else-if="name === 'image_off'">
      <rect x="3.2" y="4.2" width="17.6" height="15.6" rx="2.4" />
      <circle cx="8.6" cy="9.6" r="1.6" />
      <path d="M4 16.4l4.9-4.6 3.4 3.2 3-2.7 4.7 4.3" />
      <path d="M3.6 3.6 20.4 20.4" />
    </template>

    <template v-else-if="name === 'add_box'">
      <rect x="3.8" y="3.8" width="16.4" height="16.4" rx="2.4" />
      <path d="M12 8.4v7.2M8.4 12h7.2" />
    </template>

    <template v-else-if="name === 'edit'">
      <path d="M4.2 19.8h3.5L19.2 8.3a1.9 1.9 0 0 0 0-2.7l-.8-.8a1.9 1.9 0 0 0-2.7 0L4.2 16.3z" />
      <path d="M14.4 6.9l3.1 3.1" />
    </template>

    <template v-else-if="name === 'share'">
      <circle cx="17.4" cy="6" r="2.4" />
      <circle cx="6.4" cy="12" r="2.4" />
      <circle cx="17.4" cy="18" r="2.4" />
      <path d="M8.5 10.9l6.9-3.6" />
      <path d="M8.5 13.1l6.9 3.6" />
    </template>

    <template v-else-if="name === 'zoom_in'">
      <circle cx="10.8" cy="10.8" r="6.4" />
      <path d="M15.5 15.5 20.4 20.4" />
      <path d="M10.8 8v5.6M8 10.8h5.6" />
    </template>

    <template v-else-if="name === 'zoom_out'">
      <circle cx="10.8" cy="10.8" r="6.4" />
      <path d="M15.5 15.5 20.4 20.4" />
      <path d="M8 10.8h5.6" />
    </template>

    <template v-else-if="name === 'warning'">
      <path d="M12 3.7 21.1 19.6H2.9z" />
      <path d="M12 9.9v4" />
      <circle cx="12" cy="16.7" r="1" fill="currentColor" stroke="none" />
    </template>
  </svg>
</template>
