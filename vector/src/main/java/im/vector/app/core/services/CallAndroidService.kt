/*
 * Copyright 2020-2024 New Vector Ltd.
 * Copyright 2019 New Vector Ltd
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.services

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Binder
import android.support.v4.media.session.MediaSessionCompat
import android.view.KeyEvent
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.media.session.MediaButtonReceiver
import com.airbnb.mvrx.Mavericks
import com.bumptech.glide.Glide
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.core.extensions.singletonEntryPoint
import im.vector.app.core.extensions.startForegroundCompat
import im.vector.app.features.call.CallArgs
import im.vector.app.features.call.VectorCallActivity
import im.vector.app.features.call.audio.MicrophoneAccessService
import im.vector.app.features.call.telecom.CallConnection
import im.vector.app.features.call.webrtc.WebRtcCall
import im.vector.app.features.call.webrtc.WebRtcCallManager
import im.vector.app.features.call.webrtc.getOpponentAsMatrixItem
import im.vector.app.features.displayname.getBestName
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.notifications.CallForegroundService
import im.vector.app.features.notifications.NotificationUtils
import im.vector.app.features.popup.IncomingCallAlert
import im.vector.app.features.popup.PopupAlertManager
import im.vector.app.features.settings.VectorPreferences
import im.vector.lib.core.utils.compat.getParcelableExtraCompat
import im.vector.lib.core.utils.compat.getSerializableExtraCompat
import org.matrix.android.sdk.api.logger.LoggerTag
import org.matrix.android.sdk.api.session.content.ContentUrlResolver
import org.matrix.android.sdk.api.session.room.model.call.EndCallReason
import org.matrix.android.sdk.api.util.MatrixItem
import timber.log.Timber
import javax.inject.Inject

private val loggerTag = LoggerTag("CallService", LoggerTag.VOIP)

@AndroidEntryPoint
class CallAndroidService : VectorAndroidService() {

    private val connections = mutableMapOf<String, CallConnection>()
    private val knownCalls = mutableMapOf<String, CallInformation>()
    private val connectedCallIds = mutableSetOf<String>()

    private lateinit var notificationManager: NotificationManagerCompat
    @Inject lateinit var notificationUtils: NotificationUtils
    @Inject lateinit var callManager: WebRtcCallManager
    @Inject lateinit var avatarRenderer: AvatarRenderer
    @Inject lateinit var alertManager: PopupAlertManager
    @Inject lateinit var vectorPreferences: VectorPreferences
    @Inject lateinit var incomingCallRinger: IncomingCallRinger

    private var callRingPlayerOutgoing: CallRingPlayerOutgoing? = null

    private var mediaSession: MediaSessionCompat? = null
    private val mediaSessionButtonCallback = object : MediaSessionCompat.Callback() {
        override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
            val keyEvent = mediaButtonEvent?.getParcelableExtraCompat<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    ?: return false
            if (keyEvent.keyCode == KeyEvent.KEYCODE_HEADSETHOOK) {
                callManager.headSetButtonTapped()
                return true
            }
            return false
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = NotificationManagerCompat.from(this)
        callRingPlayerOutgoing = CallRingPlayerOutgoing(applicationContext, callManager)
    }

    override fun onDestroy() {
        incomingCallRinger.stop()
        callRingPlayerOutgoing?.stop()
        notificationManager.cancelAll()
        stopForegroundCompat()
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.tag(loggerTag.value).v("onStartCommand $intent")
        if (mediaSession == null) {
            mediaSession = MediaSessionCompat(applicationContext, CallAndroidService::class.java.name).apply {
                setCallback(mediaSessionButtonCallback)
            }
        }
        mediaSession?.let {
            MediaButtonReceiver.handleIntent(it, intent)
        }

        when (intent?.action) {

//            ACTION_INCOMING_RINGING_CALL -> {
//                mediaSession?.isActive = true
//                val fromBg = intent.getBooleanExtra(EXTRA_IS_IN_BG, false)
//                val callId = intent.getStringExtra(EXTRA_CALL_ID)
//                val customTone = callId
//                        ?.let { callManager.getCallById(it) }
//                        ?.nativeRoomId
//                        ?.let { vectorPreferences.getRoomNotificationTone(it) }
//                displayIncomingCallNotification(intent)  // become foreground first
//                incomingCallRinger.start(fromBg, customTone)
//
//                // Step 3: stop CallForegroundService LAST — after we are foreground
//                // Stopping it first cancels CALL_NOTIFICATION_ID before we post ours
//                CallForegroundService.stop(applicationContext)
//            }
            ACTION_INCOMING_RINGING_CALL -> {
                mediaSession?.isActive = true
                val fromBg = intent.getBooleanExtra(EXTRA_IS_IN_BG, false)
                val callId = intent.getStringExtra(EXTRA_CALL_ID)
                val customTone = callId
                        ?.let { callManager.getCallById(it) }
                        ?.nativeRoomId
                        ?.let { vectorPreferences.getRoomNotificationTone(it) }
                displayIncomingCallNotification(intent)
                incomingCallRinger.start(fromBg, customTone)
                // No CallForegroundService.stop() here
            }
            ACTION_OUTGOING_RINGING_CALL -> {
                mediaSession?.isActive = true
                callRingPlayerOutgoing?.start()
                displayOutgoingRingingCallNotification(intent)
            }
            ACTION_ONGOING_CALL -> {
                incomingCallRinger.stop()
                callRingPlayerOutgoing?.stop()
                CallForegroundService.stop(applicationContext)
                displayCallInProgressNotification(intent)
            }
            ACTION_CALL_TERMINATED -> {
                handleCallTerminated(intent)
            }
            else -> {
                handleUnexpectedState(null)
            }
        }

        return START_REDELIVER_INTENT
    }

    // ================================================================================
    // Call notification management
    // ================================================================================

    private fun displayIncomingCallNotification(intent: Intent) {
        Timber.tag(loggerTag.value).v("displayIncomingCallNotification $intent")
        val callId = intent.getStringExtra(EXTRA_CALL_ID) ?: ""
        val call = callManager.getCallById(callId) ?: return Unit.also {
            handleUnexpectedState(callId)
        }
        val callInformation = call.toCallInformation()
        val isVideoCall = call.mxCall.isVideoCall
        val fromBg = intent.getBooleanExtra(EXTRA_IS_IN_BG, false)

        Timber.tag(loggerTag.value).v("displayIncomingCallNotification : display the dedicated notification")

        val incomingCallAlert = IncomingCallAlert(callId,
                shouldBeDisplayedIn = { activity ->
                    if (activity is VectorCallActivity) {
                        activity.intent.getParcelableExtraCompat<CallArgs>(Mavericks.KEY_ARG)?.callId != call.callId
                    } else true
                }
        ).apply {
            viewBinder = IncomingCallAlert.ViewBinder(
                    matrixItem = callInformation.opponentMatrixItem,
                    avatarRenderer = avatarRenderer,
                    isVideoCall = isVideoCall,
                    onAccept = { showCallScreen(call, VectorCallActivity.INCOMING_ACCEPT) },
                    onReject = { call.endCall() }
            )
            dismissedAction = Runnable { call.endCall() }
            contentAction = Runnable { showCallScreen(call, VectorCallActivity.INCOMING_RINGING) }
        }
        alertManager.postVectorAlert(incomingCallAlert)

        val avatarBitmap: Bitmap? = try {
            callInformation.opponentMatrixItem?.avatarUrl?.let { url ->
                val resolvedUrl = singletonEntryPoint()
                        .activeSessionHolder()
                        .getSafeActiveSession()
                        ?.contentUrlResolver()
                        ?.resolveThumbnail(url, 128, 128, ContentUrlResolver.ThumbnailMethod.SCALE)
                resolvedUrl?.let {
                    Glide.with(applicationContext)
                            .asBitmap()
                            .load(it)
                            .circleCrop()
                            .submit(128, 128)
                            .get()
                }
            }
        } catch (e: Exception) {
            Timber.tag(loggerTag.value).w(e, "Failed to load avatar for notification")
            null
        }

        val notification = notificationUtils.buildIncomingCallNotification(
                call = call,
                title = callInformation.opponentMatrixItem?.getBestName() ?: callInformation.opponentUserId,
                fromBg = fromBg,
                avatarBitmap = avatarBitmap
        )
        if (knownCalls.isEmpty()) {
            startForegroundCompat(NotificationUtils.CALL_NOTIFICATION_ID, notification) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            }
        } else {
            notificationManager.notify(NotificationUtils.CALL_NOTIFICATION_ID, notification)
        }
        knownCalls[callId] = callInformation
    }

    private fun handleCallTerminated(intent: Intent) {
        incomingCallRinger.stop()
        callRingPlayerOutgoing?.stop()
        NotificationManagerCompat.from(this).cancel(NotificationUtils.CALL_NOTIFICATION_ID)
        // Force stop CallForegroundService
        try {
            stopService(Intent(this, CallForegroundService::class.java))
        } catch (e: Exception) { }
        val callId = intent.getStringExtra(EXTRA_CALL_ID) ?: ""
        val endCallReason = intent.getSerializableExtraCompat<EndCallReason>(EXTRA_END_CALL_REASON)
        val rejected = intent.getBooleanExtra(EXTRA_END_CALL_REJECTED, false)
        val nativeRoomId = intent.getStringExtra(EXTRA_NATIVE_ROOM_ID)
        val opponentUserId = intent.getStringExtra(EXTRA_OPPONENT_USER_ID) ?: ""
        val isVideoCall = intent.getBooleanExtra(EXTRA_IS_VIDEO_CALL, false)
        val isOutgoing = intent.getBooleanExtra(EXTRA_IS_OUTGOING, false)

        // Stop ringers and cancel ALL notifications immediately
        incomingCallRinger.stop()
        callRingPlayerOutgoing?.stop()
        alertManager.cancelAlert(callId)
        notificationManager.cancel(callId.hashCode())
        notificationManager.cancel(NotificationUtils.CALL_NOTIFICATION_ID)
// Don't cancelAll() — missed call notification posted by WebRtcCallManager
        CallForegroundService.stop(applicationContext)

        val terminatedCall = knownCalls.remove(callId)
        val wasConnected = connectedCallIds.remove(callId)

        val callInfo = terminatedCall ?: if (nativeRoomId != null) {
            CallInformation(
                    callId = callId,
                    nativeRoomId = nativeRoomId,
                    opponentUserId = opponentUserId,
                    opponentMatrixItem = null,
                    isVideoCall = isVideoCall,
                    isOutgoing = isOutgoing
            )
        } else null

//        if (callInfo != null && !wasConnected && !rejected &&
//                endCallReason != EndCallReason.ANSWERED_ELSEWHERE) {
//
//            if (isOutgoing) {
//                // Caller side — receiver didn't answer
//                Timber.tag(loggerTag.value).v("Showing call not answered notification for $callId")
//                val notification = notificationUtils.buildCallNotAnsweredNotification(callInfo)
//                notificationManager.notify(
//                        MISSED_CALL_TAG,
//                        callInfo.nativeRoomId.hashCode(),
//                        notification
//                )
//            } else {
//                // Receiver side — missed incoming call
//                Timber.tag(loggerTag.value).v("Showing missed call notification for $callId")
//                val notification = notificationUtils.buildCallMissedNotification(callInfo)
//                notificationManager.notify(
//                        MISSED_CALL_TAG,
//                        callInfo.nativeRoomId.hashCode(),
//                        notification
//                )
//            }
//        }

        if (knownCalls.isEmpty()) {
            Timber.tag(loggerTag.value).v("No more calls, stopping service")
            stopForegroundCompat()
            mediaSession?.isActive = false
            myStopSelf()
            stopService(Intent(this, MicrophoneAccessService::class.java))
        }
    }

    private fun showCallScreen(call: WebRtcCall, mode: String) {
        val intent = VectorCallActivity.newIntent(
                context = this,
                call = call,
                mode = mode
        )
        startActivity(intent)
    }

    private fun displayOutgoingRingingCallNotification(intent: Intent) {
        val callId = intent.getStringExtra(EXTRA_CALL_ID) ?: ""
        val call = callManager.getCallById(callId) ?: return Unit.also {
            handleUnexpectedState(callId)
        }
        val callInformation = call.toCallInformation()
        Timber.tag(loggerTag.value).v("displayOutgoingCallNotification : display the dedicated notification")
        val notification = notificationUtils.buildOutgoingRingingCallNotification(
                call = call,
                title = callInformation.opponentMatrixItem?.getBestName() ?: callInformation.opponentUserId
        )
        if (knownCalls.isEmpty()) {
            startForegroundCompat(callId.hashCode(), notification) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            }
        } else {
            notificationManager.notify(callId.hashCode(), notification)
        }
        knownCalls[callId] = callInformation
    }

    private fun displayCallInProgressNotification(intent: Intent) {
        Timber.tag(loggerTag.value).v("displayCallInProgressNotification")
        val callId = intent.getStringExtra(EXTRA_CALL_ID) ?: ""
        connectedCallIds.add(callId)
        val call = callManager.getCallById(callId) ?: return Unit.also {
            handleUnexpectedState(callId)
        }
        alertManager.cancelAlert(callId)
        val callInformation = call.toCallInformation()
        val notification = notificationUtils.buildPendingCallNotification(
                call = call,
                title = callInformation.opponentMatrixItem?.getBestName() ?: callInformation.opponentUserId
        )
        if (knownCalls.isEmpty()) {
            startForegroundCompat(callId.hashCode(), notification) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            }
        } else {
            notificationManager.notify(callId.hashCode(), notification)
        }
        knownCalls[callId] = callInformation
    }

    private fun handleUnexpectedState(callId: String?) {
        incomingCallRinger.stop()
        callRingPlayerOutgoing?.stop()
        notificationManager.cancelAll()
        CallForegroundService.stop(applicationContext)
        stopForegroundCompat()
        mediaSession?.isActive = false
        myStopSelf()
    }

    fun addConnection(callConnection: CallConnection) {
        connections[callConnection.callId] = callConnection
    }

    private fun WebRtcCall.toCallInformation(): CallInformation {
        return CallInformation(
                callId = this.callId,
                nativeRoomId = this.nativeRoomId,
                opponentUserId = this.mxCall.opponentUserId,
                opponentMatrixItem = singletonEntryPoint().activeSessionHolder().getSafeActiveSession()?.let {
                    this.getOpponentAsMatrixItem(it)
                },
                isVideoCall = this.mxCall.isVideoCall,
                isOutgoing = this.mxCall.isOutgoing
        )
    }

    data class CallInformation(
            val callId: String,
            val nativeRoomId: String,
            val opponentUserId: String,
            val opponentMatrixItem: MatrixItem?,
            val isVideoCall: Boolean,
            val isOutgoing: Boolean
    )

    companion object {
        private const val DEFAULT_NOTIFICATION_ID = 6480
        private const val MISSED_CALL_TAG = "MISSED_CALL_TAG"

        private const val ACTION_INCOMING_RINGING_CALL = "im.vector.app.core.services.CallService.ACTION_INCOMING_RINGING_CALL"
        private const val ACTION_OUTGOING_RINGING_CALL = "im.vector.app.core.services.CallService.ACTION_OUTGOING_RINGING_CALL"
        private const val ACTION_ONGOING_CALL = "im.vector.app.core.services.CallService.ACTION_ONGOING_CALL"
        private const val ACTION_CALL_TERMINATED = "im.vector.app.core.services.CallService.ACTION_CALL_TERMINATED"

        private const val EXTRA_CALL_ID = "EXTRA_CALL_ID"
        private const val EXTRA_IS_IN_BG = "EXTRA_IS_IN_BG"
        private const val EXTRA_END_CALL_REJECTED = "EXTRA_END_CALL_REJECTED"
        private const val EXTRA_END_CALL_REASON = "EXTRA_END_CALL_REASON"
        private const val EXTRA_NATIVE_ROOM_ID = "EXTRA_NATIVE_ROOM_ID"
        private const val EXTRA_OPPONENT_USER_ID = "EXTRA_OPPONENT_USER_ID"
        private const val EXTRA_IS_VIDEO_CALL = "EXTRA_IS_VIDEO_CALL"
        private const val EXTRA_IS_OUTGOING = "EXTRA_IS_OUTGOING"

        fun onIncomingCallRinging(
                context: Context,
                callId: String,
                isInBackground: Boolean
        ) {
            val intent = Intent(context, CallAndroidService::class.java).apply {
                action = ACTION_INCOMING_RINGING_CALL
                putExtra(EXTRA_CALL_ID, callId)
                putExtra(EXTRA_IS_IN_BG, isInBackground)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun onOutgoingCallRinging(
                context: Context,
                callId: String
        ) {
            val intent = Intent(context, CallAndroidService::class.java).apply {
                action = ACTION_OUTGOING_RINGING_CALL
                putExtra(EXTRA_CALL_ID, callId)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun onPendingCall(
                context: Context,
                callId: String
        ) {
            val intent = Intent(context, CallAndroidService::class.java).apply {
                action = ACTION_ONGOING_CALL
                putExtra(EXTRA_CALL_ID, callId)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun onCallTerminated(
                context: Context,
                callId: String,
                endCallReason: EndCallReason?,
                rejected: Boolean,
                nativeRoomId: String? = null,
                opponentUserId: String? = null,
                isVideoCall: Boolean = false,
                isOutgoing: Boolean = false
        ) {
            val intent = Intent(context, CallAndroidService::class.java).apply {
                action = ACTION_CALL_TERMINATED
                putExtra(EXTRA_CALL_ID, callId)
                putExtra(EXTRA_END_CALL_REASON, endCallReason)
                putExtra(EXTRA_END_CALL_REJECTED, rejected)
                nativeRoomId?.let { putExtra(EXTRA_NATIVE_ROOM_ID, it) }
                opponentUserId?.let { putExtra(EXTRA_OPPONENT_USER_ID, it) }
                putExtra(EXTRA_IS_VIDEO_CALL, isVideoCall)
                putExtra(EXTRA_IS_OUTGOING, isOutgoing)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }

    inner class CallServiceBinder : Binder() {
        fun getCallService(): CallAndroidService {
            return this@CallAndroidService
        }
    }
}
