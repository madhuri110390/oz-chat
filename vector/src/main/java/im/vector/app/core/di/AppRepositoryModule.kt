/*
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import im.vector.app.features.home.callhometab.CallHistoryRepository
import im.vector.app.features.home.callhometab.DefaultCallHistoryRepository

@Module
@InstallIn(SingletonComponent::class) // Or use ViewModelComponent if you prefer scoped instantiation
interface AppRepositoryModule {

    @Binds
    fun bindCallHistoryRepository(
            impl: DefaultCallHistoryRepository
    ): CallHistoryRepository
}
