/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.StrictMode
import android.util.Log
import android.view.Gravity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.provider.FontRequest
import androidx.core.provider.FontsContractCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.multidex.MultiDex
import androidx.recyclerview.widget.SnapHelper
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.airbnb.epoxy.Carousel
import com.airbnb.epoxy.EpoxyAsyncUtil
import com.airbnb.epoxy.EpoxyController
import com.airbnb.mvrx.Mavericks
import com.facebook.stetho.Stetho
import com.gabrielittner.threetenbp.LazyThreeTen
import com.github.rubensousa.gravitysnaphelper.GravitySnapHelper
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.vanniktech.emoji.EmojiManager
import com.vanniktech.emoji.google.GoogleEmojiProvider
import dagger.hilt.android.HiltAndroidApp
//import im.vector.app.backgroundsync.receiver.NetworkChangeReceiver
import im.vector.app.core.debug.FlipperProxy
import im.vector.app.core.debug.LeakDetector
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.network.NetworkConnectionMonitor
import im.vector.app.core.pushers.FcmHelper
import im.vector.app.core.pushers.PushersManager
import im.vector.app.core.resources.BuildMeta
import im.vector.app.core.worker.MessageBackupWorker
import im.vector.app.features.analytics.DecryptionFailureTracker
import im.vector.app.features.analytics.VectorAnalytics
import im.vector.app.features.analytics.plan.SuperProperties
import im.vector.app.features.call.webrtc.WebRtcCallManager
import im.vector.app.features.configuration.VectorConfiguration
import im.vector.app.features.invite.InvitesAcceptor
import im.vector.app.features.lifecycle.VectorActivityLifecycleCallbacks
import im.vector.app.features.link.VectorReferrerHandler
import im.vector.app.features.notifications.NotificationDrawerManager
import im.vector.app.features.notifications.NotificationUtils
import im.vector.app.features.pin.PinLocker
import im.vector.app.features.popup.PopupAlertManager
import im.vector.app.features.presence.SimplePresenceHandler
import im.vector.app.features.rageshake.VectorFileLogger
import im.vector.app.features.rageshake.VectorUncaughtExceptionHandler
import im.vector.app.features.settings.VectorLocale
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.themes.ThemeUtils
import im.vector.app.features.version.VersionProvider
import im.vector.application.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jitsi.meet.sdk.log.JitsiMeetDefaultLogHandler
import org.maplibre.android.MapLibre
import org.matrix.android.sdk.api.Matrix
import org.matrix.android.sdk.api.MatrixCallback
import org.matrix.android.sdk.api.auth.AuthenticationService
import org.matrix.android.sdk.api.session.pushers.Pusher
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import androidx.work.Configuration as WorkConfiguration

