package com.fusionphotos.app

import android.app.Application
import com.fusionphotos.app.work.BackupNotifications
import com.fusionphotos.app.work.BackupScheduler

/**
 * Application entry point.
 *
 * Two things belong here rather than in an Activity, because they must hold whether or not the
 * user ever opens the UI: the notification channel (creating it later would drop the first
 * notification) and the periodic backup work (WorkManager persists it, so registering it on
 * every process start keeps it registered without the user doing anything).
 */
class FusionApp : Application() {
    override fun onCreate() {
        super.onCreate()
        BackupNotifications.ensureChannel(this)
        BackupScheduler.ensurePeriodic(this)
    }
}
