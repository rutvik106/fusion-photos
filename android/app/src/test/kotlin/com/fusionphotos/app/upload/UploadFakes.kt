package com.fusionphotos.app.upload

import com.fusionphotos.app.net.ApiException
import com.fusionphotos.app.net.UploadDtos
import com.fusionphotos.app.net.UploadRemote
import java.util.concurrent.atomic.AtomicLong

/**
 * Test doubles shared by the upload suites: the fakes mirror the server's documented behaviour
 * (including its idempotent `committed` replay), so the pipeline and the queue are exercised
 * against the same contract the real API implements.
 */
class FakeUploadRemote(
    var committed: Boolean = false,
    /** Number of `complete` calls to fail before succeeding, for commit-failure tests. */
    var failCompletions: Int = 0,
    /** Parts the fake server already holds (part number -> size), for resume tests. */
    var uploadedParts: MutableMap<Int, Long> = mutableMapOf(),
    /** Report a multipart upload as already started, as if a previous run got that far. */
    var multipartStarted: Boolean = false,
    /** Return HTTP 409 from multipart complete (expired session). */
    var multipartConflict: Boolean = false,
    /** Force a wrong assembled size from multipart complete. */
    var completeSizeOverride: Long? = null,
) : UploadRemote {
    val requests = mutableListOf<UploadDtos.UploadItemRequest>()
    val completions = mutableListOf<UploadDtos.UploadCompletion>()
    val multipartInits = mutableListOf<Pair<String, Int>>()
    val multipartUrlRequests = mutableListOf<List<Int>>()
    val multipartCompletions = mutableListOf<List<UploadDtos.ClientPart>>()
    var aborted = false

    /** The size the client reserved, which is what the server slices from. */
    var reservedSize: Long = 0

    override suspend fun startSession(
        items: List<UploadDtos.UploadItemRequest>,
    ): List<UploadDtos.UploadItemPlan> {
        requests += items
        items.firstOrNull()?.let { reservedSize = it.encryptedSizes["original"] ?: 0 }
        return items.map {
            // Mirror the server: one object key and one presigned URL per reserved resource
            // variant (`manifest` is a database column, not an object).
            val variants = it.encryptedSizes.keys.filterNot { variant -> variant == "manifest" }
            UploadDtos.UploadItemPlan(
                assetIdClient = it.assetIdClient,
                assetId = ASSET_ID,
                keys = if (committed) emptyMap() else variants.associateWith { v -> "$ASSET_ID/$v" },
                putUrls = if (committed) emptyMap() else variants.associateWith { v -> putUrl(v) },
                maxSizeBytes = 1L shl 40,
                committed = committed,
            )
        }
    }

    override suspend fun complete(
        items: List<UploadDtos.UploadCompletion>,
    ): List<UploadDtos.CommittedAsset> {
        if (failCompletions > 0) {
            failCompletions--
            throw IllegalStateException("commit failed")
        }
        completions += items
        return items.map { UploadDtos.CommittedAsset(it.assetId, seq = 7) }
    }

    override suspend fun multipartInit(
        assetId: String,
        variant: String,
        partCount: Int,
    ): UploadDtos.MultipartInit {
        multipartInits += assetId to partCount
        multipartStarted = true
        val parts = (1..partCount).map { number ->
            val range = UploadPlan.partRange(reservedSize, partCount, number - 1)
            UploadDtos.MultipartPart(
                number = number,
                offset = range.first,
                length = range.last - range.first + 1,
                url = partUrl(number),
            )
        }
        return UploadDtos.MultipartInit(size = reservedSize, parts = parts)
    }

    override suspend fun multipartStatus(assetId: String, variant: String) = UploadDtos.MultipartStatus(
        started = multipartStarted,
        parts = uploadedParts.entries
            .sortedBy { it.key }
            .map { UploadDtos.ServerPart(number = it.key, etag = "etag-${it.key}", size = it.value) },
    )

    override suspend fun multipartPartUrls(
        assetId: String,
        variant: String,
        numbers: List<Int>,
    ): List<UploadDtos.PartUrl> {
        multipartUrlRequests += numbers
        return numbers.map { UploadDtos.PartUrl(it, partUrl(it), uploadedParts[it]) }
    }

    override suspend fun multipartComplete(
        assetId: String,
        variant: String,
        parts: List<UploadDtos.ClientPart>,
    ): Long {
        multipartCompletions += parts
        if (multipartConflict) throw ApiException(409, "expired", "upload session expired")
        return completeSizeOverride ?: reservedSize
    }

    override suspend fun multipartAbort(assetId: String, variant: String) {
        aborted = true
    }

    /**
     * Stand in for "a previous run left the first [uploadedCount] parts in S3", with the real
     * slice lengths the server would report (not an average — a wrong size would be —correctly—
     * distrusted by the client and re-sent).
     */
    fun assumePartsUploaded(uploadedCount: Int) {
        val partCount = UploadPlan.planFor(reservedSize).partCount
        for (n in 1..uploadedCount) {
            uploadedParts[n] = UploadPlan.partRange(reservedSize, partCount, n - 1).count().toLong()
        }
        multipartStarted = true
    }

    companion object {
        const val ASSET_ID = "asset-1"
        fun partUrl(number: Int) = "https://s3.invalid/part/$number"

        /** Presigned URL for a resource variant; the variant is readable from the URL. */
        fun putUrl(variant: String) = "https://s3.invalid/put/$variant"
    }
}

