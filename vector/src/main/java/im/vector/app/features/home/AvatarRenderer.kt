/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.widget.ImageView
import androidx.annotation.AnyThread
import androidx.annotation.ColorInt
import androidx.annotation.UiThread
import androidx.core.graphics.drawable.toBitmap
import com.amulyakhare.textdrawable.TextDrawable
import com.bumptech.glide.load.MultiTransformation
import com.bumptech.glide.load.Transformation
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.DrawableImageViewTarget
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.signature.ObjectKey
import im.vector.app.core.contacts.MappedContact
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.glide.AvatarPlaceholder
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.RequestManager
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.utils.DimensionConverter
import im.vector.app.features.displayname.getBestName
import im.vector.app.features.home.room.detail.timeline.helper.MatrixItemColorProvider
import im.vector.lib.strings.CommonStrings
import jp.wasabeef.glide.transformations.BlurTransformation
import jp.wasabeef.glide.transformations.ColorFilterTransformation
import org.matrix.android.sdk.api.auth.login.LoginProfileInfo
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.content.ContentUrlResolver
import org.matrix.android.sdk.api.util.MatrixItem
import java.io.File
import javax.inject.Inject

/**
 * This helper centralise ways to retrieve avatar into ImageView or even generic Target<Drawable>.
 */
