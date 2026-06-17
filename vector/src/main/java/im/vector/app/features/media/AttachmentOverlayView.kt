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
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.SeekBar
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

    private var isPlaying = false
    private var suspendSeekBarUpdate = false
    private var hideControlsRunnable: Runnable? = null
    private var isVideoAttachment = false

    private val controlViews get() = listOf(
            views.overlayBackButton,
            views.overlayShareButton,
            views.overlayDownloadButton,
            views.overlayPlayPauseButton,

    )

    init {
        inflate(context, R.layout.merge_image_attachment_overlay, this)
        views = MergeImageAttachmentOverlayBinding.bind(this)
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = true
        isFocusable = true

        views.overlayBackButton.setOnClickListener { interactionListener?.onDismiss() }
        views.overlayShareButton.setOnClickListener { interactionListener?.onShare() }
        views.overlayDownloadButton.setOnClickListener { interactionListener?.onDownload() }
        views.overlayPlayPauseButton.setOnClickListener {
            Log.d("OVERLAY_TAP", "play/pause button tapped, isPlaying=$isPlaying")
            interactionListener?.onPlayPause(!isPlaying)
            scheduleHideControls()
        }

//        views.overlaySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
//            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
//                if (fromUser) interactionListener?.videoSeekTo(progress)
//            }
//            override fun onStartTrackingTouch(seekBar: SeekBar?) {
//                suspendSeekBarUpdate = true
//                cancelHideControls()
//            }
//            override fun onStopTrackingTouch(seekBar: SeekBar?) {
//                suspendSeekBarUpdate = false
//                scheduleHideControls()
//            }
//        })

        setOnClickListener {
            Log.d("OVERLAY_TAP", "overlay root tapped, groupVisible=${views.overlayVideoControlsGroup.visibility == View.VISIBLE}, isVideoAttachment=$isVideoAttachment")
            if (!isVideoAttachment) return@setOnClickListener
            val controlsVisible = views.overlayVideoControlsGroup.visibility == View.VISIBLE
            if (controlsVisible) {
                hideControlsNow()
            } else {
                showControls()
                if (isPlaying) scheduleHideControls()
            }
        }
    }

    fun setIsVideo(isVideo: Boolean) {
        isVideoAttachment = isVideo
        if (isVideo) {
            showControls()
            scheduleHideControls()
        } else {
            cancelHideControls()
            views.overlayVideoControlsGroup.visibility = View.GONE
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            Log.d("OVERLAY_TAP", "overlay dispatchTouchEvent ACTION_DOWN received")
        }
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
        controlViews.forEach {
            it.animate().cancel()
            it.alpha = 0f
            it.visibility = View.VISIBLE
            it.animate().alpha(1f).setDuration(200).start()
        }
    }

    private fun hideControlsNow() {
        cancelHideControls()
        controlViews.forEach {
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
                if (!suspendSeekBarUpdate) {
                    val safeDuration = (if (event.duration == 0) 100 else event.duration).toFloat()
                    val percent = ((event.progress / safeDuration) * 100f).toInt().coerceAtMost(100)
                    val wasPlaying = isPlaying
                    isPlaying = event.isPlaying
//                    views.overlaySeekBar.progress = percent
                    views.overlayPlayPauseButton.setImageResource(
                            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
                    )
                    if (!wasPlaying && isPlaying) {
                        showControls()
                        scheduleHideControls()
                    }
                    if (wasPlaying && !isPlaying) {
                        cancelHideControls()
                        showControls()
                    }
                }
            }
        }
    }
}
