/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home


import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import androidx.work.*
import java.util.concurrent.TimeUnit
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import com.airbnb.mvrx.Mavericks
import com.airbnb.mvrx.viewModel
import com.airbnb.mvrx.withState
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.SpaceStateHandler
import im.vector.app.core.extensions.hideKeyboard
import im.vector.app.core.extensions.registerStartForActivityResult
import im.vector.app.core.extensions.replaceFragment
import im.vector.app.core.extensions.restart
import im.vector.app.core.extensions.validateBackPressed
import im.vector.app.core.extensions.singletonEntryPoint
import im.vector.app.core.platform.VectorBaseActivity
import im.vector.app.core.platform.VectorMenuProvider
import im.vector.app.core.pushers.FcmHelper
import im.vector.app.core.pushers.UnifiedPushHelper
import im.vector.app.core.utils.isIgnoringBatteryOptimizations
import im.vector.app.core.utils.registerForPermissionsResult
import im.vector.app.core.utils.requestDisablingBatteryOptimization
import im.vector.app.core.utils.startNotificationSettingsIntent
import im.vector.app.core.utils.startSharePlainTextIntent
import im.vector.app.databinding.ActivityHomeBinding
import im.vector.app.features.MainActivity
import im.vector.app.features.MainActivityArgs
import im.vector.app.features.analytics.accountdata.AnalyticsAccountDataViewModel
import im.vector.app.features.analytics.plan.MobileScreen
import im.vector.app.features.analytics.plan.ViewRoom
import im.vector.app.features.crypto.recover.SetupMode
import im.vector.app.features.home.callhometab.CallScreenComposeUI
import im.vector.app.features.home.room.list.actions.RoomListSharedAction
import im.vector.app.features.home.room.list.actions.RoomListSharedActionViewModel
import im.vector.app.features.home.room.list.home.layout.HomeLayoutSettingBottomDialogFragment
import im.vector.app.features.home.room.list.home.release.ReleaseNotesActivity
import im.vector.app.features.matrixto.MatrixToBottomSheet
import im.vector.app.features.matrixto.OriginOfMatrixTo
import im.vector.app.features.navigation.Navigator
import im.vector.app.features.notifications.NotificationDrawerManager
import im.vector.app.features.onboarding.AuthenticationDescription
import im.vector.app.features.permalink.NavigationInterceptor
import im.vector.app.features.permalink.PermalinkHandler
import im.vector.app.features.permalink.PermalinkHandler.Companion.MATRIX_TO_CUSTOM_SCHEME_URL_BASE
import im.vector.app.features.permalink.PermalinkHandler.Companion.ROOM_LINK_PREFIX
import im.vector.app.features.permalink.PermalinkHandler.Companion.USER_LINK_PREFIX
import im.vector.app.features.popup.DefaultVectorAlert
import im.vector.app.features.popup.PopupAlertManager
import im.vector.app.features.popup.VerificationVectorAlert
import im.vector.app.features.rageshake.ReportType
import im.vector.app.features.rageshake.VectorUncaughtExceptionHandler
import im.vector.app.features.session.coroutineScope
import im.vector.app.features.settings.VectorSettingsActivity
import im.vector.app.features.spaces.SpaceCreationActivity
import im.vector.app.features.spaces.SpacePreviewActivity
import im.vector.app.features.spaces.SpaceSettingsMenuBottomSheet
import im.vector.app.features.spaces.invite.SpaceInviteBottomSheet
import im.vector.app.features.spaces.share.ShareSpaceBottomSheet
import im.vector.app.features.themes.ThemeUtils
import im.vector.app.features.usercode.UserCodeActivity
import im.vector.app.features.workers.signout.ServerBackupStatusViewModel
import im.vector.app.features.workers.signout.SignOutUiWorker
import im.vector.lib.core.utils.compat.getParcelableExtraCompat
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import im.vector.app.features.invite.InviteFriendsBottomSheet
import im.vector.app.features.workers.sync.SyncWorker
import org.matrix.android.sdk.api.session.permalinks.PermalinkService
import org.matrix.android.sdk.api.session.sync.InitialSyncStrategy
import org.matrix.android.sdk.api.session.sync.SyncRequestState
import org.matrix.android.sdk.api.session.sync.initialSyncStrategy
import org.matrix.android.sdk.api.util.MatrixItem
import timber.log.Timber
import javax.inject.Inject
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability

@Parcelize
data class HomeActivityArgs(
        val clearNotification: Boolean,
        val authenticationDescription: AuthenticationDescription? = null,
        val hasExistingSession: Boolean = false,
        val inviteNotificationRoomId: String? = null
) : Parcelable

