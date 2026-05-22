/*
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.room.model.call

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = false)
enum class UpdateCallType {
    @Json(name = "voice")
    VOICE,

    @Json(name = "video")
    VIDEO,

    @Json(name = "video_request")
    VIDEO_REQUEST,

    @Json(name = "video_accept")
    VIDEO_ACCEPT,

    @Json(name = "screen_share")
    SCREEN_SHARE
}
