package com.fusionphotos.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/** A non-2xx control-plane response, carrying the server's error code. */
class ApiException(val status: Int, val code: String, message: String) : Exception(message)

/** The vault half of the control plane, so the vault logic is testable without HTTP. */
interface VaultRemote {
    suspend fun getVault(): Dtos.VaultBlob?
    suspend fun putVault(blob: Dtos.VaultBlob)
}

/**
 * The control-plane client.
 *
 * Media bytes never pass through the API: images are PUT/GET directly to S3 with short-lived
 * presigned URLs, exactly as the web client does. This client only speaks the small JSON
 * control messages (auth, vault, upload sessions, sync).
 *
 * Base URL and token are providers so the session store can change them at runtime.
 */
class ApiClient(
    private val baseUrlProvider: () -> String,
    private val tokenProvider: () -> String?,
) : VaultRemote {
    suspend fun requestOtp(email: String): Dtos.OtpRequest =
        Dtos.parseOtpRequest(send("/auth/otp/request", "POST", JSONObject().put("email", email)))

    suspend fun verifyOtp(email: String, code: String): Dtos.OtpVerify =
        Dtos.parseOtpVerify(
            send("/auth/otp/verify", "POST", JSONObject().put("email", email).put("code", code)),
        )

    suspend fun me(): Dtos.Me = Dtos.parseMe(send("/me", "GET", null))

    override suspend fun getVault(): Dtos.VaultBlob? = Dtos.parseVaultResponse(send("/vault", "GET", null))

    override suspend fun putVault(blob: Dtos.VaultBlob) {
        send("/vault", "PUT", Dtos.vaultPutBody(blob))
    }

    private suspend fun send(path: String, method: String, body: JSONObject?): JSONObject =
        withContext(Dispatchers.IO) {
            val url = URL(baseUrlProvider().trimEnd('/') + "/v1" + path)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                setRequestProperty("Content-Type", "application/json")
                tokenProvider()?.let { setRequestProperty("Authorization", "Bearer $it") }
                connectTimeout = 15_000
                readTimeout = 60_000
                if (body != null) doOutput = true
            }
            try {
                if (body != null) {
                    conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
                val status = conn.responseCode
                val text = (if (status in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
                if (status !in 200..299) {
                    val err = runCatching { JSONObject(text) }.getOrNull()
                    throw ApiException(
                        status,
                        err?.optString("code")?.ifBlank { null } ?: "unknown",
                        err?.optString("error")?.ifBlank { null } ?: "HTTP $status",
                    )
                }
                if (text.isBlank()) JSONObject() else JSONObject(text)
            } finally {
                conn.disconnect()
            }
        }
}