@AndroidEntryPoint
class HomeActivity :
        VectorBaseActivity<ActivityHomeBinding>(),
        NavigationInterceptor,
        SpaceInviteBottomSheet.InteractionListener,
        MatrixToBottomSheet.InteractionListener,
        VectorMenuProvider {

    private lateinit var sharedActionViewModel: HomeSharedActionViewModel
    private lateinit var roomListSharedActionViewModel: RoomListSharedActionViewModel
//    private lateinit var authStateListener: FirebaseAuth.AuthStateListener
    val homeActivityViewModel: HomeActivityViewModel by viewModel()

    // ─── In-App Update ────────────────────────────────────────────────────────
    private lateinit var appUpdateManager: AppUpdateManager

    private val installStateListener = InstallStateUpdatedListener { state ->
        when (state.installStatus()) {
            InstallStatus.DOWNLOADED -> completeUpdate()
            InstallStatus.FAILED     -> Timber.e("In-App Update download FAILED")
            InstallStatus.CANCELED   -> Timber.d("In-App Update download CANCELED")
            else                     -> Timber.d("In-App Update status: ${state.installStatus()}")
        }
    }

    private val updateFlowLauncher = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        when (result.resultCode) {
            RESULT_OK -> Timber.d("In-App Update: Update flow completed successfully")
            RESULT_CANCELED -> {
                Timber.w("In-App Update: Update flow cancelled by user")
                // If the update is mandatory, we show the fallback dialog again
                showForcedUpdateDialog()
            }
            com.google.android.play.core.install.model.ActivityResult.RESULT_IN_APP_UPDATE_FAILED ->
                Timber.e("In-App Update: Update flow failed")
            else -> Timber.w("In-App Update: flow dismissed or failed, resultCode=${result.resultCode}")
        }
    }

    @Suppress("UNUSED")
    private val analyticsAccountDataViewModel: AnalyticsAccountDataViewModel by viewModel()

    @Suppress("UNUSED")
    private val userColorAccountDataViewModel: UserColorAccountDataViewModel by viewModel()

    private val serverBackupStatusViewModel: ServerBackupStatusViewModel by viewModel()

    @Inject lateinit var vectorUncaughtExceptionHandler: VectorUncaughtExceptionHandler
    @Inject lateinit var notificationDrawerManager: NotificationDrawerManager
    @Inject lateinit var popupAlertManager: PopupAlertManager
    @Inject lateinit var shortcutsHandler: ShortcutsHandler
    @Inject lateinit var permalinkHandler: PermalinkHandler
    @Inject lateinit var avatarRenderer: AvatarRenderer
    @Inject lateinit var initSyncStepFormatter: InitSyncStepFormatter
    @Inject lateinit var spaceStateHandler: SpaceStateHandler
    @Inject lateinit var unifiedPushHelper: UnifiedPushHelper
    @Inject lateinit var nightlyProxy: NightlyProxy
    @Inject lateinit var notificationPermissionManager: NotificationPermissionManager

    private var isNewAppLayoutEnabled: Boolean = false // delete once old app layout is removed

    private val createSpaceResultLauncher = registerStartForActivityResult { activityResult ->
        if (activityResult.resultCode == Activity.RESULT_OK) {
            val spaceId = SpaceCreationActivity.getCreatedSpaceId(activityResult.data)
            val defaultRoomId = SpaceCreationActivity.getDefaultRoomId(activityResult.data)
            val isJustMe = SpaceCreationActivity.isJustMeSpace(activityResult.data)
            views.drawerLayout.closeDrawer(GravityCompat.START)

            val postSwitchOption: Navigator.PostSwitchSpaceAction = if (defaultRoomId != null) {
                Navigator.PostSwitchSpaceAction.OpenDefaultRoom(defaultRoomId, !isJustMe)
            } else if (isJustMe) {
                Navigator.PostSwitchSpaceAction.OpenAddExistingRooms
            } else {
                Navigator.PostSwitchSpaceAction.None
            }
            // Here we want to change current space to the newly created one, and then immediately open the default room
            if (spaceId != null) {
                navigator.switchToSpace(
                        context = this,
                        spaceId = spaceId,
                        postSwitchOption,
                )
                roomListSharedActionViewModel.post(RoomListSharedAction.CloseBottomSheet)
            }
        }
    }


//    @RequiresApi(Build.VERSION_CODES.M)
//    private val overlayPermissionLauncher = registerForActivityResult(
//            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
//    ) {
//        if (android.provider.Settings.canDrawOverlays(this)) {
//            // User granted overlay ✅
//            checkAppNotificationSettings()
//        } else {
//            // NOT granted — force ask again, no way to skip
//            checkOverlayPermission()
//        }
//    }
@RequiresApi(Build.VERSION_CODES.M)
private val overlayPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
) {
    if (android.provider.Settings.canDrawOverlays(this)) {
        // ✅ Granted — proceed
        checkAppNotificationSettings()
    } else {
        // Not granted — show dialog again (not immediate redirect)
        checkOverlayPermission()
    }
}
    private val batteryOptimizationLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
        checkFullScreenIntent()
    }

//    private val fullScreenIntentSettingsLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
//        checkOverlayPermission()
//    }
private val fullScreenIntentSettingsLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
) {
    // ✅ Skip overlay — go straight to notification settings
    checkAppNotificationSettings()
}
    private val appNotificationSettingsLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
        // Done with unified flow
    }

    private val appSettingsLauncher = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) {
        checkBatteryOptimization() // → battery → overlay
    }
