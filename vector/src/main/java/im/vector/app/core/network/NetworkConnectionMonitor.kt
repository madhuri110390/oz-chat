
/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.annotation.RequiresApi
import com.google.androidbrowserhelper.trusted.NotificationUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkConnectionMonitor @Inject constructor(
        @ApplicationContext private val context: Context,
        private val notificationUtils: im.vector.app.features.notifications.NotificationUtils
) {
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @RequiresApi(Build.VERSION_CODES.M)
    private val _isConnected = MutableStateFlow(checkInitialConnection())
    @RequiresApi(Build.VERSION_CODES.M) val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        @RequiresApi(Build.VERSION_CODES.M)
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            Timber.d("Network available")
            _isConnected.value = true


            //notificationUtils.refreshNotificationsAfterConnectionRestored()
        }

        @RequiresApi(Build.VERSION_CODES.M)
        override fun onLost(network: Network) {
            super.onLost(network)
            Timber.d("Network lost")
            _isConnected.value = false
        }

        @RequiresApi(Build.VERSION_CODES.M)
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            super.onCapabilitiesChanged(network, networkCapabilities)
            val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            _isConnected.value = hasInternet
        }
    }

    init {
        registerNetworkCallback()
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun checkInitialConnection(): Boolean {
        return try {
            val activeNetwork = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } catch (e: Exception) {
            Timber.e(e, "Failed to check initial connection")
            false
        }
    }

    private fun registerNetworkCallback() {
        try {
            val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()

            connectivityManager.registerNetworkCallback(request, networkCallback)
            Timber.d("Network callback registered")
        } catch (e: Exception) {
            Timber.e(e, "Failed to register network callback")
        }
    }

    fun unregisterNetworkCallback() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            Timber.d("Network callback unregistered")
        } catch (e: Exception) {
            Timber.e(e, "Failed to unregister network callback")
        }
    }
}
