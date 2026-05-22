/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */
package im.vector.app.features.home.room.detail.timeline.item

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Resources
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import com.google.android.material.shape.MaterialShapeDrawable
import im.vector.app.R
import im.vector.app.core.epoxy.ClickListener
import im.vector.app.core.epoxy.onClick
import im.vector.app.core.extensions.setLeftDrawable
import im.vector.app.core.utils.DateUtilsExt.getRelativeTimeString
import im.vector.app.features.displayname.getBestName

import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.room.detail.RoomDetailAction
import im.vector.app.features.home.room.detail.timeline.MessageColorProvider
import im.vector.app.features.home.room.detail.timeline.TimelineEventController
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.strings.CommonPlurals
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.util.MatrixItem
import timber.log.Timber

@EpoxyModelClass
abstract class CallTileTimelineItem : AbsBaseMessageItem<CallTileTimelineItem.Holder>(R.layout.item_timeline_event_base_state) {

    override val baseAttributes: AbsBaseMessageItem.Attributes
        get() = attributes

    override fun isCacheable() = false

    @EpoxyAttribute
    lateinit var attributes: Attributes

    override fun getViewStubId() = STUB_ID
    private fun MatrixItem.getUsernameFromMatrixId(): String {
        return if (id.startsWith("@") && id.contains(":")) {
            id.substringAfter("@").substringBefore(":")
        } else {
            getBestName()
        }
    }
    private fun MatrixItem.withUsernameAsDisplayName(): MatrixItem {
        val username = getUsernameFromMatrixId()
        return when (this) {
            is MatrixItem.UserItem -> copy(displayName = username)
            else -> this
        }
    }
    override fun bind(holder: Holder) {
        super.bind(holder)
        holder.endGuideline.updateLayoutParams<RelativeLayout.LayoutParams> {
            this.marginEnd = leftGuideline
        }
/*        holder.creatorNameView.text = attributes.userOfInterest.getBestName()
        attributes.avatarRenderer.render(attributes.userOfInterest, holder.creatorAvatarView)*/
        // 1. Set name and avatar based on sender
//        if (attributes.isCallPlacedByMe) {
//            // Construct full MatrixItem for current user
//            val myMatrixItem = attributes.myUser
//            holder.creatorNameView.text = myMatrixItem.getBestName()
//            attributes.avatarRenderer.render(myMatrixItem, holder.creatorAvatarView)
//        } else {
//            // For the other participant
//            holder.creatorNameView.text = attributes.userOfInterest.getBestName()
//            attributes.avatarRenderer.render(attributes.userOfInterest, holder.creatorAvatarView)
//        }

//        if (attributes.isCallPlacedByMe) {
//            val myMatrixItem = attributes.myUser
//            holder.creatorNameView.text = myMatrixItem.getUsernameFromMatrixId() // ← fixed
//            attributes.avatarRenderer.render(myMatrixItem, holder.creatorAvatarView)
//        } else {
//            holder.creatorNameView.text = attributes.userOfInterest.getUsernameFromMatrixId() // ← fixed
//            attributes.avatarRenderer.render(attributes.userOfInterest, holder.creatorAvatarView)
//        }
        if (attributes.isCallPlacedByMe) {
            val myMatrixItem = attributes.myUser.withUsernameAsDisplayName() // ← changed
            holder.creatorNameView.text = myMatrixItem.getUsernameFromMatrixId()
            attributes.avatarRenderer.render(myMatrixItem, holder.creatorAvatarView) // ← now correct initial
        } else {
            val otherMatrixItem = attributes.userOfInterest.withUsernameAsDisplayName() // ← changed
            holder.creatorNameView.text = otherMatrixItem.getUsernameFromMatrixId()
            attributes.avatarRenderer.render(otherMatrixItem, holder.creatorAvatarView) // ← now correct initial
        }
       // 2. Apply themed bubble background using MaterialShapeDrawable
        val bubbleView = holder.view.findViewById<View>(R.id.viewStubContainer)
        val bubbleParams = bubbleView.layoutParams as ViewGroup.MarginLayoutParams

        val isMine = attributes.isCallPlacedByMe
        bubbleParams.marginStart = if (isMine) 64 else 0
        bubbleParams.marginEnd = if (isMine) 0 else 64
        bubbleView.layoutParams = bubbleParams
        val backgroundColorAttr = if (isMine) {
            im.vector.lib.ui.styles.R.attr.vctr_message_bubble_outbound
        } else {
            im.vector.lib.ui.styles.R.attr.vctr_message_bubble_inbound
        }
        val backgroundColor = ThemeUtils.getColor(holder.view.context, backgroundColorAttr)
     //   holder.acceptView.backgroundTintList = ColorStateList.valueOf(backgroundColor)
// Create a MaterialShapeDrawable with rounded corners
        val shapeDrawable = MaterialShapeDrawable().apply {
            fillColor = ColorStateList.valueOf(backgroundColor)
            shapeAppearanceModel = com.google.android.material.shape.ShapeAppearanceModel().withCornerSize(16f) // or use styles
        }
// Assign the shape drawable as background while preserving ripple (optional)
        bubbleView.background = shapeDrawable
        bubbleView.clipToOutline = true
        bubbleView.outlineProvider = ViewOutlineProvider.BACKGROUND

        when (attributes.callStatus) {
            CallStatus.INVITED -> renderInvitedStatus(holder)
            CallStatus.IN_CALL -> renderInCallStatus(holder)
            CallStatus.REJECTED -> renderRejectedStatus(holder)
            CallStatus.ENDED -> renderEndedStatus(holder)
            CallStatus.MISSED -> renderMissedStatus(holder)
        }
        renderSendState(holder.view, null, holder.failedToSendIndicator)
        holder.timeView.isVisible = attributes.informationData.messageLayout.showTimestamp
        holder.timeView.text = attributes.informationData.time
    }

