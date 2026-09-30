package com.fusionphotos.crypto

import com.goterl.lazysodium.Sodium as LibSodium
import com.goterl.lazysodium.interfaces.PwHash
import com.goterl.lazysodium.interfaces.SecretStream
import com.sun.jna.NativeLong

/**
 * The one libsodium adapter, shared by every platform.
 *
 * lazysodium exposes the same `com.goterl.lazysodium.Sodium` API on both artifacts, so the
 * Android app passes `SodiumAndroid` and the JVM tests pass `SodiumJava` — identical code,
 * identical libsodium functions, no hand-rolled nonce management. The dependency is
 * `compileOnly` (see build.gradle.kts): each platform provides its own implementation.
 */
class LibSodiumAdapter(private val sodium: LibSodium) : Sodium {

    init {
        check(sodium.sodium_init() >= 0) { "libsodium init failed" }
    }

    override fun pwhashKey(passphrase: String, salt: ByteArray, keyLen: Int): ByteArray {
        val pass = passphrase.toByteArray(Charsets.UTF_8)
        val key = ByteArray(keyLen)
        val rc = sodium.crypto_pwhash(
            key, key.size.toLong(),
            pass, pass.size.toLong(),
            salt,
            PwHash.OPSLIMIT_MODERATE,
            NativeLong(PwHash.ARGON2ID_MEMLIMIT_MODERATE.toLong()),
            ALG_ARGON2ID13,
        )
        check(rc == 0) { "argon2id derivation failed (rc=$rc)" }
        return key
    }

    override fun aeadEncrypt(plaintext: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray {
        val out = ByteArray(plaintext.size + AEAD_ABYTES)
        val outLen = longArrayOf(0)
        val rc = sodium.crypto_aead_xchacha20poly1305_ietf_encrypt(
            out, outLen, plaintext, plaintext.size.toLong(), null, 0L, null, nonce, key,
        )
        check(rc == 0) { "aead encrypt failed (rc=$rc)" }
        return out
    }

    override fun aeadDecrypt(ciphertext: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray? {
        val out = ByteArray(ciphertext.size)
        val outLen = longArrayOf(0)
        val rc = sodium.crypto_aead_xchacha20poly1305_ietf_decrypt(
            out, outLen, null, ciphertext, ciphertext.size.toLong(), null, 0L, nonce, key,
        )
        if (rc != 0) return null // authentication failure — callers fail closed
        return out.copyOfRange(0, outLen[0].toInt())
    }

    override fun streamEncrypt(plaintext: ByteArray, key: ByteArray): ByteArray {
        val state = SecretStream.State()
        val header = ByteArray(HEADERBYTES)
        check(sodium.crypto_secretstream_xchacha20poly1305_init_push(state, header, key) == 0) {
            "secretstream init_push failed"
        }
        val ct = ByteArray(plaintext.size + STREAM_ABYTES)
        val ctLen = longArrayOf(0)
        check(
            sodium.crypto_secretstream_xchacha20poly1305_push(
                state, ct, ctLen, plaintext, plaintext.size.toLong(), null, 0L, TAG_FINAL,
            ) == 0,
        ) { "secretstream push failed" }
        return header + ct.copyOfRange(0, ctLen[0].toInt())
    }

    override fun streamDecrypt(wire: ByteArray, key: ByteArray): ByteArray? {
        if (wire.size <= HEADERBYTES) return null
        val state = SecretStream.State()
        val header = wire.copyOfRange(0, HEADERBYTES)
        if (sodium.crypto_secretstream_xchacha20poly1305_init_pull(state, header, key) != 0) return null
        val ct = wire.copyOfRange(HEADERBYTES, wire.size)
        val out = ByteArray(ct.size)
        val outLen = longArrayOf(0)
        val tag = byteArrayOf(0)
        val rc = sodium.crypto_secretstream_xchacha20poly1305_pull(
            state, out, outLen, tag, ct, ct.size.toLong(), null, 0L,
        )
        if (rc != 0) return null
        return out.copyOfRange(0, outLen[0].toInt())
    }

    override fun randomBytes(n: Int): ByteArray = ByteArray(n).also { sodium.randombytes_buf(it, n) }

    private companion object {
        /** crypto_pwhash_ALG_ARGON2ID13 — pinned by shared/crypto-vectors.json. */
        const val ALG_ARGON2ID13 = 2
        const val AEAD_ABYTES = 16
        /** crypto_secretstream_xchacha20poly1305_abytes() = tag(16) + padding(1). */
        const val STREAM_ABYTES = 17
        val HEADERBYTES = SecretStream.HEADERBYTES
        val TAG_FINAL = SecretStream.XCHACHA20POLY1305_TAG_FINAL
    }
}
