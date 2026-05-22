/*
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.story

import org.matrix.android.sdk.api.session.Session

/*class StatusRepository(private val session: Session) {

    // Post your own status
    suspend fun postStatus(status: UserStatusModel) {
        val content = mapOf(
                "msgtype" to status.msgtype,
                "body" to status.body,
                "url" to status.url,
                "timestamp" to status.timestamp
        )
        session.accountDataService().updateUserAccountData("org.ozchat.status", content)
    }

    // Fetch status for any user (by userId)
    fun getStatusForUser(userId: String): UserStatusModel? {
        val event = session.accountDataService().getUserAccountDataEvent("org.ozchat.status", userId)
        val map = event?.content ?: return null
        val timestamp = (map["timestamp"] as? Number)?.toLong() ?: return null
        if (System.currentTimeMillis() - timestamp > 24 * 60 * 60 * 1000) return null // Only last 24h
        return UserStatusModel(
                msgtype = map["msgtype"] as? String ?: "m.text",
                body = map["body"] as? String ?: "",
                url = map["url"] as? String,
                timestamp = timestamp
        )
    }

    // Fetch your own status
    fun getMyStatus(): UserStatusModel? {
        return getStatusForUser(session.myUserId)
    }

    // Fetch statuses for a list of user IDs (your contacts/DMs)
    fun getStatusesForUsers(userIds: List<String>): List<Pair<String, UserStatusModel>> {
        return userIds.mapNotNull { userId ->
            getStatusForUser(userId)?.let { status -> userId to status }
        }
    }
}*/