//private val startAppPermissionsLauncher = registerForPermissionsResult { allGranted, deniedPermanently ->
//    if (allGranted) {
//        vectorPreferences.setNotificationEnabledForDevice(true)
//        homeActivityViewModel.handle(
//                HomeActivityViewActions.RegisterPushDistributor(distributor = "")
//        )
//        checkBatteryOptimization()
//    } else if (deniedPermanently) {
//        // Some permissions permanently denied — send user to app settings first
//        MaterialAlertDialogBuilder(this)
//                .setTitle("Permissions Required")
//                .setMessage("Some permissions were denied. Please enable them in app settings to continue.")
//                .setCancelable(false)
//                .setPositiveButton("Open Settings") { _, _ ->
//                    val intent = Intent(
//                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
//                            Uri.parse("package:$packageName")
//                    )
//                    appSettingsLauncher.launch(intent)
//                }
//                .show()
//    } else {
//        // Denied but not permanently — still proceed
//        checkBatteryOptimization()
//    }
//}

    private val startAppPermissionsLauncher = registerForPermissionsResult { allGranted, deniedPermanently ->
        if (allGranted) {
            vectorPreferences.setNotificationEnabledForDevice(true)
            homeActivityViewModel.handle(
                    HomeActivityViewActions.RegisterPushDistributor(distributor = "")
            )
            checkBatteryOptimization() // → battery → overlay
        } else if (deniedPermanently) {
            MaterialAlertDialogBuilder(this)
                    .setTitle("Permissions Required")
                    .setMessage("Some permissions were denied. Please enable them in app settings.")
                    .setCancelable(false)
                    .setPositiveButton("Open Settings") { _, _ ->
                        val intent = Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:$packageName")
                        )
                        appSettingsLauncher.launch(intent) // → app settings → battery → overlay
                    }
                    .show()
        } else {
            checkBatteryOptimization() // → battery → overlay
        }
    }


    private val postPermissionLauncher = registerForPermissionsResult { allGranted, _ ->
        if (allGranted) {
            // Permission granted — enable notifications in preferences and re-register push
            vectorPreferences.setNotificationEnabledForDevice(true)
            homeActivityViewModel.handle(HomeActivityViewActions.RegisterPushDistributor(distributor = ""))
        }
    }

    private val fragmentLifecycleCallbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
            if (f is MatrixToBottomSheet) {
                f.interactionListener = this@HomeActivity
            }
            super.onFragmentResumed(fm, f)
        }

        override fun onFragmentPaused(fm: FragmentManager, f: Fragment) {
            if (f is MatrixToBottomSheet) {
                f.interactionListener = null
            }
            super.onFragmentPaused(fm, f)
        }
    }

    private val drawerListener = object : DrawerLayout.SimpleDrawerListener() {
        override fun onDrawerOpened(drawerView: View) {
            analyticsTracker.screen(MobileScreen(screenName = MobileScreen.ScreenName.Sidebar))
        }

        override fun onDrawerStateChanged(newState: Int) {
            hideKeyboard()
        }
    }

    override fun getCoordinatorLayout() = views.coordinatorLayout

    override fun getBinding() = ActivityHomeBinding.inflate(layoutInflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isNewAppLayoutEnabled = vectorPreferences.isNewAppLayoutEnabled()
        analyticsScreenName = MobileScreen.ScreenName.Home
        supportFragmentManager.registerFragmentLifecycleCallbacks(fragmentLifecycleCallbacks, false)
        sharedActionViewModel = viewModelProvider[HomeSharedActionViewModel::class.java]
        roomListSharedActionViewModel = viewModelProvider[RoomListSharedActionViewModel::class.java]
        views.drawerLayout.addDrawerListener(drawerListener)
        val pagerAdapter = HomePagerAdapter(this)
        views.homeDetailViewPager.adapter = pagerAdapter
        views.homeDetailViewPager.isUserInputEnabled = true // Enable dragging/swiping

//        if (!FirebaseSessionManager.isUserSignedIn()) {
//            // Redirect to login
//            startActivity(Intent(this, LoginActivity::class.java))
//            finish()
//            return
//        }

        views.bottomNavigationView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_chats -> {
                    views.homeDetailViewPager.setCurrentItem(0, true)
                    true
                }
                R.id.nav_calls -> {
                    views.homeDetailViewPager.setCurrentItem(1, true)
                    true
                }
                else -> false
            }
        }

        views.homeDetailViewPager.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                when (position) {
                    0 -> views.bottomNavigationView.selectedItemId = R.id.nav_chats
                    1 -> views.bottomNavigationView.selectedItemId = R.id.nav_calls
                }
            }
        })

        if (isFirstCreation()) {
            views.bottomNavigationView.selectedItemId = R.id.nav_chats
            views.homeDetailViewPager.setCurrentItem(0, false)
        }

        sharedActionViewModel
                .stream()
                .onEach { sharedAction ->
                    when (sharedAction) {
                        is HomeActivitySharedAction.OpenDrawer -> views.drawerLayout.openDrawer(GravityCompat.START)
                        is HomeActivitySharedAction.CloseDrawer -> views.drawerLayout.closeDrawer(GravityCompat.START)
                        is HomeActivitySharedAction.OpenSpacePreview -> startActivity(SpacePreviewActivity.newIntent(this, sharedAction.spaceId))
                        is HomeActivitySharedAction.AddSpace -> createSpaceResultLauncher.launch(SpaceCreationActivity.newIntent(this))
                        is HomeActivitySharedAction.ShowSpaceSettings -> showSpaceSettings(sharedAction.spaceId)
                        is HomeActivitySharedAction.OpenSpaceInvite -> openSpaceInvite(sharedAction.spaceId)
                        HomeActivitySharedAction.SendSpaceFeedBack -> bugReporter.openBugReportScreen(this, ReportType.SPACE_BETA_FEEDBACK)
                        HomeActivitySharedAction.OnCloseSpace -> onCloseSpace()
                    }
                }
                .launchIn(lifecycleScope)

        val args = intent.getParcelableExtraCompat<HomeActivityArgs>(Mavericks.KEY_ARG)

        if (args?.clearNotification == true) {
            notificationDrawerManager.clearAllEvents()
        }
        if (args?.inviteNotificationRoomId != null) {
            activeSessionHolder.getSafeActiveSession()?.permalinkService()?.createPermalink(args.inviteNotificationRoomId)?.let {
                navigator.openMatrixToBottomSheet(this, it, OriginOfMatrixTo.NOTIFICATION)
            }
        }

        homeActivityViewModel.observeViewEvents {
            when (it) {
               // is HomeActivityViewEvents.AskPasswordToInitCrossSigning -> handleAskPasswordToInitCrossSigning(it)
             //   is HomeActivityViewEvents.CurrentSessionNotVerified -> handleOnNewSession(it)
             //   is HomeActivityViewEvents.CurrentSessionCannotBeVerified -> handleCantVerify(it)
                HomeActivityViewEvents.PromptToEnableSessionPush -> handlePromptToEnablePush()
                HomeActivityViewEvents.StartRecoverySetupFlow -> handleStartRecoverySetup()
//                is HomeActivityViewEvents.ForceVerification -> {
//                    navigator.requestSelfSessionVerification(this)
//                }
             //   is HomeActivityViewEvents.OnCrossSignedInvalidated -> handleCrossSigningInvalidated(it)
                HomeActivityViewEvents.ShowAnalyticsOptIn -> handleShowAnalyticsOptIn()
                HomeActivityViewEvents.ShowNotificationDialog -> handleShowNotificationDialog()
                HomeActivityViewEvents.ShowReleaseNotes -> handleShowReleaseNotes()
                HomeActivityViewEvents.NotifyUserForThreadsMigration -> handleNotifyUserForThreadsMigration()
                is HomeActivityViewEvents.MigrateThreads -> migrateThreadsIfNeeded(it.checkSession)
                is HomeActivityViewEvents.AskUserForPushDistributor -> askUserToSelectPushDistributor()
                else -> {
                    Timber.e( "HomeActivity: handle failed due to exception")
                }
            }
        }
        homeActivityViewModel.onEach { renderState(it) }

        shortcutsHandler.observeRoomsAndBuildShortcuts(lifecycleScope)

        if (isFirstCreation()) {
            handleIntent(intent)
        }
        homeActivityViewModel.handle(HomeActivityViewActions.ViewStarted)
        // Ensure notifications are enabled for the device first
        ensureNotificationsEnabled()
        if (isFirstCreation()) {
            showUnifiedPermissionDialog()
        }

        // ─── In-App Update (Google Play) ─────────────────────────────────────
        appUpdateManager = AppUpdateManagerFactory.create(this)
        appUpdateManager.registerListener(installStateListener)
        // Check will happen in onResume to avoid overlapping with permission dialogs
    }
    override fun onResume() {
        super.onResume()
//        authStateListener = FirebaseAuth.AuthStateListener { auth ->
//            if (auth.currentUser == null) {
//                // Session expired or signed out
//                runOnUiThread {
//                    MaterialAlertDialogBuilder(this)
//                            .setTitle("Session Expired")
//                            .setMessage("Your session has expired. Please sign in again.")
//                            .setCancelable(false)
//                            .setPositiveButton("Sign In") { _, _ ->
//                                startActivity(Intent(this, LoginActivity::class.java))
//                                finish()
//                            }
//                            .show()
//                }
//            }
//        }
//        FirebaseAuth.getInstance().addAuthStateListener(authStateListener)
//        if (vectorUncaughtExceptionHandler.didAppCrash()) {
//            vectorUncaughtExceptionHandler.clearAppCrashStatus()
//
//            MaterialAlertDialogBuilder(this)
//                    .setMessage(CommonStrings.send_bug_report_app_crashed)
//                    .setCancelable(false)
//                    .setPositiveButton(CommonStrings.yes) { _, _ -> bugReporter.openBugReportScreen(this) }
//                    .setNegativeButton(CommonStrings.no) { _, _ -> bugReporter.deleteCrashFile() }
//                    .show()
//        }

        // Force remote backup state update to update the banner if needed
        serverBackupStatusViewModel.refreshRemoteStateIfNeeded()

        // Check nightly
        if (nightlyProxy.canDisplayPopup()) {
            nightlyProxy.updateApplication()
        }

        checkNewAppLayoutFlagChange()

        // Check for updates or resume a stale update that was interrupted
        if (::appUpdateManager.isInitialized) {
            checkForAppUpdate()
        }
    }
    override fun onPause() {
        super.onPause()
       // FirebaseAuth.getInstance().removeAuthStateListener(authStateListener)
    }
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                            this, Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                        1001
                )
            }
        }
    }
    private fun showUnifiedPermissionDialog() {
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("permission_dialog_shown", false)) return
        prefs.edit().putBoolean("permission_dialog_shown", true).apply()

        val permissionsToRequest = mutableListOf(
                android.Manifest.permission.RECORD_AUDIO,
                android.Manifest.permission.CAMERA
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = permissionsToRequest.any {
            androidx.core.content.ContextCompat.checkSelfPermission(this, it) ==
                    android.content.pm.PackageManager.PERMISSION_DENIED
        }
        val missingBattery = !isIgnoringBatteryOptimizations()

        if (missingPermissions || missingBattery) {
            MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.unified_permissions_title)
                    .setMessage(R.string.unified_permissions_message)
                    .setCancelable(false)
                    .setPositiveButton(CommonStrings.action_accept) { _, _ ->
                        if (missingPermissions) {
                            startAppPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
                        } else {
                            checkBatteryOptimization()
                        }
                    }
                    .setNegativeButton(CommonStrings.action_decline) { _, _ ->
                        // ✅ FIX: Don't loop. Just proceed — user declined, continue the flow.
                        checkBatteryOptimization()
                    }
                    .show()
        } else {
            checkBatteryOptimization()
        }
    }
