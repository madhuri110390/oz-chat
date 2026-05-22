/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.dialogs

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.yalantis.ucrop.UCrop
import im.vector.app.R
import im.vector.app.core.dialogs.GalleryOrCameraDialogHelper.Listener
import im.vector.app.core.extensions.insertBeforeLast
import im.vector.app.core.extensions.registerStartForActivityResult
import im.vector.app.core.resources.ColorProvider
import im.vector.app.core.utils.PERMISSIONS_FOR_TAKING_PHOTO
import im.vector.app.core.utils.checkPermissions
import im.vector.app.core.utils.onPermissionDeniedDialog
import im.vector.app.core.utils.registerForPermissionsResult
import im.vector.app.features.media.createUCropWithDefaultSettings
import im.vector.app.features.roomprofile.notifications.RoomNotificationSettingsFragment
import im.vector.lib.core.utils.timer.Clock
import im.vector.lib.multipicker.MultiPicker
import im.vector.lib.multipicker.entity.MultiPickerImageType
import im.vector.lib.strings.CommonStrings
import java.io.File

/**
 * Use to let the user choose between Camera (with permission handling) and Gallery (with single image selection),
 * then edit the image
 * [Listener.onImageReady] will be called with an uri of a square image store in the cache of the application.
 * It's up to the caller to delete the file.
 */
class GalleryOrCameraDialogHelper(
        // must implement GalleryOrCameraDialogHelper.Listener
        private val fragment: Fragment,
        private val colorProvider: ColorProvider,
        private val clock: Clock,
) {
    interface Listener {
        fun onImageReady(uri: Uri?)
        fun onRingtoneSelected(uri: Uri?, name: String) {}
    }

    private val activity
        get() = fragment.requireActivity()

    private val listener = fragment as? Listener ?: error("Fragment must implement GalleryOrCameraDialogHelper.Listener")

    private val takePhotoPermissionActivityResultLauncher = fragment.registerForPermissionsResult { allGranted, deniedPermanently ->
        if (allGranted) {
            doOpenCamera()
        } else if (deniedPermanently) {
            activity.onPermissionDeniedDialog(CommonStrings.denied_permission_camera)
        }
    }

    private val takePhotoActivityResultLauncher = fragment.registerStartForActivityResult { activityResult ->
        if (activityResult.resultCode == Activity.RESULT_OK) {
            avatarCameraUri?.let { uri ->
                MultiPicker.get(MultiPicker.CAMERA)
                        .getTakenPhoto(activity, uri)
                        ?.let { startUCrop(it) }
            }
        }
    }

    private val pickImageActivityResultLauncher = fragment.registerStartForActivityResult { activityResult ->
        if (activityResult.resultCode == Activity.RESULT_OK) {
            MultiPicker
                    .get(MultiPicker.IMAGE)
                    .getSelectedFiles(activity, activityResult.data)
                    .firstOrNull()
                    ?.let { startUCrop(it) }
        }
    }

    private val uCropActivityResultLauncher = fragment.registerStartForActivityResult { activityResult ->
        if (activityResult.resultCode == Activity.RESULT_OK) {
            activityResult.data?.let { listener.onImageReady(UCrop.getOutput(it)) }
        }
    }

    private fun startUCrop(image: MultiPickerImageType) {
        val destinationFile = File(activity.cacheDir, image.displayName.insertBeforeLast("_e_${clock.epochMillis()}"))
        val uri = image.contentUri
        createUCropWithDefaultSettings(colorProvider, uri, destinationFile.toUri(), fragment.getString(CommonStrings.rotate_and_crop_screen_title))
                .withAspectRatio(1f, 1f)
                .getIntent(activity)
                .let { uCropActivityResultLauncher.launch(it) }
    }

    private enum class Type {
        Camera,
        Gallery,
        NotificationTone
    }

    private var dynamicTitle: String? = null
    private var currentToneUri: Uri? = null

    fun show(dynamicTitle: String? = null, currentToneUri: Uri? = null) {
        this.dynamicTitle = dynamicTitle
        this.currentToneUri = currentToneUri
        MaterialAlertDialogBuilder(activity)
                .setTitle(CommonStrings.attachment_type_dialog_title)
                .setItems(
                        arrayOf(
                                fragment.getString(CommonStrings.attachment_type_camera),
                                fragment.getString(CommonStrings.attachment_type_gallery),
                                "Notification Tone"
                        )
                ) { _, which ->
                    onAvatarTypeSelected(
                            when (which) {
                                0 -> Type.Camera
                                1 -> Type.Gallery
                                else -> Type.NotificationTone
                            }
                    )
                }
                .setPositiveButton(CommonStrings.action_cancel, null)
                .show()
    }
    private fun openRingtonePicker() {
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, dynamicTitle ?: "Notification tone")
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            if (currentToneUri != null) {
                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, currentToneUri)
            }
        }

        ringtonePickerLauncher.launch(intent)
    }
    private val ringtonePickerLauncher =
            fragment.registerStartForActivityResult { result ->

                if (result.resultCode == Activity.RESULT_OK) {
                    val uri = if (android.os.Build.VERSION.SDK_INT >= 33) {
                        result.data?.getParcelableExtra(
                                RingtoneManager.EXTRA_RINGTONE_PICKED_URI,
                                Uri::class.java
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                    }

                    val name = when {
                        uri == null -> "Default"
                        uri == Uri.EMPTY -> "Silent"
                        else -> {
                            val ringtone = RingtoneManager.getRingtone(activity, uri)
                            ringtone?.getTitle(activity) ?: "Custom"
                        }
                    }
                    listener.onRingtoneSelected(uri, name)
                }
            }
    private fun onAvatarTypeSelected(type: Type) {
        when (type) {
            Type.Camera ->
                if (checkPermissions(PERMISSIONS_FOR_TAKING_PHOTO, activity, takePhotoPermissionActivityResultLauncher)) {
                    doOpenCamera()
                }
            Type.Gallery ->
                MultiPicker.get(MultiPicker.IMAGE).single().startWith(pickImageActivityResultLauncher)

            Type.NotificationTone -> {
                openRingtonePicker()
            }
        }
    }

    private var avatarCameraUri: Uri? = null
    private fun doOpenCamera() {
        avatarCameraUri = MultiPicker.get(MultiPicker.CAMERA).startWithExpectingFile(activity, takePhotoActivityResultLauncher)
    }
}
