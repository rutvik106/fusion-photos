package com.fusionphotos.app.net

import com.fusionphotos.crypto.WrappedVault
import org.json.JSONObject

/**
 * Wire mapping for the control plane, kept separate from the HTTP plumbing so the shapes
 * are unit-testable on the host (`:app:testDebugUnitTest`) with the real org.json.
 *
 * These mirror the Go API and the web client's `web/src/lib/api.ts` one-for-one.
 */
object Dtos {

    /** `POST /v1/auth/otp/request` — devCode is only present when the server is in dev mode. */
    data class OtpRequest(val ok: Boolean, val devCode: String?)

    /** `POST /v1/auth/otp/verify` */
    data class OtpVerify(val sessionToken: String, val userId: String, val hasVault: Boolean)

    /** `GET /v1/me` — which account a session belongs to (used to scope local state). */
    data class Me(val userId: String, val email: String)

    /**
     * The stored vault blob. The server treats it as opaque JSON.
     *
     * Two shapes exist: the current structured form (`passphrase` + optional `recovery`),
     * and the legacy flat form where the passphrase envelope sits at the top level. Only the
     * envelope the client needs is exposed, so callers cannot accidentally depend on a shape
     * that the server does not guarantee.
     */
    data class VaultBlob(val passphrase: WrappedVault?, val recovery: WrappedVault?) {
        companion object {
            const val CRYPTO_VERSION = 1
        }
    }

    fun parseOtpRequest(json: JSONObject) =
        OtpRequest(ok = json.optBoolean("ok", false), devCode = json.optString("devCode").ifBlank { null })

    fun parseOtpVerify(json: JSONObject) = OtpVerify(
        sessionToken = json.getString("sessionToken"),
        userId = json.getString("userId"),
        hasVault = json.optBoolean("hasVault", false),
    )

    fun parseMe(json: JSONObject) = Me(json.getString("userId"), json.getString("email"))

    /** `GET /v1/vault` returns `{wrapped: {...} | null}`. */
    fun parseVaultResponse(json: JSONObject): VaultBlob? {
        if (json.isNull("wrapped")) return null
        val wrapped = json.optJSONObject("wrapped") ?: return null
        return parseVaultBlob(wrapped)
    }

    /** Accepts both the structured blob and the legacy flat envelope. */
    fun parseVaultBlob(json: JSONObject): VaultBlob {
        val passphrase = json.optJSONObject("passphrase")?.let(::parseEnvelope)
            ?: parseEnvelope(json) // legacy flat form: kdfSalt/wrapped at the top level
        val recovery = json.optJSONObject("recovery")?.let(::parseEnvelope)
        return VaultBlob(passphrase = passphrase, recovery = recovery)
    }

    private fun parseEnvelope(json: JSONObject): WrappedVault? {
        val wrapped = json.optString("wrapped")
        if (wrapped.isBlank()) return null
        return WrappedVault(
            kdfSalt = json.optString("kdfSalt"),
            wrapped = wrapped,
            v = json.optInt("v", VaultBlob.CRYPTO_VERSION),
        )
    }

    /** The JSON `PUT /v1/vault` expects: `{wrapped: {passphrase, recovery?}}`. */
    fun vaultPutBody(blob: VaultBlob): JSONObject {
        val wrapped = JSONObject()
        blob.passphrase?.let { wrapped.put("passphrase", envelopeJson(it)) }
        blob.recovery?.let { wrapped.put("recovery", envelopeJson(it)) }
        return JSONObject().put("wrapped", wrapped)
    }

    private fun envelopeJson(envelope: WrappedVault) = JSONObject()
        .put("kdfSalt", envelope.kdfSalt)
        .put("wrapped", envelope.wrapped)
        .put("v", envelope.v)
}
