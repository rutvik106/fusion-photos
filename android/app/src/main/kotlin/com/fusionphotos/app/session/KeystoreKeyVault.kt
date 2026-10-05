package com.fusionphotos.app.session

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [KeyVault] backed by the Android Keystore.
 *
 * The wrapping key is generated in the Keystore and never leaves it; only AES-GCM ciphertext
 * (plus its IV) is written to private storage. No user-authentication requirement is set,
 * because the whole purpose is to let WorkManager decrypt while the device is locked — see the
 * trade-off documented on [KeyVault].
 */
class KeystoreKeyVault(context: Context) : KeyVault {
    private val prefs = context.getSharedPreferences("fusion.keystore", Context.MODE_PRIVATE)

    override fun isUsable(): Boolean = runCatching { wrappingKey().algorithm.isNotEmpty() }.getOrDefault(false)

    override fun save(vaultKey: ByteArray): Boolean = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val ciphertext = cipher.doFinal(vaultKey)
        prefs.edit()
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(KEY_WRAPPED, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .commit()
    }.onFailure { Log.w(TAG, "could not enrol the vault key in the Keystore", it) }.isSuccess

    override fun load(): ByteArray? {
        val iv = prefs.getString(KEY_IV, null) ?: return null
        val wrapped = prefs.getString(KEY_WRAPPED, null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                wrappingKey(),
                GCMParameterSpec(GCM_TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)),
            )
            cipher.doFinal(Base64.decode(wrapped, Base64.NO_WRAP))
        }.onFailure {
            // Key invalidated (e.g. the Keystore was reset) or the blob was tampered with:
            // drop it rather than leave a record that can never open.
            Log.w(TAG, "stored vault key is no longer usable; clearing it", it)
            clear()
        }.getOrNull()
    }

    override fun clear() {
        prefs.edit().remove(KEY_IV).remove(KEY_WRAPPED).commit()
    }

    private fun wrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Deliberately NOT setUserAuthenticationRequired(true): a background worker
                // must be able to unwrap this while the phone sits locked in a pocket.
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val TAG = "KeystoreKeyVault"
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "fusion-photos.vault-key.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val KEY_IV = "vaultKeyIv"
        const val KEY_WRAPPED = "vaultKeyWrapped"
    }
}