class FakeObjectTransfer(var status: Int = 200, private var failAfter: Int? = null) : ObjectTransfer {
    val uploaded = mutableListOf<Pair<String, ByteArray>>()

    override suspend fun put(url: String, bytes: ByteArray): Int {
        val limit = failAfter
        if (limit != null && uploaded.size >= limit) {
            // Simulate a transfer that dies part-way through: the earlier parts are already in
            // S3, and this one never arrived (recorded as empty so assertions can tell).
            uploaded += url to ByteArray(0)
            return 500
        }
        uploaded += url to bytes
        return status
    }

    /** Only the parts that really made it, for resume assertions. */
    fun successfulParts(): List<Int> = uploaded
        .filter { it.second.isNotEmpty() }
        .map { it.first.substringAfterLast('/').toInt() }

    fun bytesForPart(number: Int): ByteArray? =
        uploaded.firstOrNull { it.first.endsWith("/$number") && it.second.isNotEmpty() }?.second
}

/**
 * In-memory queue used to test [BackupRunner]. It implements the contract documented on
 * [UploadQueueStore] — including "pending first, then the retry that has waited longest" — so
 * the runner is tested against the same ordering rule the SQLite query uses.
 */
class InMemoryQueueStore : UploadQueueStore {
    private class Row(var item: QueueItem, var nextAttemptAtMs: Long, var touched: Long)

    private val rows = LinkedHashMap<String, Row>()
    private val sequence = AtomicLong(0)

    override fun enqueue(items: List<QueueItem>): Int =
        items.count { rows.putIfAbsent(it.clientRef, Row(it, 0, sequence.incrementAndGet())) == null }

    override fun claimNext(nowMs: Long): QueueItem? {
        val row = rows.values
            .filter { it.item.state == QueueState.PENDING || (it.item.state == QueueState.FAILED && it.nextAttemptAtMs <= nowMs) }
            .minWithOrNull(compareBy({ it.nextAttemptAtMs }, { it.touched }))
            ?: return null
        row.item = row.item.copy(state = QueueState.IN_FLIGHT)
        row.touched = sequence.incrementAndGet()
        return row.item
    }

    override fun markUploaded(clientRef: String, assetId: String, nowMs: Long) {
        rows[clientRef]?.let {
            it.item = it.item.copy(state = QueueState.UPLOADED, assetId = assetId, lastError = null)
            it.nextAttemptAtMs = 0
            it.touched = sequence.incrementAndGet()
        }
    }

    override fun markFailed(
        clientRef: String,
        error: String,
        attempts: Int,
        nextAttemptAtMs: Long,
        dead: Boolean,
        nowMs: Long,
    ) {
        rows[clientRef]?.let {
            it.item = it.item.copy(
                state = if (dead) QueueState.DEAD else QueueState.FAILED,
                attempts = attempts,
                lastError = error,
            )
            it.nextAttemptAtMs = if (dead) 0 else nextAttemptAtMs
            it.touched = sequence.incrementAndGet()
        }
    }

    override fun releaseInFlight(nowMs: Long): Int =
        rows.values.count { row ->
            if (row.item.state == QueueState.IN_FLIGHT) {
                row.item = row.item.copy(state = QueueState.PENDING)
                true
            } else {
                false
            }
        }

    override fun counts() = QueueCounts(
        pending = rows.values.count { it.item.state == QueueState.PENDING },
        inFlight = rows.values.count { it.item.state == QueueState.IN_FLIGHT },
        uploaded = rows.values.count { it.item.state == QueueState.UPLOADED },
        failed = rows.values.count { it.item.state == QueueState.FAILED },
        dead = rows.values.count { it.item.state == QueueState.DEAD },
    )

    override fun items(state: QueueState, limit: Int): List<QueueItem> =
        rows.values.map { it.item }.filter { it.state == state }.take(limit)

    override fun retryDead(nowMs: Long): Int =
        rows.values.count { row ->
            if (row.item.state == QueueState.DEAD) {
                row.item = row.item.copy(state = QueueState.PENDING, attempts = 0)
                row.nextAttemptAtMs = 0
                true
            } else {
                false
            }
        }

    override fun clearUploaded(): Int {
        val before = rows.size
        rows.entries.removeIf { it.value.item.state == QueueState.UPLOADED }
        return before - rows.size
    }
}

fun queueItem(
    ref: String,
    name: String = "IMG_$ref.png",
    state: QueueState = QueueState.PENDING,
    attempts: Int = 0,
) = QueueItem(
    clientRef = ref,
    mediaId = ref.hashCode().toLong(),
    uri = "content://media/external/images/media/$ref",
    displayName = name,
    mimeType = "image/png",
    capturedAtMs = 1_700_000_000_000,
    state = state,
    attempts = attempts,
    lastError = null,
    assetId = null,
)
