package com.fusionphotos.app.upload

/**
 * How a resource is transferred: one presigned PUT, or a multipart upload with independent,
 * retryable parts.
 *
 * These numbers are not arbitrary — they mirror the web client (`web/src/lib/upload.ts`)
 * and must stay inside the server's rules (`api/cmd/server/validation.go`):
 *   - MULTIPART_THRESHOLD / PART_SIZE match the web client, so both clients slice a resource
 *     the same way and a resumed upload is interchangeable.
 *   - The server caps a request at MAX_PARTS and rejects any non-final part below
 *     MIN_PART_SIZE, so the plan here can never propose something the server refuses.
 */
object UploadPlan {
    const val MULTIPART_THRESHOLD = 32L * 1024 * 1024
    const val PART_SIZE = 8L * 1024 * 1024
    const val MAX_PARTS = 1024
    const val MIN_PART_SIZE = 5L * 1024 * 1024

    data class Plan(val multipart: Boolean, val partCount: Int, val partSize: Long)

    private fun ceilDiv(value: Long, divisor: Long): Long = (value + divisor - 1) / divisor

    /**
     * Choose a transfer plan for a ciphertext of [size] bytes. Small resources go out in one
     * PUT; larger ones use multipart, widening the parts rather than exceeding the server's
     * part-count cap (a wider part is always legal; too many parts is not).
     */
    fun planFor(size: Long): Plan {
        require(size > 0) { "resource size must be positive" }
        if (size <= MULTIPART_THRESHOLD) return Plan(multipart = false, partCount = 1, partSize = size)

        var parts = ceilDiv(size, PART_SIZE).coerceAtMost(MAX_PARTS.toLong()).toInt()
        val partSize = ceilDiv(size, parts.toLong())
        // Only reachable for absurd sizes; better to fail loudly than to be rejected by S3.
        require(partSize >= MIN_PART_SIZE) {
            "cannot slice $size bytes into $parts parts of at least $MIN_PART_SIZE bytes"
        }
        parts = ceilDiv(size, partSize).toInt()
        return Plan(multipart = true, partCount = parts, partSize = partSize)
    }

    /**
     * The byte range of one part (0-based index), matching the server's `computePartSlices`
     * exactly: `partLen = ceil(size / partCount)`, parts are consecutive, the last takes the
     * remainder. Both sides must agree or a resumed upload would request the wrong bytes.
     */
    fun partRange(size: Long, partCount: Int, partIndex: Int): LongRange {
        require(partCount >= 1 && partIndex in 0 until partCount) { "bad part index" }
        val partLen = ceilDiv(size, partCount.toLong())
        val start = partIndex * partLen
        val end = minOf(start + partLen, size) - 1
        return start..end
    }
}
