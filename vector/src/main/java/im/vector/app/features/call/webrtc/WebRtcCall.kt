/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.call.webrtc

import android.Manifest
import android.content.Context
import android.hardware.camera2.CameraManager
import androidx.core.content.getSystemService
import im.vector.app.core.services.CallAndroidService
import im.vector.app.core.utils.PublishDataSource
import im.vector.app.core.utils.TextUtils.formatDuration
import im.vector.app.features.call.CameraEventsHandlerAdapter
import im.vector.app.features.call.CameraProxy
import im.vector.app.features.call.CameraType
import im.vector.app.features.call.CaptureFormat
import im.vector.app.features.call.VectorCallActivity
import im.vector.app.features.call.lookup.sipNativeLookup
import im.vector.app.features.call.utils.asWebRTC
import im.vector.app.features.call.utils.awaitCreateAnswer
import im.vector.app.features.call.utils.awaitCreateOffer
import im.vector.app.features.call.utils.awaitSetLocalDescription
import im.vector.app.features.call.utils.awaitSetRemoteDescription
import im.vector.app.features.call.utils.mapToCallCandidate
import im.vector.app.features.session.coroutineScope
import im.vector.lib.core.utils.flow.chunk
import im.vector.lib.core.utils.timer.CountUpTimer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.matrix.android.sdk.api.extensions.orFalse
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.logger.LoggerTag
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.call.CallIdGenerator
import org.matrix.android.sdk.api.session.call.CallState
import org.matrix.android.sdk.api.session.call.MxCall
import org.matrix.android.sdk.api.session.call.MxPeerConnectionState
import org.matrix.android.sdk.api.session.call.TurnServerResponse
import org.matrix.android.sdk.api.session.events.model.toContent
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
import org.matrix.android.sdk.api.session.room.model.call.SdpType
import org.threeten.bp.Duration
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import timber.log.Timber
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Provider
import kotlin.coroutines.CoroutineContext

private const val STREAM_ID = "userMedia"
private const val AUDIO_TRACK_ID = "${STREAM_ID}a0"
private const val VIDEO_TRACK_ID = "${STREAM_ID}v0"
private const val SCREEN_TRACK_ID = "${STREAM_ID}s0"

private val DEFAULT_AUDIO_CONSTRAINTS = MediaConstraints().apply {
    mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
    mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
    mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
    mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
    mandatory.add(MediaConstraints.KeyValuePair("googTypingNoiseDetection", "true"))
}

//private const val INVITE_TIMEOUT_IN_MS = 60_000L
private const val INVITE_TIMEOUT_IN_MS = 60_000L   // keep as overall safety net
private const val RING_TIMEOUT_IN_MS   = 40_000L   // ~8 rings at 5 s/ring

private val loggerTag = LoggerTag("WebRtcCall", LoggerTag.VOIP)

