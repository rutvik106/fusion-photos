package com.fusionphotos.crypto

import com.google.gson.JsonParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the SHARED vector suite ([CryptoVectorChecks]) against the shared file, exactly as
 * the Android app does on device — one implementation of the checks, two platforms.
 *
 * Run: cd android && ./gradlew :crypto:test
 */
class CryptoVectorsTest {
    // The app does exactly this with SodiumAndroid; here SodiumJava drives the same adapter.
    private val sodium: Sodium = LibSodiumAdapter(com.goterl.lazysodium.SodiumJava())

    private fun vectorsPath(): String =
        System.getProperty("fusion.cryptoVectors")
            ?: error("fusion.cryptoVectors system property not set (see crypto/build.gradle.kts)")

    private fun parseVectors(): CryptoVectors {
        val root = JsonParser.parseString(File(vectorsPath()).readText()).asJsonObject
        fun i(section: String, key: String) = root.getAsJsonObject(section).get(key).asInt
        fun s(section: String, key: String) = root.getAsJsonObject(section).get(key).asString
        return CryptoVectors(
            pwhashSaltBytes = i("constants", "pwhashSaltBytes"),
            aeadNonceBytes = i("constants", "aeadNonceBytes"),
            aeadKeyBytes = i("constants", "aeadKeyBytes"),
            streamHeaderBytes = i("constants", "streamHeaderBytes"),
            streamTagFinal = i("constants", "streamTagFinal"),
            passphrase = s("argon2id", "passphrase"),
            kdfSaltB64 = s("argon2id", "saltB64"),
            kekB64 = s("argon2id", "kekB64"),
            vaultKeyB64 = s("vaultKeyWrap", "vaultKeyB64"),
            vaultNonceB64 = s("vaultKeyWrap", "nonceB64"),
            vaultWrappedB64 = s("vaultKeyWrap", "wrappedB64"),
            assetKeyB64 = s("assetKeyWrap", "assetKeyB64"),
            assetVaultKeyB64 = s("assetKeyWrap", "vaultKeyB64"),
            assetEnvelopeB64 = s("assetKeyWrap", "envelopeB64"),
            recoveryKeyB64 = s("recoveryWrap", "recoveryKeyB64"),
            recoveryEnvelopeB64 = s("recoveryWrap", "envelopeB64"),
            plaintextB64 = s("payload", "plaintextB64"),
            headerB64 = s("payload", "headerB64"),
            blobB64 = s("payload", "blobB64"),
            wireB64 = s("payload", "wireB64"),
        )
    }

    @Test
    fun `the Kotlin client matches every shared crypto vector`() {
        val results = CryptoVectorChecks.run(sodium, parseVectors())
        results.forEach {
            val mark = if (it.passed) "PASS" else "FAIL"
            println("  $mark  ${it.name}${if (it.passed) "" else " -- ${it.detail}"}")
        }
        // Pinned so a silently-skipped check fails the build instead of shrinking the suite.
        assertEquals(22, results.size, "expected the full vector suite to run")
        assertEquals(
            emptyList(),
            results.filterNot { it.passed }.map { "${it.name}: ${it.detail}" },
            "crypto vector failures",
        )
    }

    @Test
    fun `the vector file is the shared cross-client contract`() {
        val root = JsonParser.parseString(File(vectorsPath()).readText()).asJsonObject
        assertEquals("fusion-photos-crypto-vectors", root.get("format").asString)
        assertEquals(1, root.get("version").asInt)
        assertTrue(root.getAsJsonObject("constants").get("streamHeaderBytes").asInt == 24)
    }
}
