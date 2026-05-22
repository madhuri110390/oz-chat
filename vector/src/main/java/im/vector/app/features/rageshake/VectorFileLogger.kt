/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.rageshake

import android.content.Context
import android.util.Log
import im.vector.app.BuildConfig
import im.vector.app.features.settings.VectorPreferences
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.extensions.tryOrNull
import timber.log.Timber
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.logging.FileHandler
import java.util.logging.Level
import java.util.logging.Logger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VectorFileLogger @Inject constructor(
        context: Context,
        private val vectorPreferences: VectorPreferences
) : Timber.Tree() {

    companion object {
        private const val SIZE_20MB = 20 * 1024 * 1024
        private const val SIZE_50MB = 50 * 1024 * 1024
    }

    private val maxLogSizeByte =
            if (vectorPreferences.labAllowedExtendedLogging()) SIZE_50MB else SIZE_20MB

    private val logRotationCount =
            if (vectorPreferences.labAllowedExtendedLogging()) 15 else 7

    private val cacheDirectory = File(context.cacheDir, "logs")
    private val fileNamePrefix = "logs"

    private val logger = Logger.getLogger(context.packageName).apply {
        useParentHandlers = false
        level = Level.ALL
    }

    private val fileHandler: FileHandler?

    private val scope = kotlinx.coroutines.CoroutineScope(
            Dispatchers.IO + kotlinx.coroutines.SupervisorJob()
    )

    private val prioPrefixes = mapOf(
            Log.VERBOSE to "V",
            Log.DEBUG to "D",
            Log.INFO to "I",
            Log.WARN to "W",
            Log.ERROR to "E",
            Log.ASSERT to "WTF"
    )

    init {
        if (!cacheDirectory.exists()) {
            cacheDirectory.mkdirs()
        }

        // Clean old logs safely
        for (i in 0..15) {
            runCatching {
                File(cacheDirectory, "elementLogs.$i.txt").delete()
            }
        }

        fileHandler = runCatching {
            FileHandler(
                    "${cacheDirectory.absolutePath}/$fileNamePrefix.%g.txt",
                    maxLogSizeByte,
                    logRotationCount,
                    true // append mode (important for prod)
            ).apply {
                formatter = LogFormatter()
                level = Level.ALL   // ✅ IMPORTANT FIX
            }.also {
                logger.addHandler(it)
            }
        }.getOrNull()
    }

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val handler = fileHandler ?: return

        if (skipLog(priority)) return

        scope.launch {
            runCatching {
                t?.let { logThrowable(it) }
                logMessage(priority, tag ?: "Tag", message)
            }
        }
    }

    private fun skipLog(priority: Int): Boolean {
        return if (vectorPreferences.labAllowedExtendedLogging()) {
            false
        } else {
            priority < Log.DEBUG // skip VERBOSE
        }
    }

    fun getLogFiles(): List<File> {
        return runCatching {
            fileHandler?.flush()
            (0 until logRotationCount).mapNotNull { index ->
                File(cacheDirectory, "$fileNamePrefix.$index.txt")
                        .takeIf { it.exists() }
            }
        }.getOrDefault(emptyList())
    }

    private fun logThrowable(throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        logger.log(Level.SEVERE, sw.toString())
    }

    private fun logMessage(priority: Int, tag: String, content: String) {
//        val prefix = prioPrefixes[priority] ?: priority.toString()
//
//        val logLine = buildString {
//            append(Thread.currentThread().id)
//            append(" ")
//            append(prefix)
//            append("/")
//            append(tag)
//            append(": ")
//            append(content)
//        }
//
//        logger.log(Level.INFO, logLine)
        ""
    }
}
