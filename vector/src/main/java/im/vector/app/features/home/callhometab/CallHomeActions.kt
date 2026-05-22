/*
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.callhometab

import im.vector.app.core.platform.VectorViewModelAction

sealed class CallHomeActions : VectorViewModelAction {
    // Add actions like RefreshCalls or LoadMore if needed in future
    data object LoadCallLogs : CallHomeActions()
    data object LoadMoreCallLogs : CallHomeActions()
    data class CallUser(val roomId: String, val userId: String, val isVideo: Boolean) : CallHomeActions()
}
