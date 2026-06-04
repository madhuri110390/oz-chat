/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.call.webrtc

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import im.vector.app.ActiveSessionDataSource
import im.vector.app.core.pushers.UnifiedPushHelper
import im.vector.app.core.services.CallAndroidService
import im.vector.app.features.analytics.AnalyticsTracker
import im.vector.app.features.analytics.plan.CallEnded
import im.vector.app.features.analytics.plan.CallStarted
import im.vector.app.features.call.VectorCallActivity
import im.vector.app.features.call.audio.CallAudioManager
import im.vector.app.features.call.lookup.CallProtocolsChecker
import im.vector.app.features.call.lookup.CallUserMapper
import im.vector.app.features.call.utils.EglUtils
import im.vector.app.features.call.vectorCallService
import im.vector.app.features.notifications.CallForegroundService
import im.vector.app.features.session.coroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.extensions.orFalse
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.logger.LoggerTag
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.call.CallListener
import org.matrix.android.sdk.api.session.call.CallState
import org.matrix.android.sdk.api.session.call.MxCall
import org.matrix.android.sdk.api.session.room.model.call.CallAnswerContent
import org.matrix.android.sdk.api.session.room.model.call.CallAssertedIdentityContent
import org.matrix.android.sdk.api.session.room.model.call.CallCandidatesContent
import org.matrix.android.sdk.api.session.room.model.call.CallHangupContent
import org.matrix.android.sdk.api.session.room.model.call.CallInviteContent
import org.matrix.android.sdk.api.session.room.model.call.CallNegotiateContent
import org.matrix.android.sdk.api.session.room.model.call.CallRejectContent
import org.matrix.android.sdk.api.session.room.model.call.CallSelectAnswerContent
import org.matrix.android.sdk.api.session.room.model.call.CallUpdateTypeContent
import org.matrix.android.sdk.api.session.room.model.call.EndCallReason
import org.matrix.android.sdk.api.session.room.model.call.UpdateCallType
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.PeerConnectionFactory
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

private val loggerTag = LoggerTag("WebRtcCallManager", LoggerTag.VOIP)
private const val RING_DURATION_MS = 40_000L

