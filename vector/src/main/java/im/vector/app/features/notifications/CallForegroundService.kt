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

//        fun stop(context: Context) {
//            NotificationManagerCompat.from(context).cancel(NotificationUtils.CALL_NOTIFICATION_ID)
//            val intent = Intent(context, CallForegroundService::class.java).apply {
//                action = ACTION_STOP
//            }
//            try { context.startService(intent) } catch (e: Exception) { /* already dead */ }
//        }

        fun stop(context: Context) {
            // Do NOT cancel notification — CallAndroidService reuses same ID
            try {
                context.stopService(Intent(context, CallForegroundService::class.java))
            } catch (e: Exception) { }
        }

    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_INCOMING_CALL -> handleIncomingCall(intent)
            ACTION_STOP, ACTION_END_CALL -> {
                stopSelfClean()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // DO NOT stop ringer here — CallAndroidService owns it after handoff
        // DO NOT cancel notification — CallAndroidService reuses CALL_NOTIFICATION_ID
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION") stopForeground(false)
        }
        super.onDestroy()
    }
//    private fun stopSelfClean() {
//        NotificationManagerCompat.from(this).cancel(NotificationUtils.CALL_NOTIFICATION_ID)
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
//            stopForeground(STOP_FOREGROUND_REMOVE)
//        } else {
//            @Suppress("DEPRECATION") stopForeground(true)
//        }
//        stopSelf()
//    }
private fun stopSelfClean() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        stopForeground(STOP_FOREGROUND_DETACH)
    } else {
        @Suppress("DEPRECATION") stopForeground(false)
    }
    stopSelf()
}
    private fun handleIncomingCall(intent: Intent) {
        val callId = intent.getStringExtra("callId") ?: return
        val roomId = intent.getStringExtra("room_id") ?: return
        val callerName = intent.getStringExtra("caller_name")
                ?.takeIf { it.isNotBlank() }
                ?: getString(CommonStrings.incoming_voice_call)

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
        // DO NOT start ringer here — CallAndroidService owns the ringer
        // Starting it here resets the 8-cycle counter when CFS hands off to CallAndroidService
        Timber.tag(TAG).d("CallForegroundService placeholder notification shown")
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

        val acceptPi = PendingIntent.getBroadcast(
                this, 1,
                Intent(this, CallActionReceiver::class.java).apply {
                    action = CallActionReceiver.ACTION_ACCEPT
                    putExtra("callId", callId)
                    putExtra("room_id", roomId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val declinePi = PendingIntent.getBroadcast(
                this, 2,
                Intent(this, CallActionReceiver::class.java).apply {
                    action = CallActionReceiver.ACTION_DECLINE
                    putExtra("callId", callId)
                    putExtra("room_id", roomId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NotificationUtils.CALL_NOTIFICATION_CHANNEL_ID)
                .setContentTitle(callerName)
                .setContentText(getString(CommonStrings.incoming_voice_call))
                .setSmallIcon(R.drawable.oz_chat_playstore_icon)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setFullScreenIntent(fullScreenPi, true)
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .addAction(R.drawable.ic_call_hangup, "Decline", declinePi)
                .addAction(R.drawable.ic_call_answer, "Accept", acceptPi)
                .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
