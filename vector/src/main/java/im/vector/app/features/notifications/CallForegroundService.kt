package im.vector.app.features.notifications

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.services.IncomingCallRinger
import im.vector.app.features.call.VectorCallActivity
import im.vector.lib.strings.CommonStrings
import javax.inject.Inject

@AndroidEntryPoint
class CallForegroundService : Service() {

    @Inject lateinit var incomingCallRinger: IncomingCallRinger

    companion object {
        const val ACTION_INCOMING_CALL = "action_incoming_call"
        const val ACTION_END_CALL = "action_end_call"
        const val ACTION_STOP = "ACTION_STOP"

        fun stop(context: Context) {
            // Cancel ALL possible notification IDs used by call notifications
            val nm = NotificationManagerCompat.from(context)
            nm.cancel(NotificationUtils.CALL_NOTIFICATION_ID)
            nm.cancelAll()  // ← nuclear option — clears everything
            val intent = Intent(context, CallForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                // Service may already be dead — ignore
            }
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
        return START_STICKY
    }

    // Always stop ringer when service is destroyed — covers all exit paths
    override fun onDestroy() {
        incomingCallRinger.stop()
        NotificationManagerCompat.from(this).cancel(NotificationUtils.CALL_NOTIFICATION_ID)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    private fun stopRingerAndSelf() {
        incomingCallRinger.stop()
        NotificationManagerCompat.from(this).cancel(NotificationUtils.CALL_NOTIFICATION_ID)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun handleIncomingCall(intent: Intent) {
        val callId = intent.getStringExtra("callId") ?: return
        val roomId = intent.getStringExtra("room_id") ?: return

        incomingCallRinger.start(fromBg = true, roomId = roomId)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(
                    NotificationUtils.CALL_NOTIFICATION_ID,
                    buildCallNotification(callId, roomId),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            )
        } else {
            startForeground(
                    NotificationUtils.CALL_NOTIFICATION_ID,
                    buildCallNotification(callId, roomId)
            )
        }
    }

    private fun buildCallNotification(callId: String, roomId: String): Notification {
        val fullScreenIntent = VectorCallActivity.newIntent(
                context = this,
                callId = callId,
                signalingRoomId = roomId,
                otherUserId = "",
                isIncomingCall = true,
                isVideoCall = false,
                mode = null,
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        val fullScreenPi = PendingIntent.getActivity(
                this, 0, fullScreenIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val acceptPi = PendingIntent.getBroadcast(
                this, 1,
                Intent(this, CallActionReceiver::class.java).apply {
                    action = CallActionReceiver.ACTION_ACCEPT
                    putExtra("callId", callId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val declinePi = PendingIntent.getBroadcast(
                this, 2,
                Intent(this, CallActionReceiver::class.java).apply {
                    action = CallActionReceiver.ACTION_DECLINE
                    putExtra("callId", callId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NotificationUtils.CALL_NOTIFICATION_CHANNEL_ID)
                .setContentTitle(getString(CommonStrings.incoming_voice_call))
                .setContentText(getString(CommonStrings.incoming_voice_call))
                .setSmallIcon(R.drawable.oz_chat_playstore_icon)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setFullScreenIntent(fullScreenPi, true)
                .addAction(R.drawable.vector_notification_accept_invitation, "Accept", acceptPi)
                .addAction(R.drawable.vector_notification_reject_invitation, "Decline", declinePi)
                .setOngoing(true)
                .setAutoCancel(false)
                .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
