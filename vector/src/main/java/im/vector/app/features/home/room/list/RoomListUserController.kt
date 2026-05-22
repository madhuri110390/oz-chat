/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.list

import org.matrix.android.sdk.api.session.user.model.User

class RoomListUserController(
        private val roomSummaryItemFactory: RoomSummaryItemFactory
) : CollapsableTypedEpoxyController<List<User>>() {

    var listener: RoomListListener? = null

    override fun buildModels(data: List<User>?) {
        data?.forEach {
            add(roomSummaryItemFactory.createUser(it, listener))
        }
    }
}
