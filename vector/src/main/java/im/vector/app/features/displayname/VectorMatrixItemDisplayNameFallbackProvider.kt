/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.displayname

import org.matrix.android.sdk.api.provider.MatrixItemDisplayNameFallbackProvider
import org.matrix.android.sdk.api.util.MatrixItem

// Used to provide the fallback to the MatrixSDK, in the MatrixConfiguration
object VectorMatrixItemDisplayNameFallbackProvider : MatrixItemDisplayNameFallbackProvider {
    override fun getDefaultName(matrixItem: MatrixItem): String {
        val id = matrixItem.id
        val name = when {
            id.startsWith("@") && id.contains(":") -> id.substring(1, id.indexOf(":"))
            id.startsWith("#") && id.contains(":") -> id.substring(0, id.indexOf(":"))
            else -> id
        }
        return name.removePrefix("@").substringBefore(":")
    }
}