    private fun renderMissedStatus(holder: Holder) {
        val count = attributes.groupedCallCount ?: 1
        val sentByMe = attributes.informationData.sentByMe
        val statusText: String
        val statusIcon: Int

        if (sentByMe) {
            // I placed the call, they didn’t answer
            statusText = if (count > 1) {
                holder.resources.getQuantityString(
                        CommonPlurals.call_tile_no_answer_plural,
                        count,
                        count
                )
            } else {
                holder.resources.getString(CommonStrings.call_tile_no_answer)
            }

            statusIcon = if (attributes.callKind.isVoiceCall) {
                R.drawable.ic_voice_call_declined
            } else {
                R.drawable.ic_video_call_declined
            }

        } else {
            // Opponent called me and I missed
            statusText = if (attributes.callKind.isVoiceCall) {
                if (count > 1) {
                    holder.resources.getQuantityString(
                            CommonPlurals.call_tile_voice_missed_plural,
                            count,
                            count
                    )
                } else {
                    holder.resources.getString(CommonStrings.call_tile_voice_missed)
                }
            } else {
                if (count > 1) {
                    holder.resources.getQuantityString(
                            CommonPlurals.call_tile_video_missed_plural,
                            count,
                            count
                    )
                } else {
                    holder.resources.getString(CommonStrings.call_tile_video_missed)
                }
            }

            statusIcon = if (attributes.callKind.isVoiceCall) {
                R.drawable.ic_missed_voice_call_small
            } else {
                R.drawable.ic_missed_video_call_small
            }
        }

        holder.statusView.setStatus(statusText, statusIcon)
        holder.acceptRejectViewGroup.isVisible = true
        holder.acceptView.setText(CommonStrings.call_tile_call_back)
        holder.acceptView.setLeftDrawable(
                attributes.callKind.icon,
                im.vector.lib.ui.styles.R.attr.vctr_content_primary
        )
        holder.acceptView.onClick {
            showBatteryDialog(holder.view.context) {
                val callbackAction = RoomDetailAction.StartCall(attributes.callKind == CallKind.VIDEO)
                attributes.callback?.onTimelineItemAction(callbackAction)
            }
        }
        holder.rejectView.isVisible = false
    }
    private fun showBatteryDialog(context: Context, onContinue: () -> Unit) {
        AlertDialog.Builder(context)
                .setTitle("Improve Call Reliability")
                .setMessage("To receive calls reliably, please allow battery optimization to be turned off.")
                .setPositiveButton("Continue") { _, _ ->
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    intent.data = Uri.parse("package:${context.packageName}")
                    context.startActivity(intent)

                    onContinue() // still start call
                }
                .setNegativeButton("Skip") { _, _ ->
                    onContinue() // still start call
                }
                .show()
    }
    private fun renderEndedStatus(holder: Holder) {
        holder.acceptRejectViewGroup.isVisible = false
        when (attributes.callKind) {
            CallKind.VIDEO -> {
                val endCallStatus = holder.resources.getString(CommonStrings.call_tile_video_call_has_ended, attributes.formattedDuration)
                holder.statusView.setStatus(endCallStatus)
            }
            CallKind.AUDIO -> {
                val endCallStatus = holder.resources.getString(CommonStrings.call_tile_voice_call_has_ended, attributes.formattedDuration)
                holder.statusView.setStatus(endCallStatus)
            }
            CallKind.CONFERENCE -> {
                holder.statusView.setStatus(CommonStrings.call_tile_ended)
            }
        }
    }

