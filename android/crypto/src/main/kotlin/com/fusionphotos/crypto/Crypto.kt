package com.fusionphotos.crypto

import java.util.Base64

/** Raised for a malformed envelope or a failed authentication — never swallowed. */
class CryptoException(message: String) : Exception(message)

/** Key-wrapping envelope (structured form stored server-side as JSON). */
data class WrappedVault(val kdfSalt: String, val wrapped: String, val v: Int)

/** Manifest envelope: the stream header and frames as separate base64 fields. */
data class EncryptedPayload(val headerB64: String, val blobB64: String)

/**
 * The shared E2EE wire format — byte-for-byte the same as the web client's
 * `src/lib/crypto.ts`, pinned by `shared/crypto-vectors.json`:
 *
 *  - vault key: 32 random bytes, wrapped with a KEK derived from the passphrase by
 *    Argon2id (MODERATE). `wrapped = base64(salt is separate; nonce(24) || ct||tag)`.
 *  - asset key: 32 random bytes, wrapped under the vault key with XChaCha20-Poly1305;
 *    the envelope is `base64(nonce(24) || ct||tag)` (96 base64 chars).
 *  - resource objects: `header(24) || secretstream frames` (one FINAL frame today).
 *  - encrypted manifest: the same stream, but header and ciphertext are stored as two
 *    base64 fields so neither can be dropped.
 */
object Crypto {
    const val VAULT_KEY_BYTES = 32
    const val ASSET_KEY_BYTES = 32
    const val KDF_SALT_BYTES = 16
    const val AEAD_NONCE_BYTES = 24
    const val STREAM_HEADER_BYTES = 24

    fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    fun unb64(text: String): ByteArray = Base64.getDecoder().decode(text)

    private fun failed(): Nothing =
        throw CryptoException("payload decryption failed (corrupt ciphertext or wrong key)")

    // ---- vault key ----

    fun deriveKek(sodium: Sodium, passphrase: String, salt: ByteArray): ByteArray =
        sodium.pwhashKey(passphrase, salt, VAULT_KEY_BYTES)

    fun newVaultKey(sodium: Sodium): ByteArray = sodium.randomBytes(VAULT_KEY_BYTES)

    fun wrapVaultKey(sodium: Sodium, vaultKey: ByteArray, passphrase: String): WrappedVault {
        val salt = sodium.randomBytes(KDF_SALT_BYTES)
        val kek = deriveKek(sodium, passphrase, salt)
        val nonce = sodium.randomBytes(AEAD_NONCE_BYTES)
        val ct = sodium.aeadEncrypt(vaultKey, nonce, kek)
        return WrappedVault(kdfSalt = b64(salt), wrapped = b64(nonce + ct), v = 1)
    }

    fun unwrapVaultKey(sodium: Sodium, envelope: WrappedVault, passphrase: String): ByteArray {
        val salt = unb64(envelope.kdfSalt)
        val kek = deriveKek(sodium, passphrase, salt)
        return splitOpen(sodium, envelope.wrapped, kek) ?: failed()
    }

    // ---- recovery key (no KDF: the key is already high entropy) ----

    fun newRecoveryKey(sodium: Sodium): String =
        b64(sodium.randomBytes(ASSET_KEY_BYTES)).chunked(8).joinToString("-")

    fun parseRecoveryKey(display: String): ByteArray {
        val raw = unb64(display.replace(Regex("[\\s-]"), ""))
        if (raw.size != ASSET_KEY_BYTES) throw CryptoException("Recovery key looks wrong (bad length)")
        return raw
    }

    fun wrapVaultWithRecovery(
        sodium: Sodium,
        vaultKey: ByteArray,
        recoveryKey: ByteArray,
    ): WrappedVault {
        val nonce = sodium.randomBytes(AEAD_NONCE_BYTES)
        val ct = sodium.aeadEncrypt(vaultKey, nonce, recoveryKey)
        return WrappedVault(kdfSalt = "", wrapped = b64(nonce + ct), v = 1)
    }

    fun unwrapVaultWithRecovery(
        sodium: Sodium,
        envelope: WrappedVault,
        recoveryKey: ByteArray,
    ): ByteArray = splitOpen(sodium, envelope.wrapped, recoveryKey) ?: failed()

    // ---- asset key ----

    fun newAssetKey(sodium: Sodium): ByteArray = sodium.randomBytes(ASSET_KEY_BYTES)

    fun wrapAssetKey(sodium: Sodium, assetKey: ByteArray, vaultKey: ByteArray): String {
        val nonce = sodium.randomBytes(AEAD_NONCE_BYTES)
        return b64(nonce + sodium.aeadEncrypt(assetKey, nonce, vaultKey))
    }

    fun unwrapAssetKey(sodium: Sodium, envelopeB64: String, vaultKey: ByteArray): ByteArray =
        splitOpen(sodium, envelopeB64, vaultKey) ?: failed()

    // ---- resource payloads ----

    fun encryptPayloadBytes(sodium: Sodium, plaintext: ByteArray, key: ByteArray): ByteArray =
        sodium.streamEncrypt(plaintext, key)

    fun decryptPayloadBytes(sodium: Sodium, wire: ByteArray, key: ByteArray): ByteArray =
        sodium.streamDecrypt(wire, key) ?: failed()

    fun encryptPayload(sodium: Sodium, plaintext: ByteArray, key: ByteArray): EncryptedPayload {
        val wire = sodium.streamEncrypt(plaintext, key)
        return EncryptedPayload(
            headerB64 = b64(wire.copyOfRange(0, STREAM_HEADER_BYTES)),
            blobB64 = b64(wire.copyOfRange(STREAM_HEADER_BYTES, wire.size)),
        )
    }

    fun decryptPayload(sodium: Sodium, payload: EncryptedPayload, key: ByteArray): ByteArray =
        decryptPayloadBytes(sodium, unb64(payload.headerB64) + unb64(payload.blobB64), key)

    /** Open a `base64(nonce || ct||tag)` envelope; null means authentication failed. */
    private fun splitOpen(sodium: Sodium, envelopeB64: String, key: ByteArray): ByteArray? {
        val blob = unb64(envelopeB64)
        if (blob.size <= AEAD_NONCE_BYTES) return null
        val nonce = blob.copyOfRange(0, AEAD_NONCE_BYTES)
        val ct = blob.copyOfRange(AEAD_NONCE_BYTES, blob.size)
        return sodium.aeadDecrypt(ct, nonce, key)
    }
}
