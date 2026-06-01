/*
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.callhometab

import im.vector.app.features.displayname.getBestName
import im.vector.app.features.home.room.detail.timeline.helper.CallSignalingEventsGroup
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.content.ContentUrlResolver
import org.matrix.android.sdk.api.session.getUser
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.api.util.MatrixItem
import javax.inject.Inject

class CallItemMapper @Inject constructor(
        private val session: Session
) {
    fun map(roomSummary: RoomSummary, group: CallSignalingEventsGroup): CallScreenItem {
        val callStatus = group.callStatus
        val callKind = group.callKind
        val isSentByMe = group.isCallPlacedBy(session.myUserId)
        val timestamp = group.getInvite()?.root?.originServerTs ?: 0L

        val otherUserId: String = roomSummary.otherMemberIds
                .firstOrNull { it != session.myUserId }
                ?: group.getInvite()?.root?.senderId
                ?: session.myUserId

        val user = session.getUser(otherUserId)
        val matrixItem = MatrixItem.UserItem(
                id = otherUserId,
                displayName = user?.displayName,
                avatarUrl = user?.avatarUrl
        )

        val resolvedAvatarUrl = user?.avatarUrl?.let {
            session.contentUrlResolver()
                    .resolveThumbnail(it, 250, 250, ContentUrlResolver.ThumbnailMethod.SCALE)
        }
        val isIncoming = !isSentByMe

        return CallScreenItem(
                userId = otherUserId,
                userName = matrixItem.id
                        .removePrefix("@")
                        .substringBefore(":"),
                callKind = callKind,
                callStatus = callStatus,
                resolvedAvatarUrl = resolvedAvatarUrl,
                timestamp = timestamp,
                callCount = 1,
                isSentByMe = isSentByMe,
                isIncoming = isIncoming,
                matrixItem = matrixItem,
                callRoomId = roomSummary.roomId,
                onCallBackClick = {}
        )
    }

}
