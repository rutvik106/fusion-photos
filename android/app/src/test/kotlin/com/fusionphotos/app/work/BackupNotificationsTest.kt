package com.fusionphotos.app.work

import com.fusionphotos.app.upload.BackupRunSummary
import com.fusionphotos.app.upload.StopReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * While a long backup runs, the notification is the only thing the user sees. So its wording is
 * pinned: no "0 uploaded" before anything happened, no silent omission of failures, and no
 * pretending a paused run finished.
 */
class BackupNotificationsTest {
    private fun summary(
        uploaded: Int = 0,
        alreadyPresent: Int = 0,
        failed: Int = 0,
        dead: Int = 0,
        processed: Int = 0,
        remaining: Int = 0,
        reason: StopReason = StopReason.DRAINED,
    ) = BackupRunSummary(
        uploaded = uploaded,
        alreadyPresent = alreadyPresent,
        failed = failed,
        dead = dead,
        processed = processed,
        remaining = remaining,
        stopReason = reason,
    )

    @Test
    fun `nothing is claimed before any work is done`() {
        assertEquals("Preparing…", BackupNotifications.progressText(uploaded = 0, processed = 0, remaining = 3))
    }

    @Test
    fun `progress reports what is done and what is left`() {
        assertEquals("2 uploaded · 3 still to go", BackupNotifications.progressText(2, 2, 3))
        assertEquals("2 uploaded", BackupNotifications.progressText(2, 2, 0))
    }

    @Test
    fun `the progress bar stays inside its bounds`() {
        assertEquals(0, BackupNotifications.progressPercent(0, 0))
        assertEquals(50, BackupNotifications.progressPercent(1, 1))
        assertEquals(100, BackupNotifications.progressPercent(5, 0))
        // 9 of 10 done is 90%, and a pathological input still cannot overflow the bar.
        assertEquals(90, BackupNotifications.progressPercent(9, 1))
    }

    @Test
    fun `a locked vault is reported as something the user must act on`() {
        val text = BackupNotifications.finishedText(summary(reason = StopReason.KEY_MISSING, remaining = 2))
        assertTrue(text.contains("unlock"), text)
    }

    @Test
    fun `the finished summary accounts for everything that happened`() {
        val text = BackupNotifications.finishedText(
            summary(uploaded = 3, alreadyPresent = 1, failed = 1, dead = 2, processed = 7, remaining = 2),
        )
        assertTrue(text.contains("3 uploaded"), text)
        assertTrue(text.contains("1 already there"), text)
        assertTrue(text.contains("2 failed"), text)
        assertTrue(text.contains("1 will retry"), text)
        assertTrue(text.contains("2 still queued"), text)
    }

    @Test
    fun `a clean finish does not invent problems`() {
        assertEquals("3 uploaded", BackupNotifications.finishedText(summary(uploaded = 3, processed = 3)))
    }
}
