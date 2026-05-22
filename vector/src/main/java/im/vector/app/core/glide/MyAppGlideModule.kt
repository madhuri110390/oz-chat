/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.glide

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.Log
import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.module.AppGlideModule
import im.vector.app.features.media.ImageContentRenderer
import java.io.InputStream

@GlideModule
class MyAppGlideModule : AppGlideModule() {

    override fun applyOptions(context: Context, builder: GlideBuilder) {
        builder.setLogLevel(Log.ERROR)

        builder.setDefaultRequestOptions(
                com.bumptech.glide.request.RequestOptions()
                        .disallowHardwareConfig()
                        .format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565)
                        .downsample(com.bumptech.glide.load.resource.bitmap.DownsampleStrategy.CENTER_INSIDE)
        )
    }

    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        registry.append(
                ImageContentRenderer.Data::class.java,
                InputStream::class.java,
                ImageContentRendererDataLoaderFactory(context)
        )
        registry.append(
                AvatarPlaceholder::class.java,
                Drawable::class.java,
                AvatarPlaceholderModelLoaderFactory(context)
        )
    }
}