//    private fun showUnifiedPermissionDialog() {
//        val permissionsToRequest = mutableListOf(
//                android.Manifest.permission.RECORD_AUDIO,
//                android.Manifest.permission.CAMERA
//        )
//        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
//            permissionsToRequest.add(android.Manifest.permission.POST_NOTIFICATIONS)
//        }
//
//        val missingPermissions = permissionsToRequest.any {
//            androidx.core.content.ContextCompat.checkSelfPermission(this, it) ==
//                    android.content.pm.PackageManager.PERMISSION_DENIED
//        }
//        val missingBattery = !isIgnoringBatteryOptimizations()
//
//        if (missingPermissions || missingBattery) {
//            MaterialAlertDialogBuilder(this)
//                    .setTitle(R.string.unified_permissions_title)
//                    .setMessage(R.string.unified_permissions_message)
//                    .setCancelable(false)
//                    .setPositiveButton(CommonStrings.action_accept) { _, _ ->
//                        if (missingPermissions) {
//                            startAppPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
//                        } else {
//                            checkBatteryOptimization()
//                        }
//                    }
//                    .setNegativeButton(CommonStrings.action_decline) { _, _ ->
//                        MaterialAlertDialogBuilder(this)
//                                .setTitle(CommonStrings.dialog_title_warning)
//                                .setMessage(R.string.unified_permissions_denied_warning)
//                                .setCancelable(false)
//                                .setPositiveButton(CommonStrings.ok) { _, _ ->
//                                    showUnifiedPermissionDialog() // Keep showing it
//                                }
//                                .show()
//                    }
//                    .show()
//        } else {
//            checkBatteryOptimization()
//        }
//    }
private fun checkOverlayPermission() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !android.provider.Settings.canDrawOverlays(this)) {

        MaterialAlertDialogBuilder(this)
                .setTitle("Permission Required")
                .setMessage("'Display over other apps' is required for incoming call popups to work properly. Please enable it to continue.")
                .setCancelable(false)
                .setPositiveButton("Open Settings") { _, _ ->
                    val intent = android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:$packageName")
                    )
                    overlayPermissionLauncher.launch(intent)
                }
                .show()

    } else {
        checkAppNotificationSettings()
    }
}
//    private fun checkOverlayPermission() {
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
//                !android.provider.Settings.canDrawOverlays(this)) {
//
//            MaterialAlertDialogBuilder(this)
//                    .setTitle("Display Over Other Apps Required")
//                    .setMessage(
//                            "This permission is required for call popups to work.\n\n" +
//                                    "If you see 'Restricted Settings':\n" +
//                                    "1. Tap 3-dot menu (top right)\n" +
//                                    "2. Select 'Allow Restricted Settings'\n" +
//                                    "3. Come back and enable the permission"
//                    )
//                    .setCancelable(false)  // user cannot dismiss by tapping outside
//                    .setPositiveButton("Open Settings") { _, _ ->
//                        val intent = android.content.Intent(
//                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
//                                android.net.Uri.parse("package:$packageName")
//                        )
//                        overlayPermissionLauncher.launch(intent)
//                    }
//                    // NO negative/skip button ✅
//                    .show()
//        } else {
//            checkAppNotificationSettings()
//        }
//    }
//    private fun checkFullScreenIntent() {
//        if (Build.VERSION.SDK_INT >= 34) {
//            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
//            if (notificationManager?.canUseFullScreenIntent() == false) {
//                MaterialAlertDialogBuilder(this)
//                        .setTitle("Full Screen Calling Required")
//                        .setMessage(
//                                "To properly show incoming calls on your locked screen, this app needs full-screen intent permission.\n\n" +
//                                "Please click 'Open Settings' and enable 'Allow apps to send full-screen intents' for this app."
//                        )
//                        .setCancelable(false)
//                        .setPositiveButton("Open Settings") { _, _ ->
//                            val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
//                            intent.data = Uri.parse("package:$packageName")
//                            fullScreenIntentSettingsLauncher.launch(intent)
//                        }
//                        .show()
//                return
//            }
//        }
//        checkOverlayPermission()
//    }
private fun checkFullScreenIntent() {
    if (Build.VERSION.SDK_INT >= 34) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
        if (notificationManager?.canUseFullScreenIntent() == false) {
            MaterialAlertDialogBuilder(this)
                    .setTitle("Full Screen Calling Required")
                    .setMessage("To properly show incoming calls on your locked screen, please enable full-screen intent permission.")
                    .setCancelable(false)
                    .setPositiveButton("Open Settings") { _, _ ->
                        val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                        intent.data = Uri.parse("package:$packageName")
                        fullScreenIntentSettingsLauncher.launch(intent)
                    }
                    .show()
            return
        }
    }
    // ✅ Skip overlay — don't block user at startup
    checkAppNotificationSettings()
}
    private fun checkBatteryOptimization() {
        if (!isIgnoringBatteryOptimizations()) {
            requestDisablingBatteryOptimization(this, batteryOptimizationLauncher)
        } else {
            checkFullScreenIntent()
        }
    }



    private fun checkAppNotificationSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = NotificationManagerCompat.from(this)
            if (!notificationManager.areNotificationsEnabled()) {
                val intent = Intent(
                        android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS
                ).apply {
                    putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
                }
                appNotificationSettingsLauncher.launch(intent)
            }
        }
    }
    private fun ensureNotificationsEnabled() {
        if (!vectorPreferences.areNotificationEnabledForDevice()) {
            vectorPreferences.setNotificationEnabledForDevice(true)
            // Re-register push distributor
            homeActivityViewModel.handle(HomeActivityViewActions.RegisterPushDistributor(distributor = ""))
        }
    }

    private fun askUserToSelectPushDistributor() {
        unifiedPushHelper.showSelectDistributorDialog(this) { selection ->
            homeActivityViewModel.handle(HomeActivityViewActions.RegisterPushDistributor(selection))
        }
    }

    private fun handleShowNotificationDialog() {
        notificationPermissionManager.eventuallyRequestPermission(this, postPermissionLauncher)
    }

    private fun handleShowReleaseNotes() {
        startActivity(Intent(this, ReleaseNotesActivity::class.java))
    }

    private fun showSpaceSettings(spaceId: String) {
        // open bottom sheet
        SpaceSettingsMenuBottomSheet
                .newInstance(spaceId, object : SpaceSettingsMenuBottomSheet.InteractionListener {
                    override fun onShareSpaceSelected(spaceId: String) {
                        ShareSpaceBottomSheet.show(supportFragmentManager, spaceId)
                    }
                })
                .show(supportFragmentManager, "SPACE_SETTINGS")
    }

    private fun showLayoutSettings() {
        HomeLayoutSettingBottomDialogFragment()
                .show(supportFragmentManager, "LAYOUT_SETTINGS")
    }

    private fun openSpaceInvite(spaceId: String) {
        SpaceInviteBottomSheet.newInstance(spaceId)
                .show(supportFragmentManager, "SPACE_INVITE")
    }

    private fun onCloseSpace() {
        views.drawerLayout.openDrawer(GravityCompat.START)
    }

    private fun handleShowAnalyticsOptIn() {
        navigator.openAnalyticsOptIn(this)
    }

    /**
     * Migrating from old threads io.element.thread to new m.thread needs an initial sync to
     * sync and display existing messages appropriately.
     */
    private fun migrateThreadsIfNeeded(checkSession: Boolean) {
        if (checkSession) {
            // We should check session to ensure we will only clear cache if needed
            val args = intent.getParcelableExtraCompat<HomeActivityArgs>(Mavericks.KEY_ARG)
            if (args?.hasExistingSession == true) {
                // existingSession --> Will be true only if we came from an existing active session
                Timber.i("----> Migrating threads from an existing session..")
                handleThreadsMigration()
            } else {
                // We came from a new session and not an existing one,
                // so there is no need to migrate threads while an initial synced performed
                Timber.i("----> No thread migration needed, we are ok")
                vectorPreferences.setShouldMigrateThreads(shouldMigrate = false)
            }
        } else {
            // Proceed with migration
            handleThreadsMigration()
        }
    }

    /**
     * Clear cache and restart to invoke an initial sync for threads migration.
     */
    private fun handleThreadsMigration() {
        Timber.i("----> Threads Migration detected, clearing cache and sync...")
        vectorPreferences.setShouldMigrateThreads(shouldMigrate = false)
        MainActivity.restartApp(this, MainActivityArgs(clearCache = true))
    }

    private fun handleNotifyUserForThreadsMigration() {
        MaterialAlertDialogBuilder(this)
                .setTitle(CommonStrings.threads_notice_migration_title)
                .setMessage(CommonStrings.threads_notice_migration_message)
                .setCancelable(true)
                .setPositiveButton(CommonStrings.sas_got_it) { _, _ -> }
                .show()
    }

    private fun handleIntent(intent: Intent?) {
        intent?.dataString?.let { deepLink ->
            val resolvedLink = when {
                // Element custom scheme is not handled by the sdk, convert it to matrix.to link for compatibility
                deepLink.startsWith(MATRIX_TO_CUSTOM_SCHEME_URL_BASE) -> {
                    when {
                        deepLink.startsWith(USER_LINK_PREFIX) -> deepLink.substring(USER_LINK_PREFIX.length)
                        deepLink.startsWith(ROOM_LINK_PREFIX) -> deepLink.substring(ROOM_LINK_PREFIX.length)
                        else -> null
                    }?.let { permalinkId ->
                        activeSessionHolder.getSafeActiveSession()?.permalinkService()?.createPermalink(permalinkId)
                    }
                }
                else -> deepLink
            }

            lifecycleScope.launch {
                val isHandled = permalinkHandler.launch(
                        fragmentActivity = this@HomeActivity,
                        deepLink = resolvedLink,
                        navigationInterceptor = this@HomeActivity,
                        buildTask = true
                )
                if (!isHandled) {
                    val isMatrixToLink = deepLink.startsWith(PermalinkService.MATRIX_TO_URL_BASE) ||
                            deepLink.startsWith(MATRIX_TO_CUSTOM_SCHEME_URL_BASE)
                    MaterialAlertDialogBuilder(this@HomeActivity)
                            .setTitle(CommonStrings.dialog_title_error)
                            .setMessage(if (isMatrixToLink) CommonStrings.permalink_malformed else CommonStrings.universal_link_malformed)
                            .setPositiveButton(CommonStrings.ok, null)
                            .show()
                }
            }
        }
    }

    private fun handleStartRecoverySetup() {
        // To avoid IllegalStateException in case the transaction was executed after onSaveInstanceState
        lifecycleScope.launch {
            withResumed {
                navigator.open4SSetup(this@HomeActivity, SetupMode.NORMAL)
            }
        }
    }

    private fun renderState(state: HomeActivityViewState) {
        when (val status = state.syncRequestState) {
            is SyncRequestState.InitialSyncProgressing -> {
                val initSyncStepStr = initSyncStepFormatter.format(status.initialSyncStep)
                Timber.v("$initSyncStepStr ${status.percentProgress}")
                views.waitingView.root.setOnClickListener { /* block interactions */ }
                views.waitingView.waitingHorizontalProgress.apply {
                    isIndeterminate = false
                    max = 100
                    progress = status.percentProgress
                    isVisible = true
                }
                views.waitingView.waitingStatusText.apply {
                    text = initSyncStepStr
                    isVisible = true
                }
                views.waitingView.root.isVisible = true
            }
            else -> {
                views.waitingView.root.isVisible = false

                // Trigger background sync when idle to ensure data is updated
                if (status == SyncRequestState.Idle) {
                    triggerWorkManagerSync()
                }
            }
        }
    }

    private fun triggerWorkManagerSync() {
        SyncWorker.enqueue(this)
    }

