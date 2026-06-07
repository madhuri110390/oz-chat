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

        // Request audio focus FIRST — without this, STREAM_RING is silenced
        // on many devices when the app is killed/backgrounded
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusRequest = android.media.AudioFocusRequest.Builder(
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            )
                    .setAudioAttributes(
                            AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                    .build()
                    )
                    .setAcceptsDelayedFocusGain(false)
                    .build()
            audioManager?.requestAudioFocus(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager?.requestAudioFocus(null, AudioManager.STREAM_RING, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        }

        val incomingCallChannel = notificationUtils.getChannelForIncomingCall(fromBg)
        val ringerMode = audioManager?.ringerMode
        if (ringerMode == AudioManager.RINGER_MODE_NORMAL) {
            playRingtoneIfNeeded(incomingCallChannel, customToneUri)
        } else if (ringerMode == AudioManager.RINGER_MODE_VIBRATE) {
            vibrateIfNeeded(incomingCallChannel)
        }
    }

    private fun playRingtoneIfNeeded(incomingCallChannel: NotificationChannel?, customToneUri: Uri?) {
        ringPlayer?.release()
        ringPlayer = null
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
            // Use USAGE_NOTIFICATION_RINGTONE so the system routes through
            // STREAM_RING and respects the ringer volume, not media volume
            player.setAudioAttributes(
                    AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setLegacyStreamType(AudioManager.STREAM_RING)
                            .build()
            )

            player.setDataSource(applicationContext, ringtoneUri)
            player.isLooping = false
            player.setOnPreparedListener { it.start() }
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

            player.prepareAsync()   // prepareAsync → onPreparedListener → start()
            Timber.v("Preparing ringtone for incoming call ($RING_CYCLES cycles)")
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

    fun stop() {
        ringPlayer?.release()
        ringPlayer = null
        ringCyclesPlayed = 0
        vibrator?.cancel()
        vibrator = null
        // Abandon audio focus so earpiece/speaker routes back to normal after call
        val audioManager = applicationContext.getSystemService<AudioManager>()
        @Suppress("DEPRECATION")
        audioManager?.abandonAudioFocus(null)
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
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }
}



