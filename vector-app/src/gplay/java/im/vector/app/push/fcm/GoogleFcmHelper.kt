/*
 * Copyright 2018-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */
package im.vector.app.push.fcm

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.widget.Toast
import androidx.core.content.edit
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.qualifiers.ApplicationContext
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.di.DefaultPreferences
import im.vector.app.core.dispatchers.CoroutineDispatchers
import im.vector.app.core.pushers.FcmHelper
import im.vector.app.core.pushers.PushersManager
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * This class store the FCM token in SharedPrefs and ensure this token is retrieved.
 * It has an alter ego in the fdroid variant.
 */
class GoogleFcmHelper @Inject constructor(
        @ApplicationContext private val context: Context,
        @DefaultPreferences private val sharedPrefs: SharedPreferences,
        appScope: CoroutineScope,
        private val coroutineDispatchers: CoroutineDispatchers,
) : FcmHelper {

    private val scope = CoroutineScope(appScope.coroutineContext + coroutineDispatchers.io)

    companion object {
        private const val PREFS_KEY_FCM_TOKEN = "FCM_TOKEN"
    }

    override fun isFirebaseAvailable(): Boolean = true

    override fun getFcmToken(): String? {
        return sharedPrefs.getString(PREFS_KEY_FCM_TOKEN, null)
    }

    override fun storeFcmToken(token: String?) {
        // TODO Store in realm
        sharedPrefs.edit {
            putString(PREFS_KEY_FCM_TOKEN, token)
        }
    }

    override fun ensureFcmTokenIsRetrieved(pushersManager: PushersManager, registerPusher: Boolean) {
        // 'app should always check the device for a compatible Google Play services APK before accessing Google Play services features'
        if (checkPlayServices(context)) {
            scope.launch {
                try {
                    val currentVersionCode = try {
                        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                            packageInfo.longVersionCode
                        } else {
                            @Suppress("DEPRECATION")
                            packageInfo.versionCode.toLong()
                        }
                    } catch (e: Exception) {
                        -1L
                    }

                    val lastVersionCode = sharedPrefs.getLong("LAST_KNOWN_APP_VERSION", -1L)
                    val tokenFreshnessMarker = sharedPrefs.getBoolean("TOKEN_FRESHNESS_MARKER_V2", false)
                    val shouldForceTokenRefresh = (currentVersionCode != lastVersionCode) || !tokenFreshnessMarker

                    if (shouldForceTokenRefresh) {
                        try {
                            Timber.d("Forcing FCM token refresh to avoid stale tokens (update/reinstall/clear data detected)")
                            com.google.android.gms.tasks.Tasks.await(FirebaseMessaging.getInstance().deleteToken())
                            Timber.d("Stale FCM token successfully deleted from Firebase")
                        } catch (e: Exception) {
                            Timber.w("Failed to delete FCM token: ${e.message}")
                        }
                    }

                    val token = com.google.android.gms.tasks.Tasks.await(FirebaseMessaging.getInstance().token)
                    if (token != null) {
                        Timber.d("current FCM token: $token")
                        Timber.d("token generated: $token")
                        Timber.d("token generated / retrieved successfully")
                        
                        if (shouldForceTokenRefresh) {
                            sharedPrefs.edit {
                                putLong("LAST_KNOWN_APP_VERSION", currentVersionCode)
                                putBoolean("TOKEN_FRESHNESS_MARKER_V2", true)
                            }
                        }

                        val currentPusher = pushersManager.getPusherForCurrentSession()
                        val noPusher = currentPusher == null
                        val tokenChanged = currentPusher?.pushKey != token
                        
                        if (tokenChanged) {
                            Timber.d("token refreshed: $token")
                            Timber.d("token refreshed")
                        }
                        Timber.d("## ensureFcmTokenIsRetrieved() : Retrieved new FCM token, length=${token.length}, tokenChanged=$tokenChanged, registerPusher=$registerPusher")
                        storeFcmToken(token)
                        
                        // Clean up existing stale pushers
                        pushersManager.unregisterStalePushers(token)
                        
                        if (registerPusher || noPusher || tokenChanged) {
                            Timber.d("## ensureFcmTokenIsRetrieved() : Registering pusher with FCM token")
                            Timber.d("token sent to backend / Matrix push registration")
                            try {
                                pushersManager.enqueueRegisterPusherWithFcmKey(token)
                                Timber.d("push registration success")
                                Timber.d("token registered to Matrix")
                                Timber.d("## ensureFcmTokenIsRetrieved() : Successfully registered pusher with FCM token")
                            } catch (e: Exception) {
                                Timber.e(e, "push registration failure")
                                Timber.e(e, "## ensureFcmTokenIsRetrieved() : Failed to register pusher with FCM token")
                            }
                        }
                    } else {
                        Timber.e("## ensureFcmTokenIsRetrieved() : Retrieved token was null")
                    }
                } catch (e: Throwable) {
                    Timber.e(e, "## ensureFcmTokenIsRetrieved() : task failed")
                }
            }
        } else {
            Toast.makeText(context, CommonStrings.no_valid_google_play_services_apk, Toast.LENGTH_SHORT).show()
            Timber.e("No valid Google Play Services found. Cannot use FCM.")
        }
    }
// Add inside GoogleFcmHelper or a separate PermissionHelper

    fun checkFullScreenIntentPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 34) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
                    as android.app.NotificationManager
            val canUse = nm.canUseFullScreenIntent()
            Timber.d("USE_FULL_SCREEN_INTENT permission granted=$canUse")
            if (!canUse) {
                // On Android 14, must redirect user to settings
                // Intent: Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT
                Timber.w("Full screen intent not permitted — call notification will NOT show on lock screen")
            }
            return canUse
        }
        return true
    }
    /**
     * Check the device to make sure it has the Google Play Services APK. If
     * it doesn't, display a dialog that allows users to download the APK from
     * the Google Play Store or enable it in the device's system settings.
     */
    private fun checkPlayServices(context: Context): Boolean {
        val apiAvailability = GoogleApiAvailability.getInstance()
        val resultCode = apiAvailability.isGooglePlayServicesAvailable(context)
        return resultCode == ConnectionResult.SUCCESS
    }

    override fun onEnterForeground(activeSessionHolder: ActiveSessionHolder) {
        // On FCM/GPlay builds, FCM is the sole notification wakeup mechanism.
        // Do NOT cancel background sync here — a FCM-triggered requireBackgroundSync()
        // may still be running (e.g. the user opened the app right as a push arrived).
        // Cancelling it here would cause the notification to be missed.
    }

    override fun onEnterBackground(activeSessionHolder: ActiveSessionHolder) {
        // On FCM/GPlay builds, FCM wakes the app via push; the WorkManager periodic
        // sync (BackgroundSyncStarter) is only needed for F-Droid builds without FCM.
        // Starting it here conflicts with requireBackgroundSync() (same work-name, different
        // ExistingWorkPolicy), causing pushes to be dropped randomly in the background.
    }
}
