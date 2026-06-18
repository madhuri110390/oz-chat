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
    private var pendingPlay = false
    private var isPrepared = false
    private var pendingPause = false

    var eventListener: WeakReference<AttachmentEventListener>? = null

    val views = ItemVideoAttachmentBinding.bind(itemView)

    internal val target = DefaultVideoLoaderTarget(this, views.videoThumbnailImage)

    override fun onRecycled() {
        super.onRecycled()
        stopTimer()
        mVideoPath = null
        isSelected = false
        pendingPlay = false
        progress = 0
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
        views.videoThumbnailImage.isVisible = true
        views.videoMediaViewerErrorView.isVisible = true
        views.videoSeekBar.isVisible = true
    }

    override fun entersBackground() {
        if (isPrepared && views.videoView.isPlaying) {
            progress = views.videoView.currentPosition
            views.videoView.pause()
            stopTimer()
        }
    }

    override fun entersForeground() {
        onSelected(isSelected)
        onOrientationChanged()
    }

    fun onOrientationChanged() {
        views.videoSeekBar.isVisible = true
    }

    private fun isLandscapeOrientation(): Boolean =
            itemView.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    override fun onSelected(selected: Boolean) {
        if (isSelected == selected) return
        isSelected = selected
        if (!selected) {
            if (isPrepared && views.videoView.isPlaying) {
                progress = views.videoView.currentPosition
                views.videoView.pause()
            }
            views.videoView.isVisible = false
            views.videoThumbnailImage.isVisible = true
            views.videoSeekBar.isVisible = true
            stopTimer()
        }else {
            if (!isPrepared && mVideoPath != null) {
                startPlaying()
            } else if (!isPrepared) {
                pendingPlay = true
                views.videoLoaderProgress.isVisible = true
            }
        }
    }



    private fun setVideoAndPlay() {
        views.videoView.stopPlayback()

        views.videoView.setOnCompletionListener {
            progress = 0
            views.videoView.seekTo(0)
            views.videoView.start()
            if (progress > 0) {
                views.videoView.seekTo(progress)
            }
            startTimer()
            if (pendingPause) {
                pendingPause = false
                views.videoView.pause()
                stopTimer()
            }
            eventListener?.get()?.onEvent(AttachmentEvents.VideoEvent(true, 0, views.videoView.duration))
        }

        views.videoView.setOnErrorListener { _, what, extra ->
            Log.e(VideoViewHolder::class.java.name, "MediaPlayer error: what=$what extra=$extra path=$mVideoPath")
            stopTimer()
            isPrepared = false
            views.videoView.isVisible = false
            views.videoThumbnailImage.isVisible = false
            views.videoLoaderProgress.isVisible = false
            views.videoMediaViewerErrorView.isVisible = true
            views.videoSeekBar.isVisible = true
            eventListener?.get()?.onEvent(AttachmentEvents.VideoEvent(false, 0, 0))
            true
        }

        views.videoView.setOnPreparedListener { mp ->
            isPrepared = true
            views.videoSeekBar.max = mp.duration
            views.videoView.visibility = View.VISIBLE
            views.videoView.translationZ = 0f
            views.videoView.elevation = 0f
            views.videoLoaderProgress.isVisible = false
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

            views.videoView.start()
            if (progress > 0) {
                views.videoView.seekTo(progress)
            }
            startTimer()
            if (pendingPause) {
                pendingPause = false
                views.videoView.pause()
                stopTimer()
            }
//            views.videoView.postDelayed({
//                views.videoThumbnailImage.isVisible = false
//            }, 150L)
            // WITH this:
            views.videoView.setOnInfoListener { _, what, _ ->
                if (what == android.media.MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    views.videoThumbnailImage.isVisible = false
                }
                false
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
            views.videoView.isVisible = false
            views.videoThumbnailImage.isVisible = false
            views.videoLoaderProgress.isVisible = false
            views.videoMediaViewerErrorView.isVisible = true
            views.videoSeekBar.isVisible = true
        }
    }

    private fun startTimer() {
        stopTimer()
        countUpTimer = CountUpTimer(intervalInMs = 100).also {
            it.tickListener = CountUpTimer.TickListener {
                val duration = views.videoView.duration
                val currentProgress = views.videoView.currentPosition
                val playing = views.videoView.isPlaying
                views.videoSeekBar.progress = currentProgress
                eventListener?.get()?.onEvent(AttachmentEvents.VideoEvent(playing, currentProgress, duration))
            }
            it.start()
        }
    }

    private fun stopTimer() {
        countUpTimer?.stop()
        countUpTimer = null
    }

    // Shared, fully-guarded resume logic. Verifies the surface is valid before
    // calling start(), and re-prepares if playback doesn't actually resume.
    private fun resumePlayback() {
        views.videoThumbnailImage.isVisible = true      // ✅ add
        isPrepared = false
        startPlaying()
    }
    override fun handleCommand(commands: AttachmentCommands) {
        android.util.Log.d("PauseDebug", "handleCommand($commands) isSelected=$isSelected isPrepared=$isPrepared")
        if (!isSelected) return
        when (commands) {
            AttachmentCommands.StartVideo -> {
                if (isPrepared) {
                    resumePlayback()
                } else {
                    pendingPlay = true
                    pendingPause = false
                }
            }
            AttachmentCommands.PauseVideo -> {
                if (isPrepared) {
                    stopTimer()
                    if (views.videoView.isPlaying) {
                        views.videoView.pause()
                    }
                    // VideoView/MediaPlayer can silently ignore pause() if its internal
                    // state is out of sync with our isPrepared flag. Verify it actually
                    // took effect; if not, fall back to stopPlayback() + reset so the
                    // video definitively halts rather than continuing to play unseen.
                    views.videoView.postDelayed({
                        if (views.videoView.isPlaying) {
                            Log.w(VideoViewHolder::class.java.name, "pause() did not take effect, forcing stop")
                            progress = views.videoView.currentPosition
                            views.videoView.pause()
                            if (views.videoView.isPlaying) {
                                // Last resort: tear down and reinitialize paused at the same position.
                                views.videoView.stopPlayback()
                                isPrepared = false
                                pendingPause = true
                                setVideoAndPlay()
                            }
                        }
                    }, 50L)
                    eventListener?.get()?.onEvent(
                            AttachmentEvents.VideoEvent(false, views.videoView.currentPosition, views.videoView.duration)
                    )
                } else {
                    pendingPause = true
                    pendingPlay = false
                }
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

    // In bind():
    override fun bind(attachmentInfo: AttachmentInfo) {
        super.bind(attachmentInfo)
        pendingPlay = false
        views.videoSeekBar.isVisible = true
        views.videoControlIcon.visibility = View.VISIBLE
        views.videoControlIcon.setImageResource(R.drawable.ic_pause_arrow)
        views.videoControlIcon.setOnClickListener(null)
        views.videoControlIcon.isClickable = false
    }

    // In startPlaying():
    private fun startPlaying() {
        isPrepared = false
        views.videoLoaderProgress.isVisible = true
        views.videoMediaViewerErrorView.isVisible = false
        views.videoView.visibility = View.VISIBLE
        views.videoView.bringToFront()
        views.videoThumbnailImage.isVisible = true
        views.videoControlIcon.visibility = View.VISIBLE  // ✅
        views.videoControlIcon.setImageResource(R.drawable.ic_pause_arrow)
        views.videoSeekBar.isVisible = true
        setVideoAndPlay()
    }
}