//    private fun handleAskPasswordToInitCrossSigning(events: HomeActivityViewEvents.AskPasswordToInitCrossSigning) {
//        // We need to ask
//        promptSecurityEvent(
//                uid = PopupAlertManager.UPGRADE_SECURITY_UID,
//                userItem = events.userItem,
//                titleRes = CommonStrings.upgrade_security,
//                descRes = CommonStrings.security_prompt_text,
//        ) {
//          //  it.navigator.upgradeSessionSecurity(it, true)
//        }
//    }

 //   private fun handleCrossSigningInvalidated(event: HomeActivityViewEvents.OnCrossSignedInvalidated) {
        // We need to ask
//        promptSecurityEvent(
//                uid = PopupAlertManager.VERIFY_SESSION_UID,
//                userItem = event.userItem,
//                titleRes = CommonStrings.crosssigning_verify_this_session,
//                descRes = CommonStrings.confirm_your_identity,
//        ) {
//            // check first if it's not an outdated request?
//            activeSessionHolder.getSafeActiveSession()?.let { session ->
//                session.coroutineScope.launch {
//                    if (!session.cryptoService().crossSigningService().isCrossSigningVerified()) {
//                        withContext(Dispatchers.Main) {
//                            it.navigator.requestSelfSessionVerification(it)
//                        }
//                    }
//                }
//            }
//        }
 //   }

    private fun handleOnNewSession(event: HomeActivityViewEvents.CurrentSessionNotVerified) {
        // We need to ask
        val titleRes = if (event.afterMigration) {
            CommonStrings.crosssigning_verify_after_update
        } else {
            CommonStrings.crosssigning_verify_this_session
        }
        val descRes = if (event.afterMigration) {
            CommonStrings.confirm_your_identity_after_update
        } else {
        //    CommonStrings.confirm_your_identity
        }
//        promptSecurityEvent(
//                uid = PopupAlertManager.VERIFY_SESSION_UID,
//                userItem = event.userItem,
//                titleRes = titleRes,
//                descRes = descRes,
//        ) {
//          //  it.navigator.requestSelfSessionVerification(it)
//        }
    }

