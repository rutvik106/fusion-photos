package com.fusionphotos.app.upload

import kotlin.random.Random

/**
 * Retry policy for the queue: exponential backoff with jitter, bounded, then a dead letter
 * (plan: "use exponential backoff with jitter, bounded parallelism, a dead-letter queue").
 *
 * The base delay is minutes, not milliseconds, because a failed background upload is almost
 * always "no usable network" or "the service is unhappy" — both of which are better answered by
 * waiting than by hammering. The jitter keeps a fleet of devices from retrying in lockstep.
 */
object QueueBackoff {
    /** After this many failures an item stops being retried automatically (dead letter). */
    const val MAX_ATTEMPTS = 5
    const val BASE_DELAY_MS = 30_000L // 30 s
    const val MAX_DELAY_MS = 6 * 60 * 60 * 1_000L // 6 h

    /** Delay before the retry that follows [attempts] failures (1-based). */
    fun delayMs(attempts: Int, random: Random = Random.Default): Long {
        require(attempts >= 1) { "attempts counts failures, so it starts at 1" }
        val exponent = (attempts - 1).coerceAtMost(20)
        val capped = (BASE_DELAY_MS shl exponent).coerceAtMost(MAX_DELAY_MS)
        // Up to 25% extra, never past the ceiling.
        val jitter = random.nextLong(0, capped / 4 + 1)
        return (capped + jitter).coerceAtMost(MAX_DELAY_MS)
    }

    /** True when the item has failed too often and belongs in the dead-letter state. */
    fun isDead(attempts: Int): Boolean = attempts >= MAX_ATTEMPTS
}
