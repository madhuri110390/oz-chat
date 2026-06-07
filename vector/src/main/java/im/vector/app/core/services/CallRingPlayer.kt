/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.services

import android.app.NotificationChannel
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.content.getSystemService
import im.vector.app.R
import im.vector.app.features.call.webrtc.WebRtcCallManager
import im.vector.app.features.notifications.NotificationUtils
import org.matrix.android.sdk.api.extensions.orFalse
import timber.log.Timber

class CallRingPlayerIncoming(
        context: Context,
        private val notificationUtils: NotificationUtils
) {

    private val applicationContext = context.applicationContext
    private var ringPlayer: MediaPlayer? = null
    private var ringCyclesPlayed = 0
    private var vibrator: Vibrator? = null

    private val VIBRATE_PATTERN = longArrayOf(0, 400, 600)
    private val RING_CYCLES = 8
    fun start(fromBg: Boolean, customToneUri: Uri? = null) {
        val audioManager = applicationContext.getSystemService<AudioManager>()
        val incomingCallChannel = notificationUtils.getChannelForIncomingCall(fromBg)
        val ringerMode = audioManager?.ringerMode
        if (ringerMode == AudioManager.RINGER_MODE_NORMAL) {
            playRingtoneIfNeeded(incomingCallChannel, customToneUri)
        } else if (ringerMode == AudioManager.RINGER_MODE_VIBRATE) {
            vibrateIfNeeded(incomingCallChannel)
        }
    }

    private fun playRingtoneIfNeeded(incomingCallChannel: NotificationChannel?, customToneUri: Uri?) {
        if (ringPlayer != null) {
            Timber.v("Ringtone already playing — skipping restart")
            return
        }
        ringCyclesPlayed = 0

        val ringtoneUri = customToneUri
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: run {
                    Timber.w("No default ringtone URI found")
                    return
                }

        val player = MediaPlayer()
        ringPlayer = player

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                player.setAudioAttributes(
                        AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build()
                )
            } else {
                @Suppress("DEPRECATION")
                player.setAudioStreamType(AudioManager.STREAM_RING)
            }

            player.setDataSource(applicationContext, ringtoneUri)
            player.isLooping = false
            player.setOnCompletionListener {
                val current = ringPlayer ?: return@setOnCompletionListener
                ringCyclesPlayed += 1
                if (ringCyclesPlayed < RING_CYCLES) {
                    try {
                        current.seekTo(0)
                        current.start()
                    } catch (failure: Throwable) {
                        Timber.e(failure, "Failed to replay incoming ringtone")
                        stop()
                    }
                } else {
                    Timber.v("Stop incoming ringtone after $RING_CYCLES cycles")
                    stop()
                }
            }
            player.setOnErrorListener { _, what, extra ->
                Timber.w("Incoming ringtone MediaPlayer error what=$what extra=$extra")
                stop()
                false
            }

            player.prepare()
            Timber.v("Play ringtone for incoming call (app-managed $RING_CYCLES cycles)")
            player.start()
        } catch (failure: Throwable) {
            Timber.e(failure, "Failed to start incoming ringtone")
            stop()
        }
    }



    private fun vibrateIfNeeded(incomingCallChannel: NotificationChannel?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && incomingCallChannel?.shouldVibrate().orFalse()) {
            Timber.v("## Vibration already configured by notification channel")
            return
        }
        vibrator = applicationContext.getSystemService()
        Timber.v("Vibrate for incoming call")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val vibrationEffect = VibrationEffect.createWaveform(VIBRATE_PATTERN, 0)
            vibrator?.vibrate(vibrationEffect)
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(VIBRATE_PATTERN, 0)
        }
    }

    // FIXED — always releases even if stop() throws
    fun stop() {
        ringPlayer?.release()
        ringPlayer = null
        ringCyclesPlayed = 0
        vibrator?.cancel()
        vibrator = null
    }

}

class CallRingPlayerOutgoing(
        context: Context,
        private val callManager: WebRtcCallManager
) {

    private val applicationContext = context.applicationContext

    private var ringtone: Ringtone? = null
    private var mediaPlayer: MediaPlayer? = null

    fun start() {
        stop()

        try {
            mediaPlayer = MediaPlayer.create(
                    applicationContext,
                    R.raw.ring
            )

            mediaPlayer?.isLooping = true
            mediaPlayer?.setVolume(1f, 1f)
            mediaPlayer?.start()

        } catch (e: Exception) {
            Timber.e(e, "Failed to play ringback")
        }
    }

    fun stop() {
        mediaPlayer?.let { player ->
            try {
                if (player.isPlaying) player.stop()
            } catch (e: IllegalStateException) {
                Timber.w(e, "MediaPlayer stop() in invalid state")
            } finally {
                player.release()
            }
        }
        mediaPlayer = null
    }
}



