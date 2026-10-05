package com.fusionphotos.app.upload

import com.fusionphotos.app.media.DerivativeGenerator
import com.fusionphotos.app.media.Derivatives
import com.fusionphotos.app.net.ApiException
import com.fusionphotos.app.net.UploadDtos
import com.fusionphotos.app.net.UploadRemote
import com.fusionphotos.crypto.Crypto
import com.fusionphotos.crypto.Sodium
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/** One local photo to back up. The bytes are read lazily so the caller bounds memory. */
class LocalSource(
    val clientRef: String,
    val fileName: String,
    val mimeType: String,
    val capturedAtMs: Long?,
    /**
     * Display derivatives, generated locally from the same original. Null (or a null pair) means
     * the format could not be decoded here — the original is still uploaded, and the manifest
     * records that there is no preview.
     */
    val readDerivatives: suspend () -> Derivatives? = { null },
    val readBytes: suspend () -> ByteArray,
)

/** A resource variant together with the ciphertext that goes with it. */
private class Variant(val variant: String, val payload: ByteArray)

/** Presigned-object transfer (S3). Separate from the JSON control plane on purpose. */
fun interface ObjectTransfer {
    /** PUT the ciphertext to a presigned URL; returns the HTTP status. */
    suspend fun put(url: String, bytes: ByteArray): Int
}

/**
 * An upload that could not be completed.
 *
 * [permanent] marks a failure that retrying cannot fix — an expired upload session, a rejected
 * part layout, a server that says "no". Retrying those only burns battery and delays the user's
 * ability to see what actually went wrong, so the queue dead-letters them immediately.
 */
class UploadException(message: String, val permanent: Boolean = false) : Exception(message)

class UploadResult(
    val assetId: String,
    val seq: Long,
    val ciphertextSize: Long,
    val originalSha256: String,
    /** The server already had this client ref committed — nothing was re-uploaded. */
    val alreadyCommitted: Boolean,
)

/**
 * The upload pipeline: read → encrypt → reserve → transfer → commit.
 *
 * Two properties this deliberately preserves:
 *
 *  - **The plaintext never leaves the device.** Only `header(24) || secretstream frames` is
 *    PUT to S3, and the manifest (file name, date, digest) is encrypted under the same asset
 *    key before being sent as a database column. The server sees sizes and ciphertext only.
 *  - **The declared sizes are exact.** The server rejects a completion whose `ciphertextSize`
 *    differs from the reserved size, or whose manifest size differs from the decoded base64
 *    length — so both are computed from the very bytes that get sent.
 *
 * The original is held in memory for now (tens of MiB at most); streaming the payload, and the
 * multipart path for very large originals, are follow-ups.
 */
