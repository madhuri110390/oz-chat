/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.media

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import im.vector.app.R
import im.vector.app.databinding.MergeImageAttachmentOverlayBinding
import im.vector.lib.attachmentviewer.AttachmentEventListener
import im.vector.lib.attachmentviewer.AttachmentEvents


class AttachmentOverlayView @JvmOverloads constructor(
        context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr), AttachmentEventListener {

    var interactionListener: AttachmentInteractionListener? = null
    val views: MergeImageAttachmentOverlayBinding
    private var lastManualToggleAtMs = 0L
    private val manualToggleGuardMs = 400L
    private var isPlaying = false
    private var suspendSeekBarUpdate = false
    private var hideControlsRunnable: Runnable? = null
    private var isVideoAttachment = false

    // Only the center play/pause button auto-hides during playback.
    private val playbackControlViews get() = listOf(
            views.overlayPlayPauseButton,

    )

    // Back/share/download stay visible at all times, regardless of play state.
    private val topBarViews get() = listOf(
            views.overlayBackButton,
            views.overlayShareButton,
            views.overlayDownloadButton,
    )

    init {
        inflate(context, R.layout.merge_image_attachment_overlay, this)
        views = MergeImageAttachmentOverlayBinding.bind(this)
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = true
        isFocusable = true
        views.overlayPlayPauseButton.setOnClickListener { togglePlayPause() }
        views.overlayBackButton.setOnClickListener { interactionListener?.onDismiss() }
        views.overlayShareButton.setOnClickListener { interactionListener?.onShare() }
        views.overlayDownloadButton.setOnClickListener { interactionListener?.onDownload() }


        // Tapping anywhere on the (video) overlay toggles play/pause, but only when
        // the tap doesn't land on the dedicated play/pause button (which has its own
        // listener above). A single shared togglePlayPause() is the only source of
        // truth, so a single tap reliably plays/pauses.
        setOnClickListener {
            if (!isVideoAttachment) return@setOnClickListener
            if (views.overlayVideoControlsGroup.visibility != View.VISIBLE) {
                showControls()
                scheduleHideControls()
            } else {
                hideControlsNow()    // <-- this branch runs on tap 1, not the reveal branch!
            }
        }
    }

    /**
     * Single source of truth for play/pause. Updates local state and the icon
     * optimistically (so a tap is never undone by an in-flight 100ms VideoEvent
     * tick), then notifies the listener exactly once.
     */
    private fun togglePlayPause() {
        val requestedPlay = !isPlaying
        isPlaying = requestedPlay
        views.overlayPlayPauseButton.setImageResource(
                if (requestedPlay) R.drawable.ic_pause else R.drawable.ic_play_arrow
        )
        interactionListener?.onPlayPause(requestedPlay)
    }
    fun setIsVideo(isVideo: Boolean) {
        isVideoAttachment = isVideo
        // Top bar (back/share/download) is always visible, video or not.
        topBarViews.forEach { it.alpha = 1f; it.visibility = View.VISIBLE }
        if (isVideo) {
            // Don't auto-schedule a hide here — we don't know play state yet.
            // onEvent(VideoEvent) will drive playback-control visibility once playback starts.
            views.overlayVideoControlsGroup.visibility = View.VISIBLE
            views.overlayVideoControlsGroup.isClickable= true
            playbackControlViews.forEach { it.alpha = 1f; it.visibility = View.VISIBLE }
        } else {
            cancelHideControls()

        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.pointerCount > 1) return false
        return super.dispatchTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!isVisible) return false
        if (ev.pointerCount > 1) return false
        return super.onInterceptTouchEvent(ev)
    }

    private fun showControls() {
        cancelHideControls()
        views.overlayVideoControlsGroup.visibility = View.VISIBLE
        views.overlayVideoControlsGroup.isClickable= true
        playbackControlViews.forEach {
            if (it.alpha < 1f || it.visibility != View.VISIBLE) {
                it.animate().cancel()
                it.alpha = 0f
                it.visibility = View.VISIBLE
                it.animate().alpha(1f).setDuration(200).start()
            }
        }
    }

    private fun hideControlsNow() {
        cancelHideControls()
        playbackControlViews.forEach {
            it.animate().cancel()
            it.animate().alpha(0f).setDuration(200).withEndAction {
                views.overlayVideoControlsGroup.visibility = View.GONE
            }.start()
        }
    }

    private fun scheduleHideControls() {
        cancelHideControls()
        hideControlsRunnable = Runnable { hideControlsNow() }.also {
            postDelayed(it, 3000L)
        }
    }

    private fun cancelHideControls() {
        hideControlsRunnable?.let { removeCallbacks(it) }
        hideControlsRunnable = null
    }

    fun updateWith(counter: String, senderInfo: String) {
        views.overlayCounterText.text = counter
        views.overlayInfoText.text = senderInfo
    }

    override fun onEvent(event: AttachmentEvents) {
        when (event) {
            is AttachmentEvents.VideoEvent -> {
                // Ignore stale ticks that arrive shortly after a manual toggle and
                // disagree with what the user just requested — these come from the
                // old timer/MediaPlayer callback racing the pause/play command.
                val withinGuardWindow = System.currentTimeMillis() - lastManualToggleAtMs < manualToggleGuardMs
                if (withinGuardWindow && event.isPlaying != isPlaying) {
                    return
                }

                isPlaying = event.isPlaying
                views.overlayPlayPauseButton.setImageResource(
                        if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
                )

                if (!suspendSeekBarUpdate) {
                    val safeDuration = (if (event.duration == 0) 100 else event.duration).toFloat()
                    val percent = ((event.progress / safeDuration) * 100f).toInt().coerceAtMost(100)
                }

                showControls()
            }
        }
    }
}
