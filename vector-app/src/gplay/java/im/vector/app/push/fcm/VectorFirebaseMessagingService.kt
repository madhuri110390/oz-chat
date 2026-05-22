package im.vector.app.push.fcm

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
import im.vector.app.features.mdm.MdmData
import im.vector.app.features.mdm.MdmService
import im.vector.app.features.notifications.NotificationUtils

import im.vector.app.features.settings.VectorPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.logger.LoggerTag
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
    @Inject lateinit var mdmService: MdmService
    @Inject lateinit var notificationUtils: NotificationUtils
    @Inject lateinit var screenWakeManager: ScreenWakeManager
    @Inject lateinit var appScope: CoroutineScope


    override fun onNewToken(token: String) {
        Timber.tag(loggerTag.value).d("onNewToken: New Firebase token received, length=${token.length}")
        fcmHelper.storeFcmToken(token)
        if (vectorPreferences.areNotificationEnabledForDevice() &&
                activeSessionHolder.hasActiveSession() &&
                unifiedPushHelper.isEmbeddedDistributor()
        ) {
            appScope.launch {
                try {
                    // Unregister stale pushers across the current device
                    Timber.tag(loggerTag.value).d("onNewToken: Checking and unregistering stale pushers from Matrix server")
                    pushersManager.unregisterStalePushers(token)

                    Timber.tag(loggerTag.value).d("onNewToken: Sending explicit Matrix push registration for new token")
                    try {
                        pushersManager.registerPusherWithFcmKey(token)
                        Timber.tag(loggerTag.value).d("onNewToken: Successfully registered new FCM pusher on Matrix server")
                    } catch (e: Exception) {
                        Timber.tag(loggerTag.value).e(e, "onNewToken: Failed to register new FCM pusher on Matrix server")
                    }
                } catch (e: Exception) {
                    Timber.tag(loggerTag.value).e(e, "Failed to manage pusher lifecycle on new token")
                }
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        Timber.tag(loggerTag.value).d("=== FCM RECEIVED === keys=%s", message.data.keys)

        if (!vectorPreferences.areNotificationEnabledForDevice()) return

        // 1. Wake screen immediately — this is what turns on the display from locked state
        screenWakeManager.wakeScreenForNotification()

        // 2. Skip placeholder if app is in foreground and showing the same room
        val data = message.data
        val roomId = data["room_id"]
        val isAppInForeground = ProcessLifecycleOwner.get()
            .lifecycle.currentState
            .isAtLeast(Lifecycle.State.STARTED)
        if (activeSessionHolder.hasActiveSession() &&
                roomId != null &&
                isAppInForeground &&
                roomId == notificationUtils.notificationDrawerManager.currentRoomId) {
            Timber.tag(loggerTag.value).d("Skip placeholder: user is already in the room")
        } else {
            // Show placeholder instantly so user sees something on the woken screen
            val placeholderTag = "PENDING_${message.messageId ?: System.currentTimeMillis()}"
            val callId = data["call_id"] ?: data["callId"]
            showGenericNotificationFromFcm(message, placeholderTag, callId)

            // 3. Run Matrix sync in background — replaces placeholder with rich notification
            appScope.launch {
                try {
                    if (data.isNotEmpty()) {
                        vectorPushHandler.handle(pushParser.parsePushDataFcm(data))
                    }
                    // Rich notification now posted — remove placeholder
                    notificationUtils.cancelNotificationMessage(
                            placeholderTag,
                            NotificationUtils.ROOM_MESSAGES_NOTIFICATION_ID
                    )
                } catch (e: Exception) {
                    Timber.tag(loggerTag.value).e(e, "Push handling failed — placeholder kept")
                } finally {
                    screenWakeManager.releaseCpuWake()
                }
            }
        }
    }

    private fun showGenericNotificationFromFcm(message: RemoteMessage, overrideTag: String, callId: String?) {
        if (!notificationUtils.areSystemNotificationsEnabled()) return

        try {
            notificationUtils.createNotificationChannels()

            val data = message.data

            val isCall = data["type"] == "m.call.invite"                // ✅ Fixed: removed loose `callId != null` fallback;
                    || data["type"] == "call"                           //    type-based check is authoritative

            val title = message.notification?.title                     // ✅ Fixed: guaranteed non-null fallback so notification
                    ?: data["title"]                                    //    title is never blank
                    ?: data["subject"]
                    ?: if (isCall) "Incoming Call" else "New Message"

            val body = message.notification?.body
                    ?: data["body"]
                    ?: data["message"]
                    ?: if (isCall) "You have an incoming call" else "New message received"

            val isNoisy = data["noisy"]?.toBooleanStrictOrNull() ?: true // ✅ Fixed: derive noisiness from payload; default true

            val notificationId = if (isCall)                            // ✅ Extracted for clarity / easier unit-testing
                NotificationUtils.CALL_NOTIFICATION_ID
            else
                NotificationUtils.ROOM_MESSAGES_NOTIFICATION_ID

            if (isCall) {
                Timber.tag(loggerTag.value).d("push received: call invite detected")
                val isAppForeground = androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
                val appState = if (isAppForeground) "foreground" else "background/locked"
                Timber.tag(loggerTag.value).d("app state: $appState")
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    val permissionState = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                    Timber.tag(loggerTag.value).d("permission state for notifications: $permissionState")
                }
                val notifManager = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    Timber.tag(loggerTag.value).d("full-screen intent availability: ${notifManager?.canUseFullScreenIntent()}")
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    val channel = notifManager?.getNotificationChannel(NotificationUtils.CALL_NOTIFICATION_CHANNEL_ID)
                    Timber.tag(loggerTag.value).d("notification channel selected: ${channel?.id}, importance: ${channel?.importance}, sound: ${channel?.sound}")
                }
            }

            notificationUtils.showNotificationMessage(
                    tag = overrideTag,
                    id = notificationId,
                    notification = notificationUtils.buildGenericPushNotification(
                            title    = title,
                            body     = body,
                            isCall   = isCall,
                            roomId   = data["room_id"],
                            threadId = data["thread_id"],
                            noisy    = isNoisy,
                            callId   = callId, // ✅ Fixed: pass callId
                    ).build(),
            )
            if (isCall) {
                Timber.tag(loggerTag.value).d("notification posted: full-screen intent attempted")
            }
        } catch (e: Exception) {
            Timber.tag(loggerTag.value).e(e, "Failed to show placeholder notification")
        }
    }
    override fun onDestroy() {
        super.onDestroy()
    }
}
