/*
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.callhometab

import im.vector.app.features.home.room.detail.timeline.item.CallTileTimelineItem
import org.matrix.android.sdk.api.util.MatrixItem

data class CallScreenItem(
    val userId: String,
    val userName: String?,
    val matrixItem: MatrixItem,
    val callKind: CallTileTimelineItem.CallKind,
    val callStatus: CallTileTimelineItem.CallStatus,
    val resolvedAvatarUrl: String?,
    val timestamp: Long,
    val callCount: Int = 1,
    val isSentByMe: Boolean,
    val isIncoming: Boolean,
    val callRoomId: String,
    val onCallBackClick: (() -> Unit)? = null,
)

