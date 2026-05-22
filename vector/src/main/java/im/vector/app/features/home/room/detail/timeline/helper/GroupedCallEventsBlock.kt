/*
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.helper

import im.vector.app.features.home.room.detail.timeline.item.CallTileTimelineItem
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent

data class GroupedCallEventsBlock(
        val events: MutableList<TimelineEvent>,
        val senderId: String,
        val callKind: CallTileTimelineItem.CallKind,
        val callStatus: CallTileTimelineItem.CallStatus,
        val firstTimestamp: Long,
        var lastTimestamp: Long
) {
    val representativeEventId: String
        get() = events.firstOrNull()?.eventId ?: "unknown"
}
