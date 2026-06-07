/*
 * Copyright 2018-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

@file:Suppress("UNUSED_PARAMETER")

package im.vector.app.features.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import androidx.annotation.AttrRes
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.app.TaskStackBuilder
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.fragment.app.Fragment
import im.vector.app.R
import im.vector.app.core.extensions.createIgnoredUri
import im.vector.app.core.platform.PendingIntentCompat
import im.vector.app.core.resources.BuildMeta
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.services.CallAndroidService
import im.vector.app.core.utils.startNotificationChannelSettingsIntent
import im.vector.app.features.MainActivity
import im.vector.app.features.call.VectorCallActivity
import im.vector.app.features.call.service.CallHeadsUpActionReceiver
import im.vector.app.features.call.webrtc.WebRtcCall
import im.vector.app.features.displayname.getBestName
import im.vector.app.features.home.HomeActivity
import im.vector.app.features.home.room.detail.RoomDetailActivity
import im.vector.app.features.home.room.detail.arguments.TimelineArgs
import im.vector.app.features.home.room.threads.ThreadsActivity
import im.vector.app.features.home.room.threads.arguments.ThreadTimelineArgs
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.settings.troubleshoot.TestNotificationReceiver
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.core.utils.timer.Clock
import im.vector.lib.strings.CommonPlurals
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.Matrix
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.random.Random

@Singleton
class NotificationUtils @Inject constructor(
        private val context: Context,
        private val stringProvider: StringProvider,
        private val vectorPreferences: VectorPreferences,
        private val clock: Clock,
        private val actionIds: NotificationActionIds,
        private val buildMeta: BuildMeta,
        private val notificationDrawerManagerProvider: Provider<NotificationDrawerManager>,

) {

    val notificationDrawer: NotificationDrawerManager get() = notificationDrawerManagerProvider.get()
    val notificationDrawerManager: NotificationDrawerManager get() = notificationDrawer
    companion object {
        // Notification IDs
        const val NOTIFICATION_ID_FOREGROUND_SERVICE = 61
        const val SCREEN_SHARING_NOTIFICATION_ID = 62
        const val DIAGNOSTIC_NOTIFICATION_ID = 888
        const val CALL_NOTIFICATION_ID = 3000
        const val ROOM_MESSAGES_NOTIFICATION_ID = 2000
        private const val FULL_SCREEN_INTENT_REQUEST_CODE = 9001
        private const val DIAGNOSTIC_TAG = "DIAGNOSTIC"
        // Active channel IDs — bump version suffix whenever channel config changes
        private const val NOISY_NOTIFICATION_CHANNEL_ID = "DEFAULT_NOISY_NOTIFICATION_CHANNEL_ID_V4"
        const val SILENT_NOTIFICATION_CHANNEL_ID = "DEFAULT_SILENT_NOTIFICATION_CHANNEL_ID_V5"
        const val CALL_NOTIFICATION_CHANNEL_ID = "CALL_NOTIFICATION_CHANNEL_ID_V6"
        private const val LISTENING_FOR_EVENTS_NOTIFICATION_CHANNEL_ID = "LISTEN_FOR_EVENTS_NOTIFICATION_CHANNEL_ID_V2"

        // All legacy channel IDs — deleted on first run after upgrade
        private val DEPRECATED_CHANNEL_IDS = listOf(
                "DEFAULT_SILENT_NOTIFICATION_CHANNEL_ID",
                "DEFAULT_SILENT_NOTIFICATION_CHANNEL_ID_V2",
                "DEFAULT_SILENT_NOTIFICATION_CHANNEL_ID_V3",
                "DEFAULT_SILENT_NOTIFICATION_CHANNEL_ID_V4",
                "DEFAULT_NOISY_NOTIFICATION_CHANNEL_ID",
                "DEFAULT_NOISY_NOTIFICATION_CHANNEL_ID_V2",
                "DEFAULT_NOISY_NOTIFICATION_CHANNEL_ID_V3",
                "DEFAULT_NOISY_NOTIFICATION_CHANNEL_ID_BASE",
                "CALL_NOTIFICATION_CHANNEL_ID",
                "CALL_NOTIFICATION_CHANNEL_ID_V2",
                "CALL_NOTIFICATION_CHANNEL_ID_V3",
                "CALL_NOTIFICATION_CHANNEL_ID_V4",
                "CALL_NOTIFICATION_CHANNEL_ID_V5",
                "LISTEN_FOR_EVENTS_NOTIFICATION_CHANNEL_ID",
        )

        @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.O)
        fun supportNotificationChannels() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

        fun openSystemSettingsForSilentCategory(fragment: Fragment) {
            startNotificationChannelSettingsIntent(fragment, SILENT_NOTIFICATION_CHANNEL_ID)
        }

        fun openSystemSettingsForNoisyCategory(fragment: Fragment) {
            startNotificationChannelSettingsIntent(fragment, NOISY_NOTIFICATION_CHANNEL_ID)
        }

        fun openSystemSettingsForCallCategory(fragment: Fragment) {
            startNotificationChannelSettingsIntent(fragment, CALL_NOTIFICATION_CHANNEL_ID)
        }
    }
    fun deleteRoomNotificationChannel(roomId: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService<NotificationManager>() ?: return
            nm.notificationChannels.forEach { channel ->
                if (channel.id.startsWith("ROOM_CHANNEL_$roomId")) {
                    nm.deleteNotificationChannel(channel.id)
                    Timber.d("Deleted old channel: ${channel.id}")
                }
            }
        }
    }
    private val notificationManager = NotificationManagerCompat.from(context)

    // ==============================================================================================
    // Channel creation
    // ==============================================================================================

    /**
     * Creates all required notification channels and removes any legacy ones.
     * Safe to call multiple times — Android deduplicates channel creation by ID.
     */
    fun createNotificationChannels(context: Context, notificationManager: NotificationManagerCompat) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val accentColor = ContextCompat.getColor(context, im.vector.lib.ui.styles.R.color.notification_accent_color)

        try {
            // Remove old dynamic noisy channels (legacy pre-versioning scheme)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                notificationManager.notificationChannels
                        .filter { it.id.startsWith("DEFAULT_NOISY_NOTIFICATION_CHANNEL_ID_BASE") }
                        .forEach {
                            notificationManager.deleteNotificationChannel(it.id)
                            Timber.d("Deleted dynamic legacy channel: ${it.id}")
                        }
            }

            // Remove all known deprecated channels
            DEPRECATED_CHANNEL_IDS.forEach { channelId ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (notificationManager.getNotificationChannel(channelId) != null) {
                        notificationManager.deleteNotificationChannel(channelId)
                        Timber.d("Deleted deprecated channel: $channelId")
                    }
                }
            }

            // NOISY — sound + vibration, shown everywhere, bypasses DND
            createOrUpdateChannel(
                    NOISY_NOTIFICATION_CHANNEL_ID,
                    stringProvider.getString(CommonStrings.notification_noisy_notifications)
                            .ifEmpty { "Noisy notifications" },
                    NotificationManager.IMPORTANCE_HIGH
            ) {
                description = stringProvider.getString(CommonStrings.notification_noisy_notifications)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 300, 200, 300)
                enableLights(true)
                lightColor = accentColor
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
                setSound(
                        Settings.System.DEFAULT_NOTIFICATION_URI,
                        Notification.AUDIO_ATTRIBUTES_DEFAULT
                )
            }

            // SILENT — no sound/vibration, still shows as heads-up at HIGH importance
            createOrUpdateChannel(
                    SILENT_NOTIFICATION_CHANNEL_ID,
                    stringProvider.getString(CommonStrings.notification_silent_notifications)
                            .ifEmpty { "Silent notifications" },
                    NotificationManager.IMPORTANCE_HIGH
            ) {
                description = stringProvider.getString(CommonStrings.notification_silent_notifications)
                setSound(null, null)
                enableVibration(false)
                enableLights(true)
                lightColor = accentColor
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
            }

            // LISTENING — background service ticker, never makes noise
            createOrUpdateChannel(
                    LISTENING_FOR_EVENTS_NOTIFICATION_CHANNEL_ID,
                    stringProvider.getString(CommonStrings.notification_listening_for_events)
                            .ifEmpty { "Listening for events" },
                    NotificationManager.IMPORTANCE_LOW
            ) {
                description = stringProvider.getString(CommonStrings.notification_listening_for_events)
                setSound(null, null)
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }

            // CALL — vibration only; ringtone is owned by CallRingPlayerIncoming
            // (channel sound is intentionally null to avoid double-ringing).
            createOrUpdateChannel(
                    CALL_NOTIFICATION_CHANNEL_ID,
                    stringProvider.getString(CommonStrings.call).ifEmpty { "Call" },
                    NotificationManager.IMPORTANCE_HIGH
            ) {
                description = stringProvider.getString(CommonStrings.call)
                setSound(null, null)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 1000, 500, 1000)
                enableLights(true)
                lightColor = accentColor
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setAllowBubbles(false)
                }
            }

            Timber.d("Notification channels created/updated successfully")
        } catch (e: Exception) {
            Timber.e(e, "Failed to create notification channels")
        }
    }

    /**
     * Creates a new channel or recreates it if the importance level has changed.
     * Note: most channel properties cannot be changed after creation — only importance
     * changes trigger a delete+recreate.
     */
    private fun createOrUpdateChannel(
            channelId: String,
            name: String,
            importance: Int,
            config: NotificationChannel.() -> Unit,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val existing = notificationManager.getNotificationChannel(channelId)
        if (existing != null && existing.importance == importance) {
            Timber.d("Channel already exists with correct importance: $channelId")
            return
        }
        if (existing != null) {
            notificationManager.deleteNotificationChannel(channelId)
            Timber.d("Deleted channel for recreation (importance changed): $channelId")
        }
        NotificationChannel(channelId, name, importance).apply(config).also {
            notificationManager.createNotificationChannel(it)
            Timber.d("Created channel: $channelId")
        }
    }

    fun getChannel(channelId: String): NotificationChannel? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                notificationManager.getNotificationChannel(channelId)
            } else null

    fun getChannelForIncomingCall(@Suppress("UNUSED_PARAMETER") fromBg: Boolean): NotificationChannel? =
            getChannel(CALL_NOTIFICATION_CHANNEL_ID)

    // ==============================================================================================
    // Permission helpers
    // ==============================================================================================

    fun areSystemNotificationsEnabled(): Boolean =
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun hasNotificationPermission(): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                        context, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else true

    fun hasNotificationPermission(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                        context, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else true

    fun isDoNotDisturbModeOn(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        return try {
            val mgr = context.getSystemService<NotificationManager>()
            val filter = mgr?.currentInterruptionFilter
            filter == NotificationManager.INTERRUPTION_FILTER_NONE ||
                    filter == NotificationManager.INTERRUPTION_FILTER_ALARMS
        } catch (e: Exception) {
            Timber.e(e, "Failed to check DND mode")
            false
        }
    }

    // ==============================================================================================
    // Service / infrastructure notifications
    // ==============================================================================================

    fun buildForegroundServiceNotification(
            @StringRes subTitleResId: Int,
            withProgress: Boolean = true,
    ): Notification {
        val intent = HomeActivity.newIntent(context, firstStartMainActivity = false).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
                context, 0,
                MainActivity.getIntentWithNextIntent(context, intent),
                PendingIntentCompat.FLAG_IMMUTABLE
        )
        val accentColor = ContextCompat.getColor(context, im.vector.lib.ui.styles.R.color.notification_accent_color)

        return NotificationCompat.Builder(context, LISTENING_FOR_EVENTS_NOTIFICATION_CHANNEL_ID)
                .setContentTitle(stringProvider.getString(subTitleResId))
                .setContentText(buildMeta.applicationName)
                .setSmallIcon(R.drawable.sync)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setColor(accentColor)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .apply { if (withProgress) setProgress(0, 0, true) }
                .build()
                .apply { flags = flags or Notification.FLAG_NO_CLEAR }
    }

    fun buildStartAppNotification(): Notification =
            NotificationCompat.Builder(context, LISTENING_FOR_EVENTS_NOTIFICATION_CHANNEL_ID)
                    .setContentTitle(stringProvider.getString(CommonStrings.updating_your_data))
                    .setSmallIcon(R.drawable.sync)
                    .setColor(ThemeUtils.getColor(context, android.R.attr.colorPrimary))
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .setPriority(NotificationCompat.PRIORITY_MIN)
                    .build()

    fun buildScreenSharingNotification(): Notification =
            NotificationCompat.Builder(context, SILENT_NOTIFICATION_CHANNEL_ID)
                    .setContentTitle(stringProvider.getString(CommonStrings.screen_sharing_notification_title))
                    .setContentText(stringProvider.getString(CommonStrings.screen_sharing_notification_description))
                    .setSmallIcon(R.drawable.ic_share_screen)
                    .setColor(ThemeUtils.getColor(context, android.R.attr.colorPrimary))
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setContentIntent(buildOpenHomePendingIntentForSummary())
                    .setOngoing(true)
                    .build()

    fun buildMicrophoneAccessNotification(): Notification =
            NotificationCompat.Builder(context, SILENT_NOTIFICATION_CHANNEL_ID)
                    .setContentTitle(stringProvider.getString(CommonStrings.microphone_in_use))
                    .setSmallIcon(R.drawable.ic_call_answer)
                    .setColor(ThemeUtils.getColor(context, android.R.attr.colorPrimary))
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOngoing(true)
                    .build()

    // ==============================================================================================
    // Call notifications
    // ==============================================================================================

    fun buildIncomingCallNotification(
            call: WebRtcCall,
            title: String,
            fromBg: Boolean,
            avatarBitmap: Bitmap? = null,
    ): Notification {
        val contentIntent = VectorCallActivity.newIntent(
                context = context,
                call = call,
                mode = VectorCallActivity.INCOMING_RINGING,
        ).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NEW_TASK
            data = createIgnoredUri(call.callId)
        }
        val fullScreenPi = PendingIntent.getActivity(
                context,
                clock.epochMillis().toInt(),
                contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntentCompat.FLAG_IMMUTABLE
        )

        val answerPi = TaskStackBuilder.create(context)
                .addNextIntentWithParentStack(HomeActivity.newIntent(context, firstStartMainActivity = false))
                .addNextIntent(VectorCallActivity.newIntent(context, call, VectorCallActivity.INCOMING_ACCEPT))
                .getPendingIntent(
                        clock.epochMillis().toInt() + 1,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntentCompat.FLAG_IMMUTABLE
                ) ?: PendingIntent.getActivity(
                context,
                clock.epochMillis().toInt() + 1,
                VectorCallActivity.newIntent(context, call, VectorCallActivity.INCOMING_ACCEPT),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntentCompat.FLAG_IMMUTABLE
        )
        val rejectPi = buildRejectCallPendingIntent(call.callId)

        // CallStyle: mandatory for lock screen call UI on Android 12+
        // Without this the system treats it as a regular notification and
        // suppresses the full-screen call UI on every OEM lock screen.
        val caller = androidx.core.app.Person.Builder()
                .setName(title.ifBlank { "Incoming call" })
                .apply { if (avatarBitmap != null) setIcon(IconCompat.createWithBitmap(avatarBitmap)) }
                .setImportant(true)
                .build()

        val channelId = vectorPreferences.getRoomNotificationTone(call.nativeRoomId)?.let {
            getOrCreateRoomChannel(context, call.nativeRoomId, title, it, isCall = true)
        } ?: CALL_NOTIFICATION_CHANNEL_ID

        return NotificationCompat.Builder(context, channelId)
                .setContentTitle(title.ifBlank { "Incoming call" })
                .setContentText(
                        if (call.mxCall.isVideoCall)
                            stringProvider.getString(CommonStrings.incoming_video_call)
                        else
                            stringProvider.getString(CommonStrings.incoming_voice_call)
                )
                .setSmallIcon(R.drawable.oz_chat_playstore_icon)
                .setLargeIcon(avatarBitmap)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setOngoing(true)
                .setAutoCancel(false)
                // KEY FIX: CallStyle triggers full-screen lock screen call UI on Android 12+
                .setStyle(
                        NotificationCompat.CallStyle.forIncomingCall(caller, rejectPi, answerPi)
                                .setIsVideo(call.mxCall.isVideoCall)
                )
                .setFullScreenIntent(fullScreenPi, true)
                .build()
    }

    fun buildOutgoingRingingCallNotification(
            call: WebRtcCall,
            title: String,
    ): Notification {
        val accentColor = ContextCompat.getColor(context, im.vector.lib.ui.styles.R.color.notification_accent_color)
        val contentIntent = VectorCallActivity.newIntent(context, call, null).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NEW_TASK
            data = createIgnoredUri(call.callId)
        }
        val contentPendingIntent = PendingIntent.getActivity(
                context,
                clock.epochMillis().toInt(),
                contentIntent,
                PendingIntentCompat.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, SILENT_NOTIFICATION_CHANNEL_ID)
                .setContentTitle(ensureTitleNotEmpty(title))
                .apply {
                    setContentText(stringProvider.getString(CommonStrings.call_ringing))
                    if (call.mxCall.isVideoCall) {
                        setSmallIcon(R.drawable.ic_call_answer_video)
                    } else {
                        setSmallIcon(R.drawable.oz_chat_playstore_icon)
                    }
                }
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setLights(accentColor, 500, 500)
                .setColor(ThemeUtils.getColor(context, android.R.attr.colorPrimary))
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setFullScreenIntent(contentPendingIntent, true)
                .setContentIntent(contentPendingIntent)
                .addAction(
                        NotificationCompat.Action(
                                IconCompat.createWithResource(context, R.drawable.ic_call_hangup)
                                        .setTint(ThemeUtils.getColor(context, com.google.android.material.R.attr.colorError)),
                                getActionText(CommonStrings.call_notification_hangup, com.google.android.material.R.attr.colorError),
                                buildRejectCallPendingIntent(call.callId)
                        )
                )
                .build()
    }

    fun buildPendingCallNotification(
            call: WebRtcCall,
            title: String,
    ): Notification {
        val contentPendingIntent = TaskStackBuilder.create(context)
                .addNextIntentWithParentStack(HomeActivity.newIntent(context, firstStartMainActivity = false))
                .addNextIntent(VectorCallActivity.newIntent(context, call, null))
                .getPendingIntent(
                        clock.epochMillis().toInt(),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntentCompat.FLAG_IMMUTABLE
                )

        return NotificationCompat.Builder(context, SILENT_NOTIFICATION_CHANNEL_ID)
                .setContentTitle(ensureTitleNotEmpty(title))
                .apply {
                    if (call.mxCall.isVideoCall) {
                        setContentText(stringProvider.getString(CommonStrings.video_call_in_progress))
                        setSmallIcon(R.drawable.ic_call_answer_video)
                    } else {
                        setContentText(stringProvider.getString(CommonStrings.call_in_progress))
                        setSmallIcon(R.drawable.ic_call_answer)
                    }
                }
                .setColor(ThemeUtils.getColor(context, android.R.attr.colorPrimary))
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setContentIntent(contentPendingIntent)
                .addAction(
                        NotificationCompat.Action(
                                IconCompat.createWithResource(context, R.drawable.ic_call_hangup)
                                        .setTint(ThemeUtils.getColor(context, com.google.android.material.R.attr.colorError)),
                                getActionText(CommonStrings.call_notification_hangup, com.google.android.material.R.attr.colorError),
                                buildRejectCallPendingIntent(call.callId)
                        )
                )
                .build()
    }
    fun buildCallEndedNotification(isVideoCall: Boolean): Notification {
        return NotificationCompat.Builder(context, SILENT_NOTIFICATION_CHANNEL_ID)
                .setContentTitle(stringProvider.getString(CommonStrings.call_ended))
                .apply {
                    setSmallIcon(
                            if (isVideoCall) R.drawable.ic_call_answer_video
                            else R.drawable.ic_call_answer
                    )
                }
                .setTimeoutAfter(1)
                .setColor(ThemeUtils.getColor(context, android.R.attr.colorPrimary))
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setAutoCancel(true)
                .build()
    }

    fun buildCallMissedNotification(callInformation: CallAndroidService.CallInformation): Notification {
        val accentColor = ContextCompat.getColor(context, im.vector.lib.ui.styles.R.color.notification_accent_color)
        val contentPendingIntent = TaskStackBuilder.create(context)
                .addNextIntentWithParentStack(HomeActivity.newIntent(context, firstStartMainActivity = false))
                .addNextIntent(RoomDetailActivity.newIntent(context, TimelineArgs(callInformation.nativeRoomId), true))
                .getPendingIntent(
                        clock.epochMillis().toInt(),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntentCompat.FLAG_IMMUTABLE
                )

        return NotificationCompat.Builder(context, NOISY_NOTIFICATION_CHANNEL_ID)
                .setContentTitle(callInformation.opponentMatrixItem?.getBestName() ?: callInformation.opponentUserId)
                .apply {
                    if (callInformation.isVideoCall) {
                        setContentText(stringProvider.getQuantityString(CommonPlurals.missed_video_call, 1, 1))
                        setSmallIcon(R.drawable.ic_missed_video_call)
                    } else {
                        setContentText(stringProvider.getQuantityString(CommonPlurals.missed_audio_call, 1, 1))
                        setSmallIcon(R.drawable.ic_missed_voice_call)
                    }
                }
                .setShowWhen(true)
                .setColor(accentColor)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setContentIntent(contentPendingIntent)
                .build()
    }

    // ==============================================================================================
    // Generic FCM push notification (immediate placeholder shown before Matrix sync completes)
    // ==============================================================================================

    /**
     * Builds a best-effort notification directly from raw FCM payload.
     * Used as an instant placeholder while the Matrix sync pipeline runs in the background.
     * For calls: opens the room (NOT a full call screen — that requires a real WebRtcCall object).
     * For messages: respects the [noisy] flag to pick the correct channel.

     */
    fun buildCallNotAnsweredNotification(callInformation: CallAndroidService.CallInformation): Notification {
        val accentColor = ContextCompat.getColor(context, im.vector.lib.ui.styles.R.color.notification_accent_color)
        val contentPendingIntent = TaskStackBuilder.create(context)
                .addNextIntentWithParentStack(HomeActivity.newIntent(context, firstStartMainActivity = false))
                .addNextIntent(RoomDetailActivity.newIntent(context, TimelineArgs(callInformation.nativeRoomId), true))
                .getPendingIntent(
                        clock.epochMillis().toInt(),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntentCompat.FLAG_IMMUTABLE
                )

        return NotificationCompat.Builder(context, NOISY_NOTIFICATION_CHANNEL_ID)
                .setContentTitle(callInformation.opponentMatrixItem?.getBestName() ?: callInformation.opponentUserId)
                .apply {
                    if (callInformation.isVideoCall) {
                        setContentText("Video call not answered")
                        setSmallIcon(R.drawable.ic_missed_video_call)
                    } else {
                        setContentText("Voice call not answered")
                        setSmallIcon(R.drawable.ic_missed_voice_call)
                    }
                }
                .setShowWhen(true)
                .setColor(accentColor)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setContentIntent(contentPendingIntent)
                .build()
    }
    private fun buildCallFullScreenPendingIntent(
            context: Context,
            roomId: String?,
            callId: String?
    ): PendingIntent {
        val intent = if (roomId != null) {
            // VectorCallActivity already has android:showOnLockScreen="true"
            VectorCallActivity.newIntent(
                    context = context,
                    callId = callId ?: "",         // ✅ Fixed: pass callId if available
                    signalingRoomId = roomId,
                    otherUserId = "",
                    isIncomingCall = true,
                    isVideoCall = false,
                    mode = null
            )
        } else {
            // Fallback: use a dedicated TurnScreenOnActivity or ensure HomeActivity
            // has showOnLockScreen (Bug 3 fix in manifest above)
            HomeActivity.newIntent(context, firstStartMainActivity = false)
        }

        intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
        )

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        return PendingIntent.getActivity(context, FULL_SCREEN_INTENT_REQUEST_CODE, intent, flags)
    }
    fun buildGenericPushNotification(
            title: String?,
            body: String?,
            isCall: Boolean = false,
            roomId: String? = null,
            threadId: String? = null,
            noisy: Boolean = true,
            callId: String? = null, // ✅ Fixed: added callId parameter
    ) : NotificationCompat.Builder {
        val accentColor = ContextCompat.getColor(context, im.vector.lib.ui.styles.R.color.notification_accent_color)
        val safeTitle = ensureTitleNotEmpty(title)
        val safeBody = body?.takeIf { it.isNotBlank() }
                ?: if (isCall) stringProvider.getString(CommonStrings.incoming_voice_call)
                else stringProvider.getString(CommonStrings.notification_new_messages)

        val customTone = roomId?.let { vectorPreferences.getRoomNotificationTone(it) }
        val channelId = customTone?.let {
            getOrCreateRoomChannel(context, roomId!!, roomId, it, isCall = isCall)
        } ?: if (isCall) CALL_NOTIFICATION_CHANNEL_ID else NOISY_NOTIFICATION_CHANNEL_ID
        val contentIntent = if (roomId != null) {
            buildOpenRoomIntent(roomId) ?: buildOpenHomePendingIntentForSummary()
        } else {
            buildOpenHomePendingIntentForSummary()
        }
        val builder = NotificationCompat.Builder(context, channelId)
                .setOngoing(isCall)
                .setContentTitle(safeTitle)
                .setContentText(safeBody)
                .setStyle(NotificationCompat.BigTextStyle().bigText(safeBody))
                .setSmallIcon(if (isCall) R.drawable.ic_call_answer else R.drawable.oz_chat_playstore_icon)
                .setColor(accentColor)
                .setAutoCancel(true)
                .setCategory(if (isCall) NotificationCompat.CATEGORY_CALL else NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(contentIntent)
                .setGroup(buildMeta.applicationName)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)

        if (isCall) {
            val fullScreenIntent = buildCallFullScreenPendingIntent(context, roomId, callId)

            builder
                    // Bug 2 fix: set full screen intent so Android shows heads-up / lock screen overlay
                    .setFullScreenIntent(fullScreenIntent, /* highPriority = */ true)
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    // Ensures heads-up on Android 10+ even when app is foregrounded
                    .setPriority(NotificationCompat.PRIORITY_MAX)
        } else {
            if (customTone != null) {
                builder.setSound(customTone)
                builder.setDefaults(NotificationCompat.DEFAULT_VIBRATE or NotificationCompat.DEFAULT_LIGHTS)
            } else {
                builder.setDefaults(if (noisy) NotificationCompat.DEFAULT_ALL else NotificationCompat.DEFAULT_LIGHTS)
            }
            builder.setLights(accentColor, 500, 500)

            // Mark as read action
            if (roomId != null) {
                val markReadIntent = Intent(context, NotificationBroadcastReceiver::class.java).apply {
                    action = actionIds.markRoomRead
                    data = createIgnoredUri(roomId)
                    putExtra(NotificationBroadcastReceiver.KEY_ROOM_ID, roomId)
                }
                val markReadPi = PendingIntent.getBroadcast(
                        context,
                        clock.epochMillis().toInt(),
                        markReadIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntentCompat.FLAG_IMMUTABLE
                )
                builder.addAction(
                        R.drawable.ic_material_done_all_white,
                        stringProvider.getString(CommonStrings.action_mark_room_read),
                        markReadPi
                )

                // Quick reply action
                buildQuickReplyIntent(roomId, threadId, null)?.let { replyPi ->
                    val remoteInput = RemoteInput.Builder(NotificationBroadcastReceiver.KEY_TEXT_REPLY)
                            .setLabel(stringProvider.getString(CommonStrings.action_quick_reply))
                            .build()
                    builder.addAction(
                            NotificationCompat.Action.Builder(
                                    R.drawable.vector_notification_quick_reply,
                                    stringProvider.getString(CommonStrings.action_quick_reply),
                                    replyPi
                            )
                                    .addRemoteInput(remoteInput)
                                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                                    .setShowsUserInterface(false)
                                    .build()
                    )
                }
            }
        }

        return builder
    }
    fun createNotificationChannels() {
        createNotificationChannels(context, notificationManager)
    }
    // ==============================================================================================
    // Rich room / thread / invite / simple-event notifications (from NotificationDrawerManager)
    // ==============================================================================================

    fun buildMessagesListNotification(
            messageStyle: NotificationCompat.MessagingStyle,
            roomInfo: RoomEventGroupInfo,
            threadId: String?,
            largeIcon: Bitmap?,
            lastMessageTimestamp: Long,
            senderDisplayNameForReplyCompat: String?,
            tickerText: String,
    ): Notification {
        val accentColor = ContextCompat.getColor(context, im.vector.lib.ui.styles.R.color.notification_accent_color)
        // Respect shouldBing — only noisy channel for mentions/keywords, silent for everything else
       // val channelId = if (roomInfo.shouldBing) NOISY_NOTIFICATION_CHANNEL_ID else SILENT_NOTIFICATION_CHANNEL_ID
//        val customTone = vectorPreferences.getRoomNotificationTone(roomInfo.roomId)
//        val channelId = if (customTone != null || roomInfo.shouldBing) {
//            getOrCreateRoomChannel(context, roomInfo.roomId, roomInfo.roomDisplayName ?: roomInfo.roomId, customTone)
//        } else {
//            SILENT_NOTIFICATION_CHANNEL_ID
//        }
        val customTone = vectorPreferences.getRoomNotificationTone(roomInfo.roomId)
//        val channelId = when {
//            customTone != null ->
//                getOrCreateRoomChannel(
//                        context,
//                        roomInfo.roomId,
//                        roomInfo.roomDisplayName ?: roomInfo.roomId,
//                        customTone,
//                        isCall = false
//                )
//            roomInfo.shouldBing -> NOISY_NOTIFICATION_CHANNEL_ID
//            else -> SILENT_NOTIFICATION_CHANNEL_ID
//        }
        val channelId = when {
            roomInfo.shouldBing -> NOISY_NOTIFICATION_CHANNEL_ID
            else -> SILENT_NOTIFICATION_CHANNEL_ID
        }
        val contentIntent = when {
            threadId != null && vectorPreferences.areThreadMessagesEnabled() ->
                buildOpenThreadIntent(roomInfo, threadId)
            else -> buildOpenRoomIntent(roomInfo.roomId)
        } ?: buildOpenHomePendingIntentForSummary()

        val builder = NotificationCompat.Builder(context, channelId)
                .setOnlyAlertOnce(false)
                .setWhen(lastMessageTimestamp)
                .setStyle(messageStyle)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setShortcutId(roomInfo.roomId)
                .setContentTitle(roomInfo.roomDisplayName)
                .setContentText(stringProvider.getString(CommonStrings.notification_new_messages))
                .setSubText(
                        stringProvider.getQuantityString(
                                CommonPlurals.room_new_messages_notification,
                                messageStyle.messages.size,
                                messageStyle.messages.size
                        )
                )
                .setGroup(buildMeta.applicationName)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                .setSmallIcon(R.drawable.oz_chat_playstore_icon)
                .setColor(accentColor)
                .setPriority(NotificationCompat.PRIORITY_MAX) // ✅ Fixed: set MAX priority for noisy messages
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .setTicker(tickerText)

        if (largeIcon != null) builder.setLargeIcon(largeIcon)

        when {
            customTone != null -> {
                builder.setSound(customTone)
                builder.setLights(accentColor, 500, 500)
                builder.setDefaults(NotificationCompat.DEFAULT_VIBRATE)
            }
            roomInfo.shouldBing -> {
                vectorPreferences.getNotificationRingTone()?.let { builder.setSound(it) }
                builder.setLights(accentColor, 500, 500)
                builder.setDefaults(NotificationCompat.DEFAULT_VIBRATE)
            }
            else -> {
                builder.setDefaults(NotificationCompat.DEFAULT_LIGHTS)
            }
        }

        // Mark as read
        builder.addAction(
                NotificationCompat.Action.Builder(
                        R.drawable.ic_material_done_all_white,
                        stringProvider.getString(CommonStrings.action_mark_room_read),
                        buildMarkRoomReadPendingIntent(roomInfo.roomId)
                )
                        .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
                        .setShowsUserInterface(false)
                        .build()
        )

        // Quick reply
        if (!roomInfo.hasSmartReplyError) {
            buildQuickReplyIntent(roomInfo.roomId, threadId, senderDisplayNameForReplyCompat)?.let { replyPi ->
                val remoteInput = RemoteInput.Builder(NotificationBroadcastReceiver.KEY_TEXT_REPLY)
                        .setLabel(stringProvider.getString(CommonStrings.action_quick_reply))
                        .build()
                builder.addAction(
                        NotificationCompat.Action.Builder(
                                R.drawable.vector_notification_quick_reply,
                                stringProvider.getString(CommonStrings.action_quick_reply),
                                replyPi
                        )
                                .addRemoteInput(remoteInput)
                                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                                .setShowsUserInterface(false)
                                .build()
                )
            }
        }

        // Dismiss action
        val dismissIntent = Intent(context, NotificationBroadcastReceiver::class.java).apply {
            action = actionIds.dismissRoom
            putExtra(NotificationBroadcastReceiver.KEY_ROOM_ID, roomInfo.roomId)
        }
        val dismissPi = PendingIntent.getBroadcast(
                context.applicationContext,
                clock.epochMillis().toInt(),
                dismissIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntentCompat.FLAG_IMMUTABLE
        )
        builder.setDeleteIntent(dismissPi)

        return builder.build()
    }

    fun buildRoomInvitationNotification(
            inviteNotifiableEvent: InviteNotifiableEvent,
            matrixId: String,
    ): Notification {
        val accentColor = ContextCompat.getColor(context, im.vector.lib.ui.styles.R.color.notification_accent_color)
        val roomId = inviteNotifiableEvent.roomId

        val rejectPi = PendingIntent.getBroadcast(
                context,
                clock.epochMillis().toInt(),
                Intent(context, NotificationBroadcastReceiver::class.java).apply {
                    action = actionIds.reject
                    data = createIgnoredUri("$roomId&$matrixId")
                    putExtra(NotificationBroadcastReceiver.KEY_ROOM_ID, roomId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntentCompat.FLAG_IMMUTABLE
        )
        val joinPi = PendingIntent.getBroadcast(
                context,
                clock.epochMillis().toInt() + 1,
                Intent(context, NotificationBroadcastReceiver::class.java).apply {
                    action = actionIds.join
                    data = createIgnoredUri("$roomId&$matrixId")
                    putExtra(NotificationBroadcastReceiver.KEY_ROOM_ID, roomId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntentCompat.FLAG_IMMUTABLE
        )
        val contentIntent = HomeActivity.newIntent(
                context,
                firstStartMainActivity = true,
                inviteNotificationRoomId = roomId
        ).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            data = createIgnoredUri(inviteNotifiableEvent.eventId)
        }

        val builder = NotificationCompat.Builder(context, NOISY_NOTIFICATION_CHANNEL_ID)
                .setOnlyAlertOnce(false)
                .setContentTitle(inviteNotifiableEvent.roomName ?: buildMeta.applicationName)
                .setContentText(inviteNotifiableEvent.description)
                .setGroup(buildMeta.applicationName)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_ALL)
                .setSmallIcon(R.drawable.oz_chat_playstore_icon)
                .setColor(accentColor)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .addAction(R.drawable.vector_notification_reject_invitation,
                        stringProvider.getString(CommonStrings.action_reject), rejectPi)
                .addAction(R.drawable.vector_notification_accept_invitation,
                        stringProvider.getString(CommonStrings.action_join), joinPi)
                .setContentIntent(PendingIntent.getActivity(context, 0, contentIntent, PendingIntentCompat.FLAG_IMMUTABLE))

        if (inviteNotifiableEvent.noisy) {
            vectorPreferences.getNotificationRingTone()?.let { builder.setSound(it) }
            builder.setLights(accentColor, 500, 500)
            builder.setDefaults(NotificationCompat.DEFAULT_VIBRATE)
        }

        return builder.build()
    }

    fun buildSimpleEventNotification(
            simpleNotifiableEvent: SimpleNotifiableEvent,
            matrixId: String,
    ): Notification {
        val accentColor = ContextCompat.getColor(context, im.vector.lib.ui.styles.R.color.notification_accent_color)
      val channelId = if (simpleNotifiableEvent.noisy) NOISY_NOTIFICATION_CHANNEL_ID else SILENT_NOTIFICATION_CHANNEL_ID
//        val channelId = roomId?.let {
//            val tone = vectorPreferences.getRoomNotificationTone(it)
//            if (tone != null) getOrCreateRoomChannel(context, it, it, tone)
//            else NOISY_NOTIFICATION_CHANNEL_ID
//        } ?: NOISY_NOTIFICATION_CHANNEL_ID
//        val contentIntent = HomeActivity.newIntent(context, firstStartMainActivity = true).apply {
//            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
//            data = createIgnoredUri(simpleNotifiableEvent.eventId)
//        }
        val contentIntent = HomeActivity.newIntent(context, firstStartMainActivity = true).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            data = createIgnoredUri(simpleNotifiableEvent.eventId)
        }
        val builder = NotificationCompat.Builder(context, channelId)
                .setOnlyAlertOnce(false)
                .setContentTitle(buildMeta.applicationName)
                .setContentText(simpleNotifiableEvent.description)
                .setGroup(buildMeta.applicationName)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_ALL)
                .setSmallIcon(R.drawable.oz_chat_playstore_icon)
                .setColor(accentColor)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(PendingIntent.getActivity(context, 0, contentIntent, PendingIntentCompat.FLAG_IMMUTABLE))

        if (simpleNotifiableEvent.noisy) {
            vectorPreferences.getNotificationRingTone()?.let { builder.setSound(it) }
            builder.setLights(accentColor, 500, 500)
            builder.setDefaults(NotificationCompat.DEFAULT_VIBRATE)
        }

        return builder.build()
    }

    fun buildSummaryListNotification(
            style: NotificationCompat.InboxStyle?,
            compatSummary: String,
            noisy: Boolean,
            lastMessageTimestamp: Long,
    ): Notification {
        val accentColor = ContextCompat.getColor(context, im.vector.lib.ui.styles.R.color.notification_accent_color)
        val channelId = if (noisy) NOISY_NOTIFICATION_CHANNEL_ID else SILENT_NOTIFICATION_CHANNEL_ID

        val builder = NotificationCompat.Builder(context, channelId)
                .setOnlyAlertOnce(false)
                .setWhen(lastMessageTimestamp)
                .setStyle(style)
                .setContentTitle(buildMeta.applicationName)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setSmallIcon(R.drawable.oz_chat_playstore_icon)
                .setContentText(compatSummary)
                .setGroup(buildMeta.applicationName)
                .setGroupSummary(true)
                .setColor(accentColor)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setLights(accentColor, 500, 500)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(buildOpenHomePendingIntentForSummary())
                .setDeleteIntent(getDismissSummaryPendingIntent())

        if (noisy) {
            vectorPreferences.getNotificationRingTone()?.let { builder.setSound(it) }
            builder.setDefaults(NotificationCompat.DEFAULT_ALL)
        } else {
            builder.setDefaults(NotificationCompat.DEFAULT_LIGHTS)
        }

        return builder.build()
    }

    // ==============================================================================================
    // File download
    // ==============================================================================================

    fun buildDownloadFileNotification(uri: Uri, fileName: String, mimeType: String): Notification {
        val contentIntent = PendingIntent.getActivity(
                context,
                clock.epochMillis().toInt(),
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntentCompat.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, SILENT_NOTIFICATION_CHANNEL_ID)
                .setGroup(buildMeta.applicationName)
                .setSmallIcon(R.drawable.ic_download)
                .setContentText(stringProvider.getString(CommonStrings.downloaded_file, fileName))
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
    }

    // ==============================================================================================
    // Diagnostic
    // ==============================================================================================



    fun displayDiagnosticNotification() {
        if (!hasNotificationPermission()) {
            Timber.w("Cannot display diagnostic notification — permission denied")
            return
        }

        val testPi = PendingIntent.getBroadcast(
                context, 0,
                Intent(context, TestNotificationReceiver::class.java).apply {
                    action = actionIds.diagnostic
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE  // ✅ Fixed: FLAG_IMMUTABLE is on PendingIntent, not PendingIntentCompat
        )

        val largeIcon = BitmapFactory.decodeResource(                           // ✅ Fixed: use standard BitmapFactory instead of ambiguous getBitmap()
                context.resources,
                im.vector.lib.ui.styles.R.drawable.oz_splash_screen
        )

        showNotificationMessage(
                DIAGNOSTIC_TAG,                                                     // ✅ Fixed: use named constant instead of magic string
                DIAGNOSTIC_NOTIFICATION_ID,
                NotificationCompat.Builder(context, NOISY_NOTIFICATION_CHANNEL_ID)
                        .setContentTitle(buildMeta.applicationName)
                        .setContentText(
                                stringProvider.getString(CommonStrings.settings_troubleshoot_test_push_notification_content)
                        )
                        .setSmallIcon(R.drawable.oz_chat_playstore_icon)
                        .setLargeIcon(largeIcon)
                        .setColor(ContextCompat.getColor(context, im.vector.lib.ui.styles.R.color.notification_accent_color))
                        .setPriority(NotificationCompat.PRIORITY_MAX)
                        .setCategory(NotificationCompat.CATEGORY_STATUS)
                        .setAutoCancel(true)
                        .setContentIntent(testPi)
                        .build()
        )
    }

    // ==============================================================================================
    // Show / cancel helpers
    // ==============================================================================================

    fun showNotificationMessage(tag: String?, id: Int, notification: Notification) {
        if (!hasNotificationPermission()) {
            Timber.w("Notification permission not granted — skipping tag=$tag id=$id")
            return
        }
        if (!areSystemNotificationsEnabled()) {
            Timber.w("System notifications disabled — skipping tag=$tag id=$id")
            return
        }
        try {
            createNotificationChannels()
            notificationManager.notify(tag, id, notification)
            Timber.d("Notification shown: tag=$tag id=$id")
        } catch (e: Exception) {
            Timber.e(e, "Failed to show notification: tag=$tag id=$id")
        }
    }

    fun cancelNotificationMessage(tag: String?, id: Int) {
        try {
            notificationManager.cancel(tag, id)
            Timber.d("Cancelled notification: tag=$tag id=$id")
        } catch (e: Exception) {
            Timber.e(e, "Failed to cancel notification: tag=$tag id=$id")
        }
    }

    fun cancelNotificationForegroundService() {
        cancelNotificationMessage(null, NOTIFICATION_ID_FOREGROUND_SERVICE)
    }

    fun cancelAllNotifications() {
        try {
            notificationManager.cancelAll()
            Timber.d("All notifications cancelled")
        } catch (e: Exception) {
            Timber.e(e, "Failed to cancel all notifications")
        }
    }

    // ==============================================================================================
    // Shortcut management
    // ==============================================================================================

    fun updateShortcut(roomId: String, roomDisplayName: String, icon: IconCompat?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return
        try {
            val intent = RoomDetailActivity.newIntent(
                    context, TimelineArgs(roomId = roomId, switchToParentSpace = true), true
            ).apply {
                action = actionIds.tapToView
                data = createIgnoredUri("openRoom?$roomId")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val shortcut = ShortcutInfoCompat.Builder(context, roomId)
                    .setShortLabel(roomDisplayName)
                    .setLongLabel(roomDisplayName)
                    .setIcon(icon)
                    .setIntent(intent)
                    .setPerson(
                            androidx.core.app.Person.Builder()
                                    .setName(roomDisplayName)
                                    .setKey(roomId)
                                    .build()
                    )
                    .setLongLived(true)
                    .setIsConversation()
                    .build()
            ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
        } catch (e: Exception) {
            Timber.e(e, "Failed to update shortcut for room: $roomId")
        }
    }

    // Public wrapper used by VectorFirebaseMessagingService
    fun buildOpenRoomPendingIntent(roomId: String): PendingIntent? = buildOpenRoomIntent(roomId)

    // ==============================================================================================
    // Private helpers
    // ==============================================================================================

    private fun buildOpenRoomIntent(roomId: String): PendingIntent? {
        val roomIntent = RoomDetailActivity.newIntent(
                context, TimelineArgs(roomId = roomId, switchToParentSpace = true), true
        ).apply {
            action = actionIds.tapToView
            data = createIgnoredUri("openRoom?$roomId")
        }
        return try {
            TaskStackBuilder.create(context)
                    .addNextIntentWithParentStack(HomeActivity.newIntent(context, firstStartMainActivity = false))
                    .addNextIntent(roomIntent)
                    .getPendingIntent(
                            clock.epochMillis().toInt(),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntentCompat.FLAG_IMMUTABLE
                    )
        } catch (e: Exception) {
            Timber.e(e, "Failed to build open room intent: $roomId")
            null
        }
    }

    private fun buildOpenThreadIntent(roomInfo: RoomEventGroupInfo, threadId: String?): PendingIntent? {
        if (threadId == null) return null
        val threadIntent = ThreadsActivity.newIntent(
                context = context,
                threadTimelineArgs = ThreadTimelineArgs(
                        startsThread = false,
                        roomId = roomInfo.roomId,
                        rootThreadEventId = threadId,
                        showKeyboard = false,
                        displayName = roomInfo.roomDisplayName,
                        avatarUrl = null,
                        roomEncryptionTrustLevel = null,
                ),
                threadListArgs = null,
                firstStartMainActivity = true,
        ).apply {
            action = actionIds.tapToView
            data = createIgnoredUri("openThread?$threadId")
        }
        val roomIntent = RoomDetailActivity.newIntent(
                context, TimelineArgs(roomId = roomInfo.roomId, switchToParentSpace = true), false
        )
        return try {
            TaskStackBuilder.create(context)
                    .addNextIntentWithParentStack(HomeActivity.newIntent(context, firstStartMainActivity = false))
                    .addNextIntentWithParentStack(roomIntent)
                    .addNextIntent(threadIntent)
                    .getPendingIntent(
                            clock.epochMillis().toInt(),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntentCompat.FLAG_IMMUTABLE
                    )
        } catch (e: Exception) {
            Timber.e(e, "Failed to build open thread intent: $threadId")
            null
        }
    }

    private fun buildOpenHomePendingIntentForSummary(): PendingIntent {
        val intent = HomeActivity.newIntent(context, firstStartMainActivity = false, clearNotification = true).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            data = createIgnoredUri("tapSummary")
        }
        return PendingIntent.getActivity(
                context,
                Random.nextInt(1000),
                MainActivity.getIntentWithNextIntent(context, intent),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntentCompat.FLAG_IMMUTABLE
        )
    }

    private fun buildMarkRoomReadPendingIntent(roomId: String): PendingIntent =
            PendingIntent.getBroadcast(
                    context,
                    clock.epochMillis().toInt(),
                    Intent(context, NotificationBroadcastReceiver::class.java).apply {
                        action = actionIds.markRoomRead
                        data = createIgnoredUri(roomId)
                        putExtra(NotificationBroadcastReceiver.KEY_ROOM_ID, roomId)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntentCompat.FLAG_IMMUTABLE
            )

    private fun buildQuickReplyIntent(
            roomId: String,
            threadId: String?,
            senderName: String?,
    ): PendingIntent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return try {
            PendingIntent.getBroadcast(
                    context,
                    clock.epochMillis().toInt(),
                    Intent(context, NotificationBroadcastReceiver::class.java).apply {
                        action = actionIds.smartReply
                        data = createIgnoredUri(roomId)
                        putExtra(NotificationBroadcastReceiver.KEY_ROOM_ID, roomId)
                        threadId?.let { putExtra(NotificationBroadcastReceiver.KEY_THREAD_ID, it) }
                    },
                    flags
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to create quick reply intent")
            null
        }
    }

    private fun buildRejectCallPendingIntent(callId: String): PendingIntent =
            PendingIntent.getBroadcast(
                    context,
                    clock.epochMillis().toInt() + 2,
                    Intent(context, CallHeadsUpActionReceiver::class.java).apply {
                        putExtra(CallHeadsUpActionReceiver.EXTRA_CALL_ID, callId)
                        putExtra(CallHeadsUpActionReceiver.EXTRA_CALL_ACTION_KEY, CallHeadsUpActionReceiver.CALL_ACTION_REJECT)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntentCompat.FLAG_IMMUTABLE
            )

    private fun getDismissSummaryPendingIntent(): PendingIntent =
            PendingIntent.getBroadcast(
                    context.applicationContext, 0,
                    Intent(context, NotificationBroadcastReceiver::class.java).apply {
                        action = actionIds.dismissSummary
                        data = createIgnoredUri("deleteSummary")
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntentCompat.FLAG_IMMUTABLE
            )

    private fun getActionText(@StringRes stringRes: Int, @AttrRes colorRes: Int): Spannable =
            SpannableString(context.getText(stringRes)).apply {
                setSpan(ForegroundColorSpan(ThemeUtils.getColor(context, colorRes)), 0, length, 0)
            }

    private fun ensureTitleNotEmpty(title: String?): CharSequence =
            if (title.isNullOrBlank()) buildMeta.applicationName else title

    private fun getBitmap(context: Context, @DrawableRes drawableRes: Int): Bitmap? {
        return try {
            val drawable = ResourcesCompat.getDrawable(context.resources, drawableRes, null) ?: return null
            val bitmap = Bitmap.createBitmap(
                    drawable.intrinsicWidth.coerceAtLeast(1),
                    drawable.intrinsicHeight.coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
            )
            Canvas(bitmap).also {
                drawable.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
                drawable.draw(it)
            }
            bitmap
        } catch (e: Exception) {
            Timber.e(e, "Failed to create bitmap from drawable")
            null
        }
    }

    fun getMatrixSdkVersion(): String = Matrix.getSdkVersion()

    // room channel create
    fun getOrCreateRoomChannel(
            context: Context,
            roomId: String,
            roomName: String,
            soundUri: Uri?,
            isCall: Boolean = false
    ): String {
        val baseChannelId = "ROOM_CHANNEL_$roomId"
        val channelId = when {
            // Call channels are silent (ringing handled by CallRingPlayerIncoming).
            // The _CALL_V2 suffix sidesteps any previously-created call channel that
            // had a sound configured — channel sound can't be mutated after creation.
            isCall -> "${baseChannelId}_CALL_V2"
            soundUri != null -> "${baseChannelId}_${soundUri.hashCode()}"
            else -> baseChannelId
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService<NotificationManager>() ?: return channelId

            // Delete old channels for this room to avoid clutter
            notificationManager.notificationChannels.forEach { channel ->
                if (channel.id.startsWith(baseChannelId) && channel.id != channelId) {
                    notificationManager.deleteNotificationChannel(channel.id)
                }
            }

            // If channel already exists, return it
            if (notificationManager.getNotificationChannel(channelId) != null) {
                return channelId
            }

            val channel = NotificationChannel(
                    channelId,
                    roomName,  // Shows in Android settings as chat name
                    NotificationManager.IMPORTANCE_HIGH
            ).apply {
                if (isCall) {
                    // Channel sound MUST stay null for call channels — ringing is owned
                    // by CallRingPlayerIncoming. Otherwise the OS would play the channel
                    // sound on top of MediaPlayer, producing two ringtones.
                    setSound(null, null)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 1000, 500, 1000)
                    setBypassDnd(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                } else if (soundUri != null) {
                    val audioAttributes = AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .build()
                    setSound(soundUri, audioAttributes)
                }
            }
            notificationManager.createNotificationChannel(channel)
        }

        return channelId
    }
}
