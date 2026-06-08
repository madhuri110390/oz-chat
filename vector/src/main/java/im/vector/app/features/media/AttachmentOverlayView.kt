///*
// * Copyright 2020-2024 New Vector Ltd.
// *
// * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
// * Please see LICENSE files in the repository root for full details.
// */
//
//package im.vector.app.features.media
//
//import android.content.Context
//import android.graphics.Color
//import android.util.AttributeSet
//import android.view.MotionEvent
//import android.widget.SeekBar
//import androidx.constraintlayout.widget.ConstraintLayout
//import androidx.core.view.isVisible
//import im.vector.app.R
//import im.vector.app.databinding.MergeImageAttachmentOverlayBinding
//import im.vector.lib.attachmentviewer.AttachmentEventListener
//import im.vector.lib.attachmentviewer.AttachmentEvents
//
//class AttachmentOverlayView @JvmOverloads constructor(
//        context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
//) : ConstraintLayout(context, attrs, defStyleAttr), AttachmentEventListener {
//
//    var interactionListener: AttachmentInteractionListener? = null
//    val views: MergeImageAttachmentOverlayBinding
//
//    private var isPlaying = false
//    private var suspendSeekBarUpdate = false
//    private var hideControlsRunnable: Runnable? = null
//
//    // Views that should auto-hide (top bar + bottom bar)
//    private val controlViews get() = listOf(
//            views.overlayBackButton,
//            views.overlayShareButton,
//            views.overlayDownloadButton,
//            views.overlayPlayPauseButton,
//            views.overlaySeekBar
//    )
//
//    init {
//        inflate(context, R.layout.merge_image_attachment_overlay, this)
//        views = MergeImageAttachmentOverlayBinding.bind(this)
//        setBackgroundColor(Color.TRANSPARENT)
//
//        views.overlayBackButton.setOnClickListener {
//            interactionListener?.onDismiss()
//        }
//        views.overlayShareButton.setOnClickListener {
//            interactionListener?.onShare()
//        }
//        views.overlayDownloadButton.setOnClickListener {
//            interactionListener?.onDownload()
//        }
//        views.overlayPlayPauseButton.setOnClickListener {
//            interactionListener?.onPlayPause(!isPlaying)
//            scheduleHideControls() // restart hide timer on play/pause tap
//        }
//
//        views.overlaySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
//            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
//                if (fromUser) {
//                    interactionListener?.videoSeekTo(progress)
//                }
//            }
//            override fun onStartTrackingTouch(seekBar: SeekBar?) {
//                suspendSeekBarUpdate = true
//                cancelHideControls() // don't hide while seeking
//            }
//            override fun onStopTrackingTouch(seekBar: SeekBar?) {
//                suspendSeekBarUpdate = false
//                scheduleHideControls() // restart after seek
//            }
//        })
//
//        // Tap anywhere on overlay → toggle controls
//        setOnClickListener {
//            if (isPlaying) {
//                val controlsVisible = views.overlayBackButton.alpha > 0f
//                if (controlsVisible) {
//                    hideControlsNow()
//                } else {
//                    showControls()
//                    scheduleHideControls()
//                }
//            }
//        }
//    }
//
//    private fun showControls() {
//        controlViews.forEach { it.animate().alpha(1f).setDuration(200).start() }
//    }
//
//    private fun hideControlsNow() {
//        cancelHideControls()
//        controlViews.forEach { it.animate().alpha(0f).setDuration(200).start() }
//    }
//
//    private fun scheduleHideControls() {
//        cancelHideControls()
//        hideControlsRunnable = Runnable { hideControlsNow() }.also {
//            postDelayed(it, 3000L)
//        }
//    }
//
//    private fun cancelHideControls() {
//        hideControlsRunnable?.let { removeCallbacks(it) }
//        hideControlsRunnable = null
//    }
//
//    fun updateWith(counter: String, senderInfo: String) {
//        views.overlayCounterText.text = ""
//        views.overlayInfoText.text = ""
//    }
//
//
//    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
//        android.util.Log.d("ZOOM", "activity dispatch pointers=${ev.pointerCount} action=${ev.actionMasked} overlay=${overlayView?.javaClass?.simpleName} overlayVisible=${overlayView?.isVisible}")
//}
//    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
//        if (!isVisible) return false
//        if (ev.pointerCount > 1) return false
//        return super.onInterceptTouchEvent(ev)
//    }
//    override fun onEvent(event: AttachmentEvents) {
//        when (event) {
//            is AttachmentEvents.VideoEvent -> {
//                if (!suspendSeekBarUpdate) {
//                    val safeDuration = (if (event.duration == 0) 100 else event.duration).toFloat()
//                    val percent = ((event.progress / safeDuration) * 100f).toInt().coerceAtMost(100)
//                    val wasPlaying = isPlaying
//                    isPlaying = event.isPlaying
//                    views.overlaySeekBar.progress = percent
//
//                    // Video just started playing → start auto-hide
//                    if (!wasPlaying && isPlaying) {
//                        scheduleHideControls()
//                    }
//                    // Video paused → show controls, cancel auto-hide
//                    if (wasPlaying && !isPlaying) {
//                        cancelHideControls()
//                        showControls()
//                    }
//                }
//            }
//        }
//    }
//}
package im.vector.app.features.media

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.MotionEvent
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

    private val controlViews get() = listOf(
            views.overlayBackButton,
            views.overlayShareButton,
            views.overlayDownloadButton,
            views.overlayPlayPauseButton,
            views.overlaySeekBar
    )

    init {
        inflate(context, R.layout.merge_image_attachment_overlay, this)
        views = MergeImageAttachmentOverlayBinding.bind(this)
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = false
        isFocusable = false
        views.overlayBackButton.setOnClickListener {
            interactionListener?.onDismiss()
        }
        views.overlayShareButton.setOnClickListener {
            interactionListener?.onShare()
        }
        views.overlayDownloadButton.setOnClickListener {
            interactionListener?.onDownload()
        }
        views.overlayPlayPauseButton.setOnClickListener {
            interactionListener?.onPlayPause(!isPlaying)
            scheduleHideControls()
        }

        views.overlaySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    interactionListener?.videoSeekTo(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                suspendSeekBarUpdate = true
                cancelHideControls()
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                suspendSeekBarUpdate = false
                scheduleHideControls()
            }
        })

//        setOnClickListener {
//            if (isPlaying) {
//                val controlsVisible = views.overlayBackButton.alpha > 0f
//                if (controlsVisible) {
//                    hideControlsNow()
//                } else {
//                    showControls()
//                    scheduleHideControls()
//                }
//            }
//        }
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
        controlViews.forEach { it.animate().alpha(1f).setDuration(200).start() }
    }

    private fun hideControlsNow() {
        cancelHideControls()
        controlViews.forEach { it.animate().alpha(0f).setDuration(200).start() }
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
        views.overlayCounterText.text = ""
        views.overlayInfoText.text = ""
    }

    override fun onEvent(event: AttachmentEvents) {
        when (event) {
            is AttachmentEvents.VideoEvent -> {
                if (!suspendSeekBarUpdate) {
                    val safeDuration = (if (event.duration == 0) 100 else event.duration).toFloat()
                    val percent = ((event.progress / safeDuration) * 100f).toInt().coerceAtMost(100)
                    val wasPlaying = isPlaying
                    isPlaying = event.isPlaying
                    views.overlaySeekBar.progress = percent
                    if (!wasPlaying && isPlaying) {
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
