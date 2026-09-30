<script setup lang="ts">
/**
 * Vault unlock / creation, in the same Google account card style. Kept intentionally
 * separate from sign-in: the OTP session proves account access, this passphrase (or the
 * recovery key) is the only thing that decrypts photos.
 */
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { API, getToken } from '../lib/api'
import PhotoIcon from '../components/PhotoIcon.vue'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const creating = route.query.create === '1'

const passphrase = ref('')
const confirm = ref('')
const recoveryKeyInput = ref('')
const useRecovery = ref(false)
// after creating a vault we show the recovery key once, with a save confirmation
const step = ref<'form' | 'recovery'>('form')
const recoveryKey = ref('')
const savedIt = ref(false)
const copied = ref(false)

const isCreate = computed(() => creating && step.value === 'form')

async function copyKey() {
  await navigator.clipboard.writeText(recoveryKey.value)
  copied.value = true
  setTimeout(() => (copied.value = false), 2000)
}

async function submit() {
  auth.error = ''
  try {
    if (creating) {
      if (passphrase.value.length < 12) {
        auth.error = 'Use at least 12 characters — this passphrase protects your photo keys.'
        return
      }
      if (passphrase.value !== confirm.value) {
        auth.error = 'Passphrases do not match'
        return
      }
      recoveryKey.value = await auth.createVault(passphrase.value)
      step.value = 'recovery'
    } else if (useRecovery.value) {
      if (await auth.recoverWithKey(recoveryKeyInput.value)) {
        try {
          await API.getVault()
          router.push('/gallery')
        } catch {
          await auth.logout()
          router.push('/login')
        }
      } else if (!getToken()) {
        router.push('/login')
      }
    } else {
      if (await auth.unlockVault(passphrase.value)) {
        // sanity-check the session is still valid
        try {
          await API.getVault()
          router.push('/gallery')
        } catch {
          await auth.logout()
          router.push('/login')
        }
      } else if (!getToken()) {
        // unlockVault cleared an invalid session — the login view shows the reason
        router.push('/login')
      }
    }
  } catch (e) {
    // key derivation / network failures must never fail silently
    auth.error = e instanceof Error ? e.message : String(e)
  }
}
</script>

<template>
  <main class="gp-auth">
    <div class="gp-auth-card">
      <template v-if="step === 'recovery'">
        <div class="gp-auth-head">
          <PhotoIcon name="shield" :size="36" />
          <h1>Save your recovery key</h1>
          <p>
            This is the <strong>only other way</strong> to unlock your photos besides your
            passphrase. Store it somewhere safe — a password manager or printed paper.
          </p>
        </div>

        <code class="gp-recovery-key">{{ recoveryKey }}</code>

        <div class="gp-auth-actions">
          <span class="gp-status-line"><PhotoIcon name="lock" :size="16" /> Shown only once</span>
          <button class="gp-btn tonal" type="button" @click="copyKey">
            {{ copied ? 'Copied ✓' : 'Copy key' }}
          </button>
        </div>

        <p class="gp-error">
          If you lose this key AND your passphrase AND every signed-in device, your photos are
          unrecoverable — even by us.
        </p>

        <label class="gp-check">
          <input v-model="savedIt" type="checkbox" />
          I have saved my recovery key somewhere safe
        </label>

        <div class="gp-auth-actions right">
          <button class="gp-btn" :disabled="!savedIt" @click="router.push('/gallery')">
            Continue to gallery
          </button>
        </div>
      </template>

      <template v-else>
        <div class="gp-auth-head">
          <PhotoIcon :name="isCreate ? 'shield' : 'lock'" :size="36" />
          <h1>{{ isCreate ? 'Create your vault' : 'Unlock your vault' }}</h1>
          <p v-if="isCreate">
            Your passphrase derives the key that encrypts every photo. It never leaves this
            device, so the server cannot reset it. You get a recovery key on the next screen.
          </p>
          <p v-else>Enter your vault passphrase to decrypt your library on this device.</p>
        </div>

        <form @submit.prevent="submit">
          <template v-if="!useRecovery">
            <div class="gp-field">
              <label for="passphrase">Passphrase</label>
              <input
                id="passphrase"
                v-model="passphrase"
                type="password"
                :placeholder="creating ? 'at least 12 characters' : ''"
                autocomplete="current-password"
                required
                autofocus
              />
            </div>
            <div v-if="creating" class="gp-field">
              <label for="confirm">Confirm passphrase</label>
              <input
                id="confirm"
                v-model="confirm"
                type="password"
                autocomplete="new-password"
                required
              />
            </div>
          </template>
          <div v-else class="gp-field">
            <label for="recovery-key">Recovery key</label>
            <input
              id="recovery-key"
              v-model="recoveryKeyInput"
              placeholder="aaaa1aaa-…-zzzz9zzz"
              required
              autofocus
            />
          </div>

          <div class="gp-auth-actions">
            <button
              v-if="!creating"
              type="button"
              class="gp-link-btn"
              @click="useRecovery = !useRecovery"
            >
              {{ useRecovery ? 'Use passphrase instead' : 'Use recovery key instead' }}
            </button>
            <span v-else />
            <button class="gp-btn" type="submit" :disabled="auth.busy">
              {{ auth.busy ? 'Deriving key…' : isCreate ? 'Create vault' : 'Unlock' }}
            </button>
          </div>
        </form>
      </template>

      <p v-if="auth.error" class="gp-error">{{ auth.error }}</p>

      <p class="gp-auth-note">
        Passkeys and phone OTP arrive in a later phase. This passphrase (or your recovery key)
        is what decrypts photos — the email code only signs you in.
      </p>
    </div>
  </main>
</template>
