/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.item

import android.content.res.ColorStateList
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import com.airbnb.epoxy.EpoxyAttribute
import com.airbnb.epoxy.EpoxyModelClass
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.google.android.material.card.MaterialCardView
import im.vector.app.R
import im.vector.app.core.epoxy.ClickListener
import im.vector.app.core.epoxy.onClick
import im.vector.app.core.files.LocalFilesHelper
import com.bumptech.glide.Glide
import im.vector.app.core.utils.DimensionConverter
import im.vector.app.features.home.room.detail.timeline.helper.ContentUploadStateTrackerBinder
import im.vector.app.features.home.room.detail.timeline.style.TimelineMessageLayout
import im.vector.app.features.home.room.detail.timeline.style.granularRoundedCorners
import im.vector.app.features.media.ImageContentRenderer
import im.vector.app.features.themes.ThemeUtils
import org.matrix.android.sdk.api.session.room.model.message.MessageType

@EpoxyModelClass
abstract class MessageImageVideoItem : AbsMessageItem<MessageImageVideoItem.Holder>() {
    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var itemDoubleTapListener: ClickListener? = null
    @EpoxyAttribute
    lateinit var mediaData: ImageContentRenderer.Data
    @EpoxyAttribute
    var playbackSpeed: Float = 1.0f
    var tapJob: Runnable? = null
    @EpoxyAttribute
    var playable: Boolean = false
    var lastTapTime = 0L
    @EpoxyAttribute
    var mode = ImageContentRenderer.Mode.THUMBNAIL

    @EpoxyAttribute(EpoxyAttribute.Option.DoNotHash)
    var clickListener: ClickListener? = null

    @EpoxyAttribute
    lateinit var imageContentRenderer: ImageContentRenderer

    @EpoxyAttribute
    lateinit var contentUploadStateTrackerBinder: ContentUploadStateTrackerBinder

    override fun bind(holder: Holder) {
        super.bind(holder)
        holder.imageView.visibility = View.VISIBLE
        val messageLayout = baseAttributes.informationData.messageLayout
        val dimensionConverter = DimensionConverter(holder.view.resources)
        val imageCornerTransformation = if (messageLayout is TimelineMessageLayout.Bubble) {
            messageLayout.cornersRadius.granularRoundedCorners()
        } else {
            RoundedCorners(dimensionConverter.dpToPx(8))
        }
        imageContentRenderer.render(mediaData, mode, holder.imageView, imageCornerTransformation)
        if (!attributes.informationData.sendState.hasFailed()) {
            contentUploadStateTrackerBinder.bind(
                    attributes.informationData.eventId,
                    LocalFilesHelper(holder.view.context).isLocalFile(mediaData.url),
                    holder.progressLayout
            )
        } else {
            holder.progressLayout.isVisible = false
        }
        holder.imageView.onClick(clickListener)
        holder.imageView.setOnLongClickListener(attributes.itemLongClickListener)
        ViewCompat.setTransitionName(holder.imageView, "imagePreview_${id()}")
       // holder.mediaContentView.onClick(attributes.itemClickListener)
        holder.mediaContentView.setOnClickListener {
            if (playable) {
                holder.playContentView.visibility = View.GONE
                attributes.itemClickListener?.invoke(holder.mediaContentView)
            } else {
                attributes.itemClickListener?.invoke(holder.mediaContentView)
            }
        }
        holder.mediaContentView.setOnLongClickListener(attributes.itemLongClickListener)

        val isImageMessage = attributes.informationData.messageType in listOf(MessageType.MSGTYPE_IMAGE, MessageType.MSGTYPE_STICKER_LOCAL)
        val autoplayAnimatedImages = attributes.autoplayAnimatedImages

//        holder.playContentView.setOnClickListener {
//
//
//            holder.playContentView.visibility = View.GONE
//            clickListener?.invoke(holder.imageView)
//
//
//        }



        holder.playContentView.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastTapTime < 300L) {
                // Confirmed double-tap: cancel the pending single-tap action
                tapJob?.let { holder.playContentView.removeCallbacks(it) }
                tapJob = null
               // attributes.itemDoubleTapListener?.invoke(holder.playContentView)
            } else {
                // Delay single-tap to allow a second tap to cancel it
                tapJob = Runnable {
                    holder.playContentView.visibility = View.GONE
                    clickListener?.invoke(holder.imageView)
                }.also { job ->
                    holder.playContentView.postDelayed(job, 300L)
                }
            }
            lastTapTime = now
        }

        holder.playContentView.tag = playbackSpeed

        val speed = (holder.playContentView.tag as? Float) ?: 1.0f


        holder.playContentView.visibility = if (playable && isImageMessage && autoplayAnimatedImages) {
            View.GONE
        } else if (playable) {
            View.VISIBLE
        } else {
            View.GONE
        }

// Single declaration — duplicates removed
        val isMine = attributes.informationData.sentByMe
        val backgroundAttr = if (isMine) {
            im.vector.lib.ui.styles.R.attr.vctr_message_bubble_outbound
        } else {
            im.vector.lib.ui.styles.R.attr.vctr_message_bubble_inbound
        }
        val backgroundColor = ThemeUtils.getColor(holder.view.context, backgroundAttr)

// Single stroke block — bare strokeColor line at bottom removed
        with(holder.imageCard) {
            strokeColor = backgroundColor

        }

    }

    override fun unbind(holder: Holder) {
        Glide.with(holder.view.context.applicationContext).clear(holder.imageView)
        imageContentRenderer.clear(holder.imageView)
        holder.imageView.visibility = View.VISIBLE
        holder.playContentView.visibility = View.GONE
        contentUploadStateTrackerBinder.unbind(attributes.informationData.eventId)
        holder.imageView.setOnClickListener(null)
        holder.imageView.setOnLongClickListener(null)
        super.unbind(holder)
    }

    override fun getViewStubId() = STUB_ID

    class Holder : AbsMessageItem.Holder(STUB_ID) {
        val progressLayout by bind<ViewGroup>(R.id.messageMediaUploadProgressLayout)
        val imageView by bind<ImageView>(R.id.messageThumbnailView)
        val playContentView by bind<ImageView>(R.id.messageMediaPlayView)
        val mediaContentView by bind<ViewGroup>(R.id.messageContentMedia)
        val imageCard by bind<MaterialCardView>(R.id.messageImageCard)


    }

    companion object {
        private val STUB_ID = R.id.messageContentMediaStub
    }
}
