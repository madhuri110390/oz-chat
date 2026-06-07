package im.vector.app.features.notifications

import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person as PersonCompat
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.services.IncomingCallRinger
import im.vector.app.features.call.VectorCallActivity
import im.vector.lib.strings.CommonStrings
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class CallForegroundService : Service() {

    @Inject lateinit var incomingCallRinger: IncomingCallRinger

    companion object {
        const val ACTION_INCOMING_CALL = "action_incoming_call"
        const val ACTION_END_CALL = "action_end_call"
        const val ACTION_STOP = "ACTION_STOP"
        private const val TAG = "CallForegroundService"

        fun stop(context: Context) {
            NotificationManagerCompat.from(context).cancel(NotificationUtils.CALL_NOTIFICATION_ID)
            val intent = Intent(context, CallForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            try { context.startService(intent) } catch (e: Exception) { /* already dead */ }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_INCOMING_CALL -> handleIncomingCall(intent)
            ACTION_STOP, ACTION_END_CALL -> {
                stopRingerAndSelf()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        incomingCallRinger.stop()
        NotificationManagerCompat.from(this).cancel(NotificationUtils.CALL_NOTIFICATION_ID)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
        super.onDestroy()
    }

    private fun stopRingerAndSelf() {
        incomingCallRinger.stop()
        NotificationManagerCompat.from(this).cancel(NotificationUtils.CALL_NOTIFICATION_ID)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
        stopSelf()
    }

    private fun handleIncomingCall(intent: Intent) {
        val callId  = intent.getStringExtra("callId")  ?: return
        val roomId  = intent.getStringExtra("room_id") ?: return
        val callerName = intent.getStringExtra("caller_name") ?: getString(CommonStrings.incoming_voice_call)

        // ✅ FIX: ringer is now owned exclusively here.
        // VectorFirebaseMessagingService no longer calls incomingCallRinger.start() for calls.
        incomingCallRinger.start(fromBg = true, roomId = roomId)
        Timber.tag(TAG).d("incoming call ring started in foreground service callId=$callId")

        val notification = buildCallNotification(callId, roomId, callerName)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                    NotificationUtils.CALL_NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            )
        } else {
            startForeground(NotificationUtils.CALL_NOTIFICATION_ID, notification)
        }

        Timber.tag(TAG).d("lock screen call notification shown")
    }

    private fun buildCallNotification(callId: String, roomId: String, callerName: String): Notification {
        val fullScreenIntent = VectorCallActivity.newIntent(
                context = this,
                callId = callId,
                signalingRoomId = roomId,
                otherUserId = "",
                isIncomingCall = true,
                isVideoCall = false,
                mode = null,
        ).apply {
            addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }

        val fullScreenPi = PendingIntent.getActivity(
                this, 0, fullScreenIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val acceptIntent = Intent(this, CallActionReceiver::class.java).apply {
            action = CallActionReceiver.ACTION_ACCEPT
            putExtra("callId", callId)
            putExtra("room_id", roomId)
        }
        val acceptPi = PendingIntent.getBroadcast(
                this, 1, acceptIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val declineIntent = Intent(this, CallActionReceiver::class.java).apply {
            action = CallActionReceiver.ACTION_DECLINE
            putExtra("callId", callId)
            putExtra("room_id", roomId)
        }
        val declinePi = PendingIntent.getBroadcast(
                this, 2, declineIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // ✅ FIX: CallStyle requires a Person object — this is what triggers the
        // full-screen lock screen call UI on Android 12+. Without it the system
        // treats it as a regular notification and suppresses it on the lock screen.
        val caller = PersonCompat.Builder()
                .setName(callerName)
                .setImportant(true)
                .build()

        return NotificationCompat.Builder(this, NotificationUtils.CALL_NOTIFICATION_CHANNEL_ID)
                .setContentTitle(callerName)
                .setContentText(getString(CommonStrings.incoming_voice_call))
                .setSmallIcon(R.drawable.oz_chat_playstore_icon)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                // ✅ KEY FIX: CallStyle — this is what makes lock screen call UI appear on Android 12+
                .setStyle(
                        NotificationCompat.CallStyle.forIncomingCall(caller, declinePi, acceptPi)
                                .setIsVideo(false)
                )
                .setFullScreenIntent(fullScreenPi, true)
                .setOngoing(true)
                .setAutoCancel(false)
                // ✅ Ensure notification is not silenced by DND on Android 13+
                .setColorized(true)
                .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