//    private fun handleCantVerify(event: HomeActivityViewEvents.CurrentSessionCannotBeVerified) {
        // We need to ask
//        promptSecurityEvent(
//                uid = PopupAlertManager.UPGRADE_SECURITY_UID,
//                userItem = event.userItem,
//                titleRes = CommonStrings.crosssigning_cannot_verify_this_session,
//                descRes = CommonStrings.crosssigning_cannot_verify_this_session_desc,
//        ) {
//            it.navigator.open4SSetup(it, SetupMode.PASSPHRASE_AND_NEEDED_SECRETS_RESET)
//        }
  //  }

    private fun handlePromptToEnablePush() {
        popupAlertManager.postVectorAlert(
                DefaultVectorAlert(
                        uid = PopupAlertManager.ENABLE_PUSH_UID,
                        title = getString(CommonStrings.alert_push_are_disabled_title),
                        description = getString(CommonStrings.alert_push_are_disabled_description),
                        iconId = R.drawable.ic_room_actions_notifications_mutes,
                        shouldBeDisplayedIn = {
                            it is HomeActivity
                        }
                ).apply {
                    colorInt = ThemeUtils.getColor(this@HomeActivity, im.vector.lib.ui.styles.R.attr.vctr_notice_secondary)
                    contentAction = Runnable {
                        (weakCurrentActivity?.get() as? VectorBaseActivity<*>)?.let {
                            // action(it)
                            homeActivityViewModel.handle(HomeActivityViewActions.PushPromptHasBeenReviewed)
                            it.navigator.openSettings(it, VectorSettingsActivity.EXTRA_DIRECT_ACCESS_NOTIFICATIONS)
                        }
                    }
                    dismissedAction = Runnable {
                        homeActivityViewModel.handle(HomeActivityViewActions.PushPromptHasBeenReviewed)
                    }
                    addButton(getString(CommonStrings.action_dismiss), {
                        homeActivityViewModel.handle(HomeActivityViewActions.PushPromptHasBeenReviewed)
                    }, true)
                    addButton(getString(CommonStrings.settings), {
                        (weakCurrentActivity?.get() as? VectorBaseActivity<*>)?.let {
                            // action(it)
                            homeActivityViewModel.handle(HomeActivityViewActions.PushPromptHasBeenReviewed)
                            it.navigator.openSettings(it, VectorSettingsActivity.EXTRA_DIRECT_ACCESS_NOTIFICATIONS)
                        }
                    }, true)
                }
        )
    }

