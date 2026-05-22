/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.extensions

import android.app.Notification
import android.app.Service
import android.content.pm.ServiceInfo
import android.os.Build
import timber.log.Timber

fun Service.startForegroundCompat(
        id: Int,
        notification: Notification,
        provideForegroundServiceType: (() -> Int)? = null
) {
    Timber.d("startForegroundCompat → Starting foreground service (API ${Build.VERSION.SDK_INT})")

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val type = provideForegroundServiceType?.invoke() ?: ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
        Timber.d("startForegroundCompat → Foreground type: $type")
        startForeground(id, notification, type)
    } else {
        Timber.d("startForegroundCompat → Foreground (legacy) startForeground called")
        startForeground(id, notification)
    }
}

