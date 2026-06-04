/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.call

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.Parcelable
import android.util.Rational
import android.view.MenuItem
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.annotation.StringRes
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.util.Consumer
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.airbnb.mvrx.Fail
import com.airbnb.mvrx.Mavericks
import com.airbnb.mvrx.viewModel
import com.airbnb.mvrx.withState
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.extensions.registerStartForActivityResult
import im.vector.app.core.extensions.setTextOrHide
import im.vector.app.core.platform.VectorBaseActivity
import im.vector.app.core.platform.VectorMenuProvider
import im.vector.app.core.services.CallAndroidService
import im.vector.app.core.utils.PERMISSIONS_FOR_AUDIO_IP_CALL
import im.vector.app.core.utils.PERMISSIONS_FOR_VIDEO_IP_CALL
import im.vector.app.core.utils.checkPermissions
import im.vector.app.core.utils.registerForPermissionsResult
import im.vector.app.databinding.ActivityCallBinding
import im.vector.app.features.call.audio.MicrophoneAccessService
import im.vector.app.features.call.dialpad.CallDialPadBottomSheet
import im.vector.app.features.call.dialpad.DialPadFragment
import im.vector.app.features.call.transfer.CallTransferActivity
import im.vector.app.features.call.utils.EglUtils
import im.vector.app.features.call.webrtc.ScreenCaptureAndroidService
import im.vector.app.features.call.webrtc.ScreenCaptureServiceConnection
import im.vector.app.features.call.webrtc.WebRtcCall
import im.vector.app.features.call.webrtc.WebRtcCallManager
import im.vector.app.features.displayname.getBestName
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.room.detail.RoomDetailActivity
import im.vector.app.features.home.room.detail.arguments.TimelineArgs
import im.vector.app.features.notifications.CallForegroundService
import im.vector.app.features.notifications.NotificationDrawerManager
import im.vector.lib.core.utils.compat.getParcelableExtraCompat
import im.vector.lib.strings.CommonStrings
import io.github.hyuwah.draggableviewlib.DraggableView
import io.github.hyuwah.draggableviewlib.setupDraggable
import kotlinx.parcelize.Parcelize
import org.matrix.android.sdk.api.extensions.orFalse
import org.matrix.android.sdk.api.logger.LoggerTag
import org.matrix.android.sdk.api.session.call.CallState
import org.matrix.android.sdk.api.session.call.MxPeerConnectionState
import org.matrix.android.sdk.api.session.room.model.call.EndCallReason
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.ScreenCapturerAndroid
import timber.log.Timber
import javax.inject.Inject

@Parcelize
data class CallArgs(
        val signalingRoomId: String,
        val callId: String,
        val participantUserId: String,
        val isIncomingCall: Boolean,
        val isVideoCall: Boolean
) : Parcelable

private val loggerTag = LoggerTag("VectorCallActivity", LoggerTag.VOIP)

