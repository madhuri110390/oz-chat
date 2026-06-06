/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.item

import android.content.res.ColorStateList
import android.os.Build
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.RequiresApi
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
    private var hideControlsRunnable: Runnable? = null
    var isPlaying: Boolean = false
    @RequiresApi(Build.VERSION_CODES.M)
    override fun bind(holder: Holder) {
        super.bind(holder)
        holder.imageView.clearColorFilter()
        holder.imageView.alpha = 1f
        holder.imageView.visibility = View.VISIBLE
        val messageLayout = baseAttributes.informationData.messageLayout
        val dimensionConverter = DimensionConverter(holder.view.resources)
//        holder.mediaContentView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
//        holder.imageCard.setCardBackgroundColor(android.graphics.Color.TRANSPARENT)
        val imageCornerTransformation = if (messageLayout is TimelineMessageLayout.Bubble) {
            messageLayout.cornersRadius.granularRoundedCorners()
        } else {
            RoundedCorners(dimensionConverter.dpToPx(8))
        }
        imageContentRenderer.render(mediaData, mode, holder.imageView, imageCornerTransformation)


//        if (!attributes.informationData.sendState.hasFailed()) {
//            contentUploadStateTrackerBinder.bind(
//                    attributes.informationData.eventId,
//                    LocalFilesHelper(holder.view.context).isLocalFile(mediaData.url),
//                  //  holder.progressLayout
//            )
//        } else {
//           // holder.progressLayout.isVisible = false
//        }
        holder.imageView.onClick(clickListener)
        holder.imageView.setOnLongClickListener(attributes.itemLongClickListener)
        ViewCompat.setTransitionName(holder.imageView, "imagePreview_${id()}")
        holder.mediaContentView.setOnLongClickListener(attributes.itemLongClickListener)

        val isImageMessage = attributes.informationData.messageType in listOf(
                MessageType.MSGTYPE_IMAGE, MessageType.MSGTYPE_STICKER_LOCAL
        )
        val autoplayAnimatedImages = attributes.autoplayAnimatedImages

        // Initial play button visibility
        holder.playContentView.visibility = when {
            isPlaying -> View.GONE                                       // already playing, survive rotation
            playable && isImageMessage && autoplayAnimatedImages -> View.GONE
            playable -> View.VISIBLE
            else -> View.GONE
        }
        holder.playContentView.background = null

        // Play button tap — open video on first tap, ignore double-tap
        holder.playContentView.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastTapTime < 300L) {
                tapJob?.let { holder.playContentView.removeCallbacks(it) }
                tapJob = null
            } else {
                tapJob = Runnable {
                    isPlaying = true
                    holder.playContentView.visibility = View.GONE
                    clickListener?.invoke(holder.imageView)
                }.also { job ->
                    holder.playContentView.postDelayed(job, 300L)
                }
            }
            lastTapTime = now
        }
        holder.playContentView.tag = playbackSpeed

        // SINGLE mediaContentView listener — YouTube style:
        // If play button visible (not yet playing) → open video
        // If play button gone (video playing) → toggle controls briefly

//        holder.mediaContentView.setOnClickListener {
//            if (playable) {
//                val isPlaying = holder.playContentView.visibility == View.GONE
//
//                if (isPlaying) {
//                    // Controls currently hidden → show them
//                    hideControlsRunnable?.let { holder.playContentView.removeCallbacks(it) }
//
//                    holder.playContentView.visibility = View.VISIBLE
//
//                    // Schedule auto-hide after 3s
//                    hideControlsRunnable = Runnable {
//                        holder.playContentView.visibility = View.GONE
//                    }.also { runnable ->
//                        holder.playContentView.postDelayed(runnable, 3000L)
//                    }
//                } else {
//                    // Controls are visible → hide immediately (YouTube-style toggle)
//                    hideControlsRunnable?.let { holder.playContentView.removeCallbacks(it) }
//                    hideControlsRunnable = null
//                    holder.playContentView.visibility = View.GONE
//
//                    // Only open viewer if video hasn't started yet (first tap)
//                    // If you want tap-to-dismiss only (not open), remove the line below
//                    attributes.itemClickListener?.invoke(holder.mediaContentView)
//                }
//            } else {
//                attributes.itemClickListener?.invoke(holder.mediaContentView)
//            }
//        }
        holder.mediaContentView.setOnClickListener {
            if (playable) {
                val isPlaying = holder.playContentView.visibility == View.GONE

                if (isPlaying) {
                    // Video playing, controls hidden → show them
                    hideControlsRunnable?.let { holder.playContentView.removeCallbacks(it) }
                    holder.playContentView.visibility = View.VISIBLE

                    hideControlsRunnable = Runnable {
                        holder.playContentView.visibility = View.GONE
                    }.also { runnable ->
                        holder.playContentView.postDelayed(runnable, 3000L)
                    }
                } else {
                    // Controls visible → hide immediately, do NOT open viewer
                    hideControlsRunnable?.let { holder.playContentView.removeCallbacks(it) }
                    hideControlsRunnable = null
                    holder.playContentView.visibility = View.GONE
                    // ← removed itemClickListener call here
                }
            } else {
                attributes.itemClickListener?.invoke(holder.mediaContentView)
            }
        }
        // Card stroke color
        val isMine = attributes.informationData.sentByMe
        val backgroundAttr = if (isMine) {
            im.vector.lib.ui.styles.R.attr.vctr_message_bubble_outbound
        } else {
            im.vector.lib.ui.styles.R.attr.vctr_message_bubble_inbound
        }
        val backgroundColor = ThemeUtils.getColor(holder.view.context, backgroundAttr)
        holder.imageCard.strokeColor = backgroundColor
    }

    override fun unbind(holder: Holder) {
        Glide.with(holder.view.context.applicationContext).clear(holder.imageView)
        hideControlsRunnable?.let { holder.playContentView.removeCallbacks(it) }
        hideControlsRunnable = null
        imageContentRenderer.clear(holder.imageView)
        isPlaying = false
        holder.imageView.visibility = View.VISIBLE
        holder.playContentView.visibility = View.GONE
        contentUploadStateTrackerBinder.unbind(attributes.informationData.eventId)
        holder.imageView.setOnClickListener(null)
        holder.imageView.setOnLongClickListener(null)
        super.unbind(holder)
    }

    override fun getViewStubId() = STUB_ID

    class Holder : AbsMessageItem.Holder(STUB_ID) {
       // val progressLayout by bind<ViewGroup>(R.id.messageMediaUploadProgressLayout)
        val imageView by bind<ImageView>(R.id.messageThumbnailView)
        val playContentView by bind<ImageView>(R.id.messageMediaPlayView)
        val mediaContentView by bind<ViewGroup>(R.id.messageContentMedia)
        val imageCard by bind<MaterialCardView>(R.id.messageImageCard)


    }

    companion object {
        private val STUB_ID = R.id.messageContentMediaStub
    }
}