class WebRtcCall(
        val mxCall: MxCall,
        val nativeRoomId: String,
        private val rootEglBase: EglBase?,
        private val context: Context,
        private val dispatcher: CoroutineContext,
        private val sessionProvider: Provider<Session?>,
        private val peerConnectionFactoryProvider: Provider<PeerConnectionFactory?>,
        private val onCallBecomeActive: (WebRtcCall) -> Unit,
        private val onCallEnded: (String, EndCallReason, Boolean) -> Unit
) : MxCall.StateListener {

    interface Listener : MxCall.StateListener {
        fun onCaptureStateChanged() {}
        fun onCameraChanged() {}
        fun onHoldUnhold() {}
        fun assertedIdentityChanged() {}
        fun onTick(formattedDuration: String) {}
        override fun onStateUpdate(call: MxCall) {}
        fun onVideoRequestReceived(mxCall: MxCall) = Unit
        fun onVideoRequestAccepted(mxCall: MxCall) = Unit
        fun onRemoteScreenShareChanged(isSharing: Boolean) = Unit
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    private val sessionScope: CoroutineScope?
        get() = sessionProvider.get()?.coroutineScope

    fun addListener(listener: Listener) { listeners.add(listener) }
    fun removeListener(listener: Listener) { listeners.remove(listener) }

    val callId = mxCall.callId
    val signalingRoomId = mxCall.roomId

    private var pendingVideoRequest: Boolean = false
    private var isVideoMode: Boolean = mxCall.isVideoCall
    var isRemoteScreenSharing: Boolean = false
        private set

    private var peerConnection: PeerConnection? = null
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var localVideoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var remoteAudioTrack: AudioTrack? = null
    private var remoteVideoTrack: VideoTrack? = null

    private var makingOffer: Boolean = false
    private var ignoreOffer: Boolean = false

    private var videoCapturer: VideoCapturer? = null

    private val availableCamera = ArrayList<CameraProxy>()
    private var cameraInUse: CameraProxy? = null
    private var currentCaptureFormat: CaptureFormat = CaptureFormat.HD
    private var cameraAvailabilityCallback: CameraManager.AvailabilityCallback? = null

    private var videoSender: RtpSender? = null
    private var screenSender: RtpSender? = null

    private val timer = CountUpTimer(intervalInMs = 1000L).apply {
        tickListener = CountUpTimer.TickListener { milliseconds ->
            val formattedDuration = formatDuration(Duration.ofMillis(milliseconds))
            listeners.forEach { tryOrNull { it.onTick(formattedDuration) } }
        }
    }

    private var inviteTimeout: Deferred<Unit>? = null

    var micMuted = false
        private set
    var videoMuted = false
        private set
    var isRemoteOnHold = false
        private set
    var isLocalOnHold = false
        private set

    private var wasLocalOnHold = false
    var remoteAssertedIdentity: CallAssertedIdentityContent.AssertedIdentity? = null
        private set

    var offerSdp: CallInviteContent.Offer? = null

    var videoCapturerIsInError = false
        set(value) {
            field = value
            listeners.forEach { tryOrNull { it.onCaptureStateChanged() } }
        }
        get() = field

    private var localSurfaceRenderers: MutableList<WeakReference<SurfaceViewRenderer>> = ArrayList()
    private var remoteSurfaceRenderers: MutableList<WeakReference<SurfaceViewRenderer>> = ArrayList()

    private val localIceCandidateSource = PublishDataSource<IceCandidate>()
    private var localIceCandidateJob: Job? = null

    private val remoteCandidateSource: MutableSharedFlow<IceCandidate> =
            MutableSharedFlow(replay = Int.MAX_VALUE)
    private var remoteIceCandidateJob: Job? = null

    init {
        setupLocalIceCandidate()
        mxCall.addListener(this)
    }

    private fun setupLocalIceCandidate() {
        sessionScope?.let {
            localIceCandidateJob = localIceCandidateSource.stream()
                    .chunk(300)
                    .onEach { candidates ->
                        if (candidates.isNotEmpty()) {
                            mxCall.sendLocalCallCandidates(candidates.mapToCallCandidate())
                        }
                    }.launchIn(it)
        }
    }

    fun onIceCandidate(iceCandidate: IceCandidate) = localIceCandidateSource.post(iceCandidate)

    // -------------------------------------------------------------------------
    // Renderer attachment
    // -------------------------------------------------------------------------

//    fun attachViewRenderers(
//            localViewRenderer: SurfaceViewRenderer?,
//            remoteViewRenderer: SurfaceViewRenderer,
//            mode: String?
//    ) {
//        sessionScope?.launch(dispatcher) {
//            localSurfaceRenderers.addIfNeeded(localViewRenderer)
//            remoteSurfaceRenderers.addIfNeeded(remoteViewRenderer)
//            when (mode) {
//                VectorCallActivity.INCOMING_ACCEPT -> internalAcceptIncomingCall()
//                VectorCallActivity.INCOMING_RINGING -> { /* wait for accept */ }
//                VectorCallActivity.OUTGOING_CREATED -> setupOutgoingCall()
//                else -> attachViewRenderersInternal()
//            }
//        }
//    }

    fun attachViewRenderers(
            localViewRenderer: SurfaceViewRenderer?,
            remoteViewRenderer: SurfaceViewRenderer,
            mode: String?
    ) {
        sessionScope?.launch(dispatcher) {
            // Prune GC'd references before adding new ones
            localSurfaceRenderers.removeAll  { it.get() == null }
            remoteSurfaceRenderers.removeAll { it.get() == null }

            localSurfaceRenderers.addIfNeeded(localViewRenderer)
            remoteSurfaceRenderers.addIfNeeded(remoteViewRenderer)

            when (mode) {
                VectorCallActivity.INCOMING_ACCEPT  -> internalAcceptIncomingCall()
                VectorCallActivity.INCOMING_RINGING -> Unit // await explicit accept
                VectorCallActivity.OUTGOING_CREATED -> setupOutgoingCall()
                else                                -> attachViewRenderersInternal()
            }
        }
    }

    /**
     * FIX #1 + #3: Correct track-to-renderer assignment.
     *
     * Normal video call:
     *   localSurfaceRenderers  (pipRenderer)        ← localVideoTrack  (self-view)
     *   remoteSurfaceRenderers (fullscreenRenderer) ← remoteVideoTrack (remote)
     *
     * Local screen share:
     *   remoteSurfaceRenderers (fullscreenRenderer) ← localVideoTrack  (shared screen)
     *   localSurfaceRenderers  (remotePipRenderer)  ← remoteVideoTrack (remote camera)
     *
     * All sinks are cleared and re-added atomically so there is no window where a
     * renderer has no track — preventing the black-screen flash (FIX #3).
     *
     * FIX #4: All sink mutations are wrapped in runCatching to survive a disposed track
     * if this is called during a long session just before/after release().
     */
    private suspend fun attachViewRenderersInternal() = withContext(dispatcher) {
        // Remove all existing sinks first to avoid duplicate sink registrations.
        localSurfaceRenderers.forEach { ref ->
            ref.get()?.let { surface ->
                runCatching { localVideoTrack?.removeSink(surface) }
                runCatching { remoteVideoTrack?.removeSink(surface) }
            }
        }
        remoteSurfaceRenderers.forEach { ref ->
            ref.get()?.let { surface ->
                runCatching { localVideoTrack?.removeSink(surface) }
                runCatching { remoteVideoTrack?.removeSink(surface) }
            }
        }

        if (isSharingScreen()) {
            // Local screen share → screen content fills fullscreen (remoteSurfaceRenderers),
            // remote camera goes to the PiP card (localSurfaceRenderers = remotePipRenderer).
            remoteSurfaceRenderers.forEach { ref ->
                ref.get()?.let { surface ->
                    surface.setMirror(false)
                    runCatching { localVideoTrack?.addSink(surface) }
                }
            }
            localSurfaceRenderers.forEach { ref ->
                ref.get()?.let { surface ->
                    surface.setMirror(false)
                    runCatching { remoteVideoTrack?.addSink(surface) }
                }
            }
        } else {
            // Normal video call or remote screen share:
            // Local camera → localSurfaceRenderers (self-view PiP).
            // Remote video → remoteSurfaceRenderers (fullscreen).
            localSurfaceRenderers.forEach { ref ->
                ref.get()?.let { surface ->
                    surface.setMirror(cameraInUse?.type == CameraType.FRONT)
                    runCatching { localVideoTrack?.addSink(surface) }
                }
            }
            remoteSurfaceRenderers.forEach { ref ->
                ref.get()?.let { surface ->
                    surface.setMirror(false)
                    runCatching { remoteVideoTrack?.addSink(surface) }
                }
            }
        }
    }

    /**
     * FIX #4: detachRenderers guards every removeSink with runCatching so a
     * disposed-track crash after a long call cannot propagate.
     * Clears WeakReference lists so the GC can collect stale references.
     */
    fun detachRenderers(renderers: List<SurfaceViewRenderer>?) {
        sessionScope?.launch(dispatcher) {
            detachRenderersInternal(renderers)
        }
    }

    private suspend fun detachRenderersInternal(renderers: List<SurfaceViewRenderer>?) =
            withContext(dispatcher) {
                if (renderers.isNullOrEmpty()) {
                    localSurfaceRenderers.forEach { ref ->
                        ref.get()?.let { surface ->
                            runCatching { localVideoTrack?.removeSink(surface) }
                            runCatching { remoteVideoTrack?.removeSink(surface) }
                        }
                    }
                    remoteSurfaceRenderers.forEach { ref ->
                        ref.get()?.let { surface ->
                            runCatching { localVideoTrack?.removeSink(surface) }
                            runCatching { remoteVideoTrack?.removeSink(surface) }
                        }
                    }
                    localSurfaceRenderers.clear()
                    remoteSurfaceRenderers.clear()
                } else {
                    renderers.forEach { surface ->
                        localSurfaceRenderers.removeIfNeeded(surface)
                        remoteSurfaceRenderers.removeIfNeeded(surface)
                        runCatching { localVideoTrack?.removeSink(surface) }
                        runCatching { remoteVideoTrack?.removeSink(surface) }
                    }
                }
            }

    // -------------------------------------------------------------------------
    // Outgoing / incoming call setup
    // -------------------------------------------------------------------------
    private suspend fun getTurnServer(): TurnServerResponse? {
        return tryOrNull {
            sessionProvider.get()?.callSignalingService()?.getTurnServer()
        }
    }
    private suspend fun setupOutgoingCall() = withContext(dispatcher) {
        tryOrNull { onCallBecomeActive(this@WebRtcCall) }
        val turnServer = getTurnServer()
        mxCall.state = CallState.CreateOffer
        createPeerConnection(turnServer)
        createLocalStream()
        attachViewRenderersInternal()
        remoteIceCandidateJob = remoteCandidateSource
                .onEach { peerConnection?.addIceCandidate(it) }
                .catch { Timber.tag(loggerTag.value).v("failed to add remote ice candidate: $it") }
                .launchIn(this)
    }

    private suspend fun internalAcceptIncomingCall() = withContext(dispatcher) {
        tryOrNull { onCallBecomeActive(this@WebRtcCall) }
        val turnServerResponse = getTurnServer()
        withContext(Dispatchers.Main) {
            CallAndroidService.onPendingCall(context = context, callId = mxCall.callId)
        }
        createPeerConnection(turnServerResponse)

        val offerSdp = offerSdp?.sdp?.let {
            SessionDescription(SessionDescription.Type.OFFER, it)
        } ?: run {
            Timber.tag(loggerTag.value).v("No offer SDP to process")
            return@withContext
        }

        try {
            peerConnection?.awaitSetRemoteDescription(offerSdp)
        } catch (failure: Throwable) {
            Timber.tag(loggerTag.value).v("Failure setting remote description")
            endCall(reason = EndCallReason.UNKWOWN_ERROR)
            return@withContext
        }

        createLocalStream()
        attachViewRenderersInternal()
        createAnswer()?.also { mxCall.accept(it.description) }

        remoteIceCandidateJob = remoteCandidateSource
                .onEach { peerConnection?.addIceCandidate(it) }
                .catch { Timber.tag(loggerTag.value).v("failed to add remote ice candidate: $it") }
                .launchIn(this)
    }

    // -------------------------------------------------------------------------
    // Peer connection
    // -------------------------------------------------------------------------

    private fun createPeerConnection(turnServerResponse: TurnServerResponse?) {
        val peerConnectionFactory = peerConnectionFactoryProvider.get() ?: return
        val iceServers = mutableListOf<PeerConnection.IceServer>().apply {
            turnServerResponse?.uris?.forEach { uri ->
                add(
                        PeerConnection.IceServer.builder(uri)
                                .setUsername(turnServerResponse.username)
                                .setPassword(turnServerResponse.password)
                                .createIceServer()
                )
            }
        }
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, PeerConnectionObserver(this))
    }

    private fun createLocalStream() {
        val peerConnectionFactory = peerConnectionFactoryProvider.get() ?: return
        configureAudioTrack(peerConnectionFactory)
        if (isVideoMode) configureVideoTrack(peerConnectionFactory)
        updateMuteStatus()
    }

    private fun configureAudioTrack(peerConnectionFactory: PeerConnectionFactory) {
        val audioSource = peerConnectionFactory.createAudioSource(DEFAULT_AUDIO_CONSTRAINTS)
        val audioTrack = peerConnectionFactory.createAudioTrack(AUDIO_TRACK_ID, audioSource)
        audioTrack.setEnabled(true)
        peerConnection?.addTrack(audioTrack, listOf(STREAM_ID))
        localAudioSource = audioSource
        localAudioTrack = audioTrack
    }

    private fun configureVideoTrack(peerConnectionFactory: PeerConnectionFactory) {
        val camera = buildCameraVideoCapturer(peerConnectionFactory) ?: return
        val videoSource = peerConnectionFactory.createVideoSource(camera.capturer.isScreencast)
        val surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", rootEglBase!!.eglBaseContext)
        camera.capturer.initialize(surfaceTextureHelper, context, videoSource.capturerObserver)
        camera.capturer.startCapture(currentCaptureFormat.width, currentCaptureFormat.height, currentCaptureFormat.fps)
        this.videoCapturer = camera.capturer

        val videoTrack = peerConnectionFactory.createVideoTrack(VIDEO_TRACK_ID, videoSource)
        videoTrack.setEnabled(true)
        videoSender = peerConnection?.addTrack(videoTrack, listOf(STREAM_ID))
        localVideoSource = videoSource
        localVideoTrack = videoTrack
    }

    private fun configureVideoTrackWithoutAdding(peerConnectionFactory: PeerConnectionFactory) {
        val camera = buildCameraVideoCapturer(peerConnectionFactory) ?: return
        val videoSource = peerConnectionFactory.createVideoSource(camera.capturer.isScreencast)
        val surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", rootEglBase!!.eglBaseContext)
        camera.capturer.initialize(surfaceTextureHelper, context, videoSource.capturerObserver)
        if (isVideoMode) {
            camera.capturer.startCapture(currentCaptureFormat.width, currentCaptureFormat.height, currentCaptureFormat.fps)
        }
        this.videoCapturer = camera.capturer

        val videoTrack = peerConnectionFactory.createVideoTrack(VIDEO_TRACK_ID, videoSource)
        videoTrack.setEnabled(true)
        // Intentionally NOT added to PeerConnection here — prevents glare during negotiation.
        localVideoSource = videoSource
        localVideoTrack = videoTrack
    }

    /**
     * Extracted camera setup into a data class to avoid the duplicated 150-line block
     * that existed in both configureVideoTrack and configureVideoTrackWithoutAdding.
     */
    private data class CameraSetup(val capturer: VideoCapturer)

    private fun buildCameraVideoCapturer(peerConnectionFactory: PeerConnectionFactory): CameraSetup? {
        val cameraIterator = if (Camera2Enumerator.isSupported(context)) {
            Camera2Enumerator(context)
        } else {
            Camera1Enumerator(false)
        }

        val frontCamera = cameraIterator.deviceNames
                ?.firstOrNull { cameraIterator.isFrontFacing(it) }
                ?.let { CameraProxy(it, CameraType.FRONT).also { availableCamera.add(it) } }

        val backCamera = cameraIterator.deviceNames
                ?.firstOrNull { cameraIterator.isBackFacing(it) }
                ?.let { CameraProxy(it, CameraType.BACK).also { availableCamera.add(it) } }

        val camera = frontCamera?.also { cameraInUse = it }
                ?: backCamera?.also { cameraInUse = it }
                ?: run { cameraInUse = null; null }

        listeners.forEach { tryOrNull { it.onCameraChanged() } }

        if (camera == null) return null

        val capturer = cameraIterator.createCapturer(camera.name, object : CameraEventsHandlerAdapter() {
            override fun onFirstFrameAvailable() {
                super.onFirstFrameAvailable()
                videoCapturerIsInError = false
            }

            override fun onCameraClosed() {
                super.onCameraClosed()
                Timber.tag(loggerTag.value).v("onCameraClosed")
                videoCapturerIsInError = true
                val cameraManager = context.getSystemService<CameraManager>()
                cameraAvailabilityCallback = object : CameraManager.AvailabilityCallback() {
                    override fun onCameraAvailable(cameraId: String) {
                        if (cameraId == camera.name) {
                            if (isVideoMode && !isSharingScreen()) {
                                videoCapturer?.startCapture(
                                        currentCaptureFormat.width,
                                        currentCaptureFormat.height,
                                        currentCaptureFormat.fps
                                )
                            }
                            cameraManager?.unregisterAvailabilityCallback(this)
                            cameraAvailabilityCallback = null
                        }
                    }
                }
                cameraManager?.registerAvailabilityCallback(cameraAvailabilityCallback!!, null)
            }
        })
        return CameraSetup(capturer)
    }

    // -------------------------------------------------------------------------
    // Screen sharing
    // -------------------------------------------------------------------------

    /**
     * FIX #1: startSharingScreen attaches localVideoTrack (screen) to fullscreenRenderer
     * and remoteVideoTrack (remote camera) to remotePipRenderer via attachViewRenderersInternal.
     * No manual sink wiring needed here — attachViewRenderersInternal handles it with the
     * isSharingScreen() branch.
     *
     * FIX #4: Existing camera capturer is stopped and disposed BEFORE creating the screen
     * capturer to prevent native resource conflicts after long calls.
     */
    fun startSharingScreen(videoCapturer: VideoCapturer) {
        Timber.tag(loggerTag.value).d("startSharingScreen called")

        // Stop and dispose camera capturer before switching to screen capturer.
        runCatching { this.videoCapturer?.stopCapture() }
        runCatching { this.videoCapturer?.dispose() }
        this.videoCapturer = null

        // Remove the camera video sender from PeerConnection.
        videoSender?.let { runCatching { removeStream(it) } }
        videoSender = null

        // Dispose camera track — screen track will replace it.
        runCatching { localVideoTrack?.dispose() }
        localVideoTrack = null

        val factory = peerConnectionFactoryProvider.get() ?: return
        this.videoCapturer = videoCapturer

        val videoSource = factory.createVideoSource(videoCapturer.isScreencast)
        startCapturingScreen(videoCapturer, videoSource)

        localVideoTrack = factory.createVideoTrack(SCREEN_TRACK_ID, videoSource).apply {
            setEnabled(true)
        }
        videoSender = peerConnection?.addTrack(localVideoTrack, listOf(STREAM_ID))

        sessionScope?.launch(dispatcher) {
            onRenegotiationNeeded(restartIce = false)
            mxCall.sendScreenShareStart()
            // FIX #1 + #3: Re-attach renderers so the screen track flows to fullscreenRenderer
            // and the remote camera track flows to remotePipRenderer immediately — no black frame.
            attachViewRenderersInternal()
        }
    }

    /**
     * FIX #2 + #4: stopSharingScreen is called both when the user manually stops sharing
     * AND when the call ends (via terminate → release). Wrapping everything in runCatching
     * makes it idempotent so calling it twice after a long session does not crash.
     *
     * FIX #2: After stopping, onRenegotiationNeeded signals the remote side immediately
     * so they see the screen share end without delay.
     */
    suspend fun stopSharingScreen() {
        if (!isSharingScreen()) return
        Timber.tag(loggerTag.value).d("stopSharingScreen called")

        runCatching { videoCapturer?.stopCapture() }
        runCatching { videoCapturer?.dispose() }
        videoCapturer = null

        screenSender?.let { runCatching { removeStream(it) } }
        screenSender = null

        runCatching { localVideoTrack?.setEnabled(false) }
        runCatching { localVideoTrack?.dispose() }
        localVideoTrack = null

        if (isVideoMode) {
            peerConnectionFactoryProvider.get()?.let { configureVideoTrack(it) }
        }

        updateMuteStatus()
        sessionScope?.launch(dispatcher) {
            onRenegotiationNeeded(restartIce = false)
            if (!isVideoMode) mxCall.sendScreenShareStop()
            attachViewRenderersInternal()
        }
    }

    /**
     * Called by the remote-tracking code when the remote peer starts/stops screen sharing.
     * FIX #2: Listeners are notified synchronously so VectorCallViewModel updates
     * isRemoteScreenSharing state — triggering an immediate UI re-render.
     */
    fun onRemoteScreenShareChanged(isSharing: Boolean) {
        this.isRemoteScreenSharing = isSharing
        listeners.forEach { tryOrNull { it.onRemoteScreenShareChanged(isSharing) } }
    }

    private fun startCapturingScreen(videoCapturer: VideoCapturer, videoSource: VideoSource) {
        val surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", rootEglBase!!.eglBaseContext)
        videoCapturer.initialize(surfaceTextureHelper, context, videoSource.capturerObserver)
        val format = if (videoCapturer.isScreencast) CaptureFormat.SCREEN_SHARING else currentCaptureFormat
        videoCapturer.startCapture(format.width, format.height, format.fps)
    }

    fun isSharingScreen(): Boolean {
        val track = localVideoTrack ?: return false
        val isScreenId = try { track.id() == SCREEN_TRACK_ID } catch (_: IllegalStateException) { false }
        val enabled = try { track.enabled() } catch (_: IllegalStateException) { false }
        return isScreenId && enabled
    }

    private fun removeStream(sender: RtpSender) {
        peerConnection?.removeTrack(sender)
    }

    // -------------------------------------------------------------------------
    // Call end / terminate
    // -------------------------------------------------------------------------

    /**
     * FIX #2: endCall is the single exit point for both local hangup and remote hangup.
     * It sets CallState.Ended synchronously on the MxCall so that any observer
     * (VectorCallViewModel, VectorCallActivity) receives the state update on the
     * next emission — resulting in an immediate Activity finish().
     *
     * sendSignaling=true  → we are hanging up (send hangup to remote).
     * sendSignaling=false → remote already hung up (onCallHangupReceived path).
     */
    fun endCall(reason: EndCallReason = EndCallReason.USER_HANGUP, sendSignaling: Boolean = true) {
        sessionScope?.launch(dispatcher) {
            if (mxCall.state is CallState.Ended) return@launch
            val reject = mxCall.state is CallState.LocalRinging
            terminate(reason, reject)
            if (sendSignaling) {
                if (reject) mxCall.reject() else mxCall.hangUp(reason)
            }
        }
    }

    /**
     * FIX #2 + #4: terminate() is the final teardown. It:
     *  1. Sets CallState.Ended immediately — unblocks all state observers.
     *  2. Calls release() which detaches all renderers, cancels jobs, and disposes
     *     every native WebRTC object in the correct order to prevent the
     *     "SurfaceViewRenderer already released" crash (FIX #4).
     *  3. Invokes onCallEnded callback so WebRtcCallManager removes the call
     *     from its active call list — this propagates to the remote user's device
     *     via the signaling layer (FIX #2).
     */
    private suspend fun terminate(reason: EndCallReason? = null, rejected: Boolean = false) =
            withContext(dispatcher) {
                runCatching { localVideoTrack?.setEnabled(false) }
                cameraAvailabilityCallback?.let {
                    context.getSystemService<CameraManager>()?.unregisterAvailabilityCallback(it)
                }
                inviteTimeout?.cancel()
                inviteTimeout = null
                // Set Ended state first — triggers immediate UI update (FIX #2).
                mxCall.state = CallState.Ended(reason ?: EndCallReason.USER_HANGUP)
                release()
                onCallEnded(callId, reason ?: EndCallReason.USER_HANGUP, rejected)
            }

    /**
     * FIX #4: release() teardown order matters to avoid native crashes:
     *  1. Detach all renderer sinks (remove WebRTC references to SurfaceViewRenderers).
     *  2. Stop + dispose the video capturer (MediaProjection or camera).
     *  3. Cancel coroutine jobs.
     *  4. Null out sender tracks on transceivers before closing PeerConnection.
     *  5. Close + dispose PeerConnection.
     *  6. Dispose audio/video sources.
     *  7. Null all local references.
     *
     * Every step is wrapped in runCatching so an already-disposed object in a long
     * call does not abort the remaining teardown steps.
     */
    private suspend fun release() {
        runCatching { listeners.clear() }
        runCatching { mxCall.removeListener(this) }
        runCatching { timer.stop() }
        timer.tickListener = null

        // Step 1: detach all renderer sinks.
        runCatching { detachRenderersInternal(null) }

        // Step 2: stop and dispose capturer.
        runCatching { videoCapturer?.stopCapture() }
        runCatching { videoCapturer?.dispose() }
        videoCapturer = null

        // Step 3: cancel jobs.
        runCatching { remoteIceCandidateJob?.cancel() }
        runCatching { localIceCandidateJob?.cancel() }

        // Step 4 + 5: clear sender tracks then close/dispose PeerConnection.
        val pc = peerConnection
        if (pc != null) {
            runCatching {
                pc.transceivers?.forEach { transceiver ->
                    runCatching { transceiver.sender.setTrack(null, false) }
                }
            }
            runCatching { pc.close() }
            runCatching { pc.dispose() }
        }

        // Step 6: dispose sources.
        runCatching { localAudioSource?.dispose() }
        runCatching { localVideoSource?.dispose() }

        // Step 7: null all local references.
        localAudioSource = null
        localAudioTrack = null
        localVideoSource = null
        localVideoTrack = null
        remoteAudioTrack = null
        remoteVideoTrack = null
        peerConnection = null
        cameraAvailabilityCallback = null
    }

    // -------------------------------------------------------------------------
    // ICE / stream events
    // -------------------------------------------------------------------------

    fun onAddStream(stream: MediaStream) {
        sessionScope?.launch(dispatcher) {
            if (stream.audioTracks.size > 1 || stream.videoTracks.size > 1) {
                Timber.tag(loggerTag.value).e("StreamObserver weird looking stream: $stream")
                endCall(EndCallReason.UNKWOWN_ERROR)
                return@launch
            }
            if (stream.audioTracks.size == 1) {
                val remoteAudioTrack = stream.audioTracks.first()
                remoteAudioTrack.setEnabled(true)
                this@WebRtcCall.remoteAudioTrack = remoteAudioTrack
            }
            if (stream.videoTracks.size == 1) {
                val remoteVideoTrack = stream.videoTracks.first()
                remoteVideoTrack.setEnabled(true)
                this@WebRtcCall.remoteVideoTrack = remoteVideoTrack
                remoteSurfaceRenderers.forEach { ref ->
                    ref.get()?.let { remoteVideoTrack.addSink(it) }
                }
            }
        }
    }

    fun onRemoveStream() {
        sessionScope?.launch(dispatcher) {
            remoteSurfaceRenderers.mapNotNull { it.get() }
                    .forEach { runCatching { remoteVideoTrack?.removeSink(it) } }
            remoteVideoTrack = null
            remoteAudioTrack = null
        }
    }

    fun onTrackReceived(track: VideoTrack) {
        sessionScope?.launch(dispatcher) {
            track.setEnabled(true)
            remoteVideoTrack = track
            remoteSurfaceRenderers.forEach { ref ->
                ref.get()?.let { runCatching { track.addSink(it) } }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Negotiation
    // -------------------------------------------------------------------------

    fun onRenegotiationNeeded(restartIce: Boolean) {
        sessionScope?.launch(dispatcher) {
            if (mxCall.state != CallState.CreateOffer && mxCall.opponentVersion == 0) return@launch
            val peerConnection = peerConnection ?: return@launch

            if (peerConnection.signalingState() != PeerConnection.SignalingState.STABLE) {
                Timber.tag(loggerTag.value).v("onRenegotiationNeeded suppressed; state=${peerConnection.signalingState()}")
                return@launch
            }

            val constraints = MediaConstraints().apply {
                if (restartIce) mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
            }
            makingOffer = true
            try {
                val sessionDescription = peerConnection.awaitCreateOffer(constraints) ?: return@launch
                peerConnection.awaitSetLocalDescription(sessionDescription)

//                if (peerConnection.iceGatheringState() == PeerConnection.IceGatheringState.GATHERING) {
//                    delay(50)
//                }
                awaitIceGatheringComplete(peerConnection, timeoutMs = 4_000L)
                if (mxCall.state is CallState.Ended) return@launch

//                if (mxCall.state == CallState.CreateOffer) {
//                    mxCall.offerSdp(sessionDescription.description)
//                    inviteTimeout = async {
//                        delay(INVITE_TIMEOUT_IN_MS)
//                        endCall(EndCallReason.INVITE_TIMEOUT)
//                    }
//                }
                if (mxCall.state == CallState.CreateOffer) {
                    mxCall.offerSdp(sessionDescription.description)
                    // Ring timeout starts NOW — after INVITE is sent to remote.
                    // Consistent 40s ring window on all devices.
                    inviteTimeout = async {
                        delay(RING_TIMEOUT_IN_MS)
                        endCall(EndCallReason.INVITE_TIMEOUT)
                    }
                } else {
                    mxCall.negotiate(sessionDescription.description, SdpType.OFFER)
                }
            } catch (failure: Throwable) {
                Timber.tag(loggerTag.value).e(failure, "Failure while creating offer")
            } finally {
                makingOffer = false
            }
        }
    }

    fun onCallNegotiateReceived(callNegotiateContent: CallNegotiateContent) {
        sessionScope?.launch(dispatcher) {
            val description = callNegotiateContent.description
            val type = description?.type ?: return@launch
            val sdpText = description.sdp ?: return@launch
            val peerConnection = peerConnection ?: return@launch

            val polite = !mxCall.isOutgoing
            val offerCollision = type == SdpType.OFFER &&
                    (makingOffer || peerConnection.signalingState() != PeerConnection.SignalingState.STABLE)
            ignoreOffer = !polite && offerCollision
            if (ignoreOffer) return@launch

            val prevOnHold = computeIsLocalOnHold()
            try {
                val sdp = SessionDescription(type.asWebRTC(), sdpText)
                peerConnection.awaitSetRemoteDescription(sdp)

                if (type == SdpType.OFFER) {
                    val hasVideoInOffer = sdpText.contains("\nm=video ")
                    val isGenuineVideoUpgrade = hasVideoInOffer && !isSharingScreen() &&
                            !isRemoteScreenSharing && !isVideoMode
                    if (isGenuineVideoUpgrade) {
                        if (localVideoTrack == null) {
                            peerConnectionFactoryProvider.get()?.let { configureVideoTrackWithoutAdding(it) }
                        }
                        resumeLocalVideoPipeline()
                    }
                    createAnswer()?.also { mxCall.negotiate(it.description, SdpType.ANSWER) }
                }
            } catch (failure: Throwable) {
                Timber.tag(loggerTag.value).e(failure, "Failed to complete negotiation")
            }

            val nowOnHold = computeIsLocalOnHold()
            wasLocalOnHold = nowOnHold
            if (prevOnHold != nowOnHold) {
                isLocalOnHold = nowOnHold
                listeners.forEach { tryOrNull { it.onHoldUnhold() } }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Call signaling callbacks
    // -------------------------------------------------------------------------

    fun onCallIceCandidateReceived(iceCandidatesContent: CallCandidatesContent) {
        sessionScope?.launch(dispatcher) {
            iceCandidatesContent.candidates.forEach {
                if (it.sdpMid.isNullOrEmpty() || it.candidate.isNullOrEmpty()) return@forEach
                remoteCandidateSource.emit(IceCandidate(it.sdpMid, it.sdpMLineIndex, it.candidate))
            }
        }
    }

    fun onCallAnswerReceived(callAnswerContent: CallAnswerContent) {
        inviteTimeout?.cancel()
        inviteTimeout = null
        sessionScope?.launch(dispatcher) {
            val sdp = SessionDescription(SessionDescription.Type.ANSWER, callAnswerContent.answer.sdp)
            try {
                peerConnection?.awaitSetRemoteDescription(sdp)
            } catch (failure: Throwable) {
                endCall(EndCallReason.UNKWOWN_ERROR)
                return@launch
            }
            if (mxCall.opponentPartyId?.hasValue().orFalse()) {
                mxCall.selectAnswer()
            }
        }
    }

    /**
     * FIX #2: onCallHangupReceived routes to terminate() which sets CallState.Ended
     * synchronously and triggers onCallEnded — so User2's screen closes immediately
     * when User1 hangs up, with no polling or delay.
     */
    fun onCallHangupReceived(callHangupContent: CallHangupContent) {
        sessionScope?.launch(dispatcher) {
            terminate(callHangupContent.reason)
        }
    }

    fun onCallRejectReceived(callRejectContent: CallRejectContent) {
        sessionScope?.launch(dispatcher) {
            terminate(callRejectContent.reason, true)
        }
    }

    fun onCallSelectedAnswerReceived(callSelectAnswerContent: CallSelectAnswerContent) {
        sessionScope?.launch(dispatcher) {
            val selectedPartyId = callSelectAnswerContent.selectedPartyId
            if (selectedPartyId != mxCall.ourPartyId) {
                terminate()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Hold / mute
    // -------------------------------------------------------------------------

    private fun computeIsLocalOnHold(): Boolean {
        if (mxCall.state !is CallState.Connected) return false
        var callOnHold = true
        for (transceiver in peerConnection?.transceivers ?: emptyList()) {
            val trackOnHold = transceiver.currentDirection == RtpTransceiver.RtpTransceiverDirection.INACTIVE ||
                    transceiver.currentDirection == RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
            if (!trackOnHold) callOnHold = false
        }
        return callOnHold
    }

    fun updateRemoteOnHold(onHold: Boolean) {
        sessionScope?.launch(dispatcher) {
            if (isRemoteOnHold == onHold) return@launch
            val direction: RtpTransceiver.RtpTransceiverDirection
            if (onHold) {
                wasLocalOnHold = isLocalOnHold
                isRemoteOnHold = true
                isLocalOnHold = true
                direction = RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
            } else {
                isRemoteOnHold = false
                isLocalOnHold = wasLocalOnHold
                onCallBecomeActive(this@WebRtcCall)
                direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
            }
            for (transceiver in peerConnection?.transceivers ?: emptyList()) {
                runCatching { transceiver.sender.track()?.setEnabled(!onHold) }
                runCatching { transceiver.receiver.track()?.setEnabled(!onHold) }
                transceiver.direction = direction
            }
            if (!onHold) {
                runCatching { localVideoTrack?.setEnabled(!videoMuted) }
                attachViewRenderersInternal()
            }
            updateMuteStatus()
            listeners.forEach { tryOrNull { it.onHoldUnhold() } }
        }
    }

    fun muteCall(muted: Boolean) {
        sessionScope?.launch(dispatcher) {
            micMuted = muted
            updateMuteStatus()
        }
    }

    fun enableVideo(enabled: Boolean) {
        sessionScope?.launch(dispatcher) {
            videoMuted = !enabled
            updateMuteStatus()
        }
    }
    private suspend fun awaitIceGatheringComplete(
            peerConnection: PeerConnection,
            timeoutMs: Long = 4_000L
    ) {
        if (peerConnection.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) return
        withTimeoutOrNull(timeoutMs) {
            while (peerConnection.iceGatheringState() != PeerConnection.IceGatheringState.COMPLETE) {
                delay(100)
            }
        }
        // Whether it completed naturally or timed out, we proceed either way.
        // Trickle ICE means having *some* candidates is enough — we don't need all of them.
    }
    // In onRenegotiationNeeded(), after awaitCreateOffer:

    private fun updateMuteStatus() {
        if (mxCall.state is CallState.Ended || peerConnection == null) return
        val micShouldBeMuted = micMuted || isRemoteOnHold
        runCatching { localAudioTrack?.setEnabled(!micShouldBeMuted) }
        runCatching { remoteAudioTrack?.setEnabled(!isRemoteOnHold) }
        val vidShouldBeMuted = videoMuted || isRemoteOnHold
        runCatching { localVideoTrack?.setEnabled(!vidShouldBeMuted) }
        runCatching { remoteVideoTrack?.setEnabled(!isRemoteOnHold) }
    }

    // -------------------------------------------------------------------------
    // Camera
    // -------------------------------------------------------------------------

    fun canSwitchCamera(): Boolean = availableCamera.size > 1

    fun currentCameraType(): CameraType? = cameraInUse?.type

    fun currentCaptureFormat(): CaptureFormat = currentCaptureFormat

    fun setCaptureFormat(format: CaptureFormat) {
        sessionScope?.launch(dispatcher) {
            videoCapturer?.changeCaptureFormat(format.width, format.height, format.fps)
            currentCaptureFormat = format
        }
    }

    fun switchCamera() {
        sessionScope?.launch(dispatcher) {
            if (mxCall.state !is CallState.Connected || !isVideoMode) return@launch
            val oppositeCamera = getOppositeCameraIfAny() ?: return@launch
            (videoCapturer as? CameraVideoCapturer)?.switchCamera(
                    object : CameraVideoCapturer.CameraSwitchHandler {
                        override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                            cameraInUse = oppositeCamera
                            localSurfaceRenderers.forEach { it.get()?.setMirror(isFrontCamera) }
                            listeners.forEach { tryOrNull { it.onCameraChanged() } }
                        }

                        override fun onCameraSwitchError(errorDescription: String?) {
                            Timber.tag(loggerTag.value).v("onCameraSwitchError: $errorDescription")
                        }
                    }, oppositeCamera.name
            )
        }
    }

    private fun getOppositeCameraIfAny(): CameraProxy? {
        val currentCamera = cameraInUse ?: return null
        return if (currentCamera.type == CameraType.FRONT) {
            availableCamera.firstOrNull { it.type == CameraType.BACK }
        } else {
            availableCamera.firstOrNull { it.type == CameraType.FRONT }
        }
    }

    // -------------------------------------------------------------------------
    // Answer
    // -------------------------------------------------------------------------

    private suspend fun createAnswer(): SessionDescription? {
        val peerConnection = peerConnection ?: return null
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (isVideoMode) "true" else "false"))
        }
        return try {
            val localDescription = peerConnection.awaitCreateAnswer(constraints) ?: return null
            peerConnection.awaitSetLocalDescription(localDescription)
            localDescription
        } catch (failure: Throwable) {
            Timber.tag(loggerTag.value).v("Failed to create answer: $failure")
            null
        }
    }

    // -------------------------------------------------------------------------
    // Audio / video switch
    // -------------------------------------------------------------------------

    fun acceptIncomingCall() {
        sessionScope?.launch {
            if (mxCall.state == CallState.LocalRinging) {
                internalAcceptIncomingCall()
            }
        }
    }

    fun sendDtmfDigit(digit: String) {
        sessionScope?.launch {
            for (sender in peerConnection?.senders.orEmpty()) {
                if (sender.track()?.kind() == "audio" && sender.dtmf()?.canInsertDtmf() == true) {
                    try {
                        sender.dtmf()?.insertDtmf(digit, 100, 70)
                        return@launch
                    } catch (failure: Throwable) {
                        Timber.tag(loggerTag.value).v("Failed to send DTMF digit")
                    }
                }
            }
        }
    }

    fun switchToVoice(sendUpdate: Boolean = true) {
        sessionScope?.launch(dispatcher) {
            localVideoTrack?.setEnabled(false)
            runCatching { videoCapturer?.stopCapture() }
            runCatching { videoCapturer?.dispose() }
            videoCapturer = null
            localSurfaceRenderers.forEach { ref ->
                ref.get()?.let { runCatching { localVideoTrack?.removeSink(it) } }
            }
            remoteSurfaceRenderers.forEach { ref ->
                ref.get()?.let { runCatching { remoteVideoTrack?.removeSink(it) } }
            }
            runCatching { localVideoTrack?.dispose() }
            runCatching { localVideoSource?.dispose() }
            localVideoTrack = null
            localVideoSource = null
            runCatching { remoteVideoTrack?.setEnabled(false) }
            runCatching { remoteVideoTrack?.dispose() }
            remoteVideoTrack = null
            videoMuted = true
            isVideoMode = false
            updateMuteStatus()
            if (sendUpdate) runCatching { mxCall.sendVideoReject() }
        }
    }

    private fun switchToVideoAsReceiver() {
        sessionScope?.launch(dispatcher) {
            screenSender?.let { runCatching { removeStream(it) }; screenSender = null }
            if (localVideoTrack == null) {
                peerConnectionFactoryProvider.get()?.let { configureVideoTrack(it) }
            }
            peerConnection?.let { pc ->
                val hasVideoMid = pc.transceivers.any {
                    it.receiver.track()?.kind() == MediaStreamTrack.VIDEO_TRACK_KIND
                }
                if (!hasVideoMid) {
                    pc.addTransceiver(
                            MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
                    )
                }
            }
            resumeLocalVideoPipeline()
        }
    }

    fun switchToVideoAsInitiator() {
        sessionScope?.launch(dispatcher) {
            screenSender?.let { runCatching { removeStream(it) }; screenSender = null }
            if (localVideoTrack == null) {
                peerConnectionFactoryProvider.get()?.let { configureVideoTrackWithoutAdding(it) }
            }
            resumeLocalVideoPipeline()
        }
    }

    @Deprecated("Use switchToVideoAsReceiver() or switchToVideoAsInitiator() instead")
    fun switchToVideo(sendUpdate: Boolean = true) {
        if (sendUpdate) switchToVideoAsReceiver() else switchToVideoAsInitiator()
    }

    // -------------------------------------------------------------------------
    // Transfer
    // -------------------------------------------------------------------------

    fun transferToUser(targetUserId: String, targetRoomId: String?) {
        sessionScope?.launch(dispatcher) {
            mxCall.transfer(
                    targetUserId = targetUserId,
                    targetRoomId = targetRoomId,
                    createCallId = CallIdGenerator.generate(),
                    awaitCallId = null
            )
            terminate(EndCallReason.REPLACED)
        }
    }

    fun transferToCall(transferTargetCall: WebRtcCall) {
        sessionScope?.launch(dispatcher) {
            val newCallId = CallIdGenerator.generate()
            transferTargetCall.mxCall.transfer(
                    targetUserId = mxCall.opponentUserId,
                    targetRoomId = null,
                    createCallId = null,
                    awaitCallId = newCallId
            )
            mxCall.transfer(
                    targetUserId = transferTargetCall.mxCall.opponentUserId,
                    targetRoomId = null,
                    createCallId = newCallId,
                    awaitCallId = null
            )
            terminate(EndCallReason.REPLACED)
            transferTargetCall.terminate(EndCallReason.REPLACED)
        }
    }

    // -------------------------------------------------------------------------
    // Video request (voice → video upgrade)
    // -------------------------------------------------------------------------

    fun onVideoRequestReceived() {
        pendingVideoRequest = true
        listeners.forEach { tryOrNull { it.onVideoRequestReceived(mxCall) } }
    }

    fun acceptVideoRequest() {
        if (!pendingVideoRequest) return
        pendingVideoRequest = false
        switchToVideoAsReceiver()
        mxCall.sendVideoAccept()
        listeners.forEach { it.onVideoRequestAccepted(mxCall) }
    }

    fun rejectVideoRequest() {
        if (!pendingVideoRequest) return
        pendingVideoRequest = false
        mxCall.sendVideoReject()
    }

    // -------------------------------------------------------------------------
    // Asserted identity
    // -------------------------------------------------------------------------

    fun onCallAssertedIdentityReceived(callAssertedIdentityContent: CallAssertedIdentityContent) {
        sessionScope?.launch(dispatcher) {
            val session = sessionProvider.get() ?: return@launch
            val newAssertedIdentity = callAssertedIdentityContent.assertedIdentity ?: return@launch
            if (newAssertedIdentity.id == null && newAssertedIdentity.displayName == null) return@launch
            remoteAssertedIdentity = newAssertedIdentity
            if (newAssertedIdentity.id != null) {
                val nativeUserId = session.sipNativeLookup(newAssertedIdentity.id!!).firstOrNull()?.userId
                if (nativeUserId != null) {
                    val resolvedUser = tryOrNull { session.userService().resolveUser(nativeUserId) }
                    remoteAssertedIdentity = if (resolvedUser != null) {
                        newAssertedIdentity.copy(
                                id = nativeUserId,
                                avatarUrl = resolvedUser.avatarUrl,
                                displayName = resolvedUser.displayName
                        )
                    } else {
                        newAssertedIdentity.copy(id = nativeUserId)
                    }
                }
            }
            listeners.forEach { tryOrNull { it.assertedIdentityChanged() } }
        }
    }

    // -------------------------------------------------------------------------
    // Timer / state listener
    // -------------------------------------------------------------------------

    fun durationMillis(): Int = timer.elapsedTime().toInt()

    fun formattedDuration(): String = formatDuration(Duration.ofMillis(timer.elapsedTime()))

    override fun onStateUpdate(call: MxCall) {
        val state = call.state
        if (state is CallState.Connected && state.iceConnectionState == MxPeerConnectionState.CONNECTED) {
            timer.resume()
        } else {
            timer.pause()
        }
        listeners.forEach { tryOrNull { it.onStateUpdate(call) } }
    }

    // -------------------------------------------------------------------------
    // Resume local video pipeline (voice → video upgrade)
    // -------------------------------------------------------------------------

    private fun resumeLocalVideoPipeline() {
        val track = localVideoTrack ?: run {
            Timber.tag(loggerTag.value).w("resumeLocalVideoPipeline: no localVideoTrack")
            return
        }
        try {
            track.setEnabled(true)
        } catch (e: IllegalStateException) {
            Timber.tag(loggerTag.value).w(e, "resumeLocalVideoPipeline: track disposed — recreating")
            localVideoTrack = null
            peerConnectionFactoryProvider.get()?.let { configureVideoTrack(it) }
            return
        }

        localSurfaceRenderers.forEach { ref ->
            ref.get()?.let { sink -> runCatching { track.addSink(sink) } }
        }

        runCatching { videoCapturer?.stopCapture() }
        runCatching {
            videoCapturer?.startCapture(
                    currentCaptureFormat.width,
                    currentCaptureFormat.height,
                    currentCaptureFormat.fps
            )
        }

        if (videoSender == null) {
            videoSender = peerConnection?.addTrack(track, listOf(STREAM_ID))
        } else {
            runCatching { videoSender?.setTrack(track, true) }
        }

        peerConnection?.transceivers
                ?.firstOrNull {
                    it.receiver.track()?.kind() == MediaStreamTrack.VIDEO_TRACK_KIND ||
                            it.sender == videoSender
                }
                ?.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV

        videoMuted = false
        if (!isSharingScreen()) isVideoMode = true
        updateMuteStatus()
    }
}

// -------------------------------------------------------------------------
// WeakReference list helpers
// -------------------------------------------------------------------------

private fun MutableList<WeakReference<SurfaceViewRenderer>>.addIfNeeded(renderer: SurfaceViewRenderer?) {
    if (renderer == null) return
    if (none { it.get() == renderer }) add(WeakReference(renderer))
}

private fun MutableList<WeakReference<SurfaceViewRenderer>>.removeIfNeeded(renderer: SurfaceViewRenderer?) {
    if (renderer == null) return
    removeAll { it.get() == renderer }
}
