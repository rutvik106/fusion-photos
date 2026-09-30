package com.fusionphotos.app.vault

import com.fusionphotos.app.net.Dtos
import com.fusionphotos.app.net.VaultRemote
import com.fusionphotos.crypto.Crypto
import com.fusionphotos.crypto.Sodium
import com.fusionphotos.crypto.WrappedVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VaultException(message: String) : Exception(message)

/** A freshly created vault: the key to keep in memory, and the recovery key to show once. */
class CreatedVault(val vaultKey: ByteArray, val recoveryKey: String)

/**
 * Create and unlock the vault.
 *
 * The envelopes written here are byte-identical to the web client's (they are pinned by
 * `shared/crypto-vectors.json`), so a library created on the web unlocks on Android and
 * vice versa. The vault key is returned to the caller and held **in memory only** — it is
 * never written to disk.
 *
 * Every crypto call runs on [Dispatchers.Default]: Argon2id is deliberately memory-hard
 * (~256 MiB, multi-second) and would otherwise freeze the UI thread.
 */
class VaultManager(private val sodium: Sodium, private val api: VaultRemote) {

    /** Whether this account already has a vault (decides create vs unlock). */
    suspend fun hasVault(): Boolean = api.getVault() != null

    /**
     * First device: generate the vault key and wrap it twice — once under the passphrase
     * (Argon2id) and once under a fresh recovery key.
     *
     * Returns the vault key **as well as** the recovery key: the caller must keep the key in
     * memory, otherwise the app has published envelopes it cannot itself open and would have
     * to ask for the passphrase again before it could encrypt anything.
     */
    suspend fun create(passphrase: String): CreatedVault = withContext(Dispatchers.Default) {
        val vaultKey = Crypto.newVaultKey(sodium)
        val recoveryDisplay = Crypto.newRecoveryKey(sodium)
        val blob = Dtos.VaultBlob(
            passphrase = Crypto.wrapVaultKey(sodium, vaultKey, passphrase),
            recovery = Crypto.wrapVaultWithRecovery(
                sodium,
                vaultKey,
                Crypto.parseRecoveryKey(recoveryDisplay),
            ),
        )
        api.putVault(blob)
        CreatedVault(vaultKey = vaultKey, recoveryKey = recoveryDisplay)
    }

    /**
     * Returning device: unwrap the passphrase envelope. A legacy flat blob (pre-recovery-key
     * vaults) is normalised by [Dtos.parseVaultBlob], so old accounts still open.
     */
    suspend fun unlock(passphrase: String): ByteArray = withContext(Dispatchers.Default) {
        val blob = api.getVault() ?: throw VaultException("No vault exists for this account yet.")
        val envelope: WrappedVault = blob.passphrase
            ?: throw VaultException("Vault data is unreadable.")
        Crypto.unwrapVaultKey(sodium, envelope, passphrase)
    }

    /** Recovery path: unwrap the second envelope with the displayed recovery key. */
    suspend fun recover(recoveryDisplay: String): ByteArray = withContext(Dispatchers.Default) {
        val blob = api.getVault() ?: throw VaultException("No vault exists for this account yet.")
        val envelope: WrappedVault = blob.recovery
            ?: throw VaultException("This account has no recovery key enrolled.")
        Crypto.unwrapVaultWithRecovery(sodium, envelope, Crypto.parseRecoveryKey(recoveryDisplay))
    }
}
