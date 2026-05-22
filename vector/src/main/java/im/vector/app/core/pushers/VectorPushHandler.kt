/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.pushers

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.network.WifiDetector
import im.vector.app.core.pushers.model.PushData
import im.vector.app.core.resources.BuildMeta
import im.vector.app.features.notifications.NotifiableEventResolver
import im.vector.app.features.notifications.NotifiableMessageEvent
import im.vector.app.features.notifications.NotificationActionIds
import im.vector.app.features.notifications.NotificationDrawerManager
import im.vector.app.features.settings.VectorDataStore
import im.vector.app.features.settings.VectorPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.logger.LoggerTag
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.room.getTimelineEvent
import timber.log.Timber
import javax.inject.Inject

private val loggerTag = LoggerTag("Push", LoggerTag.SYNC)

class VectorPushHandler @Inject constructor(
    private val notificationDrawerManager: NotificationDrawerManager,
    private val notifiableEventResolver: NotifiableEventResolver,
    private val activeSessionHolder: ActiveSessionHolder,
    private val vectorPreferences: VectorPreferences,
    private val vectorDataStore: VectorDataStore,
    private val wifiDetector: WifiDetector,
    private val actionIds: NotificationActionIds,
    private val context: Context,
    private val buildMeta: BuildMeta,
    private val clock: im.vector.lib.core.utils.timer.Clock,
) {

    private val coroutineScope = CoroutineScope(SupervisorJob())

    // UI handler
    private val mUIHandler by lazy {
        Handler(Looper.getMainLooper())
    }

    /**
     * Called when message is received.
     *
     * @param pushData the data received in the push.
     */
    fun handle(pushData: PushData) {
        Timber.tag(loggerTag.value).d("## handling pushData")

        if (buildMeta.lowPrivacyLoggingEnabled) {
            Timber.tag(loggerTag.value).d("## pushData: $pushData")
        }

        runBlocking {
            vectorDataStore.incrementPushCounter()
        }

        // Diagnostic Push
        if (pushData.eventId == PushersManager.TEST_EVENT_ID) {
            val intent = Intent(actionIds.push)
            LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
            return
        }

        if (!vectorPreferences.areNotificationEnabledForDevice()) {
            Timber.tag(loggerTag.value).i("Notification are disabled for this device")
            return
        }

        // Acquire a wakelock because we are likely in a broadcast receiver (FCM) or Doze mode,
        // and background work might be suspended immediately if the Wakelock isn't held.
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val wakeLock = powerManager?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "VectorPushHandler:handlePush")
        
        // We set a 30-second timeout as a safety net if something gets stuck.
        wakeLock?.acquire(30000L)

        // We process the push even if the app is in foreground, to allow "Fast Lane" notifications
        // The NotificationDrawerManager will take care of filtering events that match the currently opened room
        coroutineScope.launch(Dispatchers.IO) {
            try {
                handleInternal(pushData)
            } finally {
                try {
                    if (wakeLock?.isHeld == true) {
                        wakeLock.release()
                    }
                } catch (e: Exception) {
                    Timber.tag(loggerTag.value).e(e, "Failed to release Wakelock")
                }
            }
        }
    }

    /**
     * Synchronous block. Should be called passing the push data.
     * Keeps execution blocking and holds Wakelock to make sure Google Play Services doesn't kill the FCM service too early.
     */
    fun handleSynchronously(pushData: PushData) {
        Timber.tag(loggerTag.value).d("## handling pushData synchronously")

        if (buildMeta.lowPrivacyLoggingEnabled) {
            Timber.tag(loggerTag.value).d("## pushData: $pushData")
        }

        runBlocking {
            vectorDataStore.incrementPushCounter()
        }

        // Diagnostic Push
        if (pushData.eventId == PushersManager.TEST_EVENT_ID) {
            val intent = Intent(actionIds.push)
            LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
            return
        }

        if (!vectorPreferences.areNotificationEnabledForDevice()) {
            Timber.tag(loggerTag.value).i("Notification are disabled for this device")
            return
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val wakeLock = powerManager?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "VectorPushHandler:handlePushSync")
        wakeLock?.acquire(30000L)

        try {
            runBlocking(Dispatchers.IO) {
                handleInternal(pushData)
            }
        } finally {
            try {
                if (wakeLock?.isHeld == true) {
                    wakeLock.release()
                }
            } catch (e: Exception) {
                Timber.tag(loggerTag.value).e(e, "Failed to release Wakelock")
            }
        }
    }

    /**
     * Internal receive method.
     *
     * @param pushData Object containing message data.
     */
    private suspend fun handleInternal(pushData: PushData) {
        try {
            if (buildMeta.lowPrivacyLoggingEnabled) {
                Timber.tag(loggerTag.value).d("## handleInternal() : $pushData")
            } else {
                Timber.tag(loggerTag.value).d("## handleInternal()")
            }

            val session = activeSessionHolder.getOrInitializeSession()

            if (session == null) {
                Timber.tag(loggerTag.value).w("## Can't sync from push, no current session")
            } else {
                if (isEventAlreadyKnown(pushData)) {
                    Timber.tag(loggerTag.value).d("Ignoring push, event already known")
                } else {
                    // Try to get the Event content faster
                    Timber.tag(loggerTag.value).d("Requesting event in fast lane")
                    getEventFastLane(session, pushData)

                    Timber.tag(loggerTag.value).d("Requesting background sync to ensure delivery without blocking")
                    try {
                        session.syncService().requireBackgroundSync()
                    } catch (e: Exception) {
                        Timber.tag(loggerTag.value).e(e, "Background sync requirement failed")
                    }
                }
            }
        } catch (e: Exception) {
            Timber.tag(loggerTag.value).e(e, "## handleInternal() failed")
        }
    }

    private suspend fun getEventFastLane(session: Session, pushData: PushData) {
        val roomId = pushData.roomId ?: return
        val eventId = pushData.eventId ?: return

        Timber.tag(loggerTag.value).d("Fast lane: start request")
        val event = tryOrNull { session.eventService().getEvent(roomId, eventId) }
        
        val resolvedEvent = if (event != null) {
            notifiableEventResolver.resolveInMemoryEvent(session, event, canBeReplaced = true)
        } else {
            // Fallback to push data if available
            if (pushData.senderId != null || pushData.content != null) {
                Timber.tag(loggerTag.value).d("Fast lane: fallback to push data context")
                NotifiableMessageEvent(
                        eventId = eventId,
                        editedEventId = null,
                        canBeReplaced = true,
                        noisy = true, // Default to true for push notifications
                        timestamp = clock.epochMillis(),
                        senderName = pushData.senderName,
                        senderId = pushData.senderId,
                        body = pushData.content,
                        imageUriString = null,
                        roomId = roomId,
                        threadId = null,
                        roomName = pushData.roomName,
                        matrixID = session.myUserId
                )
            } else {
                Timber.tag(loggerTag.value).d("Fast lane: No event in memory and no fallback possible (encrypted or empty push)")
                null
            }
        }

        if (resolvedEvent is NotifiableMessageEvent) {
            // If the room is currently displayed, we will not show a notification, so no need to get the Event faster
            if (notificationDrawerManager.shouldIgnoreMessageEventInRoom(resolvedEvent)) {
                return
            }
        }

        resolvedEvent
                ?.also { Timber.tag(loggerTag.value).d("Fast lane: notify drawer") }
                ?.let {
                    notificationDrawerManager.updateEvents(immediate = true) { it.onNotifiableEventReceived(resolvedEvent) }
                }
    }

    // check if the event was not yet received
    // a previous catchup might have already retrieved the notified event
    private fun isEventAlreadyKnown(pushData: PushData): Boolean {
        if (pushData.eventId != null && pushData.roomId != null) {
            try {
                val session = activeSessionHolder.getSafeActiveSession() ?: return false
                val room = session.getRoom(pushData.roomId) ?: return false
                return room.getTimelineEvent(pushData.eventId) != null
            } catch (e: Exception) {
                Timber.tag(loggerTag.value).e(e, "## isEventAlreadyKnown() : failed to check if the event was already defined")
            }
        }
        return false
    }
}
