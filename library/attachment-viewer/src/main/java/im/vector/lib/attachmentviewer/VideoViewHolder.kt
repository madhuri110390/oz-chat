/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.attachmentviewer

import android.util.Log
import android.view.View
import androidx.core.view.isVisible
import im.vector.lib.attachmentviewer.databinding.ItemVideoAttachmentBinding
import im.vector.lib.core.utils.timer.CountUpTimer
import java.io.File
import java.lang.ref.WeakReference

class VideoViewHolder constructor(itemView: View) :
        BaseViewHolder(itemView) {

    private var isSelected = false
    private var mVideoPath: String? = null
    private var countUpTimer: CountUpTimer? = null
    private var progress: Int = 0
    private var wasPaused = false
    private var pendingPlay = false
    private var hasStartedPlayback = false

    // Single source of truth for "is the MediaPlayer instance actually prepared
    // and safe to start()/pause()/seek right now". hasStartedPlayback only tracks
    // "we attempted/initiated playback", which is NOT the same thing and was the
    // root cause of stale-state requiring multiple clicks to resume.
    private var isPrepared = false

    var eventListener: WeakReference<AttachmentEventListener>? = null

    val views = ItemVideoAttachmentBinding.bind(itemView)

    internal val target = DefaultVideoLoaderTarget(this, views.videoThumbnailImage)

    override fun onRecycled() {
        super.onRecycled()
        stopTimer()
        mVideoPath = null
        isSelected = false
        pendingPlay = false
        wasPaused = false
        progress = 0
        hasStartedPlayback = false
        isPrepared = false
        views.videoSeekBar.isVisible = true
        views.videoSeekBar.setOnSeekBarChangeListener(null)
    }

    fun videoReady(file: File) {
        mVideoPath = file.path
        if (isSelected || pendingPlay) {
            pendingPlay = false
            startPlaying()
        }
    }

    fun videoReady(path: String) {
        mVideoPath = path
        if (isSelected || pendingPlay) {
            pendingPlay = false
            startPlaying()
        }
    }

    fun videoFileLoadError() {
        views.videoLoaderProgress.isVisible = false
        views.videoControlIcon.isVisible = false
        views.videoThumbnailImage.isVisible = true
        views.videoMediaViewerErrorView.isVisible = true
        views.videoSeekBar.isVisible = true
    }

    override fun entersBackground() {
        if (views.videoView.isPlaying) {
            progress = views.videoView.currentPosition
            stopTimer()
            views.videoView.stopPlayback()
            views.videoView.pause()
            hasStartedPlayback = false
            isPrepared = false
        }
    }

    override fun entersForeground() {
        onSelected(isSelected)
        onOrientationChanged()
    }

    // Call this from the activity/fragment's onConfigurationChanged so the
    // seekbar visibility re-evaluates immediately on rotation, without
    // waiting for the next CountUpTimer tick.
    fun onOrientationChanged() {
        views.videoSeekBar.isVisible = true
    }

    private fun isLandscapeOrientation(): Boolean =
            itemView.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    override fun onSelected(selected: Boolean) {
        // Guard against redundant/duplicate selection callbacks (e.g. ViewPager
        // settle firing true then false back-to-back), which causes playback
        // to start then immediately pause.
        if (isSelected == selected) return
        isSelected = selected
        if (!selected) {
            if (views.videoView.isPlaying) {
                progress = views.videoView.currentPosition
                views.videoView.pause()
                views.videoView.stopPlayback()
            } else {
                progress = 0
            }
            hasStartedPlayback = false
            isPrepared = false
            views.videoSeekBar.isVisible = true
            stopTimer()
        } else {
            if (mVideoPath != null) {
                startPlaying()
            } else {
                pendingPlay = true
                views.videoLoaderProgress.isVisible = true
            }
        }
    }

    private fun startPlaying() {
        hasStartedPlayback = true
        isPrepared = false
        wasPaused = false
        // Keep thumbnail visible — don't hide it yet, avoid black flash
        views.videoLoaderProgress.isVisible = true
        views.videoControlIcon.isVisible = false
        views.videoMediaViewerErrorView.isVisible = false
        views.videoView.visibility = View.VISIBLE
        views.videoSeekBar.isVisible = true
        setVideoAndPlay()
    }

    private fun setVideoAndPlay() {
        views.videoView.stopPlayback()

        views.videoView.setOnCompletionListener {
            views.videoView.stopPlayback()
            hasStartedPlayback = false
            isPrepared = false
            stopTimer()
            views.videoThumbnailImage.isVisible = true
            views.videoControlIcon.isVisible = true
            views.videoControlIcon.setImageResource(R.drawable.ic_play_arrow)
            views.videoSeekBar.isVisible = true
            progress = 0
            eventListener?.get()?.onEvent(AttachmentEvents.VideoEvent(false, 0, views.videoView.duration))
        }

        views.videoView.setOnErrorListener { _, what, extra ->
            Log.e(VideoViewHolder::class.java.name, "MediaPlayer error: what=$what extra=$extra path=$mVideoPath")
            stopTimer()
            isPrepared = false
            hasStartedPlayback = false
            views.videoView.isVisible = false
            views.videoThumbnailImage.isVisible = false
            views.videoControlIcon.isVisible = false
            views.videoLoaderProgress.isVisible = false
            views.videoMediaViewerErrorView.isVisible = true
            views.videoSeekBar.isVisible = true
            eventListener?.get()?.onEvent(AttachmentEvents.VideoEvent(false, 0, 0))
            true
        }

        views.videoView.setOnPreparedListener { mp ->
            isPrepared = true // real readiness is marked here, not earlier
            views.videoSeekBar.max = mp.duration
            views.videoSeekBar.isVisible = true
            views.videoView.visibility = View.VISIBLE
            views.videoView.translationZ = 0f
            views.videoView.elevation = 0f
            views.videoLoaderProgress.isVisible = false

            views.videoSeekBar.max = mp.duration
            views.videoSeekBar.isVisible = isLandscapeOrientation()
            views.videoSeekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) {
                        views.videoView.seekTo(value)
                        progress = value
                    }
                }
                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
            })

            stopTimer()
            countUpTimer = CountUpTimer(intervalInMs = 100).also {
                it.tickListener = CountUpTimer.TickListener {
                    val duration = views.videoView.duration
                    val currentProgress = views.videoView.currentPosition
                    val isPlaying = views.videoView.isPlaying
                    views.videoSeekBar.progress = currentProgress
                    eventListener?.get()?.onEvent(
                            AttachmentEvents.VideoEvent(isPlaying, currentProgress, duration)
                    )
                }
                it.start()
            }
            if (!wasPaused) {
                views.videoView.start()
                if (progress > 0) {
                    views.videoView.seekTo(progress)
                }
                // Delay hiding thumbnail slightly so first decoded frame is ready,
                // avoiding a black flash between thumbnail-hide and first-frame-render.
                views.videoView.postDelayed({
                    views.videoThumbnailImage.isVisible = false
                }, 150L)
            } else {
                views.videoThumbnailImage.isVisible = false
            }
            eventListener?.get()?.onEvent(
                    AttachmentEvents.VideoEvent(views.videoView.isPlaying, views.videoView.currentPosition, mp.duration)
            )
        }

        try {
            val videoUri = when {
                mVideoPath!!.startsWith("content://") -> android.net.Uri.parse(mVideoPath)
                mVideoPath!!.startsWith("file://") -> android.net.Uri.parse(mVideoPath)
                else -> android.net.Uri.fromFile(java.io.File(mVideoPath!!))
            }
            views.videoView.setVideoURI(videoUri)
        } catch (failure: Throwable) {
            Log.v(VideoViewHolder::class.java.name, "Failed to start video: ${failure.message}")
            isPrepared = false
            hasStartedPlayback = false
            views.videoView.isVisible = false
            views.videoThumbnailImage.isVisible = false
            views.videoControlIcon.isVisible = false
            views.videoLoaderProgress.isVisible = false
            views.videoMediaViewerErrorView.isVisible = true
            views.videoSeekBar.isVisible = true
        }
    }

    private fun stopTimer() {
        countUpTimer?.stop()
        countUpTimer = null
    }

    override fun handleCommand(commands: AttachmentCommands) {
        if (!isSelected) return
        when (commands) {
            AttachmentCommands.StartVideo -> {
                wasPaused = false
                hasStartedPlayback = true
                views.videoView.start()
                views.videoControlIcon.isVisible = false
            }
            AttachmentCommands.PauseVideo -> {
                wasPaused = true
                views.videoView.pause()
                views.videoControlIcon.isVisible = true
                views.videoControlIcon.setImageResource(R.drawable.ic_play_arrow)
            }
            is AttachmentCommands.SeekTo -> {
                val duration = views.videoView.duration
                if (duration > 0) {
                    val seekDuration = duration * (commands.percentProgress / 100f)
                    views.videoView.seekTo(seekDuration.toInt())
                    views.videoSeekBar.progress = seekDuration.toInt()
                }
            }
        }
    }

    private fun togglePlayPause() {
        when {
            isPrepared && views.videoView.isPlaying -> {
                wasPaused = true
                progress = views.videoView.currentPosition
                views.videoView.pause()
                views.videoControlIcon.isVisible = true
                views.videoControlIcon.setImageResource(R.drawable.ic_play_arrow)
            }
            isPrepared && mVideoPath != null -> {
                wasPaused = false
                views.videoControlIcon.isVisible = false // hide immediately on tap, don't wait for next frame
                views.videoView.start()
            }
            mVideoPath != null -> {
                views.videoControlIcon.isVisible = false // immediate feedback that the tap registered
                startPlaying()
            }
            else -> {
                pendingPlay = true
                views.videoControlIcon.isVisible = false
                views.videoLoaderProgress.isVisible = true
            }
        }
    }

    override fun bind(attachmentInfo: AttachmentInfo) {
        super.bind(attachmentInfo)
        progress = 0
        wasPaused = false
        pendingPlay = false
        hasStartedPlayback = false
        isPrepared = false
        views.videoControlIcon.isVisible = true
        views.videoSeekBar.isVisible = true
        views.videoSeekBar.progress = 0
        views.videoControlIcon.setImageResource(R.drawable.ic_play_arrow)

        val tapListener = View.OnClickListener { togglePlayPause() }
        views.videoControlIcon.setOnClickListener(tapListener)
        views.videoThumbnailImage.setOnClickListener(tapListener)
        views.videoView.setOnClickListener(tapListener) // surface stays tappable after thumbnail is gone
    }
}
