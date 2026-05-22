/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.troubleshoot

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.pushers.PushersManager
import im.vector.app.core.pushers.RegisterUnifiedPushUseCase
import im.vector.app.core.pushers.UnifiedPushHelper
import im.vector.app.core.pushers.UnregisterUnifiedPushUseCase
import im.vector.app.core.resources.StringProvider
import im.vector.app.features.session.coroutineScope
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.session.pushers.PusherState
import java.util.UUID
import javax.inject.Inject

class TestEndpointAsTokenRegistration @Inject constructor(
        private val context: FragmentActivity,
        private val stringProvider: StringProvider,
        private val pushersManager: PushersManager,
        private val activeSessionHolder: ActiveSessionHolder,
        private val unifiedPushHelper: UnifiedPushHelper,
        private val registerUnifiedPushUseCase: RegisterUnifiedPushUseCase,
        private val unregisterUnifiedPushUseCase: UnregisterUnifiedPushUseCase,
) : TroubleshootTest(CommonStrings.settings_troubleshoot_test_endpoint_registration_title) {

    override fun perform(testParameters: TestParameters) {
        // Check if we have a registered pusher for this token
        val endpoint = unifiedPushHelper.getEndpointOrToken() ?: run {
            status = TestStatus.FAILED
            return
        }
        val session = activeSessionHolder.getSafeActiveSession() ?: run {
            status = TestStatus.FAILED
            return
        }
        val pushers = session.pushersService().getPushers().filter {
            it.pushKey == endpoint && it.state == PusherState.REGISTERED
        }
        if (pushers.isEmpty()) {
            description = stringProvider.getString(
                    CommonStrings.settings_troubleshoot_test_endpoint_registration_failed,
                    stringProvider.getString(CommonStrings.sas_error_unknown)
            )
            quickFix = object : TroubleshootQuickFix(CommonStrings.settings_troubleshoot_test_endpoint_registration_quick_fix) {
                override fun doFix() {
                    unregisterThenRegister(testParameters, endpoint)
                }
            }
            status = TestStatus.FAILED
        } else {
            description = stringProvider.getString(CommonStrings.settings_troubleshoot_test_endpoint_registration_success)
            status = TestStatus.SUCCESS
        }
    }

    private fun unregisterThenRegister(testParameters: TestParameters, pushKey: String) {
        val scope = activeSessionHolder.getSafeActiveSession()?.coroutineScope ?: return
        val io = activeSessionHolder.getActiveSession().coroutineDispatchers.io
        scope.launch(io) {
            unregisterUnifiedPushUseCase.execute(pushersManager)
            registerUnifiedPush(distributor = "", testParameters, pushKey)
        }
    }

    private suspend fun registerUnifiedPush(
            distributor: String,
            testParameters: TestParameters,
            pushKey: String,
    ) {
        when (registerUnifiedPushUseCase.execute(distributor)) {
            is RegisterUnifiedPushUseCase.RegisterUnifiedPushResult.NeedToAskUserForDistributor ->
                askUserForDistributor(testParameters, pushKey)
            RegisterUnifiedPushUseCase.RegisterUnifiedPushResult.Success -> {
                val workId: UUID = pushersManager.enqueueRegisterPusherWithFcmKey(pushKey)
                        ?: return // Early exit if no work was enqueued

                waitForWorkerResult(workId)
                manager?.retry(testParameters)
            }
        }
    }

    private suspend fun waitForWorkerResult(workId: UUID) {
        callbackFlow {
            val liveData = WorkManager.getInstance(context)
                    .getWorkInfoByIdLiveData(workId)
            val observer = Observer<WorkInfo?> { workInfo ->
                trySend(workInfo)
            }
            liveData.observeForever(observer)
            awaitClose { liveData.removeObserver(observer) }
        }.first { workInfo ->
            workInfo?.state?.isFinished == true
        }
    }
    private fun askUserForDistributor(
            testParameters: TestParameters,
            pushKey: String,
    ) {
        unifiedPushHelper.showSelectDistributorDialog(context) { selection ->
            context.lifecycleScope.launch {
                registerUnifiedPush(distributor = selection, testParameters, pushKey)
            }
        }
    }
}