@AndroidEntryPoint
class VectorCallActivity :
        VectorBaseActivity<ActivityCallBinding>(),
        CallControlsView.InteractionListener,
        VectorMenuProvider {

    override fun getBinding() = ActivityCallBinding.inflate(layoutInflater)
    @Inject lateinit var notificationDrawerManager: NotificationDrawerManager
    @Inject lateinit var callManager: WebRtcCallManager
    @Inject lateinit var avatarRenderer: AvatarRenderer
    private var callEndHandled = false
    @Inject lateinit var screenCaptureServiceConnection: ScreenCaptureServiceConnection
    private var callWasAnswered = false
    private val callViewModel: VectorCallViewModel by viewModel()

    private val dialPadCallback = object : DialPadFragment.Callback {
        override fun onDigitAppended(digit: String) {
            callViewModel.handle(VectorCallViewActions.SendDtmfDigit(digit))
        }
    }

    private var rootEglBase: EglBase? = null
    private var areControlsVisible = true
    private var hasAutoHiddenControls = false

    private var pipDraggableView: DraggableView<MaterialCardView>? = null
    private var otherCallDraggableView: DraggableView<MaterialCardView>? = null

    // FIX #4: Track renderer initialization state to prevent double-release crash.
    // Only release renderers that have been successfully initialized.
    var surfaceRenderersAreInitialized = false

    private var waitingDialog: AlertDialog? = null

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun doBeforeSetContentView() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON  or
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        setContentView(R.layout.activity_call)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val windowInsetsController = WindowInsetsControllerCompat(window, window.decorView)
        windowInsetsController.isAppearanceLightStatusBars = false
        windowInsetsController.isAppearanceLightNavigationBars = false
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.BLACK
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        callViewModel.onEach(VectorCallViewState::callInfo) {
            withState(callViewModel) { renderState(it) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        // Existing async observer - keep this
        // ONLY this one — remove the other two completely
        callViewModel.onEach(VectorCallViewState::callState) { callStateAsync ->
            val callState = callStateAsync.invoke() ?: return@onEach
            if (callState is CallState.Ended && !isFinishing && !isDestroyed) {
                handleCallEnded(callState)
            }
        }

        enableImmersiveMode()
        addOnPictureInPictureModeChangedListener(pictureInPictureModeChangedInfoConsumer)

        if (intent.getStringExtra(EXTRA_MODE) == INCOMING_RINGING) {
            turnScreenOnAndKeyguardOff()
        }
        if (savedInstanceState != null) {
            (supportFragmentManager.findFragmentByTag(FRAGMENT_DIAL_PAD_TAG) as? CallDialPadBottomSheet)
                    ?.callback = dialPadCallback
        }

        setupToolbar(views.callToolbar)
        configureCallViews()

        // In onCreate — ONLY ONE observer, no duplicate:


        // handleCallEnded — just finish, WebRtcCallManager already told the service




        // Main state renderer — MISSING in your current code, add this
        callViewModel.onEach { renderState(it) }

        callViewModel.observeViewEvents { handleViewEvents(it) }

        callViewModel.onEach(VectorCallViewState::callId, VectorCallViewState::isVideoCall) { _, isVideoCall ->
            if (isVideoCall) {
                if (checkPermissions(PERMISSIONS_FOR_VIDEO_IP_CALL, this, permissionCameraLauncher,
                                CommonStrings.permissions_rationale_msg_camera_and_audio)) {
                    setupRenderersIfNeeded()
                }
            } else {
                if (checkPermissions(PERMISSIONS_FOR_AUDIO_IP_CALL, this, permissionCameraLauncher,
                                CommonStrings.permissions_rationale_msg_record_audio)) {
                    setupRenderersIfNeeded()
                }
            }
        }

        views.constraintLayout.setOnClickListener { toggleControlsVisibility() }
        bindToScreenCaptureService()
    }

//    override fun onNewIntent(intent: Intent) {
//        super.onNewIntent(intent)
//        intent.takeIf { it.hasExtra(Mavericks.KEY_ARG) }
//                ?.let { intent.getParcelableExtraCompat<CallArgs>(Mavericks.KEY_ARG) }
//        this.intent = intent
//    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        this.intent = intent

        // Detach stale renderers from the outgoing call
        withState(callViewModel) { state ->
            callManager.getCallById(state.callId)?.detachRenderers(
                    listOf(views.pipRenderer, views.fullscreenRenderer, views.remotePipRenderer)
            )
        }

        // Re-attach to the incoming call after ViewModel processes the new intent
        views.root.post { reattachRenderersToCall() }
    }

    override fun getMenuRes() = R.menu.vector_call

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enterPictureInPictureIfRequired()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (!enterPictureInPictureIfRequired()) {
            super.onBackPressed()
        }
    }

    override fun onPause() {
        super.onPause()
        startMicrophoneService()
    }

    override fun onResume() {
        super.onResume()
        stopMicrophoneService()
        enableImmersiveMode()
    }


        // FIX #4: Detach renderers BEFORE releasing them to prevent
        // "SurfaceViewRenderer already released" crash after long calls.
        // detachRenderersIfNeeded() guards with surfaceRenderersAreInitialized flag.
        override fun onDestroy() {
            withState(callViewModel) { state ->
                if (state.callState.invoke() is CallState.LocalRinging) {
                    callViewModel.handle(VectorCallViewActions.DeclineCall)
                }
            }
            detachRenderersIfNeeded()
        turnScreenOffAndKeyguardOn()
        removeOnPictureInPictureModeChangedListener(pictureInPictureModeChangedInfoConsumer)
        screenCaptureServiceConnection.unbind()
        dismissWaitingDialog()
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // PiP
    // -------------------------------------------------------------------------

    private fun enterPictureInPictureIfRequired(): Boolean = withState(callViewModel) {
        if (!it.isVideoCall && !it.isLocalScreenSharing && !it.isRemoteScreenSharing) return@withState false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val aspectRatio = Rational(
                    resources.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.call_pip_width),
                    resources.getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.call_pip_height)
            )
            val params = PictureInPictureParams.Builder().setAspectRatio(aspectRatio).build()
            renderPiPMode(it)
            enterPictureInPictureMode(params)
        } else {
            false
        }
    }

    private fun isInPictureInPictureModeSafe(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode
    }

    private val pictureInPictureModeChangedInfoConsumer = Consumer<PictureInPictureModeChangedInfo> {
        withState(callViewModel) { renderState(it) }
    }

    // -------------------------------------------------------------------------
    // Menu
    // -------------------------------------------------------------------------

    override fun handleMenuItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_call_open_chat -> { returnToChat(); true }
            android.R.id.home -> { @Suppress("DEPRECATION") onBackPressed(); true }
            else -> false
        }
    }

    // -------------------------------------------------------------------------
    // Renderer lifecycle
    // -------------------------------------------------------------------------

    /**
     * FIX #4: Crash after long call / screen share.
     * Guard: only release if initialized. Detach from WebRtcCall first so the
     * native track no longer holds a reference before we call .release().
     * Also releases remotePipRenderer which was missing before.
     */
    private fun detachRenderersIfNeeded() {
        val callId = withState(callViewModel) { it.callId }
        callManager.getCallById(callId)?.detachRenderers(
                listOf(views.pipRenderer, views.fullscreenRenderer, views.remotePipRenderer)
        )
        if (surfaceRenderersAreInitialized) {
            safeRelease(views.pipRenderer, "pipRenderer")
            safeRelease(views.fullscreenRenderer, "fullscreenRenderer")
            safeRelease(views.remotePipRenderer, "remotePipRenderer")
            surfaceRenderersAreInitialized = false
        }
    }

    private fun safeRelease(renderer: org.webrtc.SurfaceViewRenderer, name: String) {
        try {
            renderer.release()
        } catch (e: Exception) {
            Timber.tag(loggerTag.value).w(e, "safeRelease: $name threw during release — ignoring")
        }
    }

    /**
     * FIX #4: Wrap renderer initialization in try/catch.
     * Initialize remotePipRenderer for screen-share remote camera card.
     */
    private fun setupRenderersIfNeeded() {
        if (surfaceRenderersAreInitialized) {
            // Already initialized — just re-attach tracks (e.g. config change / screen rotation).
            reattachRenderersToCall()
            return
        }

        rootEglBase = EglUtils.rootEglBase ?: run {
            Timber.tag(loggerTag.value).v("rootEglBase is null — cannot init renderers")
            finish()
            return
        }

        try {
            views.pipRenderer.apply {
                init(rootEglBase!!.eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_BALANCED)
                setEnableHardwareScaler(true)
                setZOrderMediaOverlay(true)
            }
            views.fullscreenRenderer.apply {
                init(rootEglBase!!.eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setEnableHardwareScaler(true)
            }
            // FIX #1: Initialize the new remotePipRenderer for screen-share mode.
            views.remotePipRenderer.apply {
                init(rootEglBase!!.eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_BALANCED)
                setEnableHardwareScaler(true)
                setZOrderMediaOverlay(true)
            }
            surfaceRenderersAreInitialized = true
            reattachRenderersToCall()
        } catch (e: Exception) {
            Timber.tag(loggerTag.value).e(e, "Failed to initialize surface renderers")
        }
    }

    private fun reattachRenderersToCall() {
        val callId = withState(callViewModel) { it.callId }
        val webRtcCall = callManager.getCallById(callId)
        if (webRtcCall != null) {
            webRtcCall.attachViewRenderers(
                    views.pipRenderer,
                    views.fullscreenRenderer,
                    intent.getStringExtra(EXTRA_MODE)
            )
            intent.removeExtra(EXTRA_MODE)
        } else {
            if (isFinishing || isDestroyed) return
            Timber.tag(loggerTag.value).w("reattachRenderersToCall: call $callId not found, retrying...")
            views.root.postDelayed({
                if (!isFinishing && !isDestroyed) reattachRenderersToCall()
            }, 500)
        }
    }

    // -------------------------------------------------------------------------
    // State rendering
    // -------------------------------------------------------------------------

    private fun renderState(state: VectorCallViewState) {
        Timber.tag(loggerTag.value).v("renderState $state")
        if (state.callState is Fail) {
            finish()
            return
        }
        if (isInPictureInPictureModeSafe()) {
            renderPiPMode(state)
        } else {
            renderFullScreenMode(state)
        }
    }

    /**
     * Full-screen (non-PiP) rendering.
     *
     * Screen share layout (FIX #1):
     *   - fullscreenRenderer  → shared screen content
     *   - remotePipRendererWrapper → remote camera (top-right card, shown ONLY during screen share)
     *   - pipRendererWrapper  → GONE during screen share (user chose "remote camera only" in PiP)
     *
     * Normal video call:
     *   - fullscreenRenderer  → remote video
     *   - pipRendererWrapper  → local self-view
     *   - remotePipRendererWrapper → GONE
     *
     * FIX #3: bgCallView + otherMemberAvatar are always the bottom layers, so there is
     * never a blank black surface visible regardless of renderer state.
     */
    private fun renderFullScreenMode(state: VectorCallViewState) {
        views.callToolbar.isVisible = true
        views.callControlsView.isVisible = true
        views.callControlsView.updateForState(state)

        val callState = state.callState.invoke()
        views.callActionText.setOnClickListener(null)
        views.callActionText.isVisible = false
        views.smallIsHeldIcon.isVisible = false
        views.reconnectingIndicator.isVisible = false

        when (callState) {
            is CallState.Idle,
            is CallState.CreateOffer,
            is CallState.LocalRinging,
            is CallState.Dialing -> {
                showAvatarLayout(state)
                hideVideoLayout()
                toolbar?.setSubtitle(CommonStrings.call_ringing)
            }

            is CallState.Answering -> {
                showAvatarLayout(state)
                hideVideoLayout()
                toolbar?.setSubtitle(CommonStrings.call_connecting)
            }

            is CallState.Connected -> {
                val isReconnecting = callState.iceConnectionState != MxPeerConnectionState.CONNECTED
                views.reconnectingIndicator.isVisible = isReconnecting
                toolbar?.subtitle = state.formattedDuration

                if (!hasAutoHiddenControls && state.isVideoCall &&
                        callState.iceConnectionState == MxPeerConnectionState.CONNECTED) {
                    hasAutoHiddenControls = true
                    areControlsVisible = false
                }

                if (callState.iceConnectionState == MxPeerConnectionState.CONNECTED) {
                    when {
                        state.isLocalOnHold || state.isRemoteOnHold -> renderHoldState(state)
                        state.transferee !is VectorCallViewState.TransfereeState.NoTransferee -> renderTransferState(state)
                        else -> renderConnectedState(state)
                    }
                } else {
                    toolbar?.setSubtitle(CommonStrings.call_connecting)
                }
            }

            is CallState.Ended -> {
                showAvatarLayout(state)
                hideVideoLayout()
                toolbar?.setSubtitle(CommonStrings.call_ended)
            }

            else -> {
                views.callInfoGroup.isInvisible = true
                hideVideoLayout()
            }
        }

        updateControlsVisibility()
    }

    private fun renderHoldState(state: VectorCallViewState) {
        views.smallIsHeldIcon.isVisible = true
        showAvatarLayout(state, blurAvatar = true)
        hideVideoLayout()
        if (state.isRemoteOnHold) {
            views.callActionText.setText(CommonStrings.call_resume_action)
            views.callActionText.isVisible = true
            views.callActionText.setOnClickListener {
                callViewModel.handle(VectorCallViewActions.ToggleHoldResume)
            }
            toolbar?.setSubtitle(CommonStrings.call_held_by_you)
        } else {
            views.callActionText.isVisible = false
            views.callActionText.text = null
            state.callInfo?.opponentUserItem?.let {
                toolbar?.subtitle = getString(CommonStrings.call_held_by_user, it.getBestName())
            }
        }
    }

    private fun renderTransferState(state: VectorCallViewState) {
        val transfereeName = if (state.transferee is VectorCallViewState.TransfereeState.KnownTransferee) {
            state.transferee.name
        } else {
            getString(CommonStrings.call_transfer_unknown_person)
        }
        views.callActionText.text = getString(CommonStrings.call_transfer_transfer_to_title, transfereeName)
        views.callActionText.isVisible = true
        views.callActionText.setOnClickListener { callViewModel.handle(VectorCallViewActions.TransferCall) }
        configureCallInfo(state)
    }

    /**
     * FIX #1 — Screen share layout:
     * When localScreenSharing: fullscreen = shared screen, remotePipCard = remote camera.
     * When remoteScreenSharing: fullscreen = remote screen, remotePipCard = GONE (remote
     *   has no separate camera track; showing their screen IS their video).
     * When normal video: fullscreen = remote camera, localPipCard = self-view.
     *
     * FIX #3: When video is not yet flowing (fullscreenRenderer GONE), bgCallView +
     * otherMemberAvatar are visible underneath — no black screen.
     */
    private fun renderConnectedState(state: VectorCallViewState) {
        configureCallInfo(state)
        val hasAnyVideo = state.isVideoCall || state.isLocalScreenSharing || state.isRemoteScreenSharing

        if (!hasAnyVideo) {
            showAvatarLayout(state)
            hideVideoLayout()
            return
        }

        // Video / screen share active
        views.fullscreenRenderer.isVisible = true
        // Keep avatar visible as a background safety net until first frame arrives.
        // fullscreenRenderer draws on top so it covers the avatar when rendering.
        views.callInfoGroup.isVisible = false

        when {
            state.isLocalScreenSharing -> {
                // Local screen share fills fullscreen.
                // Remote camera shown in top-right PiP card (remote-only per user preference).
                views.remotePipRendererWrapper.isVisible = true
                views.remotePipRenderer.isVisible = true
                renderRemotePipAvatar(state)       // fallback avatar inside card
                // Local self-view card hidden during screen share.
                views.pipRendererWrapper.isVisible = false
                views.pipRenderer.isVisible = false
            }
            state.isRemoteScreenSharing -> {
                // Remote screen share fills fullscreen.
                // No separate remote camera stream available — hide both PiP cards.
                views.remotePipRendererWrapper.isVisible = false
                views.remotePipRenderer.isVisible = false
                views.pipRendererWrapper.isVisible = false
                views.pipRenderer.isVisible = false
            }
            else -> {
                // Normal video call: remote fullscreen, local self-view in PiP card.
                views.remotePipRendererWrapper.isVisible = false
                views.remotePipRenderer.isVisible = false
                val showLocalPip = !state.isVideoCaptureInError && state.otherKnownCallInfo == null
                views.pipRendererWrapper.isVisible = showLocalPip
                views.pipRenderer.isVisible = showLocalPip
            }
        }
    }

    /**
     * PiP window rendering — minimal UI, no controls, no toolbar.
     * FIX #5: Avatar is small (80dp in XML) so it fits the tiny PiP window.
     * FIX #3: Avatar always shown as background; fullscreenRenderer draws on top.
     * FIX #2: If call has ended we finish() so the PiP window closes immediately.
     */
    private fun renderPiPMode(state: VectorCallViewState) {
        views.callToolbar.isVisible = false
        views.callControlsView.isVisible = false
        views.callActionText.isVisible = false
        views.reconnectingIndicator.isVisible = false
        // Always hide the in-call PiP cards — we don't want nested PiP cards inside a PiP window.
        views.pipRendererWrapper.isVisible = false
        views.pipRenderer.isVisible = false
        views.remotePipRendererWrapper.isVisible = false
        views.remotePipRenderer.isVisible = false
        views.otherKnownCallLayout.isVisible = false
        views.smallIsHeldIcon.isVisible = false

        val callState = state.callState.invoke()
        when (callState) {
            is CallState.Idle,
            is CallState.CreateOffer,
            is CallState.LocalRinging,
            is CallState.Dialing,
            is CallState.Answering -> {
                views.fullscreenRenderer.isVisible = false
                views.callInfoGroup.isVisible = false
            }

            is CallState.Connected -> {
                if (callState.iceConnectionState == MxPeerConnectionState.CONNECTED) {
                    callWasAnswered = true
                    if (state.isLocalOnHold || state.isRemoteOnHold) {
                        views.smallIsHeldIcon.isVisible = true
                        views.fullscreenRenderer.isVisible = false
                        views.callInfoGroup.isVisible = true
                        configureCallInfo(state, blurAvatar = true)
                    } else {
                        configureCallInfo(state)
                        val showVideo = state.isVideoCall || state.isLocalScreenSharing || state.isRemoteScreenSharing
                        views.fullscreenRenderer.isVisible = showVideo
                        // Show avatar background if no video yet (FIX #3).
                        views.callInfoGroup.isVisible = !showVideo
                    }
                } else {
                    views.fullscreenRenderer.isVisible = false
                    views.callInfoGroup.isVisible = false
                }
            }

//            is CallState.Ended -> {
//                // FIX #2: Immediately bring activity to front and finish when call ends
//                // while we are in PiP — this closes the PiP window right away.
//                val startIntent = Intent(this, VectorCallActivity::class.java).apply {
//                    flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
//                }
//                startActivity(startIntent)
//                finish()
//            }
            is CallState.Ended -> {
                val startIntent = Intent(this, VectorCallActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                }
                startActivity(startIntent)
                finish()
            }

            else -> {
                views.fullscreenRenderer.isVisible = false
                views.callInfoGroup.isVisible = false
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helper layout methods
    // -------------------------------------------------------------------------

    private fun showAvatarLayout(state: VectorCallViewState, blurAvatar: Boolean = false) {
        views.callInfoGroup.isVisible = true
        configureCallInfo(state, blurAvatar)
    }

    private fun hideVideoLayout() {
        views.fullscreenRenderer.isVisible = false
        views.pipRendererWrapper.isVisible = false
        views.pipRenderer.isVisible = false
        views.remotePipRendererWrapper.isVisible = false
        views.remotePipRenderer.isVisible = false
    }

    /**
     * Render the fallback avatar inside the remote-PiP card.
     * If the remote participant has a camera track the SurfaceViewRenderer will cover
     * this ImageView automatically once frames arrive.
     */
    private fun renderRemotePipAvatar(state: VectorCallViewState) {
        state.callInfo?.opponentUserItem?.let {
            avatarRenderer.render(it, views.remotePipAvatarView)
        }
    }

    // -------------------------------------------------------------------------
    // Call ended
    // -------------------------------------------------------------------------

    /**
     * FIX #2: Immediate disconnect for User2 when User1 ends.
     * handleCallEnded is triggered by callViewModel.onAsync on the main thread
     * the moment the SDK delivers CallState.Ended — no polling, no delay.
     * When in PiP, renderPiPMode already calls finish(); this handles non-PiP.
     */


//    private fun handleCallEnded(callState: CallState.Ended) {
//        handleStopScreenSharingService()
//
//        // Clear any stuck incoming call notification immediately
//        notificationDrawerManager.clearAllEvents()
//
//        when (callState.reason) {
//            EndCallReason.USER_BUSY -> {
//                Toast.makeText(this, "Call unanswered", Toast.LENGTH_LONG).show()
//                finish()
//            }
//            EndCallReason.INVITE_TIMEOUT -> {
//                Toast.makeText(this, "Call not answered", Toast.LENGTH_LONG).show()
//                finish()
//            }
//            EndCallReason.USER_HANGUP, EndCallReason.REPLACED -> {
//                val formattedDuration = withState(callViewModel) { it.formattedDuration }
//                val callArgs = intent.getParcelableExtraCompat<CallArgs>(Mavericks.KEY_ARG)
//                if (callArgs?.isIncomingCall == false && (formattedDuration.isEmpty() || formattedDuration == "00:00")) {
//                    Toast.makeText(this, "Call unanswered", Toast.LENGTH_LONG).show()
//                }
//                finish()
//            }
//            else -> finish()
//        }
//    }
    private fun handleCallEnded(callState: CallState.Ended) {
        if (callEndHandled) return
        callEndHandled = true
        notificationDrawerManager.clearAllEvents()
        androidx.core.app.NotificationManagerCompat.from(applicationContext).cancelAll()
        CallForegroundService.stop(applicationContext)

        // Show toast on caller side when receiver rejects
        val callArgs = intent.getParcelableExtraCompat<CallArgs>(Mavericks.KEY_ARG)
        val isOutgoing = callArgs?.isIncomingCall == false
        val formattedDuration = withState(callViewModel) { it.formattedDuration }
        val callNotAnswered = formattedDuration.isEmpty() || formattedDuration == "00:00"

        if (isOutgoing && callNotAnswered) {
            when (callState.reason) {
                EndCallReason.USER_HANGUP -> {
                    // Receiver explicitly rejected
                    Toast.makeText(this, "Call rejected", Toast.LENGTH_SHORT).show()
                }
                EndCallReason.INVITE_TIMEOUT -> {
                    // No answer within ring timeout
                    Toast.makeText(this, "No answer", Toast.LENGTH_SHORT).show()
                }
                else -> Unit
            }
        }

        finishAndRemoveTask()
    }
    private fun showEndCallDialog(@StringRes title: Int, @StringRes description: Int) {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(description)
                .setNegativeButton(CommonStrings.ok, null)
                .setOnDismissListener { finish() }
                .show()
    }

    // -------------------------------------------------------------------------
    // Call info configuration
    // -------------------------------------------------------------------------

    private fun configureCallInfo(state: VectorCallViewState, blurAvatar: Boolean = false) {
        val opponentItem = state.callInfo?.opponentUserItem

        // Always set toolbar title — use callId as fallback if name not loaded yet
        if (state.transferee is VectorCallViewState.TransfereeState.NoTransferee) {
            views.participantNameText.setTextOrHide(null)
            toolbar?.title = when {
                opponentItem != null && state.isVideoCall ->
                    getString(CommonStrings.video_call_with_participant, opponentItem.getBestName())
                opponentItem != null ->
                    getString(CommonStrings.audio_call_with_participant, opponentItem.getBestName())
                state.isVideoCall -> getString(CommonStrings.video_call_with_participant, "...")
                else -> getString(CommonStrings.audio_call_with_participant, "...")
            }
        }

        if (opponentItem != null) {
            val colorFilter = ContextCompat.getColor(this, im.vector.lib.ui.styles.R.color.bg_call_screen_blur)
            avatarRenderer.renderBlur(
                    opponentItem, views.bgCallView,
                    sampling = 20, rounded = false, colorFilter = colorFilter, addPlaceholder = false
            )
            if (state.transferee !is VectorCallViewState.TransfereeState.NoTransferee) {
                views.participantNameText.setTextOrHide(
                        getString(CommonStrings.call_transfer_consulting_with, opponentItem.getBestName())
                )
            }
            if (blurAvatar) {
                val colorFilter = ContextCompat.getColor(this, im.vector.lib.ui.styles.R.color.bg_call_screen_blur)
                avatarRenderer.renderBlur(
                        opponentItem, views.otherMemberAvatar,
                        sampling = 2, rounded = true, colorFilter = colorFilter, addPlaceholder = true
                )
            } else {
                avatarRenderer.render(opponentItem, views.otherMemberAvatar)
            }
        }

        // Other known call layout
        if (state.otherKnownCallInfo?.opponentUserItem == null || isInPictureInPictureModeSafe()) {
            views.otherKnownCallLayout.isVisible = false
        } else {
            val otherCall = callManager.getCallById(state.otherKnownCallInfo.callId)
            val colorFilter = ContextCompat.getColor(this, im.vector.lib.ui.styles.R.color.bg_call_screen_blur)
            avatarRenderer.renderBlur(
                    matrixItem = state.otherKnownCallInfo.opponentUserItem,
                    imageView = views.otherKnownCallAvatarView,
                    sampling = 20, rounded = true, colorFilter = colorFilter, addPlaceholder = true
            )
            views.otherKnownCallLayout.isVisible = true
            views.otherSmallIsHeldIcon.isVisible =
                    otherCall?.let { it.isLocalOnHold || it.isRemoteOnHold }.orFalse()
        }
    }

    // -------------------------------------------------------------------------
    // View setup
    // -------------------------------------------------------------------------

    private fun configureCallViews() {
        views.callControlsView.interactionListener = this

        views.otherKnownCallLayout.setOnClickListener {
            withState(callViewModel) {
                val otherCall = callManager.getCallById(it.otherKnownCallInfo?.callId ?: "") ?: return@withState
                val callArgs = CallArgs(
                        signalingRoomId = otherCall.nativeRoomId,
                        callId = otherCall.callId,
                        participantUserId = otherCall.mxCall.opponentUserId,
                        isIncomingCall = !otherCall.mxCall.isOutgoing,
                        isVideoCall = otherCall.mxCall.isVideoCall
                )
                callViewModel.handle(VectorCallViewActions.SwitchCall(callArgs))
            }
        }

        // Tap local pip to switch camera.
        views.pipRendererWrapper.setOnClickListener {
            callViewModel.handle(VectorCallViewActions.ToggleCamera)
        }

        pipDraggableView = views.pipRendererWrapper.setupDraggable()
                .setStickyMode(DraggableView.Mode.STICKY_XY)
                .build()

        otherCallDraggableView = views.otherKnownCallLayout.setupDraggable()
                .setStickyMode(DraggableView.Mode.STICKY_XY)
                .build()
    }

    // -------------------------------------------------------------------------
    // Immersive mode
    // -------------------------------------------------------------------------

    private fun enableImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.navigationBars())
                it.hide(WindowInsets.Type.statusBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    private fun toggleControlsVisibility() {
        areControlsVisible = !areControlsVisible
        updateControlsVisibility()
    }

    private fun updateControlsVisibility() {
        views.callToolbar.isVisible = areControlsVisible
        views.callControlsView.isVisible = areControlsVisible
    }

    // -------------------------------------------------------------------------
    // View events
    // -------------------------------------------------------------------------

    private fun handleViewEvents(event: VectorCallViewEvents?) {
        Timber.tag(loggerTag.value).v("handleViewEvents $event")
        // FIX #4: All UI updates must run on main thread.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { handleViewEvents(event) }
            return
        }
        when (event) {
            is VectorCallViewEvents.ConnectionTimeout -> {
                Toast.makeText(this, "Call not answered", Toast.LENGTH_LONG).show()
                callViewModel.handle(VectorCallViewActions.EndCall)
            }
            is VectorCallViewEvents.RemoteScreenSharingBlocked -> {
                Toast.makeText(this,
                        "Remote user is already sharing screen. Please ask them to stop first.",
                        Toast.LENGTH_LONG).show()
            }
            is VectorCallViewEvents.ShowDialPad -> {
                if (supportFragmentManager.isStateSaved) return
                CallDialPadBottomSheet.newInstance(false).apply {
                    callback = dialPadCallback
                }.show(supportFragmentManager, FRAGMENT_DIAL_PAD_TAG)
            }
            is VectorCallViewEvents.ShowScreenShareConflictDialog -> {
                if (isFinishing) return
                MaterialAlertDialogBuilder(this)
                        .setTitle("Screen sharing conflict")
                        .setMessage("You are already sharing your screen. Do you want to stop sharing?")
                        .setPositiveButton("Stop sharing") { _, _ ->
                            callViewModel.handle(VectorCallViewActions.ToggleScreenSharing)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
            }
            is VectorCallViewEvents.ShowCallTransferScreen -> {
                val callId = withState(callViewModel) { it.callId }
                navigator.openCallTransfer(this, callTransferActivityResultLauncher, callId)
            }
            is VectorCallViewEvents.FailToTransfer -> showSnackbar("Call transfer failed")
            is VectorCallViewEvents.ShowScreenSharingPermissionDialog -> handleShowScreenSharingPermissionDialog()
            is VectorCallViewEvents.StopScreenSharingService -> handleStopScreenSharingService()
            is VectorCallViewEvents.ShowVideoRequestDialog -> handleShowVideoRequestDialog()
            is VectorCallViewEvents.VideoRequestSent -> showWaitingForRemoteDialog()
            is VectorCallViewEvents.VideoRequestAccepted -> {
                dismissWaitingDialog()
                Toast.makeText(this, "Video call request accepted", Toast.LENGTH_SHORT).show()
            }
            is VectorCallViewEvents.VideoRequestRejected -> {
                dismissWaitingDialog()
                Toast.makeText(this, "Video call request rejected", Toast.LENGTH_SHORT).show()
            }
            is VectorCallViewEvents.ShowInviteFriends -> {
                if (supportFragmentManager.isStateSaved) return
                im.vector.app.features.invite.InviteFriendsBottomSheet.show(supportFragmentManager)
            }
            null -> Unit
            else -> Timber.tag(loggerTag.value).w("Unhandled view event: $event")
        }
    }

    private val callTransferActivityResultLauncher = registerStartForActivityResult { activityResult ->
        when (activityResult.resultCode) {
            Activity.RESULT_CANCELED -> callViewModel.handle(VectorCallViewActions.CallTransferSelectionCancelled)
            Activity.RESULT_OK -> {
                CallTransferActivity.getCallTransferResult(activityResult.data)
                        ?.let { callViewModel.handle(VectorCallViewActions.CallTransferSelectionResult(it)) }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Controls listener
    // -------------------------------------------------------------------------

    override fun didTapAudioSettings() {
        CallSoundDeviceChooserBottomSheet().show(supportFragmentManager, "SoundDeviceChooser")
    }

    override fun didAcceptIncomingCall() {
        callViewModel.handle(VectorCallViewActions.AcceptCall)
    }

    override fun didDeclineIncomingCall() {
        callViewModel.handle(VectorCallViewActions.DeclineCall)
    }

    override fun didEndCall() {
        callViewModel.handle(VectorCallViewActions.EndCall)
    }

    override fun didTapToggleMute() {
        callViewModel.handle(VectorCallViewActions.ToggleMute)
    }

    override fun didTapToggleVideo() {
        callViewModel.handle(VectorCallViewActions.ToggleVideo)
    }

    override fun didTapToggleCamera() {
        callViewModel.handle(VectorCallViewActions.ToggleCamera)
    }

    override fun didTapMore() {
        CallControlsBottomSheet().show(supportFragmentManager, "Controls")
    }

    // -------------------------------------------------------------------------
    // Permissions
    // -------------------------------------------------------------------------

    private val permissionCameraLauncher = registerForPermissionsResult { allGranted, _ ->
        if (allGranted) setupRenderersIfNeeded() else finish()
    }

    // -------------------------------------------------------------------------
    // Microphone service
    // -------------------------------------------------------------------------

    private fun startMicrophoneService() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) return
        if (!isAppInForeground()) return
        withState(callViewModel) {
            val callState = it.callState.invoke()
            if (callState !is CallState.LocalRinging && callState !is CallState.Ended && callState != null) {
                ContextCompat.startForegroundService(this, Intent(this, MicrophoneAccessService::class.java))
            }
        }
    }

    private fun stopMicrophoneService() {
        stopService(Intent(this, MicrophoneAccessService::class.java))
    }

    private fun isAppInForeground(): Boolean {
        return ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }

    // -------------------------------------------------------------------------
    // Screen lock
    // -------------------------------------------------------------------------

    private fun turnScreenOnAndKeyguardOff() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                            or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                            or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        with(getSystemService<KeyguardManager>()!!) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                requestDismissKeyguard(this@VectorCallActivity, null)
            }
        }
    }

    private fun turnScreenOffAndKeyguardOn() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(false)
            setTurnScreenOn(false)
        } else {
            @Suppress("DEPRECATION")
            window.clearFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                            or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
    }

    // -------------------------------------------------------------------------
    // Screen sharing
    // -------------------------------------------------------------------------

    private val screenSharingPermissionActivityResultLauncher = registerStartForActivityResult { activityResult ->
        if (activityResult.resultCode == Activity.RESULT_OK) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startScreenSharingService(activityResult)
            } else {
               // startScreenSharing(activityResult)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    startScreenSharing(activityResult)
                }, 300)
            }
        }
    }

    private fun startScreenSharing(activityResult: ActivityResult) {
        val videoCapturer = ScreenCapturerAndroid(activityResult.data, object : MediaProjection.Callback() {
            override fun onStop() {
                Timber.i("User revoked screen capturing permission")
                withState(callViewModel) {
                    if (it.isLocalScreenSharing) {
                        callViewModel.handle(VectorCallViewActions.ToggleScreenSharing)
                    }
                }
            }
        })
        callViewModel.handle(VectorCallViewActions.StartScreenSharing(videoCapturer))
    }

    private fun startScreenSharingService(activityResult: ActivityResult) {
        try {
            ContextCompat.startForegroundService(this, Intent(this, ScreenCaptureAndroidService::class.java))
            if (activityResult.data != null) {
                bindToScreenCaptureService(activityResult)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to start ScreenCaptureAndroidService")
            Toast.makeText(this, "Failed to start screen sharing.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun bindToScreenCaptureService(activityResult: ActivityResult? = null) {
        screenCaptureServiceConnection.bind(object : ScreenCaptureServiceConnection.Callback {
            override fun onServiceConnected() {
                activityResult?.let { startScreenSharing(it) }
            }
        })
    }

    private fun handleShowScreenSharingPermissionDialog() {
        getSystemService<MediaProjectionManager>()?.let {
            navigator.openScreenSharingPermissionDialog(
                    it.createScreenCaptureIntent(),
                    screenSharingPermissionActivityResultLauncher
            )
        }
    }

    /**
     * FIX #2 / #4: Stop screen sharing service immediately.
     * Called from handleCallEnded so the MediaProjection foreground service is
     * always stopped the moment the call ends — whether User1 or User2 hung up.
     */
    private fun handleStopScreenSharingService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            screenCaptureServiceConnection.stopScreenCapturing()
        }
    }

    // -------------------------------------------------------------------------
    // Chat navigation
    // -------------------------------------------------------------------------

    private fun returnToChat() {
        val roomId = withState(callViewModel) { it.roomId }
        val intent = RoomDetailActivity.newIntent(this, TimelineArgs(roomId), false).apply {
            flags = FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(intent)
        finish()
    }

    // -------------------------------------------------------------------------
    // Video request dialogs
    // -------------------------------------------------------------------------

    private fun handleShowVideoRequestDialog() {
        val otherUserName = withState(callViewModel) { state ->
            state.callInfo?.opponentUserItem?.getBestName() ?: "user"
        }
        AlertDialog.Builder(this)
                .setTitle(getString(CommonStrings.call_switch_request_title))
                .setMessage(getString(CommonStrings.call_switch_request_text, otherUserName))
                .setPositiveButton(getString(CommonStrings.call_switch_request_btn_accept)) { _, _ ->
                    callViewModel.handle(VectorCallViewActions.AcceptVideoRequest)
                }
                .setNegativeButton(getString(CommonStrings.call_switch_request_btn_reject)) { _, _ ->
                    callViewModel.handle(VectorCallViewActions.RejectVideoRequest)
                }
                .setCancelable(false)
                .show()
    }

    private fun showWaitingForRemoteDialog() {
        dismissWaitingDialog()
        val otherUserName = withState(callViewModel) { state ->
            state.callInfo?.opponentUserItem?.getBestName() ?: "user"
        }
        waitingDialog = AlertDialog.Builder(this)
                .setTitle(getString(CommonStrings.call_switch_waiting_request_title))
                .setMessage(getString(CommonStrings.call_switch_waiting_request_text, otherUserName))
                .setCancelable(false)
                .show()
    }

    private fun dismissWaitingDialog() {
        try {
            waitingDialog?.takeIf { it.isShowing }?.dismiss()
        } catch (e: Exception) {
            Timber.e(e, "Error dismissing waiting dialog")
        } finally {
            waitingDialog = null
        }
    }

    // -------------------------------------------------------------------------
    // Companion
    // -------------------------------------------------------------------------

    companion object {
        private const val EXTRA_MODE = "EXTRA_MODE"
        private const val FRAGMENT_DIAL_PAD_TAG = "FRAGMENT_DIAL_PAD_TAG"

        const val OUTGOING_CREATED = "OUTGOING_CREATED"
        const val INCOMING_RINGING = "INCOMING_RINGING"
        const val INCOMING_ACCEPT = "INCOMING_ACCEPT"

        fun newIntent(context: Context, call: WebRtcCall, mode: String?): Intent {
            val callArgs = CallArgs(
                    call.nativeRoomId, call.callId, call.mxCall.opponentUserId,
                    !call.mxCall.isOutgoing, call.mxCall.isVideoCall
            )
            return Intent(context, VectorCallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TOP
                putExtra(Mavericks.KEY_ARG, callArgs)
                putExtra(EXTRA_MODE, mode)
            }
        }

        fun newIntent(
                context: Context,
                callId: String,
                signalingRoomId: String,
                otherUserId: String,
                isIncomingCall: Boolean,
                isVideoCall: Boolean,
                mode: String?
        ): Intent {
            val callArgs = CallArgs(signalingRoomId, callId, otherUserId, isIncomingCall, isVideoCall)
            return Intent(context, VectorCallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TOP
                putExtra(Mavericks.KEY_ARG, callArgs)
                putExtra(EXTRA_MODE, mode)
            }
        }
    }
}
