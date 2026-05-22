package im.vector.app.features.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

// CallInviteReceiver.kt
class CallInviteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val callId = intent.getStringExtra("callId") ?: return
        val roomId = intent.getStringExtra("room_id") ?: return


        val serviceIntent = Intent(context, CallForegroundService::class.java).apply {
            action = CallForegroundService.ACTION_INCOMING_CALL
            putExtra("callId", callId)
            putExtra("room_id", roomId)
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }


}
