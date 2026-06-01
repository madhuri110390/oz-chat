/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.backgroundsync.receiver

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import im.vector.app.core.extensions.singletonEntryPoint
import im.vector.app.core.platform.PendingIntentCompat
import im.vector.app.core.services.VectorSyncAndroidService
import im.vector.app.features.workers.sync.SyncWorker
import im.vector.lib.core.utils.timer.Clock
import org.matrix.android.sdk.api.session.sync.job.SyncAndroidService
import timber.log.Timber

class AlarmSyncBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Timber.d("## Sync: AlarmSyncBroadcastReceiver received intent")
        val singletonEntryPoint = context.singletonEntryPoint()
        if (singletonEntryPoint.activeSessionHolder().getSafeActiveSession() == null) {
            Timber.v("No active session, so don't launch sync service.")
            return
        }
        val vectorPreferences = singletonEntryPoint.vectorPreferences()
        val clock = singletonEntryPoint.clock()

        val sessionId = intent.getStringExtra(SyncAndroidService.EXTRA_SESSION_ID) ?: return
        // Android 14+ / Play policy: do not use dataSync foreground services for background sync.
        // Trigger a constrained WorkManager sync instead.
        try {
            Timber.d("## Sync: enqueue SyncWorker from alarm")
            SyncWorker.enqueue(context)
        } catch (ex: Throwable) {
            Timber.i("## Sync: Failed to enqueue SyncWorker, Alarm scheduled to retry")
            scheduleAlarm(context, sessionId, vectorPreferences.backgroundSyncDelay(), clock)
            Timber.e(ex)
        }
    }

    companion object {
        private const val REQUEST_CODE = 0

        @SuppressLint("WrongConstant") // PendingIntentCompat.FLAG_IMMUTABLE is a false positive
        fun scheduleAlarm(context: Context, sessionId: String, delayInSeconds: Int, clock: Clock) {
            // Reschedule
            Timber.v("## Sync: Scheduling alarm for background sync in $delayInSeconds seconds")
            val intent = Intent(context, AlarmSyncBroadcastReceiver::class.java).apply {
                putExtra(SyncAndroidService.EXTRA_SESSION_ID, sessionId)
                putExtra(SyncAndroidService.EXTRA_PERIODIC, true)
            }
            val pIntent = PendingIntent.getBroadcast(
                    context,
                    REQUEST_CODE,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntentCompat.FLAG_IMMUTABLE
            )
            val firstMillis = clock.epochMillis() + delayInSeconds * 1000L
            val alarmMgr = context.getSystemService<AlarmManager>()!!
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmMgr.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, firstMillis, pIntent)
            } else {
                alarmMgr.set(AlarmManager.RTC_WAKEUP, firstMillis, pIntent)
            }
        }

        @SuppressLint("WrongConstant") // PendingIntentCompat.FLAG_IMMUTABLE is a false positive
        fun cancelAlarm(context: Context) {
            Timber.v("## Sync: Cancel alarm for background sync")
            val intent = Intent(context, AlarmSyncBroadcastReceiver::class.java)
            val pIntent = PendingIntent.getBroadcast(
                    context,
                    REQUEST_CODE,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntentCompat.FLAG_IMMUTABLE
            )
            val alarmMgr = context.getSystemService<AlarmManager>()!!
            alarmMgr.cancel(pIntent)

            // Stop any legacy sync service if still present.
            try {
                context.stopService(VectorSyncAndroidService.stopIntent(context))
            } catch (_: Throwable) {
                Timber.i("## Sync: Cancel sync (legacy service not running)")
            }
        }
    }
}
