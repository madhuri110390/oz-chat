package im.vector.app.features.notifications

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import im.vector.app.R
import im.vector.app.features.call.VectorCallActivity

// CallForegroundService.kt
// CallForegroundService.kt
class CallForegroundService : Service() {

    companion object {
        const val ACTION_INCOMING_CALL = "action_incoming_call"
        const val ACTION_END_CALL      = "action_end_call"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_INCOMING_CALL -> handleIncomingCall(intent)
            ACTION_END_CALL      -> stopSelf()
        }
        return START_STICKY  // ✅ Restart if killed
    }

    private fun handleIncomingCall(intent: Intent) {
        val callId = intent.getStringExtra("callId") ?: return
        val roomId = intent.getStringExtra("room_id") ?: return

        // ✅ Must call startForeground immediately (within 5 seconds)
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
        // Full screen intent — shows on locked screen
        val fullScreenIntent = Intent(this, VectorCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("callId", callId)
            putExtra("room_id", roomId)
        }

        val fullScreenPi = PendingIntent.getActivity(
                this, 0, fullScreenIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Accept action
        val acceptPi = PendingIntent.getBroadcast(
                this, 1,
                Intent(this, CallActionReceiver::class.java).apply {
                    action = CallActionReceiver.ACTION_ACCEPT
                    putExtra("callId", callId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Decline action
        val declinePi = PendingIntent.getBroadcast(
                this, 2,
                Intent(this, CallActionReceiver::class.java).apply {
                    action = CallActionReceiver.ACTION_DECLINE
                    putExtra("callId", callId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NotificationUtils.CALL_NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Incoming Call")
                .setContentText("You have an incoming call")
                .setSmallIcon(R.drawable.oz_chat_playstore_icon)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)       // ✅ Critical for lock screen
                .setFullScreenIntent(fullScreenPi, true)             // ✅ Shows on locked screen
                .addAction(R.drawable.vector_notification_accept_invitation, "Accept", acceptPi)
                .addAction(R.drawable.vector_notification_reject_invitation, "Decline", declinePi)
                .setOngoing(true)                                    // ✅ Cannot be dismissed
                .setAutoCancel(false)
                .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
