/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.list

import androidx.core.util.Predicate
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import javax.inject.Inject

class RoomListNameFilter @Inject constructor() : Predicate<RoomSummary> {

    var filter: String = ""

    override fun test(roomSummary: RoomSummary): Boolean {
        if (filter.isEmpty()) {
            // No filter
            return true
        }

        val displayName = roomSummary.displayName
        // Extract the username localpart from @username:server if available
        val aliasPart = roomSummary.canonicalAlias?.substringBefore(":") ?: ""
        // Strip leading # or @ prefix
        val cleanAlias = aliasPart.removePrefix("#").removePrefix("@").removePrefix("/profile/")
        val cleanName = displayName.removePrefix("/profile/")

        return displayName.contains(filter, ignoreCase = true) ||
                cleanName.contains(filter, ignoreCase = true) ||
                aliasPart.contains(filter, ignoreCase = true) ||
                cleanAlias.contains(filter, ignoreCase = true)
    }
}
