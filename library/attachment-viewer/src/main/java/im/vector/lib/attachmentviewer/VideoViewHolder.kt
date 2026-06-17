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
        views.videoControlIcon.isVisible = false
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
            views.videoSeekBar.isVisible = true
            stopTimer()
        } else {
            if (!isPrepared && mVideoPath != null) {
                startPlaying()
            } else if (!isPrepared) {
                pendingPlay = true
                views.videoLoaderProgress.isVisible = true
            }
        }
    }

    private fun startPlaying() {
        isPrepared = false
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
            isPrepared = true
            views.videoSeekBar.max = mp.duration
            views.videoView.visibility = View.VISIBLE
            views.videoView.translationZ = 0f
            views.videoView.elevation = 0f
            views.videoLoaderProgress.isVisible = false
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

            views.videoView.start()
            if (progress > 0) {
                views.videoView.seekTo(progress)
            }
            startTimer()
            views.videoView.postDelayed({
                views.videoThumbnailImage.isVisible = false
            }, 150L)

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
            views.videoControlIcon.isVisible = false
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

    // Shared, fully-guarded resume logic used by BOTH the small in-video icon
    // (via togglePlayPause) and the big overlay icon (via handleCommand).
    // Previously handleCommand called views.videoView.start() directly with
    // no safety checks, so the overlay's big button never benefited from the
    // surface-validity/verify-it-actually-started fixes added to togglePlayPause.
    private fun resumePlayback() {
        val surfaceValid = views.videoView.holder?.surface?.isValid == true
        Log.d("VideoViewHolder", "resumePlayback: surfaceValid=$surfaceValid at ${System.currentTimeMillis()}")
        if (surfaceValid) {
            views.videoView.start()
            startTimer()
            views.videoControlIcon.isVisible = false
            views.videoView.postDelayed({
                if (!views.videoView.isPlaying) {
                    Log.w("VideoViewHolder", "start() did not actually resume playback — forcing re-prepare")
                    isPrepared = false
                    views.videoControlIcon.isVisible = false
                    startPlaying()
                }
            }, 300L)
        } else {
            Log.w("VideoViewHolder", "Resume requested but surface invalid — re-preparing instead of start()")
            isPrepared = false
            views.videoControlIcon.isVisible = false
            startPlaying()
        }
    }

    override fun handleCommand(commands: AttachmentCommands) {
        if (!isSelected) return
        when (commands) {
            AttachmentCommands.StartVideo -> {
                if (isPrepared) {
                    resumePlayback()
                }
            }
            AttachmentCommands.PauseVideo -> {
                if (isPrepared) {
                    views.videoView.pause()
                    stopTimer()
                    views.videoControlIcon.isVisible = true
                    views.videoControlIcon.setImageResource(R.drawable.ic_play_arrow)
                    eventListener?.get()?.onEvent(
                            AttachmentEvents.VideoEvent(false, views.videoView.currentPosition, views.videoView.duration)
                    )
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

    private fun togglePlayPause() {
        Log.d("VideoViewHolder", "togglePlayPause ENTERED at ${System.currentTimeMillis()}, isPlaying=${views.videoView.isPlaying}, isPrepared=$isPrepared")
        if (!isPrepared) {
            if (mVideoPath != null) {
                startPlaying()
            } else {
                pendingPlay = true
            }
            return
        }
        if (views.videoView.isPlaying) {
            views.videoView.pause()
            stopTimer()
            progress = views.videoView.currentPosition
            views.videoControlIcon.isVisible = true
            views.videoControlIcon.setImageResource(R.drawable.ic_play_arrow)
        } else {
            resumePlayback()
        }
    }

    override fun bind(attachmentInfo: AttachmentInfo) {
        super.bind(attachmentInfo)
        pendingPlay = false
        views.videoControlIcon.isVisible = true
        views.videoSeekBar.isVisible = true

        val tapListener = View.OnClickListener { togglePlayPause() }
        views.videoControlIcon.setOnClickListener(tapListener)
        views.videoThumbnailImage.setOnClickListener(tapListener)
        views.videoView.setOnClickListener(tapListener)
    }
}
