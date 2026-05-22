/*
 * Copyright (c) 2023 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.app.features.invite

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R

import im.vector.app.core.platform.VectorBaseBottomSheetDialogFragment
import im.vector.app.core.utils.copyToClipboard
import im.vector.app.core.utils.startSharePlainTextIntent
import im.vector.app.core.utils.toast
import im.vector.app.databinding.BottomSheetInviteFriendsBinding
import im.vector.lib.strings.CommonStrings
import javax.inject.Inject

@AndroidEntryPoint
class InviteFriendsBottomSheet : VectorBaseBottomSheetDialogFragment<BottomSheetInviteFriendsBinding>() {

    @Inject lateinit var permalinkFactory: im.vector.app.features.permalink.PermalinkFactory
    @Inject lateinit var session: org.matrix.android.sdk.api.session.Session

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): BottomSheetInviteFriendsBinding {
        return BottomSheetInviteFriendsBinding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val username = session.myUserId.removePrefix("@").substringBefore(":")
        val shareText = getString(R.string.invite_friends_text, username)

        views.inviteWhatsAppButton.debouncedClicks {
            shareToPlatform("com.whatsapp", shareText)
        }

        views.inviteInstagramButton.debouncedClicks {
            // Instagram doesn't support direct text sharing as well as WhatsApp, but we can try
            shareToPlatform("com.instagram.android", shareText)
        }

        views.inviteFacebookButton.debouncedClicks {
            shareToPlatform("com.facebook.katana", shareText)
        }

        views.inviteContactsButton.debouncedClicks {
            startSharePlainTextIntent(
                    context = requireContext(),
                    activityResultLauncher = null,
                    chooserTitle = getString(CommonStrings.invite_friends),
                    text = shareText,
                    extraTitle = getString(CommonStrings.invite_friends_rich_title)
            )
        }
    }

    private fun shareToPlatform(packageName: String, text: String) {
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, text)
            setPackage(packageName)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // App not installed, fallback to generic share
           // requireContext().toast(getString(CommonStrings.error_common))
            startSharePlainTextIntent(
                    context = requireContext(),
                    activityResultLauncher = null,
                    chooserTitle = getString(CommonStrings.invite_friends),
                    text = text
            )
        }
    }

    companion object {
        fun show(fragmentManager: androidx.fragment.app.FragmentManager) {
            InviteFriendsBottomSheet().show(fragmentManager, "InviteFriendsBottomSheet")
        }
    }
}
