/**
 * Auth + vault session store. Login (OTP) and decryption are deliberately separate:
 * the session token proves account access; the vault key only exists in memory while
 * the user has unlocked it with their passphrase.
 */
import { defineStore } from 'pinia'
import { API, ApiError, clearSession, getToken, setToken, setUserId, type VaultBlob, type WrappedVault } from '../lib/api'
import {
  newRecoveryKey,
  newVaultKey,
  parseRecoveryKey,
  toB64,
  unwrapVaultKey,
  unwrapVaultWithRecovery,
  wrapVaultKey,
  wrapVaultWithRecovery,
} from '../lib/crypto'
import { wipeLocalCatalog } from '../lib/db'
import { clearCatalogMarker } from '../lib/storage'
import { stageWipeAll } from '../lib/opfs'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    userId: '' as string,
    email: '' as string,
    hasVault: false,
    /** plaintext vault key — memory only, never persisted */
    vaultKey: null as Uint8Array | null,
    /** last OTP request result (dev code display) */
    devCode: '' as string,
    otpSentTo: '' as string,
    error: '' as string,
    busy: false,
  }),
  actions: {
    async requestOtp(email: string) {
      this.busy = true
      this.error = ''
      try {
        const res = await API.requestOtp(email)
        this.otpSentTo = email
        this.devCode = res.devCode ?? ''
      } finally {
        this.busy = false
      }
    },
    async verifyOtp(code: string) {
      this.busy = true
      this.error = ''
      try {
        const res = await API.verifyOtp(this.otpSentTo, code)
        setToken(res.sessionToken)
        // Persist the account id: it scopes the local catalog so a later login as a
        // different account drops the previous account's cache instead of showing it.
        setUserId(res.userId)
        this.userId = res.userId
        this.hasVault = res.hasVault
        return res
      } catch (e) {
        this.error = e instanceof Error ? e.message : String(e)
        throw e
      } finally {
        this.busy = false
      }
    },
    /** First device: generate the vault key, wrap with passphrase AND a fresh
     * recovery key (two independent paths to the same key), upload. Returns the
     * recovery key for the one-time display screen. */
    async createVault(passphrase: string): Promise<string> {
      if (!this.userId) throw new Error('not signed in')
      this.busy = true
      try {
        const vk = await newVaultKey()
        const recoveryKey = await newRecoveryKey()
        const blob: VaultBlob = {
          passphrase: await wrapVaultKey(vk, passphrase),
          recovery: wrapVaultWithRecovery(vk, parseRecoveryKey(recoveryKey)),
        }
        await API.putVault({ wrapped: blob })
        this.vaultKey = vk
        this.hasVault = true
        return recoveryKey
      } finally {
        this.busy = false
      }
    },
    /** Returning device: unwrap the stored vault blob with the passphrase.
     * Handles both the structured blob and the legacy flat envelope. */
    async unlockVault(passphrase: string) {
      this.busy = true
      this.error = ''
      try {
        const { wrapped } = await API.getVault()
        if (!wrapped) {
          this.error = 'No vault exists for this account yet — sign out and sign in again to create one.'
          return false
        }
        const envelope = wrapped.passphrase ?? legacyEnvelope(wrapped)
        if (!envelope) {
          this.error = 'Vault data is unreadable — contact support.'
          return false
        }
        this.vaultKey = await unwrapVaultKey(envelope, passphrase)
        return true
      } catch (e) {
        if (e instanceof ApiError && e.status === 401) {
          // Session is gone (expired, revoked, or account removed): the passphrase
          // question is moot — start the login flow over.
          clearSession()
          this.error = 'Your session has expired — sign in again.'
        } else if (e instanceof ApiError) {
          // network/server problem — NOT a wrong passphrase
          this.error = `Could not load vault (${e.status}): ${e.message}`
        } else {
          // sodium AEAD failure = wrong key derived from this passphrase
          this.error = 'Wrong passphrase'
        }
        return false
      } finally {
        this.busy = false
      }
    },
    /** Recovery path: unwrap the vault with the displayed/pasted recovery key.
     * Works on any device, even where the passphrase was never known. */
    async recoverWithKey(recoveryKeyDisplay: string) {
      this.busy = true
      this.error = ''
      try {
        const { wrapped } = await API.getVault()
        if (!wrapped?.recovery) {
          this.error = 'This account has no recovery key enrolled.'
          return false
        }
        this.vaultKey = unwrapVaultWithRecovery(wrapped.recovery, parseRecoveryKey(recoveryKeyDisplay))
        return true
      } catch (e) {
        if (e instanceof ApiError && e.status === 401) {
          clearSession()
          this.error = 'Your session has expired — sign in again.'
        } else if (e instanceof ApiError) {
          this.error = `Could not load vault (${e.status}): ${e.message}`
        } else {
          this.error = 'Recovery key invalid'
        }
        return false
      } finally {
        this.busy = false
      }
    },
    /** Enroll (or replace) the recovery key while the vault is unlocked — for
     * accounts created before recovery keys existed. Returns the display form. */
    async enrollRecoveryKey(): Promise<string> {
      if (!this.vaultKey) throw new Error('vault locked')
      const recoveryKey = await newRecoveryKey()
      const { wrapped } = await API.getVault()
      if (!wrapped) throw new Error('no vault on account')
      // preserve the existing passphrase envelope; legacy flat form is converted
      const blob: VaultBlob = {
        passphrase: wrapped.passphrase ?? legacyEnvelope(wrapped),
        recovery: wrapVaultWithRecovery(this.vaultKey, parseRecoveryKey(recoveryKey)),
      }
      if (!blob.passphrase) throw new Error('vault data is unreadable')
      await API.putVault({ wrapped: blob })
      return recoveryKey
    },
    vaultKeyB64(): string {
      return this.vaultKey ? toB64(this.vaultKey) : ''
    },
    async logout() {
      clearSession()
      this.$reset()
      clearCatalogMarker()
      await wipeLocalCatalog()
      await stageWipeAll()
    },
    /** Restore a remembered session on app boot (vault still needs unlock). */
    hydrate() {
      if (getToken()) {
        // Session token exists; user id is confirmed by first authenticated call.
        // We leave userId empty and let the gallery page validate via /vault.
        return Boolean(getToken())
      }
      return false
    },
  },
})

/** Legacy vaults (pre-recovery-key) stored the passphrase envelope flat. */
function legacyEnvelope(blob: VaultBlob): WrappedVault | undefined {
  if (blob.kdfSalt && blob.wrapped) {
    return { kdfSalt: blob.kdfSalt, wrapped: blob.wrapped, v: blob.v ?? 1 }
  }
  return undefined
}