//    private fun promptSecurityEvent(
//            uid: String,
//            userItem: MatrixItem.UserItem,
//            titleRes: Int,
//            descRes: Int,
//            action: ((VectorBaseActivity<*>) -> Unit),
//    ) {
//        popupAlertManager.postVectorAlert(
//                VerificationVectorAlert(
//                        uid = uid,
//                        title = getString(titleRes),
//                        description = getString(descRes),
//                        iconId = R.drawable.ic_shield_warning
//                ).apply {
//                    viewBinder = VerificationVectorAlert.ViewBinder(userItem, avatarRenderer)
//                    colorInt = ThemeUtils.getColor(this@HomeActivity, com.google.android.material.R.attr.colorPrimary)
//                    contentAction = Runnable {
//                        (weakCurrentActivity?.get() as? VectorBaseActivity<*>)?.let {
//                            action(it)
//                        }
//                    }
//                    dismissedAction = Runnable {}
//                }
//        )
//    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val parcelableExtra = intent.getParcelableExtraCompat<HomeActivityArgs>(Mavericks.KEY_ARG)
        if (parcelableExtra?.clearNotification == true) {
            notificationDrawerManager.clearAllEvents()
        }
        if (parcelableExtra?.inviteNotificationRoomId != null) {
            activeSessionHolder.getSafeActiveSession()
                    ?.permalinkService()
                    ?.createPermalink(parcelableExtra.inviteNotificationRoomId)?.let {
                        navigator.openMatrixToBottomSheet(this, it, OriginOfMatrixTo.NOTIFICATION)
                    }
        }
        handleIntent(intent)
    }

    override fun onDestroy() {
        if (::appUpdateManager.isInitialized) {
            appUpdateManager.unregisterListener(installStateListener)
        }
        views.drawerLayout.removeDrawerListener(drawerListener)
        supportFragmentManager.unregisterFragmentLifecycleCallbacks(fragmentLifecycleCallbacks)
        super.onDestroy()
    }



    // ─── In-App Update helpers ────────────────────────────────────────────────
    
    private fun checkForAppUpdate() {
        if (isFinishing || isDestroyed) return

        appUpdateManager.appUpdateInfo
                .addOnSuccessListener { info ->
                    val availability = info.updateAvailability()
                    val installStatus = info.installStatus()

                    Timber.d("In-App Update: availability=$availability, installStatus=$installStatus")

                    if (availability == UpdateAvailability.UPDATE_AVAILABLE) {
                        if (info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
                            try {
                                appUpdateManager.startUpdateFlowForResult(
                                        info,
                                        updateFlowLauncher,
                                        AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
                                )
                            } catch (e: Exception) {
                                Timber.e(e, "In-App Update: startUpdateFlowForResult failed")
                                showForcedUpdateDialog()
                            }
                        } else {
                            Timber.d("In-App Update: IMMEDIATE update not allowed, but UPDATE_AVAILABLE")
                            // Fallback for cases where Play Store flow is not available but update is detected
                            showForcedUpdateDialog()
                        }
                    } else if (availability == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                        try {
                            appUpdateManager.startUpdateFlowForResult(
                                    info,
                                    updateFlowLauncher,
                                    AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
                            )
                        } catch (e: Exception) {
                            Timber.e(e, "In-App Update: failed to resume update")
                        }
                    } else if (installStatus == InstallStatus.DOWNLOADED) {
                        completeUpdate()
                    }
                }
                .addOnFailureListener { e ->
                    Timber.e(e, "In-App Update: failed to fetch update info")
                }
    }

    private fun showForcedUpdateDialog() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Update Available")
            .setMessage("A new version of the app is available. Please update to continue.")
            .setCancelable(false)
            .setPositiveButton("Update Now") { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$packageName")))
                } catch (e: Exception) {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/apps/details?id=$packageName")))
                }
            }
            .show()
    }

    private fun completeUpdate() {
        try {
            appUpdateManager.completeUpdate()
        } catch (e: Exception) {
            Timber.e(e, "In-App Update: completeUpdate failed")
        }
    }

    private fun checkNewAppLayoutFlagChange() {
        if (vectorPreferences.isNewAppLayoutEnabled() != isNewAppLayoutEnabled) {
            restart()
        }
    }
    override fun getMenuRes() = if (vectorPreferences.isNewAppLayoutEnabled()) R.menu.menu_new_home else R.menu.menu_home

    override fun handlePrepareMenu(menu: Menu) {
        menu.findItem(R.id.menu_home_init_sync_legacy).isVisible = vectorPreferences.developerMode()
        menu.findItem(R.id.menu_home_init_sync_optimized).isVisible = vectorPreferences.developerMode()
        
        // Connections toggle logic
        withState(homeActivityViewModel) { state ->
            menu.findItem(R.id.menu_home_followers)?.isVisible = state.showFollowOptions
            menu.findItem(R.id.menu_home_following)?.isVisible = state.showFollowOptions
        }
    }

    override fun handleMenuItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
