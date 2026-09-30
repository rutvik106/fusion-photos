package com.fusionphotos.crypto

/**
 * Everything `shared/crypto-vectors.json` pins. Each platform parses the file with
 * whatever JSON library it already has (Android: org.json, JVM tests: Gson), then runs
 * [CryptoVectorChecks] — so the Android app and the JVM tests assert exactly the same
 * things against the same file.
 */
data class CryptoVectors(
    val pwhashSaltBytes: Int,
    val aeadNonceBytes: Int,
    val aeadKeyBytes: Int,
    val streamHeaderBytes: Int,
    val streamTagFinal: Int,
    val passphrase: String,
    val kdfSaltB64: String,
    val kekB64: String,
    val vaultKeyB64: String,
    val vaultNonceB64: String,
    val vaultWrappedB64: String,
    val assetKeyB64: String,
    val assetVaultKeyB64: String,
    val assetEnvelopeB64: String,
    val recoveryKeyB64: String,
    val recoveryEnvelopeB64: String,
    val plaintextB64: String,
    val headerB64: String,
    val blobB64: String,
    val wireB64: String,
)

/** One named result of the shared vector suite. */
data class VectorCheck(val name: String, val passed: Boolean, val detail: String = "")

/**
 * The shared vector suite — the cross-client contract in executable form.
 *
 * It asserts BOTH directions: that this client can read bytes produced by the web client,
 * and that its own output is byte-identical for a fixed nonce. The encrypt direction is
 * the one that would otherwise silently corrupt uploads on the other platform.
 */
