package im.vector.app.push.fcm

import org.json.JSONObject
import org.matrix.android.sdk.api.MatrixPatterns
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.events.model.EventType


/**
 * Sygnal/FCM data payloads use Matrix push gateway fields (event_id, room_id, content, …).
 * Call invites are often only identifiable after fetching the event — not from top-level "type".
 */
object FcmPushPayloadHelper {

    fun isIncomingCallPush(data: Map<String, String?>): Boolean {
        when (data["type"]) {
            "m.call.invite", "call" -> return true
        }
        val content = data["content"] ?: return false
        return tryOrNull {
            val json = JSONObject(content)
            when {
                json.optString("type") == "m.call.invite" -> true
                json.has("call_id") && json.optString("type").contains("call", ignoreCase = true) -> true
                else -> content.contains("m.call.invite")
            }
        } ?: content.contains("m.call.invite")
    }

    /**
     * Fetches the pushed event when possible so calls are not shown as "New message received".
     */
    suspend fun resolveIsCallPush(session: Session, data: Map<String, String?>): Boolean {
        if (isIncomingCallPush(data)) return true
        val eventId = data["event_id"]?.takeIf { MatrixPatterns.isEventId(it) } ?: return false
        val roomId = data["room_id"]?.takeIf { MatrixPatterns.isRoomId(it) } ?: return false
        val event = tryOrNull { session.eventService().getEvent(roomId, eventId) } ?: return false
        val type = event.getClearType()
        return type == EventType.CALL_INVITE ||
                type in EventType.ELEMENT_CALL_NOTIFY.values
    }

    fun extractCallId(data: Map<String, String?>): String? {
        data["call_id"]?.takeIf { it.isNotBlank() }?.let { return it }
        data["callId"]?.takeIf { it.isNotBlank() }?.let { return it }
        val content = data["content"] ?: return null
        return tryOrNull {
            JSONObject(content).optString("call_id").takeIf { it.isNotBlank() }
        }
    }

    fun isHighPriorityPush(data: Map<String, String?>): Boolean {
        return data["prio"]?.equals("high", ignoreCase = true) == true
    }
}
