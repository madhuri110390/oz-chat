/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roomprofile.notifications

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.view.isVisible
import com.airbnb.mvrx.fragmentViewModel
import com.airbnb.mvrx.withState
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.core.dialogs.GalleryOrCameraDialogHelper
import im.vector.app.core.extensions.cleanup
import im.vector.app.core.extensions.configureWith
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.databinding.FragmentRoomSettingGenericBinding
import im.vector.app.features.analytics.plan.MobileScreen
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.notifications.NotificationUtils
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.settings.VectorSettingsActivity
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.session.room.notification.RoomNotificationState
import org.matrix.android.sdk.api.util.toMatrixItem
import javax.inject.Inject

@AndroidEntryPoint
class RoomNotificationSettingsFragment :
        VectorBaseFragment<FragmentRoomSettingGenericBinding>(),
        RoomNotificationSettingsController.Callback ,
        GalleryOrCameraDialogHelper.Listener{

    @Inject lateinit var viewModelFactory: RoomNotificationSettingsViewModel.Factory
    @Inject lateinit var roomNotificationSettingsController: RoomNotificationSettingsController
    @Inject lateinit var avatarRenderer: AvatarRenderer
    @Inject lateinit var vectorPreferences: VectorPreferences
    @Inject lateinit var notificationUtils: NotificationUtils
    private val viewModel: RoomNotificationSettingsViewModel by fragmentViewModel()
    private val roomId: String get() = withState(viewModel) { it.roomId }
    private val roomName: String get() = withState(viewModel) { it.roomSummary()?.displayName ?: it.roomId }
    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentRoomSettingGenericBinding {
        return FragmentRoomSettingGenericBinding.inflate(inflater, container, false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        analyticsScreenName = MobileScreen.ScreenName.RoomNotifications
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar(views.roomSettingsToolbar)
                .allowBack()
        roomNotificationSettingsController.callback = this
        views.roomSettingsRecyclerView.configureWith(roomNotificationSettingsController, hasFixedSize = true)
        setupWaitingView()
        observeViewEvents()
        

    }

    override fun onDestroyView() {
        views.roomSettingsRecyclerView.cleanup()
        roomNotificationSettingsController.callback = null
        super.onDestroyView()
    }

    private fun setupWaitingView() {
        views.waitingView.waitingStatusText.setText(CommonStrings.please_wait)
        views.waitingView.waitingStatusText.isVisible = true
    }

    private fun observeViewEvents() {
        viewModel.observeViewEvents {
            when (it) {
                is RoomNotificationSettingsViewEvents.Failure -> displayErrorDialog(it.throwable)
            }
        }
    }
    override fun onRingtoneClicked() {
        openRingtonePicker()
    }
    override fun invalidate() {
        withState(viewModel) { viewState ->
            views.waitingView.root.isVisible = viewState.isLoading
            renderRoomSummary(viewState)

            val currentTone = vectorPreferences.getRoomNotificationTone(viewState.roomId)
            val name = updateRingtoneSummary(currentTone)
            roomNotificationSettingsController.setToneName(name)
            roomNotificationSettingsController.setData(viewState)
        }
    }
    override fun didSelectRoomNotificationState(roomNotificationState: RoomNotificationState) {
        viewModel.handle(RoomNotificationSettingsAction.SelectNotificationState(roomNotificationState))
    }

    override fun didSelectAccountSettingsLink() {
        navigator.openSettings(requireContext(), VectorSettingsActivity.EXTRA_DIRECT_ACCESS_NOTIFICATIONS)
    }

    private fun renderRoomSummary(state: RoomNotificationSettingsViewState) {
        state.roomSummary()?.let {
            val userId = it.directUserId ?: it.roomId
            val username = userId.removePrefix("@").removePrefix("!").removePrefix("#").substringBefore(":")
            views.roomSettingsToolbarTitleView.text = username
            avatarRenderer.render(it.toMatrixItem(), views.roomSettingsToolbarAvatarImageView)
            views.roomSettingsDecorationToolbarAvatarImageView.render(it.roomEncryptionTrustLevel)
        }
    }
    // Add ringtone picker button in your fragment

    private val ringtoneLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        result.data?.getParcelableExtra(
                                RingtoneManager.EXTRA_RINGTONE_PICKED_URI,
                                Uri::class.java
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                    }

                    saveAndApplyRingtone(uri)
                }
            }

    private fun openRingtonePicker() {
        val currentTone = vectorPreferences.getRoomNotificationTone(roomId)
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
//            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
//            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Select Notification Tone")
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)  // ← ringtones only
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Select Call Ringtone")
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, currentTone)
        }
        ringtoneLauncher.launch(intent)
    }

    private fun saveAndApplyRingtone(uri: Uri?) {
        //  Delete old channel first
        val notificationManager =
                requireContext().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
//        notificationManager.deleteNotificationChannel("ROOM_CHANNEL_$roomId")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val baseId = "ROOM_CHANNEL_$roomId"
            notificationManager.notificationChannels
                    .filter { it.id.startsWith(baseId) }
                    .forEach { notificationManager.deleteNotificationChannel(it.id) }
        }
        vectorPreferences.setRoomNotificationTone(roomId, uri)
        notificationUtils.getOrCreateRoomChannel(requireContext(), roomId, roomName, uri)
        // Then save new tone

        // Then recreate channel with new ton
        updateRingtoneSummary(uri)
    }

    private fun updateRingtoneSummary(uri: Uri?): String {
        return when {
            uri == null -> "Default"
            uri == Uri.EMPTY -> "Silent"
            else -> RingtoneManager.getRingtone(requireContext(), uri)
                    ?.getTitle(requireContext()) ?: "Custom"
        }
    }

    override fun onImageReady(uri: Uri?) {
        // Do nothing or handle image if needed
    }

    override fun onRingtoneSelected(uri: Uri?, name: String) {
        saveAndApplyRingtone(uri)
    }
}
