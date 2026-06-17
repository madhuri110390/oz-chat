/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.call.audio

import android.annotation.SuppressLint
import android.media.AudioManager
import android.os.Build
import androidx.media.AudioAttributesCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import timber.log.Timber

class DefaultAudioDeviceRouter(
        private val audioManager: AudioManager,
        private val callAudioManager: CallAudioManager
) : CallAudioManager.AudioDeviceRouter, AudioManager.OnAudioFocusChangeListener {

    private var audioFocusLost = false

    private var focusRequestCompat: AudioFocusRequestCompat? = null

    @SuppressLint("WrongConstant")
    override fun setAudioRoute(device: CallAudioManager.Device) {
        // Always set MODE_IN_COMMUNICATION when a route is active
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isMicrophoneMute = false

        // Tear down whatever route is currently active FIRST, before enabling the
        // new one. Without this, the old route (e.g. earpiece) and the new route
        // (e.g. speaker) can both be briefly active at once, which is what causes
        // a continuous tone (ringback/dial tone) to sound like it plays twice or
        // from two places during a Phone <-> Speaker switch.
        clearCurrentRoute()

        when (device) {
            is CallAudioManager.Device.WirelessHeadset -> {
                setBluetoothAudioRoute(true)
            }

            is CallAudioManager.Device.Speaker -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    audioManager.availableCommunicationDevices
                            .firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                            ?.let { audioManager.setCommunicationDevice(it) }
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = true
                }
            }

            else -> {
                // Phone / Headset — route to earpiece
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    audioManager.availableCommunicationDevices
                            .firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                            ?.let { audioManager.setCommunicationDevice(it) }
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = false
                }
            }
        }
    }

    /**
     * Disables every possible output route (Bluetooth, speakerphone flag, and the
     * SDK 31+ communication device) before a new route is enabled. This prevents
     * the old and new route being briefly active simultaneously during a switch.
     */
    @SuppressLint("WrongConstant")
    private fun clearCurrentRoute() {
        setBluetoothAudioRoute(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
        }
    }

    @SuppressLint("WrongConstant")
    override fun setMode(mode: CallAudioManager.Mode): Boolean {
        if (mode === CallAudioManager.Mode.DEFAULT) {
            audioFocusLost = false
            audioManager.mode = AudioManager.MODE_NORMAL
            focusRequestCompat?.also {
                AudioManagerCompat.abandonAudioFocusRequest(audioManager, it)
            }
            focusRequestCompat = null
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
            setBluetoothAudioRoute(false)
            return true
        }

        // Do NOT set MODE_IN_COMMUNICATION here — setAudioRoute() owns audio mode
        // Setting it here before route is selected causes speaker to activate on OEMs
        audioManager.isMicrophoneMute = false

        val audioFocusRequest = AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                        AudioAttributesCompat.Builder()
                                .setUsage(AudioAttributesCompat.USAGE_VOICE_COMMUNICATION)
                                .setContentType(AudioAttributesCompat.CONTENT_TYPE_SPEECH)
                                .build()
                )
                .setOnAudioFocusChangeListener(this)
                .build()
                .also { focusRequestCompat = it }

        val gotFocus = AudioManagerCompat.requestAudioFocus(audioManager, audioFocusRequest)
        if (gotFocus == AudioManager.AUDIOFOCUS_REQUEST_FAILED) {
            Timber.w("Audio focus request failed")
            return false
        }
        return true
    }

    /**
     * Helper method to set the output route to a Bluetooth device.
     *
     * @param enabled true if Bluetooth should use used, false otherwise.
     */
    private fun setBluetoothAudioRoute(enabled: Boolean) {
        if (enabled) {
            @Suppress("DEPRECATION")
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
        } else {
            audioManager.isBluetoothScoOn = false
            @Suppress("DEPRECATION")
            audioManager.stopBluetoothSco()
        }
    }

    /**
     * [AudioManager.OnAudioFocusChangeListener] interface method. Called
     * when the audio focus of the system is updated.
     *
     * @param focusChange - The type of focus change.
     */
    override fun onAudioFocusChange(focusChange: Int) {
        callAudioManager.runInAudioThread {
            when (focusChange) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    Timber.d(" Audio focus gained")
                    if (audioFocusLost) {
                        callAudioManager.resetAudioRoute()
                    }
                    audioFocusLost = false
                }
                AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    Timber.d(" Audio focus lost")
                    audioFocusLost = true
                }
            }
        }
    }
}