/*            R.id.menu_home_suggestion -> {
                bugReporter.openBugReportScreen(this, ReportType.SUGGESTION)
                true
            }
            R.id.menu_home_report_bug -> {
                bugReporter.openBugReportScreen(this, ReportType.BUG_REPORT)
                true
            }*/
            R.id.menu_home_init_sync_legacy -> {
                // Configure the SDK
                initialSyncStrategy = InitialSyncStrategy.Legacy
                // And clear cache
                MainActivity.restartApp(this, MainActivityArgs(clearCache = true))
                true
            }
            R.id.menu_home_init_sync_optimized -> {
                // Configure the SDK
                initialSyncStrategy = InitialSyncStrategy.Optimized()
                // And clear cache
                MainActivity.restartApp(this, MainActivityArgs(clearCache = true))
                true
            }
            R.id.menu_home_filter -> {
                navigator.openRoomsFiltering(this)
                true
            }
            R.id.menu_home_connections -> {
                homeActivityViewModel.handle(HomeActivityViewActions.ToggleFollowOptions)
                invalidateOptionsMenu()
                true
            }
            R.id.menu_home_followers -> {
                navigator.openCreateDirectRoom(this, initialShowFollowers = true)
                true
            }
            R.id.menu_home_following -> {
                navigator.openCreateDirectRoom(this, initialShowFollowing = true)
                true
            }
            R.id.menu_home_setting -> {
                navigator.openSettings(this)
                true
            }
            R.id.menu_home_layout_settings -> {
                showLayoutSettings()
                true
            }
            R.id.menu_home_invite_friends -> {
                launchInviteFriends()
                true
            }
            R.id.menu_home_qr -> {
                launchQrCode()
                true
            }
            R.id.sign_out -> {
                //FirebaseSessionManager.signOut()
                SignOutUiWorker(this).perform()
                true
            }
            else -> false
        }
    }

    private fun launchQrCode() {
        startActivity(UserCodeActivity.newIntent(this, sharedActionViewModel.session.myUserId))
    }
    private fun launchInviteFriends() {
        val username = sharedActionViewModel.session.myUserId
                .removePrefix("@")        // removes @ from @john:matrix.org
                .substringBefore(":")     // removes :matrix.org part

        val inviteText = getString(R.string.invite_friends_text, username)

        startSharePlainTextIntent(
                context = this,
                activityResultLauncher = null,
                chooserTitle = "Invite Friends",
                text = inviteText
        )
    }
//    private fun launchInviteFriends() {
//        InviteFriendsBottomSheet.show(supportFragmentManager)
//    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (views.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            views.drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            validateBackPressed {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }

    override fun navToMemberProfile(userId: String, deepLink: Uri): Boolean {
        // TODO check if there is already one??
        MatrixToBottomSheet.withLink(deepLink.toString(), OriginOfMatrixTo.LINK)
                .show(supportFragmentManager, "HA#MatrixToBottomSheet")
        return true
    }

    override fun navToRoom(roomId: String?, eventId: String?, deepLink: Uri?, rootThreadEventId: String?): Boolean {
        if (roomId == null) return false
        MatrixToBottomSheet.withLink(deepLink.toString(), OriginOfMatrixTo.LINK)
                .show(supportFragmentManager, "HA#MatrixToBottomSheet")
        return true
    }

    override fun spaceInviteBottomSheetOnAccept(spaceId: String) {
        navigator.switchToSpace(this, spaceId, Navigator.PostSwitchSpaceAction.OpenRoomList)
    }

    override fun spaceInviteBottomSheetOnDecline(spaceId: String) {
        // nop
    }

    companion object {
        fun newIntent(
                context: Context,
                firstStartMainActivity: Boolean,
                clearNotification: Boolean = false,
                authenticationDescription: AuthenticationDescription? = null,
                existingSession: Boolean = false,
                inviteNotificationRoomId: String? = null
        ): Intent {
            val args = HomeActivityArgs(
                    clearNotification = clearNotification,
                    authenticationDescription = authenticationDescription,
                    hasExistingSession = existingSession,
                    inviteNotificationRoomId = inviteNotificationRoomId
            )

            val intent = Intent(context, HomeActivity::class.java)
                    .apply {
                        putExtra(Mavericks.KEY_ARG, args)
                    }

            return if (firstStartMainActivity) {
                MainActivity.getIntentWithNextIntent(context, intent)
            } else {
                intent
            }
        }
    }

    override fun mxToBottomSheetNavigateToRoom(roomId: String, trigger: ViewRoom.Trigger?) {
        navigator.openRoom(this, roomId, trigger = trigger)
    }

    override fun mxToBottomSheetSwitchToSpace(spaceId: String) {
        navigator.switchToSpace(this, spaceId, Navigator.PostSwitchSpaceAction.OpenRoomList)
    }
}