object CryptoVectorChecks {
    fun run(sodium: Sodium, v: CryptoVectors): List<VectorCheck> {
        val out = mutableListOf<VectorCheck>()

        fun expect(name: String, expected: Any?, actual: Any?) {
            val ok = expected == actual
            out += VectorCheck(name, ok, if (ok) "" else "expected $expected, got $actual")
        }

        fun expectThrows(name: String, block: () -> Unit) {
            val r = runCatching(block)
            out += VectorCheck(name, r.isFailure, if (r.isFailure) "" else "expected a failure but it succeeded")
        }

        // ---- constants ----
        expect("salt bytes = 16", v.pwhashSaltBytes, Crypto.KDF_SALT_BYTES)
        expect("aead nonce = 24 bytes", v.aeadNonceBytes, Crypto.AEAD_NONCE_BYTES)
        expect("aead key = 32 bytes", v.aeadKeyBytes, Crypto.VAULT_KEY_BYTES)
        expect("stream header = 24 bytes", v.streamHeaderBytes, Crypto.STREAM_HEADER_BYTES)
        expect("stream TAG_FINAL id = 3", v.streamTagFinal, 3)

        // ---- Argon2id -> KEK (must match the web client's derivation exactly) ----
        val kek = Crypto.deriveKek(sodium, v.passphrase, Crypto.unb64(v.kdfSaltB64))
        expect("argon2id derives the pinned KEK", v.kekB64, Crypto.b64(kek))

        // ---- vault key: unwrap the pinned envelope, then re-wrap identically ----
        val vaultBlob = Crypto.unb64(v.vaultWrappedB64)
        val vaultNonce = vaultBlob.copyOfRange(0, Crypto.AEAD_NONCE_BYTES)
        val vaultCt = vaultBlob.copyOfRange(Crypto.AEAD_NONCE_BYTES, vaultBlob.size)
        val unwrapped = sodium.aeadDecrypt(vaultCt, vaultNonce, kek)
        expect("vault key unwraps with the derived KEK", v.vaultKeyB64, unwrapped?.let { Crypto.b64(it) })
        val rewrapped = sodium.aeadEncrypt(
            Crypto.unb64(v.vaultKeyB64),
            Crypto.unb64(v.vaultNonceB64),
            kek,
        )
        expect(
            "aead encrypt reproduces the pinned vault wrap byte-for-byte",
            v.vaultWrappedB64,
            Crypto.b64(Crypto.unb64(v.vaultNonceB64) + rewrapped),
        )

        // ---- asset key envelope ----
        expect("asset envelope is nonce(24) + key(32) + tag(16)", 24 + 32 + 16, Crypto.unb64(v.assetEnvelopeB64).size)
        val assetKey = runCatching { Crypto.unwrapAssetKey(sodium, v.assetEnvelopeB64, Crypto.unb64(v.assetVaultKeyB64)) }
        expect("asset key unwraps under the vault key", v.assetKeyB64, assetKey.getOrNull()?.let { Crypto.b64(it) })
        val freshAssetKey = Crypto.newAssetKey(sodium)
        val freshEnvelope = Crypto.wrapAssetKey(sodium, freshAssetKey, Crypto.unb64(v.assetVaultKeyB64))
        expect(
            "asset key wrap then unwrap round-trips",
            Crypto.b64(freshAssetKey),
            Crypto.b64(Crypto.unwrapAssetKey(sodium, freshEnvelope, Crypto.unb64(v.assetVaultKeyB64))),
        )

        // ---- recovery key (no KDF: the key is already high entropy) ----
        val recovered = runCatching {
            Crypto.unwrapVaultWithRecovery(
                sodium,
                WrappedVault(kdfSalt = "", wrapped = v.recoveryEnvelopeB64, v = 1),
                Crypto.unb64(v.recoveryKeyB64),
            )
        }
        expect("recovery key unwraps the pinned vault key", v.vaultKeyB64, recovered.getOrNull()?.let { Crypto.b64(it) })
        val display = Crypto.newRecoveryKey(sodium)
        val groups = display.split("-")
        expect("recovery key display groups are <= 8 chars", true, groups.all { it.length in 1..8 })
        expect("recovery key display is base64(32 bytes) = 44 chars", 44, groups.joinToString("").length)
        expect(
            "recovery key parses to the same key grouped or compact",
            Crypto.b64(Crypto.parseRecoveryKey(display)),
            Crypto.b64(Crypto.parseRecoveryKey(display.replace("-", ""))),
        )

        // ---- resource payloads + manifest envelope ----
        val payload = runCatching {
            Crypto.decryptPayloadBytes(sodium, Crypto.unb64(v.wireB64), Crypto.unb64(v.assetKeyB64))
        }
        expect("resource payload decrypts to the pinned plaintext", v.plaintextB64, payload.getOrNull()?.let { Crypto.b64(it) })
        expect(
            "resource wire = header || frames",
            Crypto.STREAM_HEADER_BYTES + Crypto.unb64(v.blobB64).size,
            Crypto.unb64(v.wireB64).size,
        )
        val manifest = runCatching {
            Crypto.decryptPayload(sodium, EncryptedPayload(v.headerB64, v.blobB64), Crypto.unb64(v.assetKeyB64))
        }
        expect("manifest envelope decrypts to the same plaintext", v.plaintextB64, manifest.getOrNull()?.let { Crypto.b64(it) })
        val roundTrip = runCatching {
            val wire = Crypto.encryptPayloadBytes(
                sodium,
                Crypto.unb64(v.plaintextB64),
                Crypto.unb64(v.assetKeyB64),
            )
            Crypto.b64(Crypto.decryptPayloadBytes(sodium, wire, Crypto.unb64(v.assetKeyB64)))
        }
        expect("payload encrypt then decrypt round-trips", v.plaintextB64, roundTrip.getOrNull())

        // ---- fail-closed behaviour ----
        expect(
            "a wrong key is rejected (stream returns null)",
            true,
            sodium.streamDecrypt(Crypto.unb64(v.wireB64), Crypto.unb64(v.vaultKeyB64)) == null,
        )
        expectThrows("a wrong key is rejected (payload throws)") {
            Crypto.decryptPayloadBytes(sodium, Crypto.unb64(v.wireB64), Crypto.unb64(v.vaultKeyB64))
        }
        expectThrows("a wrong passphrase is rejected") {
            Crypto.unwrapVaultKey(sodium, WrappedVault(v.kdfSaltB64, v.vaultWrappedB64, 1), "the wrong passphrase")
        }

        return out
    }
}