class AvatarRenderer @Inject constructor(
        private val activeSessionHolder: ActiveSessionHolder,
        private val matrixItemColorProvider: MatrixItemColorProvider,
        private val dimensionConverter: DimensionConverter,
        private val stringProvider: StringProvider,
) {

    companion object {
        private const val THUMBNAIL_SIZE = 250
    }

    @UiThread
    fun render(matrixItem: MatrixItem, imageView: ImageView) {
        imageView.setContentDescription(matrixItem)
        render(
                Glide.with(imageView),
                matrixItem,
                DrawableImageViewTarget(imageView),
        )
    }



//    fun renderSpace(matrixItem: MatrixItem, imageView: ImageView) {
//        renderSpace(
//                matrixItem,
//                imageView,
//                GlideApp.with(imageView)
//        )
//    }
//
//    @UiThread
//    private fun renderSpace(matrixItem: MatrixItem, imageView: ImageView, glideRequests: GlideRequests) {
//        val placeholder = getSpacePlaceholderDrawable(matrixItem)
//        val resolvedUrl = resolvedUrl(matrixItem.avatarUrl)
//        glideRequests
//                .load(resolvedUrl)
//                .transform(MultiTransformation(CenterCrop(), RoundedCorners(dimensionConverter.dpToPx(8))))
//                .placeholder(placeholder)
//                .into(DrawableImageViewTarget(imageView))
//    }

    fun clear(imageView: ImageView) {
        // It can be called after recycler view is destroyed, just silently catch
        tryOrNull { Glide.with(imageView).clear(imageView) }
    }

    @UiThread
    fun render(matrixItem: MatrixItem, imageView: ImageView, requestManager: RequestManager) {
        imageView.setContentDescription(matrixItem)
        render(
                requestManager,
                matrixItem,
                DrawableImageViewTarget(imageView),
        )
    }

    @UiThread
    fun render(matrixItem: MatrixItem, localUri: Uri?, imageView: ImageView) {
        imageView.setContentDescription(matrixItem)
        val placeholder = getPlaceholderDrawable(matrixItem)
        Glide.with(imageView)
                .load(localUri?.let { File(localUri.path!!) })
                .apply(RequestOptions.circleCropTransform())
                .placeholder(placeholder)
                .into(imageView)
    }

    @UiThread
    fun render(mappedContact: MappedContact, imageView: ImageView) {
        // Create a Fake MatrixItem, for the placeholder
        val matrixItem = MatrixItem.UserItem(
                // Need an id starting with @
                id = "@${mappedContact.displayName}",
                displayName = mappedContact.displayName,
        )

        val placeholder = getPlaceholderDrawable(matrixItem)
        Glide.with(imageView)
                .load(mappedContact.photoURI)
                .apply(RequestOptions.circleCropTransform())
                .placeholder(placeholder)
                .into(imageView)
    }

    @UiThread
    fun render(profileInfo: LoginProfileInfo, imageView: ImageView) {
        // Create a Fake MatrixItem, for the placeholder
        val matrixItem = MatrixItem.UserItem(
                // Need an id starting with @
                id = profileInfo.matrixId,
                displayName = profileInfo.displayName,
        )

        val placeholder = getPlaceholderDrawable(matrixItem)
        Glide.with(imageView)
                .load(profileInfo.fullAvatarUrl)
                .apply(RequestOptions.circleCropTransform())
                .placeholder(placeholder)
                .into(imageView)
    }

    @UiThread
    fun render(
            requestManager: RequestManager,
            matrixItem: MatrixItem,
            target: Target<Drawable>
    ) {
        val placeholder = getPlaceholderDrawable(matrixItem)
        requestManager.loadResolvedUrl(matrixItem.avatarUrl)
                .let {
                    when (matrixItem) {
                        is MatrixItem.SpaceItem -> {
                            it.transform(MultiTransformation(CenterCrop(), RoundedCorners(dimensionConverter.dpToPx(8))))
                        }
                        else -> {
                            it.apply(RequestOptions.circleCropTransform())
                        }
                    }
                }
                .placeholder(placeholder)
                .into(target)
    }

    @AnyThread
    @Throws
    fun shortcutDrawable(requestManager: RequestManager, matrixItem: MatrixItem, iconSize: Int): Bitmap {
        return requestManager
                .asBitmap()
                .avatarOrText(matrixItem, iconSize)
                .apply(RequestOptions.centerCropTransform())
                .submit(iconSize, iconSize)
                .get()
    }

    @AnyThread
    @Throws
    fun adaptiveShortcutDrawable(
            requestManager: RequestManager,
            matrixItem: MatrixItem, iconSize: Int,
            adaptiveIconSize: Int,
            adaptiveIconOuterSides: Float
    ): Bitmap {
        return requestManager
                .asBitmap()
                .avatarOrText(matrixItem, iconSize)
                .transform(CenterCrop(), AdaptiveIconTransformation(adaptiveIconSize, adaptiveIconOuterSides))
                .signature(ObjectKey("adaptive-icon"))
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                .submit(iconSize, iconSize)
                .get()
    }

    private fun RequestBuilder<Bitmap>.avatarOrText(matrixItem: MatrixItem, iconSize: Int): RequestBuilder<Bitmap> {
        return this.let {
            val resolvedUrl = resolvedUrl(matrixItem.avatarUrl)
            if (resolvedUrl != null) {
                it.load(resolvedUrl)
            } else {
                val avatarColor = matrixItemColorProvider.getColor(matrixItem)
                it.load(
                        TextDrawable.builder()
                                .beginConfig()
                                .bold()
                                .endConfig()
                                .buildRect(matrixItem.firstLetterOfDisplayName(), avatarColor)
                                .toBitmap(width = iconSize, height = iconSize),
                )
            }
        }
    }

    @UiThread
    fun renderBlur(
            matrixItem: MatrixItem,
            imageView: ImageView,
            sampling: Int,
            rounded: Boolean,
            @ColorInt colorFilter: Int? = null,
            addPlaceholder: Boolean
    ) {
        val transformations = mutableListOf<Transformation<Bitmap>>(
                BlurTransformation(20, sampling),
        )
        if (colorFilter != null) {
            transformations.add(ColorFilterTransformation(colorFilter))
        }
        if (rounded) {
            transformations.add(CircleCrop())
        }
        val bitmapTransform = RequestOptions.bitmapTransform(MultiTransformation(transformations))
        val requestManager = Glide.with(imageView)
        val placeholderRequest = if (addPlaceholder) {
            requestManager
                    .load(AvatarPlaceholder(matrixItem))
                    .apply(bitmapTransform)
        } else {
            null
        }
        requestManager.loadResolvedUrl(matrixItem.avatarUrl)
                .apply(bitmapTransform)
                // We are using thumbnail and error API so we can have blur transformation on it...
                .thumbnail(placeholderRequest)
                .error(placeholderRequest)
                .into(imageView)
    }

    @AnyThread
    fun getCachedDrawable(requestManager: RequestManager, matrixItem: MatrixItem): Drawable {
        return requestManager.loadResolvedUrl(matrixItem.avatarUrl)
                .onlyRetrieveFromCache(true)
                .apply(RequestOptions.circleCropTransform())
                .submit()
                .get()
    }

//    @AnyThread
//    fun getPlaceholderDrawable(matrixItem: MatrixItem): Drawable {
//        val avatarColor = matrixItemColorProvider.getColor(matrixItem)
//        return TextDrawable.builder()
//                .beginConfig()
//                .bold()
//                .endConfig()
//                .let {
//                    when (matrixItem) {
//                        is MatrixItem.SpaceItem -> {
//                            it.buildRoundRect(matrixItem.firstLetterOfDisplayName(), avatarColor, dimensionConverter.dpToPx(8))
//                        }
//                        else -> {
//                            it.buildRound(matrixItem.firstLetterOfDisplayName(), avatarColor)
//                        }
//                    }
//                }
//    }
@AnyThread
fun getPlaceholderDrawable(matrixItem: MatrixItem): Drawable {
    val avatarColor = matrixItemColorProvider.getColor(matrixItem)
    val initial = matrixItem.firstLetterOfDisplayName()
    return TextDrawable.builder()
            .beginConfig()
            .bold()
            .endConfig()
            .let {
                when (matrixItem) {
                    is MatrixItem.SpaceItem -> {
                        it.buildRoundRect(initial, avatarColor, dimensionConverter.dpToPx(8))
                    }
                    else -> {
                        it.buildRound(initial, avatarColor)
                    }
                }
            }
}

    // PRIVATE API *********************************************************************************

    private fun RequestManager.loadResolvedUrl(avatarUrl: String?): RequestBuilder<Drawable> {
        val resolvedUrl = resolvedUrl(avatarUrl)
        return load(resolvedUrl)
    }

    private fun resolvedUrl(avatarUrl: String?): String? {
        return activeSessionHolder.getSafeActiveSession()?.contentUrlResolver()
                ?.resolveThumbnail(avatarUrl, THUMBNAIL_SIZE, THUMBNAIL_SIZE, ContentUrlResolver.ThumbnailMethod.SCALE)
    }

    /**
     * Accessibility management.
     */
    private fun ImageView.setContentDescription(matrixItem: MatrixItem) {
        // Do not set contentDescription if the ImageView should be ignored regarding accessibility.
        if (isImportantForAccessibility.not()) return
        when (matrixItem) {
            is MatrixItem.SpaceItem -> {
                contentDescription = stringProvider.getString(CommonStrings.avatar_of_space, matrixItem.id
                        .removePrefix("@")
                        .substringBefore(":"))
            }
            is MatrixItem.RoomAliasItem,
            is MatrixItem.RoomItem -> {
                contentDescription = stringProvider.getString(CommonStrings.avatar_of_room, matrixItem.id
                        .removePrefix("@")
                        .substringBefore(":"))
            }
            is MatrixItem.UserItem -> {
                contentDescription = stringProvider.getString(CommonStrings.avatar_of_user, matrixItem.id
                        .removePrefix("@")
                        .substringBefore(":"))
            }
            is MatrixItem.EveryoneInRoomItem,
            is MatrixItem.EventItem -> {
                // NA
            }
        }
    }
}
