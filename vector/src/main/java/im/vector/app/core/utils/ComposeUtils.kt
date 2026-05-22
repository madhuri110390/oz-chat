/*
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.utils

import android.util.TypedValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

object ComposeUtils {

    @Composable
    fun resolveAndroidAttrColor(attrResId: Int): Color {
        val context = LocalContext.current
        return remember(attrResId) {
            val typedValue = TypedValue()
            val resolved = context.theme.resolveAttribute(attrResId, typedValue, true)
            if (resolved) Color(typedValue.data) else Color.Unspecified
        }
    }
}
