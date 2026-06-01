/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.backgroundsync.service

import android.content.Context
import im.vector.app.core.services.GuardServiceStarter
import im.vector.app.features.settings.VectorPreferences
import timber.log.Timber
import javax.inject.Inject

class BackgroundSyncGuardServiceStarter @Inject constructor(
        private val preferences: VectorPreferences,
        private val appContext: Context
) : GuardServiceStarter {

    override fun start() {
        if (preferences.isBackgroundSyncEnabled()) {
            // Android 14+ / Play policy: do not keep process alive using a foreground service.
            Timber.i("## Sync: GuardService disabled (FGS policy)")
        }
    }

    override fun stop() {
        // No-op: guard foreground service is disabled.
    }
}