@HiltAndroidApp
class VectorApplication :
        Application(),
        WorkConfiguration.Provider {

    lateinit var appContext: Context
    @Inject lateinit var networkConnectionMonitor: NetworkConnectionMonitor


    @Inject lateinit var authenticationService: AuthenticationService
    @Inject lateinit var vectorConfiguration: VectorConfiguration
    @Inject lateinit var emojiCompatFontProvider: EmojiCompatFontProvider
    @Inject lateinit var emojiCompatWrapper: EmojiCompatWrapper
    @Inject lateinit var vectorUncaughtExceptionHandler: VectorUncaughtExceptionHandler
    @Inject lateinit var activeSessionHolder: ActiveSessionHolder
    @Inject lateinit var notificationDrawerManager: NotificationDrawerManager
    @Inject lateinit var vectorPreferences: VectorPreferences
    @Inject lateinit var versionProvider: VersionProvider
    @Inject lateinit var notificationUtils: NotificationUtils
    @Inject lateinit var spaceStateHandler: SpaceStateHandler
    @Inject lateinit var popupAlertManager: PopupAlertManager
    @Inject lateinit var pinLocker: PinLocker
    @Inject lateinit var callManager: WebRtcCallManager
    @Inject lateinit var invitesAcceptor: InvitesAcceptor
    @Inject lateinit var autoRageShaker: AutoRageShaker
    @Inject lateinit var decryptionFailureTracker: DecryptionFailureTracker
    @Inject lateinit var vectorFileLogger: VectorFileLogger
    @Inject lateinit var vectorAnalytics: VectorAnalytics
    @Inject lateinit var flipperProxy: FlipperProxy
    @Inject lateinit var matrix: Matrix
    @Inject lateinit var fcmHelper: FcmHelper
    @Inject lateinit var pushersManager: PushersManager
    @Inject lateinit var buildMeta: BuildMeta
    @Inject lateinit var leakDetector: LeakDetector
    @Inject lateinit var vectorLocale: VectorLocale
    @Inject lateinit var webRtcCallManager: WebRtcCallManager
    @Inject lateinit var vectorReferrerHandler: VectorReferrerHandler
    @Inject lateinit var presenceHandler: SimplePresenceHandler

    // font thread handler
    private var fontThreadHandler: Handler? = null
  //  private lateinit var networkChangeReceiver: NetworkChangeReceiver

    private val powerKeyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF &&
                    vectorPreferences.useFlagPinCode()
            ) {
                pinLocker.screenIsOff()
            }
        }
    }


    companion object {
        private const val RC_CALL_PERMS = 1001
    }


    override fun onCreate() {
        //enableStrictModeIfNeeded()
        super.onCreate()
        FirebaseApp.initializeApp(this)
        appContext = this
        networkConnectionMonitor
        // Initialize core components
        flipperProxy.init(matrix)
        vectorAnalytics.init()
        presenceHandler.setupLifecycleCallback()
//        networkChangeReceiver = NetworkChangeReceiver(this)
//        networkChangeReceiver.register()
        observeSessionForDbClear()
        registerCallPermissionPrompt()
        vectorAnalytics.updateSuperProperties(
                SuperProperties(
                        appPlatform = SuperProperties.AppPlatform.EA,
                        cryptoSDK = SuperProperties.CryptoSDK.Rust,
                        cryptoSDKVersion = Matrix.getCryptoVersion(longFormat = false)
                )
        )

        invitesAcceptor.initialize()
        autoRageShaker.initialize()
        decryptionFailureTracker.start()
        vectorUncaughtExceptionHandler.activate()
        vectorReferrerHandler.started()

        // Remove Log handler statically added by Jitsi
        Timber.forest()
                .filterIsInstance(JitsiMeetDefaultLogHandler::class.java)
                .forEach { Timber.uproot(it) }

        // Setup logging
        if (buildMeta.isDebug) {
            Timber.plant(Timber.DebugTree())
        }
        Timber.plant(vectorFileLogger)

        if (buildMeta.isDebug) {
            Stetho.initializeWithDefaults(this)
        }

        logInfo()
        LazyThreeTen.init(this)
        Mavericks.initialize(debugMode = false)

        configureEpoxy()

        registerActivityLifecycleCallbacks(VectorActivityLifecycleCallbacks(popupAlertManager))

        val fontRequest = FontRequest(
                "com.google.android.gms.fonts",
                "com.google.android.gms",
                "Noto Color Emoji Compat",
                R.array.com_google_android_gms_fonts_certs
        )

        @Suppress("DEPRECATION")
        FontsContractCompat.requestFont(this, fontRequest, emojiCompatFontProvider, getFontThreadHandler())

        vectorLocale.init()
        ThemeUtils.init(this)
        vectorConfiguration.applyToApplicationContext()
        emojiCompatWrapper.init(fontRequest)
        notificationUtils.createNotificationChannels()

        // Simplified lifecycle observer - never stops sync
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                Timber.i("App entered foreground")
                fcmHelper.onEnterForeground(activeSessionHolder)
                activeSessionHolder.getSafeActiveSessionAsync {
                    it?.syncService()?.startSync(true)
                }
            }

            override fun onPause(owner: LifecycleOwner) {
                Timber.i("App entered background")
                fcmHelper.onEnterBackground(activeSessionHolder)
                // Keep sync running in background
                activeSessionHolder.getSafeActiveSessionAsync {
                    it?.syncService()?.startSync(true)
                }
            }
        })

        ProcessLifecycleOwner.get().lifecycle.addObserver(spaceStateHandler)
        ProcessLifecycleOwner.get().lifecycle.addObserver(pinLocker)
        ProcessLifecycleOwner.get().lifecycle.addObserver(callManager)

        // Register power key receiver
