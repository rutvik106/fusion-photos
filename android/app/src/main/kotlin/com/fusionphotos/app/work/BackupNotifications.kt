package com.fusionphotos.app.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import com.fusionphotos.app.MainActivity
import com.fusionphotos.app.upload.BackupRunSummary
import com.fusionphotos.app.upload.StopReason

/**
 * The user-visible side of a long backup.
 *
 * The plan asks for "the appropriate user-visible transfer mechanism for long manual jobs", so
 * a backup the user started on purpose runs as a **foreground service** with a progress
 * notification: it is visible, it is not treated as deferrable background work, and the user can
 * see how far it has got. Background/heartbeat runs deliberately do *not* do this — a permanent
 * notification for work the user did not ask for would be rude.
 *
 * The wording is built by pure functions ([progressText], [finishedText], [progressPercent]) so
 * it is unit-tested rather than eyeballed.
 */
object BackupNotifications {
    const val CHANNEL_ID = "fusion-backup"

    private const val PROGRESS_ID = 4101
    private const val FINISHED_ID = 4102
    private const val ICON = android.R.drawable.stat_sys_upload

    /** What the progress notification says. Pure. */
    fun progressText(uploaded: Int, processed: Int, remaining: Int): String = when {
        processed == 0 -> "Preparing…"
        remaining > 0 -> "$uploaded uploaded · $remaining still to go"
        else -> "$uploaded uploaded"
    }

    /** How full the progress bar is, 0-100. Pure. */
    fun progressPercent(processed: Int, remaining: Int): Int {
        val total = processed + remaining
        if (total <= 0) return 0
        return (processed * 100 / total).coerceIn(0, 100)
    }

    /** What we tell the user when the run stops, including when it needs them. Pure. */
    fun finishedText(summary: BackupRunSummary): String = when (summary.stopReason) {
        StopReason.KEY_MISSING -> "Paused — unlock the app to continue."
        StopReason.CANCELLED -> "Stopped."
        else -> buildString {
            append("${summary.uploaded} uploaded")
            if (summary.alreadyPresent > 0) append(", ${summary.alreadyPresent} already there")
            if (summary.dead > 0) append(" · ${summary.dead} failed")
            if (summary.failed > 0) append(" · ${summary.failed} will retry")
            if (summary.remaining > 0) append(" · ${summary.remaining} still queued")
        }
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Backup", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress while photos are being backed up"
                setShowBadge(false)
            },
        )
    }

    /** Whether the progress notifications will actually be seen. */
    fun notificationsAllowed(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * The notification a foreground backup runs under. Built even when the user has not granted
     * notifications: Android still requires a foreground service to be visible, and the backup
     * must not silently stop for want of a permission.
     */
    fun foregroundInfo(context: Context, summary: BackupRunSummary?): ForegroundInfo {
        val text = summary
            ?.let { progressText(it.uploaded + it.alreadyPresent, it.processed, it.remaining) }
            ?: progressText(0, 0, 0)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(ICON)
            .setContentTitle("Backing up photos")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context))
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (summary != null) {
            builder.setProgress(100, progressPercent(summary.processed, summary.remaining), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(PROGRESS_ID, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(PROGRESS_ID, builder.build())
        }
    }

    /** A dismissible summary once a user-started run stops. */
    fun notifyFinished(context: Context, summary: BackupRunSummary) {
        if (!notificationsAllowed(context)) return
        val text = finishedText(summary)
        val notification: Notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(ICON)
            .setContentTitle(if (summary.remaining > 0) "Backup paused" else "Backup finished")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(FINISHED_ID, notification) }
    }

    private fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
