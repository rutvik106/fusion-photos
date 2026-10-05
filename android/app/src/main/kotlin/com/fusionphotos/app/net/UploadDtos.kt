package com.fusionphotos.app.net

import org.json.JSONArray
import org.json.JSONObject

/**
 * The upload half of the control plane, so the upload pipeline is testable without HTTP.
 */
interface UploadRemote {
    /** `POST /v1/upload-session` — reserve object keys and presigned PUT URLs. */
    suspend fun startSession(items: List<UploadDtos.UploadItemRequest>): List<UploadDtos.UploadItemPlan>

    /** `POST /v1/upload-session/complete` — commit the encrypted manifest + resource list. */
    suspend fun complete(items: List<UploadDtos.UploadCompletion>): List<UploadDtos.CommittedAsset>

    /** `POST …/multipart/init` — start a multipart transfer and get every part's URL. */
    suspend fun multipartInit(assetId: String, variant: String, partCount: Int): UploadDtos.MultipartInit

    /** `POST …/multipart/status` — which parts the server already holds (resume). */
    suspend fun multipartStatus(assetId: String, variant: String): UploadDtos.MultipartStatus

    /** `POST …/multipart/parts` — fresh URLs for specific part numbers. */
    suspend fun multipartPartUrls(assetId: String, variant: String, numbers: List<Int>): List<UploadDtos.PartUrl>

    /** `POST …/multipart/complete` — assemble the parts; returns the stored size. */
    suspend fun multipartComplete(assetId: String, variant: String, parts: List<UploadDtos.ClientPart>): Long

    /** `POST …/multipart/abort` — idempotent cleanup when a transfer is abandoned. */
    suspend fun multipartAbort(assetId: String, variant: String)
}

/**
 * Wire mapping for the upload flow, mirroring `api/cmd/server/handlers_upload.go` and
 * `handlers_sync.go`. The server validates declared sizes against what was reserved and
 * checks every object's real length in S3, so these shapes have to be exact — hence they are
 * unit-tested rather than trusted.
 *
 * Note what is deliberately absent: the encrypted manifest is **not** an S3 resource. It
 * travels in the completion body (it is a database column, not an object), and the server
 * refuses a `manifest` entry in the resource list.
 */
object UploadDtos {
    /** Variants that can be presigned as objects. `manifest` is not one of them. */
    val OBJECT_VARIANTS = setOf("original", "preview", "thumbnail", "original_motion")

    data class UploadItemRequest(
        val originalSize: Long,
        val encryptedSizes: Map<String, Long>,
        val assetIdClient: String,
    )

    data class UploadItemPlan(
        val assetIdClient: String,
        val assetId: String,
        val keys: Map<String, String>,
        val putUrls: Map<String, String>,
        val maxSizeBytes: Long,
        /** True when this client ref was already committed: nothing to upload. */
        val committed: Boolean,
    )

    data class ManifestBlob(val headerB64: String, val blobB64: String)

    data class ResourceRef(val variant: String, val objectKey: String, val ciphertextSize: Long)

    data class UploadCompletion(
        val assetId: String,
        val keyEnvelope: String,
        val manifest: ManifestBlob,
        val resources: List<ResourceRef>,
    )

    data class CommittedAsset(val assetId: String, val seq: Long)

    // ---- multipart transfer (large originals) ----

    /** One planned part: the exact byte range to send and the URL to send it to. */
    data class MultipartPart(val number: Int, val offset: Long, val length: Long, val url: String)

    data class MultipartInit(val size: Long, val parts: List<MultipartPart>)

    /** A part the *server* has seen in S3 (authoritative for resume). */
    data class ServerPart(val number: Int, val etag: String, val size: Long)

    data class MultipartStatus(val started: Boolean, val parts: List<ServerPart>)

    /** A freshly presigned part URL; [existingSize] non-null means the part is already there. */
    data class PartUrl(val number: Int, val url: String, val existingSize: Long?)

    /**
     * A part listed for completion. The server re-reads the ETag from S3 and validates the
     * part's real size, so this only has to name the parts, in order, exactly once.
     */
    data class ClientPart(val number: Int, val etag: String)

    fun multipartInitBody(variant: String, partCount: Int): JSONObject =
        JSONObject().put("variant", variant).put("partCount", partCount)

