package im.vector.app.features.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import im.vector.app.features.call.VectorCallActivity

// CallActionReceiver.kt
class CallActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_ACCEPT  = "action_call_accept"
        const val ACTION_DECLINE = "action_call_decline"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val callId = intent.getStringExtra("callId") ?: return

        when (intent.action) {
            ACTION_ACCEPT -> {
                // Launch CallActivity to handle the call
                context.startActivity(
                        Intent(context, VectorCallActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            putExtra("callId", callId)
                            putExtra("action", "accept")
                        }
                )
            }
            ACTION_DECLINE -> {
                // Stop the service and dismiss notification
                context.stopService(Intent(context, CallForegroundService::class.java))
            }
        }
    }


}
