package im.vector.app.push.fcm

import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.pushers.FcmHelper
import im.vector.app.core.pushers.PushParser
import im.vector.app.core.pushers.PushersManager
import im.vector.app.core.pushers.UnifiedPushHelper
import im.vector.app.core.pushers.VectorPushHandler
import im.vector.app.core.services.CallAndroidService
import im.vector.app.core.services.IncomingCallRinger
import im.vector.app.features.call.webrtc.WebRtcCallManager
import im.vector.app.features.notifications.CallForegroundService
import im.vector.app.features.notifications.NotificationUtils
import im.vector.app.features.settings.VectorPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.matrix.android.sdk.api.logger.LoggerTag
import org.matrix.android.sdk.api.session.call.CallState
import timber.log.Timber
import javax.inject.Inject

private val loggerTag = LoggerTag("Push", LoggerTag.SYNC)

@AndroidEntryPoint
class VectorFirebaseMessagingService : FirebaseMessagingService() {

    @Inject lateinit var fcmHelper: FcmHelper
    @Inject lateinit var vectorPreferences: VectorPreferences
    @Inject lateinit var activeSessionHolder: ActiveSessionHolder
    @Inject lateinit var pushersManager: PushersManager
    @Inject lateinit var pushParser: PushParser
    @Inject lateinit var vectorPushHandler: VectorPushHandler
    @Inject lateinit var unifiedPushHelper: UnifiedPushHelper
    @Inject lateinit var notificationUtils: NotificationUtils
    @Inject lateinit var screenWakeManager: ScreenWakeManager
    @Inject lateinit var incomingCallRinger: IncomingCallRinger
    @Inject lateinit var webRtcCallManager: WebRtcCallManager
    @Inject lateinit var appScope: CoroutineScope