    fun parseMultipartInit(json: JSONObject): MultipartInit {
        val parts = json.optJSONArray("parts") ?: JSONArray()
        return MultipartInit(
            size = json.optLong("size", 0),
            parts = (0 until parts.length()).map { i ->
                val o = parts.getJSONObject(i)
                MultipartPart(
                    number = o.getInt("number"),
                    offset = o.getLong("offset"),
                    length = o.getLong("length"),
                    url = o.getString("url"),
                )
            },
        )
    }

    fun multipartVariantBody(variant: String): JSONObject = JSONObject().put("variant", variant)

    fun parseMultipartStatus(json: JSONObject): MultipartStatus {
        val parts = json.optJSONArray("parts") ?: JSONArray()
        return MultipartStatus(
            started = json.optBoolean("started", false),
            parts = (0 until parts.length()).map { i ->
                val o = parts.getJSONObject(i)
                ServerPart(number = o.getInt("number"), etag = o.optString("etag"), size = o.optLong("size", 0))
            },
        )
    }

    fun multipartPartsBody(variant: String, numbers: List<Int>): JSONObject {
        val array = JSONArray()
        numbers.forEach { array.put(it) }
        return JSONObject().put("variant", variant).put("numbers", array)
    }

    fun parsePartUrls(json: JSONObject): List<PartUrl> {
        val parts = json.optJSONArray("parts") ?: JSONArray()
        return (0 until parts.length()).map { i ->
            val o = parts.getJSONObject(i)
            PartUrl(
                number = o.getInt("number"),
                url = o.getString("url"),
                // The server omits `size` for a part it has not seen.
                existingSize = if (o.has("size") && !o.isNull("size")) o.getLong("size") else null,
            )
        }
    }

    fun multipartCompleteBody(variant: String, parts: List<ClientPart>): JSONObject {
        val array = JSONArray()
        parts.forEach {
            array.put(JSONObject().put("number", it.number).put("etag", it.etag))
        }
        return JSONObject().put("variant", variant).put("parts", array)
    }

    // ---- request/response bodies ----

    fun sessionBody(items: List<UploadItemRequest>): JSONObject {
        val array = JSONArray()
        for (item in items) {
            val sizes = JSONObject()
            for ((variant, size) in item.encryptedSizes) sizes.put(variant, size)
            array.put(
                JSONObject()
                    .put("originalSize", item.originalSize)
                    .put("encryptedSizes", sizes)
                    .put("assetIdClient", item.assetIdClient),
            )
        }
        return JSONObject().put("items", array)
    }

    fun parseSession(json: JSONObject): List<UploadItemPlan> {
        val items = json.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).map { i ->
            val o = items.getJSONObject(i)
            UploadItemPlan(
                assetIdClient = o.getString("assetIdClient"),
                assetId = o.getString("assetId"),
                keys = o.optJSONObject("keys").toStringMap(),
                putUrls = o.optJSONObject("putUrls").toStringMap(),
                maxSizeBytes = o.optLong("maxSizeBytes", 0),
                committed = o.optBoolean("committed", false),
            )
        }
    }

    fun completeBody(items: List<UploadCompletion>): JSONObject {
        val array = JSONArray()
        for (item in items) {
            val resources = JSONArray()
            for (r in item.resources) {
                resources.put(
                    JSONObject()
                        .put("variant", r.variant)
                        .put("objectKey", r.objectKey)
                        .put("ciphertextSize", r.ciphertextSize),
                )
            }
            array.put(
                JSONObject()
                    .put("assetId", item.assetId)
                    .put("keyEnvelope", item.keyEnvelope)
                    .put(
                        "manifest",
                        JSONObject()
                            .put("headerB64", item.manifest.headerB64)
                            .put("blobB64", item.manifest.blobB64),
                    )
                    .put("resources", resources),
            )
        }
        return JSONObject().put("items", array)
    }

    fun parseComplete(json: JSONObject): List<CommittedAsset> {
        val assets = json.optJSONArray("assets") ?: return emptyList()
        return (0 until assets.length()).map { i ->
            val o = assets.getJSONObject(i)
            CommittedAsset(assetId = o.getString("assetId"), seq = o.optLong("seq", 0))
        }
    }

    private fun JSONObject?.toStringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (key in keys()) out[key] = optString(key)
        return out
    }
}
