/*
 * Copyright (c) 2024 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.app.features.link

import android.content.Context
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import im.vector.app.features.settings.VectorPreferences
import org.matrix.android.sdk.api.extensions.tryOrNull
import timber.log.Timber
import java.net.URLDecoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VectorReferrerHandler @Inject constructor(
        private val context: Context,
        private val vectorPreferences: VectorPreferences
) {

    fun started() {
        if (vectorPreferences.deferredLink != null) {
            Timber.d("InstallReferrer: already have a deferred link")
            return
        }

        val referrerClient = InstallReferrerClient.newBuilder(context).build()
        referrerClient.startConnection(object : InstallReferrerStateListener {
            override fun onInstallReferrerSetupFinished(responseCode: Int) {
                when (responseCode) {
                    InstallReferrerClient.InstallReferrerResponse.OK -> {
                        tryOrNull {
                            val response = referrerClient.installReferrer
                            val referrerUrl = response.installReferrer
                            Timber.d("InstallReferrer: $referrerUrl")
                            
                            // Referrer is likely "utm_source=...&uri=https%3A%2F%2Foz-chat.app%2Froom%2F..."
                            val uri = referrerUrl.split("&")
                                    .firstOrNull { it.startsWith("uri=") }
                                    ?.substringAfter("uri=")
                                    ?.let { URLDecoder.decode(it, "UTF-8") }

                            if (uri != null) {
                                Timber.d("InstallReferrer: captured deferred link $uri")
                                vectorPreferences.deferredLink = uri
                                // Immediately trigger the LinkHandlerActivity
                                val intent = android.content.Intent(context, im.vector.app.features.link.LinkHandlerActivity::class.java).apply {
                                    data = android.net.Uri.parse(uri)
                                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                try {
                                    context.startActivity(intent)
                                    vectorPreferences.deferredLink = null // Clear since we just started it
                                } catch (e: Exception) {
                                    Timber.e(e, "InstallReferrer: Failed to start LinkHandlerActivity")
                                }
                            }
                        }
                        referrerClient.endConnection()
                    }
                    InstallReferrerClient.InstallReferrerResponse.FEATURE_NOT_SUPPORTED -> {
                        Timber.w("InstallReferrer: Feature not supported")
                    }
                    InstallReferrerClient.InstallReferrerResponse.SERVICE_UNAVAILABLE -> {
                        Timber.w("InstallReferrer: Service unavailable")
                    }
                }
            }

            override fun onInstallReferrerServiceDisconnected() {
                // Try to restart the connection on the next start
            }
        })
    }
}
