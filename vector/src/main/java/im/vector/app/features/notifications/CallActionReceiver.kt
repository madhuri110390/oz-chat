package im.vector.app.features.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import im.vector.app.core.extensions.singletonEntryPoint
import im.vector.app.features.call.VectorCallActivity
import im.vector.app.features.call.webrtc.WebRtcCallManager
import timber.log.Timber

class CallActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_ACCEPT  = "action_call_accept"
        const val ACTION_DECLINE = "action_call_decline"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val callId = intent.getStringExtra("callId") ?: return
        val webRtcCallManager = context.singletonEntryPoint().webRtcCallManager()

        when (intent.action) {
            ACTION_ACCEPT -> {
                CallForegroundService.stop(context)
                // Try WebRtcCall first (sync already processed invite)
                val call = webRtcCallManager.getCallById(callId)
                if (call != null) {
                    // Call object exists — open activity with INCOMING_ACCEPT mode
                    val activityIntent = VectorCallActivity.newIntent(
                            context = context,
                            call = call,
                            mode = VectorCallActivity.INCOMING_ACCEPT
                    ).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    context.startActivity(activityIntent)
                } else {
                    // Call not yet in WebRtcCallManager (sync not done yet)
                    // Accept via headset button tap which waits for the call to be ready
                    // OR open the ringing screen and let user tap accept there
                    Timber.w("CallActionReceiver: WebRtcCall not ready yet for callId=$callId, opening ringing screen")
                    val roomId = intent.getStringExtra("room_id") ?: run {
                        Timber.w("CallActionReceiver: no room_id either, cannot proceed")
                        return
                    }
                    val activityIntent = VectorCallActivity.newIntent(
                            context = context,
                            callId = callId,
                            signalingRoomId = roomId,
                            otherUserId = "",
                            isIncomingCall = true,
                            isVideoCall = false,
                            mode = VectorCallActivity.INCOMING_RINGING
                    ).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    context.startActivity(activityIntent)
                }
            }
            ACTION_DECLINE -> {
                // End the WebRTC call properly — this triggers the full teardown chain
                val call = webRtcCallManager.getCallById(callId)
                if (call != null) {
                    call.endCall()
                } else {
                    Timber.w("CallActionReceiver: call not found for decline callId=$callId")
                }
                // Always stop foreground service
                CallForegroundService.stop(context)
            }
        }
    }
}