    private fun renderRejectedStatus(holder: Holder) {
        val count = attributes.groupedCallCount ?: 1
        val sentByMe = attributes.informationData.sentByMe
        val statusText: String
        val statusIcon: Int
        if (sentByMe) {
            // I declined their call
            statusText = if (attributes.callKind.isVoiceCall) {
                if (count > 1) {
                    holder.resources.getQuantityString(
                            CommonPlurals.call_tile_voice_declined_plural,
                            count,
                            count
                    )
                } else {
                    holder.resources.getString(CommonStrings.call_tile_voice_declined)
                }
            } else {
                if (count > 1) {
                    holder.resources.getQuantityString(
                            CommonPlurals.call_tile_video_declined_plural,
                            count,
                            count
                    )
                } else {
                    holder.resources.getString(CommonStrings.call_tile_video_declined)
                }
            }

            statusIcon = if (attributes.callKind.isVoiceCall) {
                R.drawable.ic_voice_call_declined
            } else {
                R.drawable.ic_video_call_declined
            }

        } else {
            // They declined my call → "No answer"
            statusText = if (count > 1) {
                holder.resources.getQuantityString(
                        CommonPlurals.call_tile_no_answer_plural,
                        count,
                        count
                )
            } else {
                holder.resources.getString(CommonStrings.call_tile_no_answer)
            }

            statusIcon = if (attributes.callKind.isVoiceCall) {
                R.drawable.ic_voice_call_declined
            } else {
                R.drawable.ic_video_call_declined
            }
        }
        holder.statusView.setStatus(statusText, statusIcon)
        holder.acceptRejectViewGroup.isVisible = true
        holder.acceptView.setText(CommonStrings.call_tile_call_back)
        holder.acceptView.setLeftDrawable(
                attributes.callKind.icon,
                im.vector.lib.ui.styles.R.attr.vctr_content_primary
        )
        holder.acceptView.onClick {
            val callbackAction = RoomDetailAction.StartCall(attributes.callKind == CallKind.VIDEO)
            attributes.callback?.onTimelineItemAction(callbackAction)
        }
        holder.rejectView.isVisible = false
    }


    private fun renderInCallStatus(holder: Holder) {
        holder.acceptRejectViewGroup.isVisible = true
        holder.acceptView.isVisible = false
        when {
            attributes.callKind == CallKind.CONFERENCE -> {
                holder.rejectView.isVisible = true
                holder.rejectView.setText(CommonStrings.action_leave)
                holder.rejectView.setLeftDrawable(R.drawable.ic_call_hangup, com.google.android.material.R.attr.colorOnPrimary)
                holder.rejectView.onClick {
                    attributes.callback?.onTimelineItemAction(RoomDetailAction.LeaveJitsiCall)
                }
            }
            attributes.isStillActive -> {
                holder.rejectView.isVisible = true
                holder.rejectView.setText(CommonStrings.call_notification_hangup)
                holder.rejectView.setLeftDrawable(R.drawable.ic_call_hangup, com.google.android.material.R.attr.colorOnPrimary)
                holder.rejectView.onClick {
                    attributes.callback?.onTimelineItemAction(RoomDetailAction.EndCall)
                }
            }
            else -> {
                holder.acceptRejectViewGroup.isVisible = false
            }
        }
        if (attributes.callKind.isVoiceCall) {
            holder.statusView.setStatus(CommonStrings.call_tile_voice_active)
        } else {
            holder.statusView.setStatus(CommonStrings.call_tile_video_active)
        }
    }

