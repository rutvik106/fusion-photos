package com.fusionphotos.app

import android.content.Context
import com.fusionphotos.crypto.CryptoVectors
import org.json.JSONObject

/**
 * Reads the shared cross-client vector file from assets using Android's built-in org.json.
 * The bundled asset is the SAME `shared/crypto-vectors.json` the web client and the JVM
 * tests are verified against (see app/build.gradle.kts: the assets dir points at shared/).
 */
object VectorsJson {
    fun load(context: Context): CryptoVectors {
        val text = context.assets.open("crypto-vectors.json").bufferedReader().use { it.readText() }
        val root = JSONObject(text)
        require(root.getString("format") == "fusion-photos-crypto-vectors") {
            "unexpected crypto vectors asset"
        }
        val c = root.getJSONObject("constants")
        val a = root.getJSONObject("argon2id")
        val vw = root.getJSONObject("vaultKeyWrap")
        val aw = root.getJSONObject("assetKeyWrap")
        val rw = root.getJSONObject("recoveryWrap")
        val p = root.getJSONObject("payload")
        return CryptoVectors(
            pwhashSaltBytes = c.getInt("pwhashSaltBytes"),
            aeadNonceBytes = c.getInt("aeadNonceBytes"),
            aeadKeyBytes = c.getInt("aeadKeyBytes"),
            streamHeaderBytes = c.getInt("streamHeaderBytes"),
            streamTagFinal = c.getInt("streamTagFinal"),
            passphrase = a.getString("passphrase"),
            kdfSaltB64 = a.getString("saltB64"),
            kekB64 = a.getString("kekB64"),
            vaultKeyB64 = vw.getString("vaultKeyB64"),
            vaultNonceB64 = vw.getString("nonceB64"),
            vaultWrappedB64 = vw.getString("wrappedB64"),
            assetKeyB64 = aw.getString("assetKeyB64"),
            assetVaultKeyB64 = aw.getString("vaultKeyB64"),
            assetEnvelopeB64 = aw.getString("envelopeB64"),
            recoveryKeyB64 = rw.getString("recoveryKeyB64"),
            recoveryEnvelopeB64 = rw.getString("envelopeB64"),
            plaintextB64 = p.getString("plaintextB64"),
            headerB64 = p.getString("headerB64"),
            blobB64 = p.getString("blobB64"),
            wireB64 = p.getString("wireB64"),
        )
    }
}
