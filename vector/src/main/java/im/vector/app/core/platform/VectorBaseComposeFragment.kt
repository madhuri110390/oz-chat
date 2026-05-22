/*
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.platform

import android.content.Context
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.CallSuper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.airbnb.mvrx.MavericksView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.EntryPointAccessors
import im.vector.app.core.di.ActivityEntryPoint
import im.vector.app.core.dialogs.UnrecognizedCertificateDialog
import im.vector.app.core.error.ErrorFormatter
import im.vector.app.core.extensions.singletonEntryPoint
import im.vector.app.features.analytics.AnalyticsTracker
import im.vector.app.features.analytics.plan.MobileScreen
import im.vector.app.features.navigation.Navigator
import im.vector.lib.strings.CommonStrings
import im.vector.lib.ui.styles.dialogs.MaterialProgressDialog
import kotlinx.coroutines.launch
import timber.log.Timber

abstract class VectorBaseComposeFragment : Fragment(), MavericksView {

    // Dependency-injected properties
    protected lateinit var navigator: Navigator
    protected lateinit var errorFormatter: ErrorFormatter
    protected lateinit var analyticsTracker: AnalyticsTracker
    protected lateinit var unrecognizedCertificateDialog: UnrecognizedCertificateDialog
    private var progress: androidx.appcompat.app.AlertDialog? = null

    open fun shouldDisplayFullScreenFragment(): Boolean = true

    protected val vectorBaseActivity: VectorBaseActivity<*> by lazy {
        activity as VectorBaseActivity<*>
    }

    protected lateinit var viewModelFactory: ViewModelProvider.Factory

    protected val fragmentViewModelProvider
        get() = ViewModelProvider(this, viewModelFactory)

    protected val activityViewModelProvider
        get() = ViewModelProvider(requireActivity(), viewModelFactory)

    // Optional analytics screen tracking
    protected open val analyticsScreenName: MobileScreen.ScreenName? = null

    override fun onAttach(context: Context) {
        val singletonEntryPoint = context.singletonEntryPoint()
        val activityEntryPoint = EntryPointAccessors.fromActivity(vectorBaseActivity, ActivityEntryPoint::class.java)
        navigator = singletonEntryPoint.navigator()
        errorFormatter = singletonEntryPoint.errorFormatter()
        analyticsTracker = singletonEntryPoint.analyticsTracker()
        unrecognizedCertificateDialog = singletonEntryPoint.unrecognizedCertificateDialog()
        viewModelFactory = activityEntryPoint.viewModelFactory()
        super.onAttach(context)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.i("onCreate Compose Fragment: ${javaClass.simpleName}")
    }


    override fun onResume() {
        super.onResume()
        Timber.i("onResume Compose Fragment: ${javaClass.simpleName}")
        analyticsScreenName?.let {
            analyticsTracker.screen(MobileScreen(screenName = it))
        }
        if (shouldDisplayFullScreenFragment()) {
            vectorBaseActivity.setFullScreen()
        }
    }

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View {
        Timber.i("onCreateView Compose Fragment: ${javaClass.simpleName}")
        return ComposeView(requireContext()).apply {
            setContent {
                ComposeContent()
            }
        }
    }

    @Composable
    fun resolveAndroidAttrColor(attrResId: Int): Color {
        val context = LocalContext.current
        return remember(attrResId) {
            val typedValue = TypedValue()
            val resolved = context.theme.resolveAttribute(attrResId, typedValue, true)
            if (resolved) Color(typedValue.data) else Color.Unspecified
        }
    }

    @Composable
    abstract fun ComposeContent()

    override fun invalidate() {
        Timber.v("invalidate() not implemented in ${javaClass.simpleName}")
    }

    protected fun showLoadingDialog(message: CharSequence? = null) {
        progress?.dismiss()
        progress = MaterialProgressDialog(requireContext())
                .show(message ?: getString(CommonStrings.please_wait))
    }

    protected fun dismissLoadingDialog() {
        progress?.dismiss()
    }

    protected fun displayErrorDialog(throwable: Throwable) {
        MaterialAlertDialogBuilder(requireActivity())
                .setTitle(CommonStrings.dialog_title_error)
                .setMessage(errorFormatter.toHumanReadable(throwable))
                .setPositiveButton(CommonStrings.ok, null)
                .show()
    }

    protected fun showErrorInSnackbar(throwable: Throwable) {
        vectorBaseActivity.getCoordinatorLayout()?.showOptimizedSnackbar(
                errorFormatter.toHumanReadable(throwable)
        )
    }

    protected fun <T : VectorViewEvents> VectorViewModel<*, *, T>.observeViewEvents(observer: (T) -> Unit) {
        val tag = this@VectorBaseComposeFragment::class.simpleName.toString()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewEvents
                        .stream(tag)
                        .collect {
                            dismissLoadingDialog()
                            observer(it)
                        }
            }
        }
    }
}
