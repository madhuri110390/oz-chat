/*
 * Copyright (c) 2024 New Vector Ltd.
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

@file:Suppress("DEPRECATION")

package im.vector.app.features.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.VectorDrawable
import android.net.Uri
import androidx.core.content.ContextCompat
import im.vector.app.R
import java.io.File
import java.io.FileOutputStream

object ImageBrandingUtils {
    fun brandImage(context: Context, originalBitmap: Bitmap, username: String): Bitmap {
        return originalBitmap
    }

//    fun brandImage(context: Context, originalBitmap: Bitmap, username: String): Bitmap {
//        val width = originalBitmap.width
//        val height = originalBitmap.height
//
//        val result = Bitmap.createBitmap(width, height, originalBitmap.config ?: Bitmap.Config.ARGB_8888)
//        val canvas = Canvas(result)
//        canvas.drawBitmap(originalBitmap, 0f, 0f, null)
//
//        val footerHeight = (height * 0.12f).coerceIn(120f, 300f)
//        val backgroundPaint = Paint().apply {
//            color = Color.parseColor("#CC000000") // 80% transparent black
//        }
//
//        // Draw footer background
//        canvas.drawRect(
//                0f,
//                height - footerHeight,
//                width.toFloat(),
//                height.toFloat(),
//                backgroundPaint
//        )
//
//        val padding = footerHeight * 0.15f
//        val contentHeight = footerHeight - (2 * padding)
//
//        // Draw Logo
//        val logoBitmap = getLogoBitmap(context, contentHeight.toInt())
//        if (logoBitmap != null) {
//            canvas.drawBitmap(
//                    logoBitmap,
//                    padding,
//                    height - footerHeight + padding,
//                    null
//            )
//        }
//
//        val logoWidth = logoBitmap?.width?.toFloat() ?: 0f
//        val textStartX = padding + logoWidth + padding
//
//        // Draw "OZ Chat"
//        val titlePaint = Paint().apply {
//            color = Color.WHITE
//            textSize = (footerHeight * 0.35f)
//            isAntiAlias = true
//            isFakeBoldText = true
//        }
//
//        val titleText = "OZ Chat"
//        canvas.drawText(
//                titleText,
//                textStartX,
//                height - (footerHeight * 0.55f),
//                titlePaint
//        )
//
//        // Draw "ozchat.app fix"
//        val subtitlePaint = Paint().apply {
//            color = Color.parseColor("#FDA102") // Yellow/orange
//            textSize = (footerHeight * 0.25f)
//            isAntiAlias = true
//        }
//
//        val subtitleText = "openzipper.com/ozchat"
//        canvas.drawText(
//                subtitleText,
//                textStartX,
//                height - (footerHeight * 0.20f),
//                subtitlePaint
//        )
//
//        return result
//    }

    fun getDynamicInviteLink(username: String, permalink: String? = null): String {
        return permalink?.replace("matrix.to", "openzipper.com/ozchat") ?: "https://openzipper.com/ozchat/profile/$username"
    }

    fun getShareCaption(username: String, permalink: String? = null): String {
        return """
        Sent via OZ Chat

        Download OZ Chat:
        https://play.google.com/store/apps/details?id=com.openzipper.ozchat
    """.trimIndent()
    }

    private fun getLogoBitmap(context: Context, size: Int): Bitmap? {
        val drawable = ContextCompat.getDrawable(context, R.drawable.oz_chat_playstore_icon) ?: return null
        return when (drawable) {
            is BitmapDrawable -> {
                Bitmap.createScaledBitmap(drawable.bitmap, size, size, true)
            }
            is VectorDrawable -> {
                val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bitmap
            }
            else -> {
                val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bitmap
            }
        }
    }

    fun saveBrandedImage(context: Context, bitmap: Bitmap): File? {
        return try {
            val file = File(context.cacheDir, "oz_share_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)
            }
            file
        } catch (e: Exception) {
            null
        }
    }
}
