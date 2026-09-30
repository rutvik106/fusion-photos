<script setup lang="ts">
/**
 * Material snackbar: one line of text, an optional action ("Undo"), auto-dismiss.
 * Google Photos uses this for "Item moved to trash" style confirmations.
 */
import { onBeforeUnmount, onMounted } from 'vue'
import PhotoIcon from './PhotoIcon.vue'

const props = withDefaults(
  defineProps<{
    message: string
    actionLabel?: string
    /** Auto-dismiss delay in ms; 0 keeps it until the user closes it. */
    timeout?: number
  }>(),
  { actionLabel: '', timeout: 6000 },
)

const emit = defineEmits<{
  (e: 'action'): void
  (e: 'dismiss'): void
}>()

let timer: number | undefined

onMounted(() => {
  if (props.timeout > 0) timer = window.setTimeout(() => emit('dismiss'), props.timeout)
})

onBeforeUnmount(() => {
  if (timer !== undefined) window.clearTimeout(timer)
})
</script>

<template>
  <div class="gp-snackbar" role="status" aria-live="polite">
    <span class="gp-snackbar-msg">{{ message }}</span>
    <button v-if="actionLabel" type="button" @click="emit('action')">{{ actionLabel }}</button>
    <button type="button" aria-label="Dismiss" @click="emit('dismiss')">
      <PhotoIcon name="close" :size="18" />
    </button>
  </div>
</template>