//        ContextCompat.registerReceiver(
//                applicationContext,
//                powerKeyReceiver,
//                IntentFilter().apply {
//                    addAction(Intent.ACTION_SCREEN_OFF)
//                    addAction(Intent.ACTION_SCREEN_ON)
//                },
//                //ContextCompat.RECEIVER_NOT_EXPORTED,
//        )

        EmojiManager.install(GoogleEmojiProvider())

        // Initialize MapLibre
        MapLibre.getInstance(this)

        initMemoryLeakAnalysis()

        // Verify FCM token and pusher registration
        verifyPushRegistration()
        scheduleDummyBackup()
    }
    private fun observeSessionForDbClear() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            private var lastSessionId: String? = null

            override fun onResume(owner: LifecycleOwner) {
                val currentId = activeSessionHolder.getSafeActiveSession()?.sessionId
                if (lastSessionId != null && currentId == null) clearDatabaseOnSignOut()
                lastSessionId = currentId
            }
        })
    }

    private fun clearDatabaseOnSignOut() {
        Timber.d("Session ended — clearing local databases")
        CoroutineScope(Dispatchers.IO).launch {
            listOf("matrix-sdk-db", "vector-db", "crypto_db").forEach { name ->
                runCatching { applicationContext.deleteDatabase(name) }
                        .onSuccess { Timber.d("Deleted DB: $name") }
                        .onFailure { Timber.e(it, "Failed to delete DB: $name") }
            }
        }
    }

    private fun registerCallPermissionPrompt() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var prompted = false

            override fun onActivityResumed(activity: Activity) {
                if (prompted || !activeSessionHolder.hasActiveSession()) return
                val missing = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
                        .filter { ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED }
                if (missing.isEmpty()) { prompted = true; return }
                prompted = true
                AlertDialog.Builder(activity)
                        .setTitle("Call Permissions Required")
                        .setMessage("Grant microphone and camera access to make audio/video calls.")
                        .setPositiveButton("Grant") { _, _ ->
                            ActivityCompat.requestPermissions(activity, missing.toTypedArray(), RC_CALL_PERMS)
                        }
                        .setNegativeButton("Later", null)
                        .show()
            }

            override fun onActivityCreated(activity: Activity, b: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, b: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
    private fun verifyPushRegistration() {
        Handler(mainLooper).postDelayed({
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val token = task.result
                    Timber.d("FCM Token obtained: $token")

                    // Ensure the pusher is registered even if onNewToken() hasn't run recently
                    // (token cached, upgrade, etc.).
                    runCatching {
                        fcmHelper.ensureFcmTokenIsRetrieved(
                            pushersManager = pushersManager,
                            registerPusher = vectorPreferences.areNotificationEnabledForDevice()
                        )
                    }.onFailure { e ->
                        Timber.e(e, "Failed to ensure FCM token/pusher registration")
                    }
                } else {
                    Timber.e("Failed to get FCM token: ${task.exception?.message}")
                }
            }
        }, 10000)
    }

    private fun configureEpoxy() {
        EpoxyController.defaultDiffingHandler = EpoxyAsyncUtil.getAsyncBackgroundHandler()
        EpoxyController.defaultModelBuildingHandler = EpoxyAsyncUtil.getAsyncBackgroundHandler()
        Carousel.setDefaultGlobalSnapHelperFactory(object : Carousel.SnapHelperFactory() {
            override fun buildSnapHelper(context: Context?): SnapHelper {
                return GravitySnapHelper(Gravity.START)
            }
        })
    }

//    private fun enableStrictModeIfNeeded() {
//        if (BuildConfig.DEBUG) {
//            StrictMode.setThreadPolicy(
//                    StrictMode.ThreadPolicy.Builder()
//                            .detectAll()
//                            .penaltyLog()
//                            .build()
//            )
//
//            StrictMode.setVmPolicy(
//                    StrictMode.VmPolicy.Builder()
//                            .detectAll()
//                            .penaltyLog()
//                            .build()
//            )
//        }
//    }

    override fun getWorkManagerConfiguration(): WorkConfiguration {
        return WorkConfiguration.Builder()
                .setWorkerFactory(matrix.getWorkerFactory())
                .setMinimumLoggingLevel(Log.DEBUG)
                .setExecutor(Executors.newCachedThreadPool())
                .build()
    }

    private fun logInfo() {
        val appVersion = versionProvider.getVersion(longFormat = true)
        val sdkVersion = Matrix.getSdkVersion()
        val date = SimpleDateFormat("MM-dd HH:mm:ss.SSSZ", Locale.US).format(Date())

        Timber.d("----------------------------------------------------------------")
        Timber.d("----------------------------------------------------------------")
        Timber.d(" Application version: $appVersion")
        Timber.d(" SDK version: $sdkVersion")
        Timber.d(" Local time: $date")
        Timber.d(" Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        Timber.d(" Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        Timber.d("----------------------------------------------------------------")
        Timber.d("----------------------------------------------------------------\n\n\n\n")
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        MultiDex.install(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        vectorConfiguration.onConfigurationChanged()
    }

    private fun getFontThreadHandler(): Handler {
        return fontThreadHandler ?: createFontThreadHandler().also {
            fontThreadHandler = it
        }
    }

    private fun createFontThreadHandler(): Handler {
        val handlerThread = HandlerThread("Vector-fonts")
        handlerThread.start()
        return Handler(handlerThread.looper)
    }

    private fun initMemoryLeakAnalysis() {
        leakDetector.enable(vectorPreferences.isMemoryLeakAnalysisEnabled())
    }
    private fun scheduleDummyBackup() {
        val workRequest = PeriodicWorkRequestBuilder<MessageBackupWorker>(
                15, TimeUnit.MINUTES
        ).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "dummy_backup",
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
        )
    }
}
