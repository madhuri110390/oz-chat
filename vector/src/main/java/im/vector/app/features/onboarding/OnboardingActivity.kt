/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.onboarding

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.core.extensions.lazyViewModel
import im.vector.app.core.extensions.validateBackPressed
import im.vector.app.core.platform.VectorBaseActivity
import im.vector.app.core.platform.lifecycleAwareLazy
import im.vector.app.databinding.ActivityLoginBinding
import im.vector.app.features.login.LoginConfig
import im.vector.app.features.onboarding.ftueauth.FtueAuthLoginFragment
import im.vector.app.features.pin.UnlockedActivity
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject

@AndroidEntryPoint
class OnboardingActivity : VectorBaseActivity<ActivityLoginBinding>(), UnlockedActivity {

    private val onboardingVariant by lifecycleAwareLazy {
        onboardingVariantFactory.create(this, views = views, onboardingViewModel = lazyViewModel())
    }

    @Inject lateinit var onboardingVariantFactory: OnboardingVariantFactory

    override fun getBinding() = ActivityLoginBinding.inflate(layoutInflater)

    override fun getCoordinatorLayout() = views.coordinatorLayout

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        onboardingVariant.onNewIntent(intent)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        validateBackPressed {
            super.onBackPressed()
        }
    }

    override fun initUiAndData() {
        onboardingVariant.initUiAndData(isFirstCreation())
    }
    override fun onResume() {
        super.onResume()
        val loginFragment = supportFragmentManager.findFragmentByTag("FRAGMENT_LOGIN_TAG")
        if (loginFragment is FtueAuthLoginFragment) {
            loginFragment.clearPasswordField()

        }

    }
//    fun syncPasswordToMatrix(newPassword: String) {
//        lifecycleScope.launch {
//            try {
//                val accessToken = getSharedPreferences("matrix_prefs", Context.MODE_PRIVATE)
//                        .getString("access_token", "") ?: ""
//
//                val client = okhttp3.OkHttpClient()
//                val body = """{"new_password": "$newPassword"}"""
//                        .toRequestBody("application/json".toMediaTypeOrNull())
//                val request = okhttp3.Request.Builder()
//                        .url("https://chat.openzippers.com/_matrix/client/v3/account/password")
//                        .addHeader("Authorization", "Bearer $accessToken")
//                        .post(body)
//                        .build()
//
//                client.newCall(request).execute()
//            } catch (e: Exception) {
//                android.util.Log.e("OnboardingActivity", "Sync failed: ${e.message}")
//            }
//        }
//    }
    // Hack for AccountCreatedFragment
    fun setIsLoading(isLoading: Boolean) {
        onboardingVariant.setIsLoading(isLoading)
    }

    companion object {
        const val EXTRA_CONFIG = "EXTRA_CONFIG"

        fun newIntent(context: Context, loginConfig: LoginConfig?): Intent {
            return Intent(context, OnboardingActivity::class.java).apply {
                putExtra(EXTRA_CONFIG, loginConfig)
            }
        }

        fun redirectIntent(context: Context, data: Uri?): Intent {
            return Intent(context, OnboardingActivity::class.java).apply {
                setData(data)
            }
        }
    }
}
