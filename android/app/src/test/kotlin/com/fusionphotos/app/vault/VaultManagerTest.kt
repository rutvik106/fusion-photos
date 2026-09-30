package com.fusionphotos.app.vault

import com.fusionphotos.app.net.Dtos
import com.fusionphotos.app.net.VaultRemote
import com.fusionphotos.crypto.CryptoException
import com.fusionphotos.crypto.LibSodiumAdapter
import com.fusionphotos.crypto.Sodium
import com.goterl.lazysodium.SodiumJava
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** An in-memory stand-in for the server's vault endpoint. */
private class FakeVaultRemote : VaultRemote {
    var stored: Dtos.VaultBlob? = null
    var puts = 0

    override suspend fun getVault(): Dtos.VaultBlob? = stored

    override suspend fun putVault(blob: Dtos.VaultBlob) {
        stored = blob
        puts++
    }
}

/**
 * The vault is the one thing the user cannot redo: if the app publishes envelopes it cannot
 * open, or forgets the key it just made, the library is unreachable. Running the flow on a
 * phone caught exactly that (create returned only the recovery key, so the app held no vault
 * key and could not encrypt anything), so these tests pin the behaviour.
 *
 * They run against a real JVM libsodium, so the Argon2id/wrap round trip is genuinely
 * exercised — Argon2id MODERATE is memory-hard, which makes this suite seconds, not ms.
 */
class VaultManagerTest {
    // The app does exactly this with SodiumAndroid; here the JVM libsodium drives the same
    // adapter, so the Argon2id/wrap round trip under test is the real one.
    private val sodium: Sodium = LibSodiumAdapter(SodiumJava())
    private val passphrase = "correct-horse-battery-staple"

    @Test
    fun `creating a vault publishes both distinct envelopes`() = runBlocking {
        val remote = FakeVaultRemote()
        val created = VaultManager(sodium, remote).create(passphrase)

        val stored = remote.stored ?: error("create() published nothing")
        assertEquals(1, remote.puts)
        assertEquals(Dtos.VaultBlob.CRYPTO_VERSION, stored.passphrase?.v)
        assertTrue(stored.recovery != null, "a recovery envelope must be enrolled")
        assertNotEquals(stored.passphrase?.wrapped, stored.recovery?.wrapped)
        assertTrue(created.recoveryKey.isNotBlank())
    }

    @Test
    fun `the key create returns opens the envelope create published`() = runBlocking {
        val remote = FakeVaultRemote()
        val created = VaultManager(sodium, remote).create(passphrase)
        val reopened = VaultManager(sodium, remote).unlock(passphrase)
        assertEquals(
            created.vaultKey.toList(),
            reopened.toList(),
            "the published passphrase envelope must yield the key create() handed back",
        )
    }

    @Test
    fun `the recovery key opens the recovery envelope`() = runBlocking {
        val remote = FakeVaultRemote()
        val created = VaultManager(sodium, remote).create(passphrase)
        val recovered = VaultManager(sodium, remote).recover(created.recoveryKey)
        assertEquals(created.vaultKey.toList(), recovered.toList())
    }

    @Test
    fun `a wrong passphrase fails closed and does not poison the vault`() = runBlocking {
        val remote = FakeVaultRemote()
        val created = VaultManager(sodium, remote).create(passphrase)
        assertFailsWith<CryptoException> {
            VaultManager(sodium, remote).unlock("definitely not the passphrase")
        }
        assertEquals(
            created.vaultKey.toList(),
            VaultManager(sodium, remote).unlock(passphrase).toList(),
            "the right passphrase must still work afterwards",
        )
    }

    @Test
    fun `an account with no vault says so instead of failing obscurely`() = runBlocking<Unit> {
        val remote = FakeVaultRemote()
        val manager = VaultManager(sodium, remote)
        assertFalse(manager.hasVault())
        assertFailsWith<VaultException> { manager.unlock(passphrase) }
        assertFailsWith<VaultException> { manager.recover("ABCD-EFGH-IJKL-MNOP-QRST-UVWX") }
    }
}
