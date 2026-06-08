/*
 * Copyright 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 */

package im.vector.app.core.services

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import im.vector.app.features.notifications.NotificationUtils
import im.vector.app.features.settings.VectorPreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single owner of incoming-call ringtone playback (8 cycles via [CallRingPlayerIncoming]).
 * Used from FCM push path, [CallForegroundService], and [CallAndroidService].
 */
@Singleton
class IncomingCallRinger @Inject constructor(
        @ApplicationContext private val context: Context,
        private val notificationUtils: NotificationUtils,
        private val vectorPreferences: VectorPreferences,
) {
    private val player = CallRingPlayerIncoming(context, notificationUtils)

    fun start(fromBg: Boolean = true, roomId: String? = null) {
        val customTone = roomId?.let { vectorPreferences.getRoomNotificationTone(it) }
        player.start(fromBg, customTone)
    }

    fun start(fromBg: Boolean, customToneUri: Uri?) {
        player.start(fromBg, customToneUri)
    }

    fun stop() {
        player.stop()
    }
}
