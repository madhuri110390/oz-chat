/*
 * Copyright 2018-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 */

package im.vector.app.features.settings

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import androidx.core.content.edit
import im.vector.app.core.di.DefaultPreferences
import im.vector.app.core.resources.BuildMeta
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VectorLocale @Inject constructor(
        private val context: Context,
        private val buildMeta: BuildMeta,
        @DefaultPreferences
        private val preferences: SharedPreferences,
) {

    companion object {
        const val APPLICATION_LOCALE_COUNTRY_KEY = "APPLICATION_LOCALE_COUNTRY_KEY"
        const val APPLICATION_LOCALE_VARIANT_KEY = "APPLICATION_LOCALE_VARIANT_KEY"
        const val APPLICATION_LOCALE_LANGUAGE_KEY = "APPLICATION_LOCALE_LANGUAGE_KEY"
        private const val APPLICATION_LOCALE_SCRIPT_KEY = "APPLICATION_LOCALE_SCRIPT_KEY"
        private const val ISO_15924_LATN = "Latn"
    }


//            if (BuildConfig.DEBUG) {
//        Locale("en", "US")
//    }
private val defaultLocale =  Locale.getDefault()


    private val supportedLocales = mutableListOf<Locale>()

    var applicationLocale = defaultLocale
        private set

    /**
     * Init this singleton.
     */
    fun init() {
        try {
            if (preferences.contains(APPLICATION_LOCALE_LANGUAGE_KEY)) {

                val language = preferences.getString(APPLICATION_LOCALE_LANGUAGE_KEY, "") ?: ""
                val country = preferences.getString(APPLICATION_LOCALE_COUNTRY_KEY, "") ?: ""
                val variant = preferences.getString(APPLICATION_LOCALE_VARIANT_KEY, "") ?: ""
                val script = preferences.getString(APPLICATION_LOCALE_SCRIPT_KEY, "") ?: ""

                applicationLocale = buildSafeLocale(language, country, variant, script)

            } else {
                applicationLocale = Locale.getDefault()

                val defaultStringValue = getString(context, defaultLocale, CommonStrings.resources_country_code)
                if (defaultStringValue == getString(context, applicationLocale, CommonStrings.resources_country_code)) {
                    applicationLocale = defaultLocale
                }

                saveApplicationLocale(applicationLocale)
            }
        } catch (e: Exception) {
            Timber.e(e, "Locale init failed, fallback to default")
            applicationLocale = Locale.getDefault()
        }
    }

    /**
     * Safe locale builder (NO crash in production)
     */
    private fun buildSafeLocale(
            language: String,
            country: String,
            variant: String,
            script: String
    ): Locale {
        return try {
            if (language.isBlank()) return Locale.getDefault()

            val builder = Locale.Builder().setLanguage(language)

            if (country.isNotBlank()) builder.setRegion(country)
            if (variant.isNotBlank()) builder.setVariant(variant)
            if (script.isNotBlank()) builder.setScript(script)

            builder.build()
        } catch (e: Exception) {
            Timber.e(e, "Invalid locale, fallback")
            Locale.getDefault()
        }
    }

    /**
     * Save locale
     */
    fun saveApplicationLocale(locale: Locale) {
        applicationLocale = locale

        preferences.edit {
            putOrRemove(APPLICATION_LOCALE_LANGUAGE_KEY, locale.language)
            putOrRemove(APPLICATION_LOCALE_COUNTRY_KEY, locale.country)
            putOrRemove(APPLICATION_LOCALE_VARIANT_KEY, locale.variant)
            putOrRemove(APPLICATION_LOCALE_SCRIPT_KEY, locale.script)
        }
    }

    private fun SharedPreferences.Editor.putOrRemove(key: String, value: String) {
        if (value.isEmpty()) remove(key) else putString(key, value)
    }

    /**
     * Get localized string
     */
    private fun getString(context: Context, locale: Locale, resourceId: Int): String {
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)

        return try {
            context.createConfigurationContext(config).getText(resourceId).toString()
        } catch (e: Exception) {
            Timber.e(e, "getString failed")
            context.getString(resourceId)
        }
    }

    /**
     * Init supported locales (SAFE)
     */
    private fun initApplicationLocales() {
        try {
            val locales = Locale.getAvailableLocales()
                    .filter { it.language.isNotBlank() && it.language != "und" }
                    .distinctBy { Triple(it.language, it.country, it.script) }
                    .sortedBy { it.displayName.lowercase() }

            supportedLocales.clear()
            supportedLocales.addAll(locales)

        } catch (e: Exception) {
            Timber.e(e, "initApplicationLocales failed")
            supportedLocales.clear()
            supportedLocales.add(Locale.getDefault())
        }
    }

    fun localeToLocalisedString(locale: Locale): String {
        return buildString {
            append(locale.getDisplayLanguage(locale))

            if (locale.script != ISO_15924_LATN && locale.getDisplayScript(locale).isNotEmpty()) {
                append(" - ")
                append(locale.getDisplayScript(locale))
            }

            if (locale.getDisplayCountry(locale).isNotEmpty()) {
                append(" (")
                append(locale.getDisplayCountry(locale))
                append(")")
            }
        }
    }

    fun localeToLocalisedStringInfo(locale: Locale): String {
        return buildString {
            append("[")
            append(locale.displayLanguage)

            if (locale.script != ISO_15924_LATN) {
                append(" - ")
                append(locale.displayScript)
            }

            if (locale.displayCountry.isNotEmpty()) {
                append(" (")
                append(locale.displayCountry)
                append(")")
            }

            append("]")
        }
    }

    suspend fun getSupportedLocales(): List<Locale> {
        if (supportedLocales.isEmpty()) {
            withContext(Dispatchers.IO) {
                initApplicationLocales()
            }
        }
        return supportedLocales
    }
}
