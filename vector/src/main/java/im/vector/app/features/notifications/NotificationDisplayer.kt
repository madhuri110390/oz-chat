/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.notifications

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import timber.log.Timber
import javax.inject.Inject

class NotificationDisplayer @Inject constructor(private val context: Context) {

    private val notificationManager = NotificationManagerCompat.from(context)
    fun showNotificationMessage(
            tag: String?,
            id: Int,
            notification: Notification,
            roomId: String? = null,
            currentRoomId: String? = null
    ) {
        if (roomId != null && roomId == currentRoomId) {
            return
        }
            notificationManager.notify(tag, id, notification)

    }

    fun cancelNotificationMessage(tag: String?, id: Int) {
        notificationManager.cancel(tag, id)
    }
//    private fun isAppInForeground(): Boolean {
//        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
//        val appProcesses = activityManager.runningAppProcesses ?: return false
//
//        for (process in appProcesses) {
//            if (process.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
//                    process.processName == context.packageName
//            ) {
//                return true
//            }
//        }
//        return false
//    }
    fun cancelAllNotifications() {
        // Keep this try catch (reported by GA)
        try {
            notificationManager.cancelAll()
        } catch (e: Exception) {
            Timber.e(e, "## cancelAllNotifications() failed")
        }
    }
}
