<script setup lang="ts">
/**
 * Sign-in — Google account style: a single rounded card, the product mark, one field at a
 * time. Email OTP only (passkeys/phone OTP are later phases, per the plan).
 */
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import PhotoIcon from '../components/PhotoIcon.vue'

const auth = useAuthStore()
const router = useRouter()
const email = ref('')
const code = ref('')
const step = ref<'email' | 'code'>('email')

async function sendCode() {
  await auth.requestOtp(email.value.trim().toLowerCase())
  step.value = 'code'
}

async function verify() {
  try {
    const res = await auth.verifyOtp(code.value.trim())
    // hasVault=true means the account already has a wrapped vault key; go unlock it.
    // hasVault=false means first device: create the vault (set a passphrase).
    await router.push(res.hasVault ? '/unlock' : '/unlock?create=1')
  } catch {
    /* error shown from store */
  }
}
</script>

<template>
  <main class="gp-auth">
    <div class="gp-auth-card">
      <div class="gp-auth-head">
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
        <h1>{{ step === 'email' ? 'Sign in' : 'Check your email' }}</h1>
        <p>
          {{
            step === 'email'
              ? 'Use your email to reach your end-to-end encrypted library.'
              : `We sent a sign-in code to ${auth.otpSentTo}.`
          }}
        </p>
      </div>

      <p v-if="step === 'code' && auth.devCode" class="gp-status-line">
        <PhotoIcon name="info" :size="16" />
        Dev-mode code: <code class="gp-dev-code">{{ auth.devCode }}</code>
      </p>

      <form v-if="step === 'email'" @submit.prevent="sendCode">
        <div class="gp-field">
          <label for="email">Email</label>
          <input
            id="email"
            v-model="email"
            type="email"
            required
            placeholder="you@example.com"
            autocomplete="email"
            autofocus
          />
        </div>
        <div class="gp-auth-actions right">
          <button class="gp-btn" type="submit" :disabled="auth.busy || !email">
            {{ auth.busy ? 'Sending…' : 'Send sign-in code' }}
          </button>
        </div>
      </form>

      <form v-else @submit.prevent="verify">
        <div class="gp-field">
          <label for="code">Sign-in code</label>
          <input
            id="code"
            v-model="code"
            inputmode="numeric"
            autocomplete="one-time-code"
            required
            autofocus
          />
        </div>
        <div class="gp-auth-actions">
          <button type="button" class="gp-link-btn" @click="step = 'email'">
            Use a different email
          </button>
          <button class="gp-btn" type="submit" :disabled="auth.busy || !code">
            {{ auth.busy ? 'Verifying…' : 'Verify' }}
          </button>
        </div>
      </form>

      <p v-if="auth.error" class="gp-error">{{ auth.error }}</p>

      <p class="gp-auth-note">
        Signing in proves the account is yours; it does not decrypt anything. Your photos are
        locked with a vault passphrase that never leaves your devices.
      </p>

      <div class="gp-auth-foot">
        <span>Passkeys and phone OTP arrive in a later phase.</span>
      </div>
    </div>
  </main>
</template>
