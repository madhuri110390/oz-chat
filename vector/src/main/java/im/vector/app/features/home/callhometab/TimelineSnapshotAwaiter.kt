/*
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.callhometab

import kotlinx.coroutines.CompletableDeferred
import org.matrix.android.sdk.api.session.room.timeline.Timeline
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent

class TimelineSnapshotAwaiter(
        private val timeline: Timeline
) {

    private val deferred = CompletableDeferred<List<TimelineEvent>>()

    private val listener = object : Timeline.Listener {
        override fun onTimelineUpdated(snapshot: List<TimelineEvent>) {
            if (!deferred.isCompleted) {
                deferred.complete(snapshot)
                timeline.removeListener(this)
            }
        }
    }

    suspend fun awaitSnapshot(): List<TimelineEvent> {
        timeline.addListener(listener)
        return deferred.await()
    }
}
