package com.fusionphotos.app.net

import com.fusionphotos.app.upload.ObjectTransfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * PUTs ciphertext straight to S3 with a presigned URL — the media path, deliberately separate
 * from the JSON control plane.
 *
 * Nothing beyond the body is sent: a presigned URL is signed over a specific set of headers, so
 * adding one (even `Content-Type`) invalidates the signature. The payload is already
 * ciphertext, so there is nothing to negotiate.
 */
object S3Transfer : ObjectTransfer {
    override suspend fun put(url: String, bytes: ByteArray): Int = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 120_000
            setFixedLengthStreamingMode(bytes.size)
        }
        try {
            conn.outputStream.use { it.write(bytes) }
            val status = conn.responseCode
            // Drain whatever came back so the connection closes cleanly (and an error body
            // cannot be mistaken for success).
            (if (status in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes() }
            status
        } finally {
            conn.disconnect()
        }
    }
}
