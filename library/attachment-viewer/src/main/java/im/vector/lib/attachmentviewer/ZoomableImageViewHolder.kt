///*
// * Copyright 2020-2024 New Vector Ltd.
// *
// * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
// * Please see LICENSE files in the repository root for full details.
// */
//
//package im.vector.lib.attachmentviewer
//
//import android.view.View
//import im.vector.lib.attachmentviewer.databinding.ItemImageAttachmentBinding
//
//class ZoomableImageViewHolder constructor(itemView: View) :
//        BaseViewHolder(itemView) {
//
//    val views = ItemImageAttachmentBinding.bind(itemView)
//
////    init {
////        views.touchImageView.maximumScale = 5f
////        views.touchImageView.mediumScale = 2f
////        views.touchImageView.minimumScale = 1f
////        views.touchImageView.setAllowParentInterceptOnEdge(false)
//////        views.touchImageView.setOnClickListener {
//////            itemView.performClick()
//////        }
////        views.touchImageView.setOnScaleChangeListener { scaleFactor, _, _ ->
////            val isZoomedIn = scaleFactor > 1.0008f
////            views.touchImageView.setAllowParentInterceptOnEdge(!isZoomedIn)
////            // This is the key — tells ViewPager2's RecyclerView to back off
////            views.touchImageView.parent?.requestDisallowInterceptTouchEvent(isZoomedIn)
////        }
////        views.touchImageView.setScale(1.0f, true)
////        views.touchImageView.setAllowParentInterceptOnEdge(true)
////    }
//
// // internal val target = DefaultImageLoaderTarget.ZoomableImageTarget(this, views.touchImageView)
// init {
//     views.touchImageView.apply {
//         // PhotoView scale change listener
//         attacher.setOnScaleChangeListener { scaleFactor, _, _ ->
//             // When zoomed in, prevent ViewPager2 from stealing touch events
//             val isZoomedIn = attacher.scale > 1.0f
//             parent?.requestDisallowInterceptTouchEvent(isZoomedIn)
//         }
//     }
// }
//
//    internal val target = DefaultImageLoaderTarget.ZoomableImageTarget(this, views.touchImageView)
//    override fun onRecycled() {
//        super.onRecycled()
//        views.touchImageView.setImageDrawable(null)
//    }
//
//}

package im.vector.lib.attachmentviewer

import android.view.MotionEvent
import android.view.View
import im.vector.lib.attachmentviewer.databinding.ItemImageAttachmentBinding

class ZoomableImageViewHolder constructor(itemView: View) :
        BaseViewHolder(itemView) {

    val views = ItemImageAttachmentBinding.bind(itemView)

    internal val target = DefaultImageLoaderTarget.ZoomableImageTarget(this, views.touchImageView)

    override fun onAttached() {
        super.onAttached()
        views.touchImageView.apply {
            maximumScale = 5f
            mediumScale = 2.5f
            minimumScale = 1f
            isClickable = true
            isFocusable = true
            setOnScaleChangeListener { _, _, _ ->
                parent?.requestDisallowInterceptTouchEvent(scale > 1.0f)
            }
        }
    }

    override fun onRecycled() {
        super.onRecycled()
        views.touchImageView.setImageDrawable(null)
    }
}
