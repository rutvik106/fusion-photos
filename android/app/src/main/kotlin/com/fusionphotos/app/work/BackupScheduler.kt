package com.fusionphotos.app.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Registers the backup work with WorkManager.
 *
 * Two kinds of work, matching the plan's "network, battery and retry constraints":
 *
 *  - **periodic** (every 6 h, `KEEP`): the durable heartbeat. WorkManager persists it, so it
 *    survives a restart; it also retries rows whose backoff has elapsed and picks up photos
 *    added to the camera roll later.
 *  - **one-time** (`REPLACE`): "Back up now". Replacing rather than queueing means pressing the
 *    button twice does not stack runs that would race for the same rows (the queue's atomic
 *    claim would keep it correct anyway, but this avoids the wasted work).
 */
object BackupScheduler {
    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    /** Call on launch: makes sure the durable heartbeat exists. */
    fun ensurePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<BackupWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                BackupWorker.PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
    }

    /**
     * "Back up now". When [manual] is true (the default) the run is visible: a foreground
     * service with a progress notification, which is what a long user-started job deserves.
     */
    fun backupNow(context: Context, manual: Boolean = true) {
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(BackupWorker.KEY_MANUAL to manual))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(BackupWorker.UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    /** Stops the periodic heartbeat (e.g. after signing out). */
    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(BackupWorker.PERIODIC_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(BackupWorker.UNIQUE_NAME)
    }

    fun isRunning(context: Context): Boolean {
        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(BackupWorker.UNIQUE_NAME)
            .get()
        return infos.any { !it.state.isFinished }
    }

    fun lastState(context: Context): WorkInfo.State? {
        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(BackupWorker.UNIQUE_NAME)
            .get()
        return infos.firstOrNull()?.state
    }
}