@Singleton
class WebRtcCallManager @Inject constructor(
        private val context: Context,
        private val activeSessionDataSource: ActiveSessionDataSource,
        private val analyticsTracker: AnalyticsTracker,
        private val unifiedPushHelper: UnifiedPushHelper,
        private val voipConfig: VoipConfig,
        private val notificationUtils: im.vector.app.features.notifications.NotificationUtils,
) : CallListener,
        DefaultLifecycleObserver {

    private val currentSession: Session?
        get() = activeSessionDataSource.currentValue?.orNull()

    private val protocolsChecker: CallProtocolsChecker?
        get() = currentSession?.vectorCallService?.protocolChecker

    private val callUserMapper: CallUserMapper?
        get() = currentSession?.vectorCallService?.userMapper

    private val sessionScope: CoroutineScope?
        get() = currentSession?.coroutineScope

    interface Listener {
        fun onCallEnded(callId: String) = Unit
        fun onCurrentCallChange(call: WebRtcCall?) = Unit
        fun onAudioDevicesChange() = Unit
        fun onCallUpdateTypeReceived(mxCall: MxCall, update: CallUpdateTypeContent) = Unit
        fun onVideoRequestReceived(mxCall: MxCall) = Unit
        fun onVideoRequestAccepted(mxCall: MxCall) = Unit
    }

    val supportedPSTNProtocol: String?
        get() = protocolsChecker?.supportedPSTNProtocol

    val supportsPSTNProtocol: Boolean
        get() = supportedPSTNProtocol != null

    val supportsVirtualRooms: Boolean
        get() = protocolsChecker?.supportVirtualRooms.orFalse()

    fun addProtocolsCheckerListener(listener: CallProtocolsChecker.Listener) {
        protocolsChecker?.addListener(listener)
    }

    fun removeProtocolsCheckerListener(listener: CallProtocolsChecker.Listener) {
        protocolsChecker?.removeListener(listener)
    }

    private val currentCallsListeners = CopyOnWriteArrayList<Listener>()

    fun addListener(listener: Listener) {
        currentCallsListeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        currentCallsListeners.remove(listener)
    }

    val audioManager = CallAudioManager(context) {
        currentCallsListeners.forEach {
            tryOrNull { it.onAudioDevicesChange() }
        }
    }.apply {
        setMode(CallAudioManager.Mode.DEFAULT)
    }

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val dispatcher = executor.asCoroutineDispatcher()

    // Dedicated scope for ring timeouts — never null, survives session changes
    private val ringTimeoutScope = CoroutineScope(dispatcher)

    private val rootEglBase by lazy { EglUtils.rootEglBase }

    private var isInBackground: Boolean = true
    private var syncStartedWhenInBackground: Boolean = false

    override fun onResume(owner: LifecycleOwner) {
        isInBackground = false
    }

    override fun onPause(owner: LifecycleOwner) {
        isInBackground = true
    }

    var currentCall: AtomicReference<WebRtcCall?> = AtomicReference(null)
    private fun AtomicReference<WebRtcCall?>.setAndNotify(newValue: WebRtcCall?) {
        set(newValue)
        currentCallsListeners.forEach {
            tryOrNull { it.onCurrentCallChange(newValue) }
        }
    }

    private val advertisedCalls = HashSet<String>()
    private val callsByCallId = ConcurrentHashMap<String, WebRtcCall>()
    private val callsByRoomId = ConcurrentHashMap<String, MutableList<WebRtcCall>>()
    private val transferees = ConcurrentHashMap<String, WebRtcCall>()
    private val recentlyEndedCallRooms = mutableSetOf<String>()

    fun wasCallRecentlyActiveInRoom(roomId: String): Boolean {
        return recentlyEndedCallRooms.contains(roomId)
    }
    fun getCallById(callId: String): WebRtcCall? {
        return callsByCallId[callId]
    }

    fun getCallsByRoomId(roomId: String): List<WebRtcCall> {
        return callsByRoomId[roomId] ?: emptyList()
    }

    fun getTransfereeForCallId(callId: String): WebRtcCall? {
        return transferees[callId]
    }

    fun getCurrentCall(): WebRtcCall? {
        return currentCall.get()
    }

    fun getCalls(): List<WebRtcCall> {
        return callsByCallId.values.toList()
    }

    fun checkForProtocolsSupportIfNeeded() {
        protocolsChecker?.checkProtocols()
    }

    fun getAdvertisedCalls() = advertisedCalls

    fun headSetButtonTapped() {
        Timber.tag(loggerTag.value).v("headSetButtonTapped")
        val call = getCurrentCall() ?: return
        if (call.mxCall.state is CallState.LocalRinging) {
            call.acceptIncomingCall()
        }
        if (call.mxCall.state is CallState.Connected) {
            call.endCall()
        }
    }

    private fun createPeerConnectionFactoryIfNeeded() {
        if (peerConnectionFactory != null) return
        Timber.tag(loggerTag.value).v("createPeerConnectionFactory")
        val eglBaseContext = rootEglBase?.eglBaseContext ?: return Unit.also {
            Timber.tag(loggerTag.value).e("No EGL BASE")
        }

        Timber.tag(loggerTag.value).v("PeerConnectionFactory.initialize")
        PeerConnectionFactory.initialize(
                PeerConnectionFactory
                        .InitializationOptions.builder(context.applicationContext)
                        .createInitializationOptions()
        )

        val options = PeerConnectionFactory.Options()
        val defaultVideoEncoderFactory = DefaultVideoEncoderFactory(
                eglBaseContext,
                true,
                true
        )
        val defaultVideoDecoderFactory = DefaultVideoDecoderFactory(eglBaseContext)
        Timber.tag(loggerTag.value).v("PeerConnectionFactory.createPeerConnectionFactory ...")
        peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setVideoEncoderFactory(defaultVideoEncoderFactory)
                .setVideoDecoderFactory(defaultVideoDecoderFactory)
                .createPeerConnectionFactory()
    }
    private fun postMissedCallNotificationIfNeeded(
            callId: String,
            endCallReason: EndCallReason,
            rejected: Boolean,
            nativeRoomId: String,
            opponentUserId: String,
            isVideoCall: Boolean,
            isOutgoing: Boolean,
            opponentMatrixItem: org.matrix.android.sdk.api.util.MatrixItem?
    ) {
        // Don't show if rejected by receiver or answered elsewhere
        if (rejected || endCallReason == EndCallReason.ANSWERED_ELSEWHERE) return

        val callInfo = im.vector.app.core.services.CallAndroidService.CallInformation(
                callId = callId,
                nativeRoomId = nativeRoomId,
                opponentUserId = opponentUserId,
                opponentMatrixItem = opponentMatrixItem,
                isVideoCall = isVideoCall,
                isOutgoing = isOutgoing
        )

        val nm = androidx.core.app.NotificationManagerCompat.from(context)
        val notification = if (isOutgoing) {
            // Caller — receiver didn't answer
            Timber.tag(loggerTag.value).v("Posting call not answered notification")
            notificationUtils.buildCallNotAnsweredNotification(callInfo)
        } else {
            // Receiver — missed incoming call
            Timber.tag(loggerTag.value).v("Posting missed call notification")
            notificationUtils.buildCallMissedNotification(callInfo)
        }

        try {
            nm.notify("MISSED_CALL_TAG", nativeRoomId.hashCode(), notification)
        } catch (e: Exception) {
            Timber.tag(loggerTag.value).e(e, "Failed to post missed call notification")
        }
    }
    private fun onCallActive(call: WebRtcCall) {
        Timber.tag(loggerTag.value).v("WebRtcPeerConnectionManager onCall active: ${call.mxCall.callId}")
        val currentCall = getCurrentCall().takeIf { it != call }
        currentCall?.updateRemoteOnHold(onHold = true)
        audioManager.setMode(if (call.mxCall.isVideoCall) CallAudioManager.Mode.VIDEO_CALL else CallAudioManager.Mode.AUDIO_CALL)
        call.trackCallStarted()
        this.currentCall.setAndNotify(call)
    }

    private fun onCallEnded(callId: String, endCallReason: EndCallReason, rejected: Boolean) {
        Timber.tag(loggerTag.value).v("onCall ended: $callId")
        val webRtcCall = callsByCallId.remove(callId) ?: return Unit.also {
            Timber.tag(loggerTag.value).v("On call ended for unknown call $callId")
        }
        // Capture opponent info BEFORE anything else — session/call may be gone by the time
        // the delayed handler runs
        val opponentMatrixItem = webRtcCall.getOpponentAsMatrixItem(currentSession)
        val nativeRoomId = webRtcCall.nativeRoomId
        recentlyEndedCallRooms.add(nativeRoomId)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            recentlyEndedCallRooms.remove(nativeRoomId)
        }, 30_000)
        val opponentUserId = webRtcCall.mxCall.opponentUserId
        val isVideoCall = webRtcCall.mxCall.isVideoCall
        val isOutgoing = webRtcCall.mxCall.isOutgoing

        webRtcCall.trackCallEnded()

        // Stop FCM service immediately
        CallForegroundService.stop(context)

        // Post missed call notification DIRECTLY after short delay
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            postMissedCallNotificationIfNeeded(
                    callId = callId,
                    endCallReason = endCallReason,
                    rejected = rejected,
                    nativeRoomId = nativeRoomId,
                    opponentUserId = opponentUserId,
                    isVideoCall = isVideoCall,
                    isOutgoing = isOutgoing,
                    opponentMatrixItem = opponentMatrixItem
            )
            CallAndroidService.onCallTerminated(
                    context, callId, endCallReason, rejected,
                    nativeRoomId, opponentUserId, isVideoCall, isOutgoing
            )
        }, 500)

        callsByRoomId[webRtcCall.signalingRoomId]?.remove(webRtcCall)
        callsByRoomId[nativeRoomId]?.remove(webRtcCall)
        transferees.remove(callId)
        if (currentCall.get()?.callId == callId) {
            val otherCall = getCalls().lastOrNull()
            currentCall.setAndNotify(otherCall)
        }
        tryOrNull {
            currentCallsListeners.forEach { it.onCallEnded(callId) }
        }
        if (getCurrentCall() == null) {
            Timber.tag(loggerTag.value).v("Dispose peerConnectionFactory as there is no need to keep one")
            peerConnectionFactory?.dispose()
            peerConnectionFactory = null
            audioManager.setMode(CallAudioManager.Mode.DEFAULT)
            if (syncStartedWhenInBackground) {
                if (!unifiedPushHelper.isBackgroundSync()) {
                    Timber.tag(loggerTag.value).v("Sync started when in background, stop it")
                    currentSession?.syncService()?.stopAnyBackgroundSync()
                }
                syncStartedWhenInBackground = false
            }
        }
    }

    suspend fun startOutgoingCall(
            nativeRoomId: String,
            otherUserId: String,
            isVideoCall: Boolean,
            transferee: WebRtcCall? = null
    ) {
        val signalingRoomId = callUserMapper?.getOrCreateVirtualRoomForRoom(nativeRoomId, otherUserId) ?: nativeRoomId
        if (otherUserId == currentSession?.myUserId) return
        Timber.tag(loggerTag.value).v("startOutgoingCall in room $signalingRoomId to $otherUserId isVideo $isVideoCall")
        if (getCallsByRoomId(nativeRoomId).isNotEmpty()) {
            Timber.tag(loggerTag.value).w("you already have a call in this room")
            return
        }
        if (getCurrentCall() != null && getCurrentCall()?.mxCall?.state !is CallState.Connected || getCalls().size >= 2) {
            Timber.tag(loggerTag.value).w("cannot start outgoing call")
            return
        }
        executor.execute {
            createPeerConnectionFactoryIfNeeded()
        }
        getCurrentCall()?.updateRemoteOnHold(onHold = true)
        val mxCall = currentSession?.callSignalingService()?.createOutgoingCall(signalingRoomId, otherUserId, isVideoCall) ?: return
        val webRtcCall = createWebRtcCall(mxCall, nativeRoomId)
        currentCall.setAndNotify(webRtcCall)
        if (transferee != null) {
            transferees[webRtcCall.callId] = transferee
        }
        CallAndroidService.onOutgoingCallRinging(
                context = context.applicationContext,
                callId = mxCall.callId
        )
        context.startActivity(VectorCallActivity.newIntent(context, webRtcCall, VectorCallActivity.OUTGOING_CREATED))

        // Use dedicated ring timeout scope — never null unlike sessionScope
        ringTimeoutScope.launch {
            delay(RING_DURATION_MS)
            val call = callsByCallId[mxCall.callId] ?: return@launch
            if (call.mxCall.state is CallState.Dialing || call.mxCall.state is CallState.LocalRinging) {
                Timber.tag(loggerTag.value).v("Outgoing ring timeout — auto-ending call ${mxCall.callId}")
                call.endCall(EndCallReason.USER_HANGUP)
            }
        }
    }

    override fun onCallIceCandidateReceived(mxCall: MxCall, iceCandidatesContent: CallCandidatesContent) {
        Timber.tag(loggerTag.value).v("onCallIceCandidateReceived for call ${mxCall.callId}")
        val call = callsByCallId[iceCandidatesContent.callId]
                ?: return Unit.also {
                    Timber.tag(loggerTag.value).w("onCallIceCandidateReceived for non active call? ${iceCandidatesContent.callId}")
                }
        call.onCallIceCandidateReceived(iceCandidatesContent)
    }

    private fun createWebRtcCall(mxCall: MxCall, nativeRoomId: String): WebRtcCall {
        val webRtcCall = WebRtcCall(
                mxCall = mxCall,
                nativeRoomId = nativeRoomId,
                rootEglBase = rootEglBase,
                context = context,
                dispatcher = dispatcher,
                peerConnectionFactoryProvider = {
                    createPeerConnectionFactoryIfNeeded()
                    peerConnectionFactory
                },
                sessionProvider = { currentSession },
                onCallBecomeActive = this::onCallActive,
                onCallEnded = this::onCallEnded
        )
        advertisedCalls.add(mxCall.callId)
        callsByCallId[mxCall.callId] = webRtcCall
        callsByRoomId.getOrPut(nativeRoomId) { ArrayList(1) }.add(webRtcCall)
        callsByRoomId.getOrPut(mxCall.roomId) { ArrayList(1) }.add(webRtcCall)
        if (getCurrentCall() == null) {
            currentCall.setAndNotify(webRtcCall)
        }
        return webRtcCall
    }

    fun endCallForRoom(roomId: String) {
        callsByRoomId[roomId]?.firstOrNull()?.endCall()
    }

    override fun onCallInviteReceived(mxCall: MxCall, callInviteContent: CallInviteContent) {
        Timber.tag(loggerTag.value).v("onCallInviteReceived callId ${mxCall.callId}")
        val nativeRoomId = callUserMapper?.nativeRoomForVirtualRoom(mxCall.roomId) ?: mxCall.roomId
        if (getCallsByRoomId(nativeRoomId).isNotEmpty()) {
            Timber.tag(loggerTag.value).w("you already have a call in this room")
            return
        }
        if ((getCurrentCall() != null && getCurrentCall()?.mxCall?.state !is CallState.Connected) || getCalls().size >= 2) {
            Timber.tag(loggerTag.value).w("receiving incoming call but cannot handle it")
            return
        }
        createWebRtcCall(mxCall, nativeRoomId).apply {
            offerSdp = callInviteContent.offer
        }
        CallAndroidService.onIncomingCallRinging(
                context = context,
                callId = mxCall.callId,
                isInBackground = isInBackground
        )
        if (isInBackground) {
            if (!unifiedPushHelper.isBackgroundSync()) {
                syncStartedWhenInBackground = true
                currentSession?.syncService()?.startAutomaticBackgroundSync(30, 0)
            }
        }

        // Use dedicated ring timeout scope — never null unlike sessionScope
        ringTimeoutScope.launch {
            delay(RING_DURATION_MS)
            val call = callsByCallId[mxCall.callId] ?: return@launch
            if (call.mxCall.state is CallState.LocalRinging) {
                Timber.tag(loggerTag.value).v("Incoming ring timeout — auto-ending call ${mxCall.callId}")
                call.endCall(EndCallReason.USER_HANGUP)
            }
        }
    }

    override fun onCallAnswerReceived(callAnswerContent: CallAnswerContent) {
        val call = callsByCallId[callAnswerContent.callId]
                ?: return Unit.also {
                    Timber.tag(loggerTag.value).w("onCallAnswerReceived for non active call? ${callAnswerContent.callId}")
                }
        val mxCall = call.mxCall
        CallAndroidService.onPendingCall(
                context = context,
                callId = mxCall.callId
        )
        call.onCallAnswerReceived(callAnswerContent)
    }

    override fun onCallHangupReceived(callHangupContent: CallHangupContent) {
        Timber.tag(loggerTag.value).v("onCallHangupReceived for call ${callHangupContent.callId}")
        val call = callsByCallId[callHangupContent.callId]
                ?: return Unit.also {
                    Timber.tag(loggerTag.value).w("onCallHangupReceived for non active call? ${callHangupContent.callId}")
                }
        // Delegate to WebRtcCall for proper internal state transition
        call.onCallHangupReceived(callHangupContent)
        // Safety net: if WebRtcCall didn't trigger onCallEnded within 500ms
        // (happens when hangup arrives during LocalRinging state),
        // force terminate at manager level.
        ringTimeoutScope.launch {
            delay(500)
            if (callsByCallId.containsKey(callHangupContent.callId)) {
                Timber.tag(loggerTag.value).w(
                        "Force terminating call ${callHangupContent.callId} — " +
                                "WebRtcCall did not self-terminate after hangup"
                )
                onCallEnded(
                        callHangupContent.callId,
                        callHangupContent.reason ?: EndCallReason.USER_HANGUP,
                        rejected = false
                )
            }
        }
    }

    override fun onCallRejectReceived(callRejectContent: CallRejectContent) {
        Timber.tag(loggerTag.value).v("onCallRejectReceived for call ${callRejectContent.callId}")
        val call = callsByCallId[callRejectContent.callId]
                ?: return Unit.also {
                    Timber.tag(loggerTag.value).w("onCallRejectReceived for non active call? ${callRejectContent.callId}")
                }
        call.onCallRejectReceived(callRejectContent)
        // Safety net: force terminate if WebRtcCall didn't self-terminate
        ringTimeoutScope.launch {
            delay(500)
            if (callsByCallId.containsKey(callRejectContent.callId)) {
                Timber.tag(loggerTag.value).w(
                        "Force terminating call ${callRejectContent.callId} — " +
                                "WebRtcCall did not self-terminate after reject"
                )
                onCallEnded(
                        callRejectContent.callId,
                        EndCallReason.USER_HANGUP,
                        rejected = true
                )
            }
        }
    }

    override fun onCallSelectAnswerReceived(callSelectAnswerContent: CallSelectAnswerContent) {
        val call = callsByCallId[callSelectAnswerContent.callId]
                ?: return Unit.also {
                    Timber.tag(loggerTag.value).w("onCallSelectAnswerReceived for non active call? ${callSelectAnswerContent.callId}")
                }
        call.onCallSelectedAnswerReceived(callSelectAnswerContent)
    }

    override fun onCallNegotiateReceived(callNegotiateContent: CallNegotiateContent) {
        val call = callsByCallId[callNegotiateContent.callId]
                ?: return Unit.also {
                    Timber.tag(loggerTag.value).w("onCallNegotiateReceived for non active call? ${callNegotiateContent.callId}")
                }
        call.onCallNegotiateReceived(callNegotiateContent)
    }

    override fun onCallUpdateTypeReceived(mxCall: MxCall, update: CallUpdateTypeContent) {
        Timber.tag(loggerTag.value).v("onCallUpdateTypeReceived for call ${update.callId} -> ${update.updateCallType}")
        val callId = update.callId ?: return
        val call = callsByCallId[callId] ?: return

        if (mxCall.ourPartyId == update.partyId) {
            Timber.tag(loggerTag.value).v("Ignoring CALL_UPDATE_TYPE from our own partyId: ${update.partyId}")
            return
        }

        when (update.updateCallType) {
            UpdateCallType.VOICE -> {
                call.switchToVoice(sendUpdate = false)
            }
            UpdateCallType.VIDEO -> {
                call.switchToVideoAsInitiator()
            }
            UpdateCallType.VIDEO_REQUEST -> {
                onVideoRequestReceived(mxCall)
            }
            UpdateCallType.VIDEO_ACCEPT -> {
                onVideoRequestAccepted(mxCall)
            }
            UpdateCallType.SCREEN_SHARE -> {
                call.onRemoteScreenShareChanged(isSharing = true)
            }
        }

        currentCallsListeners.forEach {
            tryOrNull { it.onCallUpdateTypeReceived(mxCall, update) }
        }
    }

    override fun onVideoRequestReceived(mxCall: MxCall) {
        Timber.tag(loggerTag.value).v("onVideoRequestReceived for call ${mxCall.callId}")
        val call = callsByCallId[mxCall.callId] ?: return
        call.onVideoRequestReceived()
        currentCallsListeners.forEach {
            tryOrNull { it.onVideoRequestReceived(mxCall) }
        }
    }

    override fun onVideoRequestAccepted(mxCall: MxCall) {
        Timber.tag(loggerTag.value).v("onVideoRequestAccepted for call ${mxCall.callId}")
        val call = callsByCallId[mxCall.callId] ?: return
        Timber.tag(loggerTag.value).v("Found call: ${call.callId}, isOutgoing: ${call.mxCall.isOutgoing}")
        currentCallsListeners.forEach {
            tryOrNull { it.onVideoRequestAccepted(mxCall) }
        }
    }

    override fun onCallManagedByOtherSession(callId: String) {
        Timber.tag(loggerTag.value).v("onCallManagedByOtherSession: $callId")
        val call = callsByCallId[callId]
                ?: return Unit.also {
                    Timber.tag(loggerTag.value).w("onCallManagedByOtherSession for non active call? $callId")
                }
        call.endCall(EndCallReason.ANSWERED_ELSEWHERE, sendSignaling = false)
    }

    override fun onCallAssertedIdentityReceived(callAssertedIdentityContent: CallAssertedIdentityContent) {
        if (!voipConfig.handleCallAssertedIdentityEvents) {
            return
        }
        val call = callsByCallId[callAssertedIdentityContent.callId]
                ?: return Unit.also {
                    Timber.tag(loggerTag.value).w("onCallAssertedIdentityReceived for non active call? ${callAssertedIdentityContent.callId}")
                }
        call.onCallAssertedIdentityReceived(callAssertedIdentityContent)
    }

    private fun WebRtcCall.trackCallStarted() {
        analyticsTracker.capture(
                CallStarted(
                        isVideo = mxCall.isVideoCall,
                        numParticipants = 2,
                        placed = mxCall.isOutgoing
                )
        )
    }

    private fun WebRtcCall.trackCallEnded() {
        analyticsTracker.capture(
                CallEnded(
                        durationMs = durationMillis(),
                        isVideo = mxCall.isVideoCall,
                        numParticipants = 2,
                        placed = mxCall.isOutgoing
                )
        )
    }
}
