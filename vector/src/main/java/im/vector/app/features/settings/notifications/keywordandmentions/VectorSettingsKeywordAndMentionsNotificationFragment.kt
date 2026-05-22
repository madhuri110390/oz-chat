/*
 * Copyright 2023, 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.notifications.keywordandmentions

import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import im.vector.app.R
import im.vector.app.core.preference.KeywordPreference
import im.vector.app.core.preference.VectorCheckboxPreference
import im.vector.app.core.preference.VectorPreference
import im.vector.app.core.preference.VectorPreferenceCategory
import im.vector.app.features.analytics.plan.MobileScreen
import im.vector.app.features.settings.notifications.NotificationIndex
import im.vector.app.features.settings.notifications.StandardActions
import im.vector.app.features.settings.notifications.VectorSettingsPushRuleNotificationFragment
import im.vector.app.features.settings.notifications.getStandardAction
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.session.pushrules.RuleIds
import org.matrix.android.sdk.api.session.pushrules.RuleKind
import org.matrix.android.sdk.api.session.pushrules.rest.PushRule
import org.matrix.android.sdk.api.session.pushrules.toJson

class VectorSettingsKeywordAndMentionsNotificationFragment :
        VectorSettingsPushRuleNotificationFragment() {

    override var titleRes: Int = CommonStrings.settings_notification_mentions_and_keywords

    override val preferenceXmlRes = R.xml.vector_settings_notification_mentions_and_keywords

    private var keywordsHasFocus = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        analyticsScreenName = MobileScreen.ScreenName.SettingsMentionsAndKeywords
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // session.pushRuleService().getKeywords().observe(viewLifecycleOwner, this::updateWithKeywords)
    }

    override fun bindPref() {
        super.bindPref()
        val mentionCategory = findPreference<VectorPreferenceCategory>("SETTINGS_KEYWORDS_AND_MENTIONS")!!
        mentionCategory.isIconSpaceReserved = false
    }

    fun updateKeywordPushRules(keywords: Set<String>, checked: Boolean, completion: (Result<Unit>) -> Unit) {
        val newIndex = if (checked) NotificationIndex.NOISY else NotificationIndex.OFF
        val standardAction = getStandardAction(RuleIds.RULE_ID_KEYWORDS, newIndex) ?: return
        val enabled = standardAction != StandardActions.Disabled
        val newActions = standardAction.actions

        lifecycleScope.launch {
            val results = keywords.map { keyword ->
                runCatching {
                    withContext(Dispatchers.Default) {
                        val pushRule = session.pushRuleService().getPushRules().content?.find { it.ruleId == keyword }
                        if (pushRule != null) {
                            session.pushRuleService().updatePushRuleActions(
                                    RuleKind.CONTENT,
                                    pushRule,
                                    enabled,
                                    newActions
                            )
                        } else {
                           error("Rule $keyword not found")
                        }
                    }
                }
            }
            val firstError = results.firstNotNullOfOrNull(Result<Unit>::exceptionOrNull)
            if (firstError == null) {
                completion(Result.success(Unit))
            } else {
                completion(Result.failure(firstError))
            }
        }
    }

    fun updateWithKeywords(keywords: Set<String>) {
        val editKeywordPreference = findPreference<KeywordPreference>("SETTINGS_KEYWORD_EDIT") ?: return
        editKeywordPreference.keywords = keywords
        if (keywordsHasFocus) {
            scrollToPreference(editKeywordPreference)
        }
    }

    fun addKeyword(keyword: String) {
        val standardAction = getStandardAction(RuleIds.RULE_ID_KEYWORDS, NotificationIndex.NOISY) ?: return
        val enabled = standardAction != StandardActions.Disabled
        val newActions = standardAction.actions ?: return
        val newRule = PushRule(actions = newActions.toJson(), pattern = keyword, enabled = enabled, ruleId = keyword)
        displayLoadingView()
        lifecycleScope.launch {
            val result = runCatching {
                session.pushRuleService().addPushRule(RuleKind.CONTENT, newRule)
            }
            hideLoadingView()
            if (!isAdded) {
                return@launch
            }
            // Already added to UI, no-op on success

            result.onFailure(::displayErrorDialog)
        }
    }

    fun removeKeyword(keyword: String) {
        displayLoadingView()
        lifecycleScope.launch {
            val result = runCatching {
                session.pushRuleService().removePushRule(RuleKind.CONTENT, keyword)
            }
            hideLoadingView()
            if (!isAdded) {
                return@launch
            }
            // Already added to UI, no-op on success

            result.onFailure(::displayErrorDialog)
        }
    }

    override val prefKeyToPushRuleId = mapOf(
            "SETTINGS_PUSH_RULE_CONTAINING_MY_DISPLAY_NAME_PREFERENCE_KEY" to RuleIds.RULE_ID_CONTAIN_DISPLAY_NAME,
            "SETTINGS_PUSH_RULE_CONTAINING_MY_USER_NAME_PREFERENCE_KEY" to RuleIds.RULE_ID_CONTAIN_USER_NAME,
            "SETTINGS_PUSH_RULE_MESSAGES_CONTAINING_AT_ROOM_PREFERENCE_KEY" to RuleIds.RULE_ID_ROOM_NOTIF
    )
}
