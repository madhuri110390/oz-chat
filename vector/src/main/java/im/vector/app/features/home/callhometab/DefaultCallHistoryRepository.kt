/*
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.callhometab

import im.vector.app.features.home.room.detail.timeline.helper.CallSignalingEventsGroup
import im.vector.app.features.home.room.detail.timeline.helper.TimelineEventsGroups
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.room.RoomSummaryQueryParams
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.timeline.Timeline
import org.matrix.android.sdk.api.session.room.timeline.TimelineSettings
import javax.inject.Inject

class DefaultCallHistoryRepository @Inject constructor(
        private val session: Session,
        private val callItemMapper: CallItemMapper,
) : CallHistoryRepository {

    private var paginationExhausted = false
    private var timelineMap: MutableMap<String, Timeline> = mutableMapOf()

    override suspend fun loadInitialCallEvents(): List<CallScreenItem> {
        val queryParams = RoomSummaryQueryParams.Builder()
                .apply { memberships = listOf(Membership.JOIN) }
                .build()

        val joinedRooms = session.roomService().getRoomSummaries(queryParams)
        val callScreenItems = mutableListOf<CallScreenItem>()

        for (roomSummary in joinedRooms) {
            val room = session.roomService().getRoom(roomSummary.roomId) ?: continue

            val timeline = room.timelineService().createTimeline(null, TimelineSettings(initialSize = 100))
            timelineMap[room.roomId] = timeline
            timeline.start()

            val snapshot = TimelineSnapshotAwaiter(timeline).awaitSnapshot()
            val groups = TimelineEventsGroups()

            snapshot.forEach { event ->
                if (EventType.isCallEvent(event.root.getClearType())) {
                    groups.addOrIgnore(event)
                }
            }

            val roomCallItems = groups.events()
                    .map { CallSignalingEventsGroup(it) }
                    .mapNotNull { callItemMapper.map(roomSummary, it) }
            callScreenItems.addAll(roomCallItems)
        }

        return callScreenItems.sortedByDescending { it.timestamp }
    }

    override suspend fun loadMoreCallEvents(): List<CallScreenItem> {
        if (paginationExhausted) return emptyList()
        val moreItems = mutableListOf<CallScreenItem>()

        for ((roomId, timeline) in timelineMap) {
            val room = session.roomService().getRoom(roomId) ?: continue

            val beforeSnapshot = timeline.getSnapshot()

            // 👇 Perform pagination
            timeline.paginate(Timeline.Direction.BACKWARDS, 50)

            // 🔄 Wait for timeline to actually update
            val updatedSnapshot = TimelineSnapshotAwaiter(timeline).awaitSnapshot()

            val newlyLoaded = updatedSnapshot - beforeSnapshot.toSet()
            if (newlyLoaded.isEmpty()) {
                paginationExhausted = true
                continue
            }

            val groups = TimelineEventsGroups()
            newlyLoaded.forEach { event ->
                if (EventType.isCallEvent(event.root.getClearType())) {
                    groups.addOrIgnore(event)
                }
            }

            val roomCallItems = groups.events()
                    .map { CallSignalingEventsGroup(it) }
                    .mapNotNull { callItemMapper.map(room.roomSummary() ?: return@mapNotNull null, it) }

            moreItems.addAll(roomCallItems)
        }

        return moreItems.sortedByDescending { it.timestamp }
    }

    override fun hasMore(): Boolean = !paginationExhausted
}
