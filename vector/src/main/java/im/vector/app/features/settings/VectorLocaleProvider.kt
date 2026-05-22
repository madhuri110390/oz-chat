/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings

import android.content.SharedPreferences
import im.vector.app.BuildConfig
import im.vector.app.core.di.DefaultPreferences
import java.util.Locale
import javax.inject.Inject

/**
 * Class to provide the Locale choice of the user.
 */
class VectorLocaleProvider @Inject constructor(
        @DefaultPreferences
        private val preferences: SharedPreferences,
) {
    /**
     * Get the current local.
     * SharedPref values has been initialized in [VectorLocale.init]
     */
    val applicationLocale: Locale
        get() = if (BuildConfig.DEBUG) {
            val language = preferences.getString(VectorLocale.APPLICATION_LOCALE_LANGUAGE_KEY, "") ?: ""
            val country = preferences.getString(VectorLocale.APPLICATION_LOCALE_COUNTRY_KEY, "") ?: ""

            if (language.isNotEmpty()) {
                Locale.forLanguageTag("$language-$country")
            } else {
                Locale.getDefault()
            }
        } else {
            Locale.getDefault()
        }
}
