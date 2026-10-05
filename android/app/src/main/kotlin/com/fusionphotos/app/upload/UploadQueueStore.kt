package com.fusionphotos.app.upload

/**
 * Explicit queue states (plan: "use a durable local queue and explicit states").
 *
 * `DEAD` is the dead-letter state: a row that has failed often enough to stop being retried
 * automatically. It is kept, not deleted, so the user can see what failed and retry it.
 */
enum class QueueState { PENDING, IN_FLIGHT, UPLOADED, FAILED, DEAD }

/** One queued backup. Identity is [clientRef] — the idempotency key the server also sees. */
data class QueueItem(
    val clientRef: String,
    val mediaId: Long,
    val uri: String,
    val displayName: String,
    val mimeType: String,
    val capturedAtMs: Long,
    val state: QueueState,
    val attempts: Int,
    val lastError: String?,
    val assetId: String?,
)

data class QueueCounts(
    val pending: Int,
    val inFlight: Int,
    val uploaded: Int,
    val failed: Int,
    val dead: Int,
) {
    /** Work still to do, including retries that are waiting out their backoff. */
    val outstanding: Int get() = pending + inFlight + failed

    val total: Int get() = pending + inFlight + uploaded + failed + dead
}

/**
 * The durable backup queue.
 *
 * The contract every implementation must honour (there is a SQLite one for the app and an
 * in-memory one used to test the runner):
 *
 *  - [enqueue] never disturbs a row that already exists: re-queueing the same photo must not
 *    reset an `UPLOADED` row back to `PENDING` or lose its attempt count.
 *  - [claimNext] is atomic and returns at most one row, moving it to `IN_FLIGHT`. It only
 *    returns rows whose backoff has elapsed, and pending rows are preferred (they have no
 *    backoff), so a retry cannot starve fresh work.
 *  - [releaseInFlight] exists because a process can die mid-upload: on restart every
 *    `IN_FLIGHT` row is put back to `PENDING` (the client ref makes the re-run idempotent).
 */
interface UploadQueueStore {
    /** Adds missing rows; returns how many were newly added. */
    fun enqueue(items: List<QueueItem>): Int

    /** Atomically claims the next due row (pending first, then retries past backoff). */
    fun claimNext(nowMs: Long): QueueItem?

    fun markUploaded(clientRef: String, assetId: String, nowMs: Long)

    /** Records a failure; [dead] moves it to the dead-letter state instead of retrying. */
    fun markFailed(clientRef: String, error: String, attempts: Int, nextAttemptAtMs: Long, dead: Boolean, nowMs: Long)

    /** Crash recovery: every `IN_FLIGHT` row goes back to `PENDING`. Returns how many. */
    fun releaseInFlight(nowMs: Long): Int

    fun counts(): QueueCounts

    fun items(state: QueueState, limit: Int = 50): List<QueueItem>

    /** Returns dead-lettered rows to `PENDING` for a user-requested retry. */
    fun retryDead(nowMs: Long): Int

    /** Removes finished rows (housekeeping). Returns how many were deleted. */
    fun clearUploaded(): Int
}
