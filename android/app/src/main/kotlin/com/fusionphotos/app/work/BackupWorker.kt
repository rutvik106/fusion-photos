package com.fusionphotos.app.work

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.fusionphotos.app.AndroidSodium
import com.fusionphotos.app.media.DerivativeGenerator
import com.fusionphotos.app.media.MediaBytes
import com.fusionphotos.app.net.ApiClient
import com.fusionphotos.app.net.S3Transfer
import com.fusionphotos.app.session.KeystoreKeyVault
import com.fusionphotos.app.session.SessionStore
import com.fusionphotos.app.upload.BackupRunner
import com.fusionphotos.app.upload.BackupRunSummary
import com.fusionphotos.app.upload.PhotoUploader
import com.fusionphotos.app.upload.SqliteUploadQueueStore
import com.fusionphotos.app.upload.StopReason

/**
 * Runs the durable backup queue in the background.
 *
 * All the interesting behaviour lives in [BackupRunner] (and is unit-tested there); this class
 * is only the Android wiring: open the queue, recover rows orphaned by a process kill, supply
 * the Keystore-held vault key and MediaStore reads, report progress, and translate the outcome
 * into a WorkManager result.
 *
 * Two returns deserve a note:
 *  - **No vault key** → `success`, never `retry`. A locked vault is not a transient error, and
 *    retrying would just wake the radio up for nothing. The queue stays put until the user
 *    unlocks, and the UI says so.
 *  - **Items still waiting on backoff** → `success` too: the periodic worker is what picks them
 *    up later, so we do not ask WorkManager to spin on our behalf.
 */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        val store = SqliteUploadQueueStore(context)
        // A previous process may have died mid-upload; those rows are not lost, just orphaned.
        store.releaseInFlight(System.currentTimeMillis())

        // A backup the user started on purpose becomes a foreground service: visible, and not
        // treated as deferrable background work. Heartbeat runs stay invisible on purpose.
        if (manual) {
            setForeground(BackupNotifications.foregroundInfo(context, null))
        }

        val session = SessionStore(context)
        val keyVault = KeystoreKeyVault(context)
        val runner = BackupRunner(
            store = store,
            uploader = PhotoUploader(
                sodium = AndroidSodium.instance,
                remote = ApiClient({ session.baseUrl }, { session.token }),
                transfer = S3Transfer,
            ),
            vaultKey = { keyVault.load() },
            readBytes = { MediaBytes.read(context, Uri.parse(it.uri)) },
            // Best effort: an undecodable photo is still backed up, just without a preview.
            readDerivatives = { DerivativeGenerator.generate(context, Uri.parse(it.uri)) },
        )

        var lastProgress = -1
        val summary = runner.run { progress ->
            if (progress.processed != lastProgress) {
                lastProgress = progress.processed
                setProgress(progressData(progress))
                // Re-posting the same ForegroundInfo updates the notification in place.
                if (manual) setForeground(BackupNotifications.foregroundInfo(context, progress))
            }
        }
        if (manual) BackupNotifications.notifyFinished(context, summary)

        return when {
            summary.stopReason == StopReason.KEY_MISSING -> {
                setProgress(progressData(summary))
                Result.success(workDataOf(KEY_RAN to true, KEY_NEEDS_UNLOCK to true))
            }
            summary.stopReason == StopReason.CANCELLED -> Result.retry()
            summary.stopReason == StopReason.ITEM_LIMIT -> {
                // Hit the per-run ceiling; more work is ready to go right now.
                setProgress(progressData(summary))
                Result.retry()
            }
            summary.remaining > 0 -> {
                // Whatever is left is waiting on backoff — the periodic worker handles it.
                setProgress(progressData(summary))
                Result.success(workDataOf(KEY_RAN to true, KEY_DEFERRED to summary.remaining))
            }
            else -> {
                setProgress(progressData(summary))
                Result.success(workDataOf(KEY_RAN to true, KEY_UPLOADED to summary.uploaded + summary.alreadyPresent))
            }
        }
    }

    private fun progressData(summary: BackupRunSummary) = workDataOf(
        KEY_UPLOADED to summary.uploaded + summary.alreadyPresent,
        KEY_PROCESSED to summary.processed,
        KEY_REMAINING to summary.remaining,
    )

    companion object {
        const val UNIQUE_NAME = "fusion-backup-queue"
        const val PERIODIC_NAME = "fusion-backup-periodic"
        const val KEY_MANUAL = "manual"
        const val KEY_RAN = "ran"
        const val KEY_UPLOADED = "uploaded"
        const val KEY_PROCESSED = "processed"
        const val KEY_REMAINING = "remaining"
        const val KEY_NEEDS_UNLOCK = "needsUnlock"
        const val KEY_DEFERRED = "deferred"
    }
}
