/*
 * Copyright 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import im.vector.app.core.extensions.singletonEntryPoint
import im.vector.app.core.pushers.UnifiedPushHelper
import timber.log.Timber

/**
 * Receiver to handle BOOT_COMPLETED and MY_PACKAGE_REPLACED to ensure background sync and FCM are re-initialized.
 * This helps in delivering notifications when the app is cleared or the phone is rebooted.
 */
class RebootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Timber.d("## RebootReceiver: received intent ${intent.action}")

        val singletonEntryPoint = context.singletonEntryPoint()
        val activeSessionHolder = singletonEntryPoint.activeSessionHolder()
        val vectorPreferences = singletonEntryPoint.vectorPreferences()
        val fcmHelper = singletonEntryPoint.fcmHelper()
        val pushersManager = singletonEntryPoint.pushersManager()
        val unifiedPushHelper = singletonEntryPoint.unifiedPushHelper()

        if (activeSessionHolder.hasActiveSession()) {
            val session = activeSessionHolder.getActiveSession()
            
            // Re-initialize FCM if needed
            if (vectorPreferences.areNotificationEnabledForDevice() && unifiedPushHelper.isEmbeddedDistributor()) {
                fcmHelper.ensureFcmTokenIsRetrieved(pushersManager, registerPusher = true)
            }

            // Ensure background sync is scheduled if foreground sync is not running
            if (vectorPreferences.isBackgroundSyncEnabled()) {
                session.syncService().requireBackgroundSync()
            }
        }
    }
}
