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

// TODO, it would be probably better to use a unique media player
// for better customization and control
// But for now VideoView is enough, it released player when detached, we use a timer to update progress
class VideoViewHolder constructor(itemView: View) :
        BaseViewHolder(itemView) {

    private var isSelected = false
    private var mVideoPath: String? = null
    private var countUpTimer: CountUpTimer? = null
    private var progress: Int = 0
    private var wasPaused = false
    private var pendingPlay = false

    var eventListener: WeakReference<AttachmentEventListener>? = null

    val views = ItemVideoAttachmentBinding.bind(itemView)

    internal val target = DefaultVideoLoaderTarget(this, views.videoThumbnailImage)

    override fun onRecycled() {
        super.onRecycled()
        stopTimer()
        mVideoPath = null
    }

    fun videoReady(file: File) {
        mVideoPath = file.path
        if (isSelected|| pendingPlay) {
            pendingPlay = false
            startPlaying()
        }
    }

    fun videoReady(path: String) {
        mVideoPath = path
        if (isSelected) {
            startPlaying()
        }
    }

    fun videoFileLoadError() {
    }

    override fun entersBackground() {
        if (views.videoView.isPlaying) {
            progress = views.videoView.currentPosition
            stopTimer()
            views.videoView.stopPlayback()
            views.videoView.pause()
        }
    }

    override fun entersForeground() {
        onSelected(isSelected)
    }

    override fun onSelected(selected: Boolean) {
        if (!selected) {
            if (views.videoView.isPlaying) {
                progress = views.videoView.currentPosition
                views.videoView.stopPlayback()
            } else {
                progress = 0
            }
            stopTimer()
        } else {
            if (mVideoPath != null) {
                startPlaying()
            }
        }
        isSelected = selected
    }
    private fun startPlaying() {
        views.videoThumbnailImage.isVisible = false
        views.videoLoaderProgress.isVisible = false
        views.videoControlIcon.isVisible = false
        views.videoView.visibility = View.VISIBLE

        setVideoAndPlay()
    }
//    private fun startPlaying() {
//        views.videoThumbnailImage.isVisible = false
//        views.videoLoaderProgress.isVisible = false
//        views.videoControlIcon.isVisible = false
//        views.videoView.visibility = View.VISIBLE
//
//        views.videoView.holder.addCallback(object : android.view.SurfaceHolder.Callback {
//            override fun surfaceCreated(holder: android.view.SurfaceHolder) {
//                // ✅ Surface is ready NOW — safe to set video
//                setVideoAndPlay()
//            }
//            override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) {}
//            override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {}
//        })
//
//        // ✅ If surface already exists, callback won't fire — call directly
//        if (views.videoView.holder.surface.isValid) {
//            setVideoAndPlay()
//        }
//    }
    private fun setVideoAndPlay() {
        views.videoView.setOnPreparedListener { mp ->
            mp.setVideoScalingMode(android.media.MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            stopTimer()
            countUpTimer = CountUpTimer(intervalInMs = 100).also {
                it.tickListener = CountUpTimer.TickListener {
                    val duration = views.videoView.duration
                    val progress = views.videoView.currentPosition
                    val isPlaying = views.videoView.isPlaying
                    eventListener?.get()?.onEvent(
                            AttachmentEvents.VideoEvent(isPlaying, progress, duration)
                    )
                }
                it.start()
            }
            if (!wasPaused) {
                views.videoView.start()
                if (progress > 0) {
                    views.videoView.seekTo(progress)
                }
            }
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
            views.videoMediaViewerErrorView.isVisible = true
        }
    }
//    private fun startPlaying() {
//        views.videoThumbnailImage.isVisible = false
//        views.videoLoaderProgress.isVisible = false
//        views.videoControlIcon.isVisible = false
//        // Toggle visibility to force SurfaceView redraw inside ViewPager2
//        views.videoView.visibility = View.GONE
//        views.videoView.visibility = View.VISIBLE
//
//        // Use MediaOverlay not OnTop — renders correctly inside ViewPager2
//        views.videoView.setZOrderMediaOverlay(true)
//        views.videoView.setOnPreparedListener {
//            stopTimer()
//            countUpTimer = CountUpTimer(intervalInMs = 100).also {
//                it.tickListener = CountUpTimer.TickListener {
//                    val duration = views.videoView.duration
//                    val progress = views.videoView.currentPosition
//                    val isPlaying = views.videoView.isPlaying
//                    //                        Log.v("FOO", "isPlaying $isPlaying $progress/$duration")
//                    eventListener?.get()?.onEvent(AttachmentEvents.VideoEvent(isPlaying, progress, duration))
//                }
//                it.start()
//            }
//            if (!wasPaused) {
//                views.videoView.start()
//                if (progress > 0) {
//                    views.videoView.seekTo(progress)
//                }
//            }
//        }
////        try {
////            views.videoView.setVideoPath(mVideoPath)
////        } catch (failure: Throwable) {
////            // Couldn't open
////            Log.v(VideoViewHolder::class.java.name, "Failed to start video")
////        }
//        try {
//            val videoUri = if (mVideoPath!!.startsWith("content://") || mVideoPath!!.startsWith("file://")) {
//                android.net.Uri.parse(mVideoPath)
//            } else {
//                android.net.Uri.fromFile(java.io.File(mVideoPath!!))
//            }
//            views.videoView.setVideoURI(videoUri)
//        } catch (failure: Throwable) {
//            Log.v(VideoViewHolder::class.java.name, "Failed to start video: ${failure.message}")
//        }
////        if (!wasPaused) {
////            views.videoView.start()
////            if (progress > 0) {
////                views.videoView.seekTo(progress)
////            }
////        }
//    }

    private fun stopTimer() {
        countUpTimer?.stop()
        countUpTimer = null
    }

    override fun handleCommand(commands: AttachmentCommands) {
        if (!isSelected) return
        when (commands) {
            AttachmentCommands.StartVideo -> {
                wasPaused = false
                views.videoView.start()
            }
            AttachmentCommands.PauseVideo -> {
                wasPaused = true
                views.videoView.pause()
            }
            is AttachmentCommands.SeekTo -> {
                val duration = views.videoView.duration
                if (duration > 0) {
                    val seekDuration = duration * (commands.percentProgress / 100f)
                    views.videoView.seekTo(seekDuration.toInt())
                }
            }
        }
    }

    override fun bind(attachmentInfo: AttachmentInfo) {
        super.bind(attachmentInfo)
        progress = 0
        wasPaused = false
        pendingPlay = false
        views.videoControlIcon.isVisible = true
        views.videoControlIcon.setImageResource(R.drawable.ic_play_arrow)
        views.videoControlIcon.setOnClickListener {
            if (mVideoPath != null) {
                startPlaying()
            } else {
                pendingPlay = true
                views.videoLoaderProgress.isVisible = true
            }
        }
        views.videoThumbnailImage.setOnClickListener {
            views.videoControlIcon.performClick()
        }
//        views.videoThumbnailImage.setOnClickListener {
//            eventListener?.get()?.onEvent(AttachmentEvents.VideoEvent(false, 0, 0))
//            if (mVideoPath != null) {
//                startPlaying()
//            }
//        }
    }
}
