package com.fusionphotos.app.upload

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import com.fusionphotos.app.media.Derivatives
import kotlin.random.Random

/** Why a run stopped. Recorded so the UI can tell "finished" from "ran out of time". */
enum class StopReason { DRAINED, ITEM_LIMIT, KEY_MISSING, CANCELLED }

data class BackupRunSummary(
    val uploaded: Int,
    val alreadyPresent: Int,
    val failed: Int,
    val dead: Int,
    val processed: Int,
    val remaining: Int,
    val stopReason: StopReason,
) {
    /** Nothing left that this run could do. */
    val isDrained: Boolean get() = remaining == 0
}

/**
 * Drains the backup queue.
 *
 * Kept free of Android types (storage, uploader, key source and byte reader are all injected)
 * so the behaviour that actually matters can be tested on the JVM: crash recovery, retry with
 * backoff, the dead letter, stopping when the vault is locked, and converging after a failure
 * that happened *after* the object reached S3.
 *
 * Two failure classes are treated differently on purpose:
 *  - **Reading the original fails** (permission revoked, file deleted, library restricted):
 *    retrying cannot fix it, so the item is dead-lettered immediately with the reason.
 *  - **Transfer or commit fails**: retried with backoff, because it is usually transient.
 */
class BackupRunner(
    private val store: UploadQueueStore,
    private val uploader: PhotoUploader,
    private val vaultKey: () -> ByteArray?,
    private val readBytes: suspend (QueueItem) -> ByteArray,
    /** Display derivatives for an item, or null when the device cannot decode it. */
    private val readDerivatives: suspend (QueueItem) -> Derivatives? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
    private val maxItemsPerRun: Int = 200,
) {
    suspend fun run(onProgress: suspend (BackupRunSummary) -> Unit = {}): BackupRunSummary {
        var uploaded = 0
        var already = 0
        var failed = 0
        var dead = 0
        var processed = 0

        fun summary(reason: StopReason) = BackupRunSummary(
            uploaded = uploaded,
            alreadyPresent = already,
            failed = failed,
            dead = dead,
            processed = processed,
            remaining = store.counts().outstanding,
            stopReason = reason,
        )

        if (vaultKey() == null) return summary(StopReason.KEY_MISSING)

        while (processed < maxItemsPerRun) {
            if (!currentCoroutineContext().isActive) return summary(StopReason.CANCELLED)
            // The key can be cleared mid-run (sign-out, storage eviction): stop rather than
            // fail every remaining item.
            val key = vaultKey() ?: return summary(StopReason.KEY_MISSING)

            val item = store.claimNext(clock()) ?: return summary(StopReason.DRAINED)
            processed++

            val bytes = try {
                readBytes(item)
            } catch (t: Throwable) {
                // Not retryable: the local file is gone or unreadable.
                dead++
                store.markFailed(
                    clientRef = item.clientRef,
                    error = "cannot read the original: ${t.message}",
                    attempts = item.attempts + 1,
                    nextAttemptAtMs = 0,
                    dead = true,
                    nowMs = clock(),
                )
                onProgress(summary(StopReason.DRAINED))
                continue
            }

            try {
                val result = uploader.upload(
                    LocalSource(
                        clientRef = item.clientRef,
                        fileName = item.displayName,
                        mimeType = item.mimeType.ifBlank { "application/octet-stream" },
                        capturedAtMs = item.capturedAtMs,
                        readBytes = { bytes },
                        readDerivatives = { readDerivatives(item) },
                    ),
                    key,
                )
                // A commit that failed after the object reached S3 lands here on the retry:
                // the server reports the ref as committed and nothing is re-uploaded.
                store.markUploaded(item.clientRef, result.assetId, clock())
                if (result.alreadyCommitted) already++ else uploaded++
            } catch (t: Throwable) {
                val attempts = item.attempts + 1
                // A permanent failure (expired session, rejected part layout) will not fix
                // itself: dead-letter it now, so the user sees the real reason immediately.
                val permanent = t is UploadException && t.permanent
                val isDead = permanent || QueueBackoff.isDead(attempts)
                if (isDead) dead++ else failed++
                store.markFailed(
                    clientRef = item.clientRef,
                    error = t.message ?: t.toString(),
                    attempts = attempts,
                    nextAttemptAtMs = clock() + QueueBackoff.delayMs(attempts, random),
                    dead = isDead,
                    nowMs = clock(),
                )
            }
            onProgress(summary(StopReason.DRAINED))
        }
        return summary(StopReason.ITEM_LIMIT)
    }
}