    override fun onNewToken(token: String) {
        Timber.tag(loggerTag.value).d("onNewToken fired, current FCM token length=${token.length}")
        fcmHelper.storeFcmToken(token)
        if (vectorPreferences.areNotificationEnabledForDevice() &&
                activeSessionHolder.hasActiveSession() &&
                unifiedPushHelper.isEmbeddedDistributor()
        ) {
            appScope.launch {
                try {
                    pushersManager.unregisterStalePushers(token)
                    pushersManager.enqueueRegisterPusherWithFcmKey(token)
                    Timber.tag(loggerTag.value).d("token registered to Matrix")
                } catch (e: Exception) {
                    Timber.tag(loggerTag.value).e(e, "onNewToken: Matrix push registration failed")
                }
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        Timber.tag(loggerTag.value).d("incoming push received keys=%s", data.keys)

        if (!vectorPreferences.areNotificationEnabledForDevice()) {
            Timber.tag(loggerTag.value).w("Push ignored: notifications disabled for device")
            return
        }

        val session = activeSessionHolder.getSafeActiveSession()
                ?: runBlocking { activeSessionHolder.getOrInitializeSession() }

        // Resolve call push — first from payload directly, then from Matrix event if needed
        var isCallPush = FcmPushPayloadHelper.isIncomingCallPush(data)
        if (!isCallPush && session != null) {
            isCallPush = runBlocking {
                FcmPushPayloadHelper.resolveIsCallPush(session, data)
            }
            if (isCallPush) {
                Timber.tag(loggerTag.value).d("call push resolved from event_id (m.call.invite)")
            }
        }

        // Declare AFTER isCallPush is fully resolved
        val callId = FcmPushPayloadHelper.extractCallId(data)
        val roomId = data["room_id"]

        screenWakeManager.wakeScreenForNotification()

        val isAppInForeground = ProcessLifecycleOwner.get()
                .lifecycle.currentState
                .isAtLeast(Lifecycle.State.STARTED)

        val hasActiveCall = webRtcCallManager.getCalls().isNotEmpty()
        val callRecentlyActiveInRoom = roomId != null &&
                webRtcCallManager.wasCallRecentlyActiveInRoom(roomId)

        val skipPlaceholder = !isCallPush && (
                hasActiveCall ||
                        callRecentlyActiveInRoom ||
                        (activeSessionHolder.hasActiveSession() &&
                                roomId != null &&
                                isAppInForeground &&
                                roomId == notificationUtils.notificationDrawerManager.currentRoomId)
                )

        if (skipPlaceholder) {
            Timber.tag(loggerTag.value).d("Skip placeholder: suppressed during active call or user in room")
        } else {
            val placeholderTag = "PENDING_${message.messageId ?: System.currentTimeMillis()}"

            if (isCallPush) {
                notificationUtils.cancelNotificationMessage(null, NotificationUtils.CALL_NOTIFICATION_ID)
                // Ringer is owned by CallAndroidService — not started here
            }

            showGenericNotificationFromFcm(message, placeholderTag, callId, isCallPush)

            // callId and roomId are declared above — no unresolved reference
            if (isCallPush && callId != null && roomId != null) {
                val callerName = data["sender_display_name"] ?: data["room_name"] ?: ""
                startCallForegroundService(callId, roomId, callerName)
            }
        }

        try {
            if (data.isNotEmpty()) {
                vectorPushHandler.handleSynchronously(pushParser.parsePushDataFcm(data))
            }
            promoteToVoipCallIfNeeded(isCallPush, roomId)
        } catch (e: Exception) {
            Timber.tag(loggerTag.value).e(e, "Push handling failed")
        } finally {
            screenWakeManager.releaseCpuWake()
        }
    }

    /**
     * After sync, WebRTC may have the real call object — hand off to CallAndroidService
     * for full call UI + ringtone.
     */
    private fun promoteToVoipCallIfNeeded(alreadyCallPush: Boolean, roomId: String?) {
        val ringingCall = webRtcCallManager.getCalls().firstOrNull {
            it.mxCall.state is CallState.LocalRinging
        } ?: return

        val callId = ringingCall.mxCall.callId
        Timber.tag(loggerTag.value).d("VoIP incoming call after sync callId=$callId")
        if (!alreadyCallPush) {
            incomingCallRinger.start(fromBg = true, roomId = roomId ?: ringingCall.nativeRoomId)
        }
        CallAndroidService.onIncomingCallRinging(
                context = applicationContext,
                callId = callId,
                isInBackground = true,
        )
    }

    private fun startCallForegroundService(callId: String, roomId: String, callerName: String = "") {
        try {
            val intent = Intent(this, CallForegroundService::class.java).apply {
                action = CallForegroundService.ACTION_INCOMING_CALL
                putExtra("callId", callId)
                putExtra("room_id", roomId)
                putExtra("caller_name", callerName)  // ← add this
            }
            ContextCompat.startForegroundService(this, intent)
            Timber.tag(loggerTag.value).d("CallForegroundService started callId=$callId")
        } catch (e: Exception) {
            Timber.tag(loggerTag.value).e(e, "Failed to start CallForegroundService")
        }
    }

    private fun showGenericNotificationFromFcm(
            message: RemoteMessage,
            overrideTag: String,
            callId: String?,
            isCall: Boolean,
    ) {
        if (!notificationUtils.areSystemNotificationsEnabled()) {
            Timber.tag(loggerTag.value).w("System notifications disabled — placeholder skipped")
            return
        }

        // ✅ FIX 1: Skip entirely for non-call pushes — real notification comes from vectorPushHandler
        if (!isCall) {
            Timber.tag(loggerTag.value).d("Skipping generic notification for message push — handled by sync")
            return
        }

        try {
            notificationUtils.createNotificationChannels()
            val data = message.data

            val title = message.notification?.title
                    ?: data["sender_display_name"]
                    ?: data["title"]
                    ?: data["subject"]
                    ?: data["room_name"]
                    ?: "Incoming Call"  // ✅ FIX 2: No "New Message" fallback needed anymore

            val body = message.notification?.body
                    ?: data["body"]
                    ?: data["message"]
                    ?: null  // ✅ FIX 3: Removed "New message received" fallback completely

            val isNoisy = data["noisy"]?.toBooleanStrictOrNull()
                    ?: FcmPushPayloadHelper.isHighPriorityPush(data)
                    ?: true

            // ✅ FIX 4: Always CALL_NOTIFICATION_ID here since we return early for messages above
            val notificationId = NotificationUtils.CALL_NOTIFICATION_ID

            Timber.tag(loggerTag.value).d("incoming call invite received callId=$callId roomId=${data["room_id"]}")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = ContextCompat.checkSelfPermission(
                        this, android.Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                Timber.tag(loggerTag.value).d("POST_NOTIFICATIONS granted=$granted")
            }

            if (Build.VERSION.SDK_INT >= 34) {
                val nm = getSystemService(NOTIFICATION_SERVICE) as? android.app.NotificationManager
                Timber.tag(loggerTag.value).d("canUseFullScreenIntent=${nm?.canUseFullScreenIntent()}")
            }

            // Cancel any stale message notifications before showing call notification
            notificationUtils.cancelNotificationMessage(
                    null, NotificationUtils.ROOM_MESSAGES_NOTIFICATION_ID
            )

            notificationUtils.showNotificationMessage(
                    tag = overrideTag,
                    id = notificationId,
                    notification = notificationUtils.buildGenericPushNotification(
                            title = title,
                            body = body,
                            isCall = true,  // ✅ always true now
                            roomId = data["room_id"],
                            threadId = data["thread_id"],
                            noisy = isNoisy,
                            callId = callId,
                    ).build(),
            )

            Timber.tag(loggerTag.value).d("lock screen call notification shown")

        } catch (e: Exception) {
            Timber.tag(loggerTag.value).e(e, "Failed to show FCM notification")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
