/*
 * Copyright 2018-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */
package im.vector.app.gplay.features.settings.troubleshoot

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Observer
import androidx.lifecycle.asFlow
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.pushers.FcmHelper
import im.vector.app.core.pushers.PushersManager
import im.vector.app.core.pushers.RegisterUnifiedPushUseCase
import im.vector.app.core.resources.StringProvider
import im.vector.app.features.settings.troubleshoot.TroubleshootTest
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.session.pushers.PusherState
import java.util.UUID
import javax.inject.Inject

/**
 * Force registration of the token to HomeServer
 */
class TestTokenRegistration @Inject constructor(
        private val context: FragmentActivity,
        private val stringProvider: StringProvider,
        private val pushersManager: PushersManager,
        private val activeSessionHolder: ActiveSessionHolder,
        private val fcmHelper: FcmHelper,
) :
        TroubleshootTest(CommonStrings.settings_troubleshoot_test_token_registration_title) {

    override fun perform(testParameters: TestParameters) {
        val fcmToken = fcmHelper.getFcmToken() ?: run {
            status = TestStatus.FAILED
            return
        }
        val session = activeSessionHolder.getSafeActiveSession() ?: run {
            status = TestStatus.FAILED
            return
        }
        val pushers = session.pushersService().getPushers().filter {
            it.pushKey == fcmToken && it.state == PusherState.REGISTERED
        }
        if (pushers.isEmpty()) {
            description = stringProvider.getString(
                    CommonStrings.settings_troubleshoot_test_token_registration_failed,
                    stringProvider.getString(CommonStrings.sas_error_unknown)
            )
            quickFix = object : TroubleshootQuickFix(CommonStrings.settings_troubleshoot_test_token_registration_quick_fix) {
                override fun doFix() {
                    context.lifecycleScope.launch(Dispatchers.IO) {
                        val workId: UUID = pushersManager.enqueueRegisterPusherWithFcmKey(fcmToken)
                                ?: return@launch // exit early if null

                        waitForWorkerResult(workId)
                        manager?.retry(testParameters)
                    }
                }
            }
            status = TestStatus.FAILED
        } else {
            description = stringProvider.getString(CommonStrings.settings_troubleshoot_test_token_registration_success)
            status = TestStatus.SUCCESS
        }
    }
    private suspend fun waitForWorkerResult(workId: UUID) {
        WorkManager.getInstance(context)
                .getWorkInfoByIdLiveData(workId)
                .asFlow()
                .first { workInfo ->
                    workInfo?.state?.isFinished == true
                }
    }
}
