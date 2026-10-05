package com.fusionphotos.app.upload

import org.json.JSONObject

/**
 * The per-asset metadata the client records — **inside the ciphertext**.
 *
 * This mirrors the web client's `AssetManifest` (`web/src/lib/upload.ts`) because the manifest
 * is encrypted client-side and later read by *any* of the owner's clients: a manifest written
 * on Android has to be understood by the web gallery, and vice versa. Fields the Android
 * client does not know yet (EXIF geometry, derivatives) are written as explicit `null` /
 * `false` rather than omitted, so a reader can tell "absent" from "not captured here".
 */
data class UploadManifest(
    val fileName: String,
    val mimeType: String,
    /** ISO-8601 instant, or null when the source gave no usable date. */
    val capturedAtIso: String?,
    val capturedTzOffsetSeconds: Int?,
    val width: Int?,
    val height: Int?,
    val originalSha256: String,
    val originalSize: Long,
    val clientAssetRef: String,
    val previewAvailable: Boolean,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("fileName", fileName)
        .put("mimeType", mimeType)
        .put("capturedAt", capturedAtIso ?: JSONObject.NULL)
        .put("capturedTzOffsetSeconds", capturedTzOffsetSeconds ?: JSONObject.NULL)
        .put("latitude", JSONObject.NULL)
        .put("longitude", JSONObject.NULL)
        .put("width", width ?: JSONObject.NULL)
        .put("height", height ?: JSONObject.NULL)
        .put("previewAvailable", previewAvailable)
        .put("quality", "original")
        .put("originalSha256", originalSha256)
        .put("originalSize", originalSize)
        .put("clientAssetRef", clientAssetRef)
        .put("crypto", JSONObject().put("payload", PAYLOAD_NAME).put("v", 1))
        .put("source", "upload")

    companion object {
        /** The wire name the web client writes; readers switch on this string. */
        const val PAYLOAD_NAME = "secretstream-xchacha20poly1305"
    }
}