class PhotoUploader(
    private val sodium: Sodium,
    private val remote: UploadRemote,
    private val transfer: ObjectTransfer,
) {
    suspend fun upload(source: LocalSource, vaultKey: ByteArray): UploadResult {
        val bytes = withContext(Dispatchers.IO) { source.readBytes() }
        if (bytes.isEmpty()) throw UploadException("refusing to upload an empty file")

        return withContext(Dispatchers.Default) {
            val sha = Crypto.sha256Hex(bytes)
            val assetKey = Crypto.newAssetKey(sodium)
            val keyEnvelope = Crypto.wrapAssetKey(sodium, assetKey, vaultKey)
            // Exactly what lands in S3: header(24) || secretstream frames.
            val payload = Crypto.encryptPayloadBytes(sodium, bytes, assetKey)

            // Derivatives are best-effort: a photo this device cannot decode is still backed up,
            // it simply carries no preview. Each one is encrypted under the *same* asset key,
            // which is what lets one keyEnvelope cover every resource of the asset.
            val derivatives = runCatching { source.readDerivatives() }.getOrNull()
            val preview = derivatives?.preview?.let {
                Variant(
                    DerivativeGenerator.PREVIEW,
                    Crypto.encryptPayloadBytes(sodium, it.jpeg, assetKey),
                )
            }
            val thumbnail = derivatives?.thumbnail?.let {
                Variant(
                    DerivativeGenerator.THUMBNAIL,
                    Crypto.encryptPayloadBytes(sodium, it.jpeg, assetKey),
                )
            }
            val extraVariants = listOfNotNull(preview, thumbnail)

            val manifest = UploadManifest(
                fileName = source.fileName,
                mimeType = source.mimeType,
                capturedAtIso = source.capturedAtMs
                    ?.takeIf { it > 0 }
                    ?.let { Instant.ofEpochMilli(it).toString() },
                capturedTzOffsetSeconds = null,
                width = derivatives?.width,
                height = derivatives?.height,
                originalSha256 = sha,
                originalSize = bytes.size.toLong(),
                clientAssetRef = source.clientRef,
                previewAvailable = derivatives?.available == true,
            )
            val encManifest = Crypto.encryptPayload(
                sodium,
                manifest.toJson().toString().toByteArray(Charsets.UTF_8),
                assetKey,
            )
            val manifestSize = Crypto.b64Len(encManifest.headerB64) + Crypto.b64Len(encManifest.blobB64)

            val plan = remote.startSession(
                listOf(
                    UploadDtos.UploadItemRequest(
                        originalSize = bytes.size.toLong(),
                        encryptedSizes = buildMap {
                            put(ORIGINAL, payload.size.toLong())
                            // `manifest` is reserved as a size but is never an S3 resource.
                            put("manifest", manifestSize)
                            extraVariants.forEach { put(it.variant, it.payload.size.toLong()) }
                        },
                        assetIdClient = source.clientRef,
                    ),
                ),
            ).singleOrNull() ?: throw UploadException("the server returned no upload plan")

            if (plan.committed) {
                // Idempotent replay: re-uploading would re-encrypt under a fresh key and
                // corrupt the stored object, so the server told us to do nothing.
                return@withContext UploadResult(
                    assetId = plan.assetId,
                    seq = 0,
                    ciphertextSize = 0,
                    originalSha256 = sha,
                    alreadyCommitted = true,
                )
            }

            // The original and every derivative go up under the same asset key, one object each.
            val resources = (listOf(Variant(ORIGINAL, payload)) + extraVariants).map { variant ->
                val url = plan.putUrls[variant.variant]
                    ?: throw UploadException("no presigned URL for the ${variant.variant}")
                val key = plan.keys[variant.variant]
                    ?: throw UploadException("no object key for the ${variant.variant}")
                transferResource(plan.assetId, variant.variant, variant.payload, url)
                UploadDtos.ResourceRef(
                    variant = variant.variant,
                    objectKey = key,
                    ciphertextSize = variant.payload.size.toLong(),
                )
            }

            val committed = remote.complete(
                listOf(
                    UploadDtos.UploadCompletion(
                        assetId = plan.assetId,
                        keyEnvelope = keyEnvelope,
                        manifest = UploadDtos.ManifestBlob(encManifest.headerB64, encManifest.blobB64),
                        resources = resources,
                    ),
                ),
            ).singleOrNull() ?: throw UploadException("the server did not confirm the commit")

            UploadResult(
                assetId = committed.assetId,
                seq = committed.seq,
                ciphertextSize = payload.size.toLong(),
                originalSha256 = sha,
                alreadyCommitted = false,
            )
        }
    }

    /**
     * Send the ciphertext, choosing single PUT or multipart by size.
     *
     * Multipart exists for large originals and is **resumable**: the server is the authority on
     * which parts it already holds, so a retry asks `multipart/status` and retransmits only what
     * is missing. That is also why nothing about parts is persisted on the client — the upload
     * session is keyed by the client ref, so a retry lands on the same asset and the same
     * multipart upload, and the missing parts are simply the ones S3 does not list.
     *
     * Parts are sent one at a time; peak extra memory is one part (8 MiB at the current sizing).
     */
    private suspend fun transferResource(
        assetId: String,
        variant: String,
        payload: ByteArray,
        singlePutUrl: String,
    ) {
        val plan = UploadPlan.planFor(payload.size.toLong())
        if (!plan.multipart) {
            val status = transfer.put(singlePutUrl, payload)
            if (status !in 200..299) throw UploadException("ciphertext upload rejected with HTTP $status")
            return
        }

        val size = payload.size.toLong()
        try {
            val status = remote.multipartStatus(assetId, variant)
            val alreadyUploaded = if (status.started) {
                // Only trust parts the server reports with the length we are about to rely on:
                // a part of the wrong size would only surface at assembly time.
                status.parts
                    .filter { it.size == sliceLength(size, plan.partCount, it.number) }
                    .map { it.number }
                    .toSet()
            } else {
                emptySet()
            }

            // A fresh upload gets every URL from init; a resumed one asks only for what is
            // missing (URLs are short-lived, so re-presigning exactly what we need is cheaper).
            val urls: Map<Int, String> = if (!status.started) {
                remote.multipartInit(assetId, variant, plan.partCount)
                    .parts
                    .associate { it.number to it.url }
            } else {
                val missing = (1..plan.partCount).filterNot { it in alreadyUploaded }
                if (missing.isEmpty()) {
                    emptyMap()
                } else {
                    remote.multipartPartUrls(assetId, variant, missing).associate { it.number to it.url }
                }
            }

            for (number in 1..plan.partCount) {
                if (number in alreadyUploaded) continue
                val url = urls[number] ?: throw UploadException("no URL for part $number")
                val range = UploadPlan.partRange(size, plan.partCount, number - 1)
                val bytes = payload.copyOfRange(range.first.toInt(), range.last.toInt() + 1)
                val result = transfer.put(url, bytes)
                if (result !in 200..299) throw UploadException("part $number rejected with HTTP $result")
            }

            // Name every part, in order, exactly once. The server re-reads the real ETags from
            // S3 and verifies the assembled size, so an incomplete list is rejected, not stored.
            val parts = (1..plan.partCount).map { UploadDtos.ClientPart(it, "") }
            val stored = remote.multipartComplete(assetId, variant, parts)
            if (stored != size) {
                throw UploadException("assembled object is $stored bytes, expected $size", permanent = true)
            }
        } catch (e: ApiException) {
            // 409 means the session expired or the multipart was never started: no amount of
            // retrying this ref will fix it, so say so instead of looping.
            if (e.status == 409) {
                throw UploadException("upload session expired (${e.message}) — the photo must be re-queued", permanent = true)
            }
            throw e
        }
    }

    /** Byte length of one part, per the slicing the server also uses. */
    private fun sliceLength(size: Long, partCount: Int, number: Int): Long {
        val range = UploadPlan.partRange(size, partCount, number - 1)
        return range.last - range.first + 1
    }

    companion object {
        /** The variant every original is uploaded as. */
        const val ORIGINAL = "original"

        /**
         * Stable idempotency key for a **MediaStore row**, cheap enough to compute for a whole
         * camera roll at enqueue time (hashing every original would mean reading gigabytes of
         * untouched files just to build a queue).
         *
         * `dateModified` is the load-bearing part: any write to the file updates it, so an
         * edited photo gets a new ref and is backed up again, while a retry of the same
         * unmodified file reuses its ref and cannot duplicate the asset.
         */
        fun clientRefForMedia(mediaId: Long, sizeBytes: Long, dateModifiedMs: Long): String =
            "android:$mediaId:$sizeBytes:${dateModifiedMs / 1000}"
    }
}
