/*
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.story

data class UserStatusModel(
        val msgtype: String,      // "m.image", "m.video", "m.text"
        val body: String,         // Text or caption (can be blank if only image/video)
        val url: String? = null,  // mxc://... for image/video, null for plain text
        val timestamp: Long = System.currentTimeMillis()
)