    private fun renderInvitedStatus(holder: Holder) {
        when {
            attributes.callKind == CallKind.CONFERENCE -> {
                holder.acceptRejectViewGroup.isVisible = true
                holder.acceptView.onClick {
                    attributes.callback?.onTimelineItemAction(RoomDetailAction.JoinJitsiCall)
                }
                holder.acceptView.isVisible = true
                holder.rejectView.isVisible = false
                holder.acceptView.setText(CommonStrings.action_join)
                holder.acceptView.setLeftDrawable(R.drawable.ic_call_video_small, com.google.android.material.R.attr.colorOnPrimary)
            }
            !attributes.informationData.sentByMe && attributes.isStillActive -> {
                holder.acceptRejectViewGroup.isVisible = true
                holder.acceptView.isVisible = true
                holder.rejectView.isVisible = true
                holder.acceptView.onClick {
                    attributes.callback?.onTimelineItemAction(RoomDetailAction.AcceptCall(callId = attributes.callId))
                }
                holder.rejectView.setLeftDrawable(R.drawable.ic_call_hangup, com.google.android.material.R.attr.colorOnPrimary)
                holder.rejectView.onClick {
                    attributes.callback?.onTimelineItemAction(RoomDetailAction.EndCall)
                }
                if (attributes.callKind == CallKind.AUDIO) {
                    holder.rejectView.setText(CommonStrings.call_notification_reject)
                    holder.acceptView.setText(CommonStrings.call_notification_answer)
                    holder.acceptView.setLeftDrawable(R.drawable.ic_call_audio_small, com.google.android.material.R.attr.colorOnPrimary)
                } else if (attributes.callKind == CallKind.VIDEO) {
                    holder.rejectView.setText(CommonStrings.call_notification_reject)
                    holder.acceptView.setText(CommonStrings.call_notification_answer)
                    holder.acceptView.setLeftDrawable(R.drawable.ic_call_video_small, com.google.android.material.R.attr.colorOnPrimary)
                }
            }
            else -> {
                holder.acceptRejectViewGroup.isVisible = false
            }
        }
        when {
            // Invite state for conference should show as InCallStatus
            attributes.callKind == CallKind.CONFERENCE -> {
                holder.statusView.setStatus(CommonStrings.call_tile_video_active)
            }
            attributes.informationData.sentByMe -> {
                holder.statusView.setStatus(CommonStrings.call_ringing)
            }
            attributes.callKind.isVoiceCall -> {
                holder.statusView.setStatus(CommonStrings.call_tile_voice_incoming)
            }
            else -> {
                holder.statusView.setStatus(CommonStrings.call_tile_video_incoming)
            }
        }
    }

    private fun TextView.setStatus(@StringRes statusRes: Int, @DrawableRes drawableRes: Int? = null) {
        val status = resources.getString(statusRes)
        setStatus(status, drawableRes)
    }

    private fun TextView.setStatus(status: String, @DrawableRes drawableRes: Int? = null) {
        setLeftDrawable(drawableRes ?: attributes.callKind.icon)
        text = status
    }

    class Holder : AbsBaseMessageItem.Holder(STUB_ID) {
        val acceptView by bind<Button>(R.id.itemCallAcceptView)
        val rejectView by bind<Button>(R.id.itemCallRejectView)
        val acceptRejectViewGroup by bind<ViewGroup>(R.id.itemCallAcceptRejectViewGroup)
        val creatorAvatarView by bind<ImageView>(R.id.itemCallCreatorAvatar)
        val creatorNameView by bind<TextView>(R.id.itemCallCreatorNameTextView)
        val statusView by bind<TextView>(R.id.itemCallStatusTextView)
        val endGuideline by bind<View>(R.id.messageEndGuideline)
        val failedToSendIndicator by bind<ImageView>(R.id.messageFailToSendIndicator)
        val timeView by bind<TextView>(R.id.callTimeView)


        val resources: Resources
            get() = view.context.resources
    }

    companion object {
        private val STUB_ID = R.id.messageCallStub
    }

    data class Attributes(
            val callId: String,
            val callKind: CallKind,
            val callStatus: CallStatus,
            val userOfInterest: MatrixItem,
            val groupedCallCount: Int? = null,
            val myUser: MatrixItem,
            val isStillActive: Boolean,
            val formattedDuration: String,
            val isCallPlacedByMe: Boolean = false,
            val callback: TimelineEventController.Callback? = null,
            override val informationData: MessageInformationData,
            override val avatarRenderer: AvatarRenderer,
            override val messageColorProvider: MessageColorProvider,
            override val itemLongClickListener: View.OnLongClickListener? = null,
            override val itemClickListener: ClickListener? = null,
            override val reactionPillCallback: TimelineEventController.ReactionPillCallback? = null,
            override val readReceiptsCallback: TimelineEventController.ReadReceiptsCallback? = null,
            override val reactionsSummaryEvents: ReactionsSummaryEvents? = null
    ) : AbsBaseMessageItem.Attributes

    enum class CallKind(@DrawableRes val icon: Int, @StringRes val title: Int) {
        VIDEO(R.drawable.ic_call_video_small, CommonStrings.action_video_call),
        AUDIO(R.drawable.ic_call_audio_small, CommonStrings.action_voice_call),
        CONFERENCE(R.drawable.ic_call_video_small, CommonStrings.action_video_call);

        val isVoiceCall
            get() = this == AUDIO

        val isVideoCall
            get() = this == VIDEO
    }

    enum class CallStatus {
        INVITED,
        IN_CALL,
        REJECTED,
        MISSED,
        ENDED;

        fun isActive() = this == INVITED || this == IN_CALL
    }
}
