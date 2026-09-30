package com.fusionphotos.crypto

/**
 * The libsodium operations the E2EE format needs.
 *
 * Deliberately tiny: the Android app adapts `lazysodium-android` and the JVM tests adapt
 * `lazysodium-java`, so the format logic in [Crypto] is identical on both and can be
 * checked against `shared/crypto-vectors.json` without an emulator.
 *
 * Per the project plan we must not invent nonce management or our own cipher, so every
 * primitive here maps 1:1 onto a libsodium function.
 */
interface Sodium {
    /** Argon2id at libsodium's MODERATE limits, returning a 32-byte key. */
    fun pwhashKey(passphrase: String, salt: ByteArray, keyLen: Int): ByteArray

    /** XChaCha20-Poly1305 IETF AEAD. Returns ciphertext||tag (never the nonce). */
    fun aeadEncrypt(plaintext: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray

    /** Returns null when authentication fails — callers must fail closed. */
    fun aeadDecrypt(ciphertext: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray?

    /** secretstream, single FINAL frame. Returns header(24) || ciphertext. */
    fun streamEncrypt(plaintext: ByteArray, key: ByteArray): ByteArray

    /** Returns null when authentication fails or the blob is too short. */
    fun streamDecrypt(wire: ByteArray, key: ByteArray): ByteArray?

    fun randomBytes(n: Int): ByteArray
}
