/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.userdirectory

import androidx.lifecycle.asFlow
import androidx.paging.PagedList
import com.airbnb.mvrx.Fail
import com.airbnb.mvrx.MavericksViewModelFactory
import com.airbnb.mvrx.Uninitialized
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import im.vector.app.core.di.MavericksAssistedViewModelFactory
import im.vector.app.core.di.hiltMavericksViewModelFactory
import im.vector.app.core.extensions.toggle
import im.vector.app.core.platform.VectorViewModel
import im.vector.app.core.resources.StringProvider
import im.vector.app.features.discovery.fetchIdentityServerWithTerms
import im.vector.app.features.raw.wellknown.getElementWellknown
import im.vector.app.features.raw.wellknown.isE2EByDefault
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.MatrixPatterns
import org.matrix.android.sdk.api.extensions.isEmail
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.raw.RawService
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.identity.IdentityServiceError
import org.matrix.android.sdk.api.session.identity.IdentityServiceListener
import org.matrix.android.sdk.api.session.identity.ThreePid
import org.matrix.android.sdk.api.session.user.model.User
import org.matrix.android.sdk.api.util.toMatrixItem
import kotlin.random.Random
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import com.airbnb.mvrx.Loading
import com.airbnb.mvrx.Success

data class ThreePidUser(
        val email: String,
        val user: User?
)

class UserListViewModel @AssistedInject constructor(
        @Assisted initialState: UserListViewState,
        private val stringProvider: StringProvider,
        private val rawService: RawService,
        val session: Session
) : VectorViewModel<UserListViewState, UserListAction, UserListViewEvents>(initialState) {

    // knownUsersSearch removed — local SDK DB search disabled; only OZ API is used
    private val directoryUsersSearch = MutableStateFlow("")
    private val identityServerUsersSearch = MutableStateFlow(UserSearch(searchTerm = ""))

    @AssistedFactory
    interface Factory : MavericksAssistedViewModelFactory<UserListViewModel, UserListViewState> {
        override fun create(initialState: UserListViewState): UserListViewModel
    }

    companion object : MavericksViewModelFactory<UserListViewModel, UserListViewState> by hiltMavericksViewModelFactory()

    private val identityServerListener = object : IdentityServiceListener {
        override fun onIdentityServerChange() {
            withState {
                identityServerUsersSearch.tryEmit(UserSearch(it.searchTerm))
                val identityServerURL = cleanISURL(session.identityService().getCurrentIdentityServerUrl())
                setState {
                    copy(configuredIdentityServer = identityServerURL)
                }
            }
        }
    }

    init {
        initAdminE2eByDefault()
        observeUsers()
        setState {
            copy(
                    configuredIdentityServer = cleanISURL(session.identityService().getCurrentIdentityServerUrl())
            )
        }
        session.identityService().addListener(identityServerListener)
    }

    private fun initAdminE2eByDefault() {
        viewModelScope.launch(Dispatchers.IO) {
            val adminE2EByDefault = tryOrNull {
                rawService.getElementWellknown(session.sessionParams)
                        ?.isE2EByDefault()
                        ?: true
            } ?: true

            setState {
                copy(
                        isE2EByDefault = adminE2EByDefault
                )
            }
        }
    }

    private fun cleanISURL(url: String?): String? {
        return url?.removePrefix("https://")
    }

    override fun onCleared() {
        session.identityService().removeListener(identityServerListener)
        super.onCleared()
    }

    override fun handle(action: UserListAction) {
        when (action) {
            is UserListAction.SearchUsers -> handleSearchUsers(action.value)
            is UserListAction.ClearSearchUsers -> handleClearSearchUsers()
            is UserListAction.AddPendingSelection -> handleSelectUser(action)
            is UserListAction.RemovePendingSelection -> handleRemoveSelectedUser(action)
            UserListAction.ComputeMatrixToLinkForSharing -> handleShareMyMatrixToLink()
            UserListAction.UserConsentRequest -> handleUserConsentRequest()
            is UserListAction.UpdateUserConsent -> handleISUpdateConsent(action)
            UserListAction.Resumed -> handleResumed()
            UserListAction.ShowFollowers -> handleShowFollowers()
            UserListAction.ShowFollowing -> handleShowFollowing()
        }
    }

    private fun handleShareMyMatrixToLink() {
        val username = session.myUserId.removePrefix("@").substringBefore(":")
        _viewEvents.post(UserListViewEvents.OpenShareMatrixToLink(username))
    }

    private fun handleShowFollowers() {
        setState {
            copy(
                following = Uninitialized,
                knownUsers = Uninitialized,
                directoryUsers = Uninitialized,
                matchingEmail = Uninitialized,
                searchTerm = ""
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            setState { copy(followers = Loading()) }
            try {
                val users = fetchConnections("followers")
                // Also include all open zipper users (not just followers) to ensure unfollowed users appear
                val allUsers = searchOpenZipperUsers("")
                val combined = (users + allUsers).distinctBy { it.userId }
                setState { copy(followers = Success(combined)) }
            } catch (e: Exception) {
                setState { copy(followers = Fail(e)) }
            }
        }
    }

    private fun handleShowFollowing() {
        setState {
            copy(
                followers = Uninitialized,
                knownUsers = Uninitialized,
                directoryUsers = Uninitialized,
                matchingEmail = Uninitialized,
                searchTerm = ""
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            setState { copy(following = Loading()) }
            try {
                val users = fetchConnections("following")
                // Also include all open zipper users (not just following) to ensure unfollowed users appear
                val allUsers = searchOpenZipperUsers("")
                val combined = (users + allUsers).distinctBy { it.userId }
                setState { copy(following = Success(combined)) }
            } catch (e: Exception) {
                setState { copy(following = Fail(e)) }
            }
        }
    }

    private fun fetchConnections(type: String): List<User> {
        val usernamePart = session.myUserId.removePrefix("@").substringBefore(":")
        val url = URL("https://openzippers.com/api/v1/connections/list?username=$usernamePart")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connect()

        val response = connection.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(response)
        val data = json.getJSONObject("data")
        val list = data.getJSONArray(type)

        val users = ArrayList<User>()
        for (i in 0 until list.length()) {
            val item = list.getJSONObject(i)
            // val status = item.optString("status", "active")
            // if (!status.equals("active", ignoreCase = true)) continue

            // Use username as the primary ID part, similar to how matrix IDs work if possible, 
            val username = item.optString("username", "")
            val usernamePart = if (username.contains("@")) username.removePrefix("@").substringBefore(":") else username
            val displayName = usernamePart
            // Ensure we have a valid-ish ID
            val userId = if (username.contains("@")) username else "@$username:${session.myUserId.substringAfter(":")}"
            
            users.add(User(
                userId = userId,
                displayName = displayName,
                avatarUrl = item.optString("avatar_url")
            ))
        }
        return users
    }

    private suspend fun searchOpenZipperUsers(search: String): List<User> = withContext(Dispatchers.IO) {
        val searchTerm = if (search.startsWith("@")) {
            search.substringAfter("@").substringBefore(":")
        } else {
            search
        }
        
        if (searchTerm.isBlank()) return@withContext emptyList()
        
        val ozoneDomain = "oz.openzippers.com"
        val expectedUserId = "@${searchTerm}:$ozoneDomain"
        
        try {
            val url = URL("https://openzippers.com/api/v1/connections/list?username=$searchTerm")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connect()
            
            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(responseText)
            
            if (json.optBoolean("success", false)) {
                val data = json.getJSONObject("data")
                val userObj = data.getJSONObject("user")
                val avatarUrl = userObj.optString("avatar_url", "").takeIf { it.isNotBlank() }
                
                // Construct the user using the exact username found in the db to be safe
                val validUsername = userObj.optString("username", searchTerm)
                val finalUserId = "@${validUsername}:$ozoneDomain"
                
                // Optional: try to fetch matrix profile for Matrix-specific avatar (if missing from DB)
                val matrixProfile = tryOrNull { session.profileService().getProfileAsUser(finalUserId) }
                
                return@withContext listOf(User(
                        userId = finalUserId,
                        displayName = validUsername,
                        avatarUrl = avatarUrl ?: matrixProfile?.avatarUrl
                ))
            } else {
                return@withContext emptyList()
            }
        } catch (e: Exception) {
            return@withContext emptyList()
        }
    }

    private fun handleUserConsentRequest() {
        viewModelScope.launch {
            val event = try {
                val result = session.fetchIdentityServerWithTerms(stringProvider.getString(CommonStrings.resources_language))
                UserListViewEvents.OnPoliciesRetrieved(result)
            } catch (throwable: Throwable) {
                UserListViewEvents.Failure(throwable)
            }
            _viewEvents.post(event)
        }
    }

    private fun handleISUpdateConsent(action: UserListAction.UpdateUserConsent) {
        session.identityService().setUserConsent(action.consent)
        withState {
            retryUserSearch(it)
        }
    }

    private fun handleResumed() {
        withState {
            if (it.hasNoIdentityServerConfigured()) {
                retryUserSearch(it)
            }
        }
    }

    private fun retryUserSearch(state: UserListViewState) {
        identityServerUsersSearch.tryEmit(UserSearch(state.searchTerm, cacheBuster = Random.nextLong()))
    }

    private fun handleSearchUsers(searchTerm: String) {
        setState {
            copy(
                    searchTerm = searchTerm
            )
        }
        if (searchTerm.isEmail().not()) {
            // if it's not an email reset to uninitialized
            // because the flow won't be triggered and result would stay
            setState {
                copy(
                        matchingEmail = Uninitialized
                )
            }
        }
        identityServerUsersSearch.tryEmit(UserSearch(searchTerm))
        // knownUsersSearch intentionally disabled — only OZ DB results allowed
        directoryUsersSearch.tryEmit(searchTerm)
    }


    private fun handleClearSearchUsers() {
        // knownUsersSearch intentionally disabled — only OZ DB results allowed
        directoryUsersSearch.tryEmit("")
        identityServerUsersSearch.tryEmit(UserSearch(""))
        setState {
            copy(searchTerm = "")
        }
    }

    private fun observeUsers() = withState { state ->
        identityServerUsersSearch
                .filter { it.searchTerm.isEmail() }
                .sample(300)
                .onEach { search ->
                    executeSearchEmail(search.searchTerm)
                }.launchIn(viewModelScope)

        // knownUsersSearch pipeline disabled: local SDK DB may contain non-OZ users.
        // All user search goes exclusively through the OpenZippers API (directoryUsers below).

        directoryUsersSearch
                .debounce(300)
                .onEach { search ->
                    executeSearchDirectory(state, search)
                }.launchIn(viewModelScope)
    }

    private suspend fun executeSearchEmail(search: String) {
        suspend {
            val params = listOf(ThreePid.Email(search))
            val foundThreePid = session.identityService().lookUp(params).firstOrNull()
            if (foundThreePid == null) {
                ThreePidUser(email = search, user = null)
            } else {
                try {
                    val user = tryOrNull { session.profileService().getProfileAsUser(foundThreePid.matrixId) } ?: User(foundThreePid.matrixId)
                    ThreePidUser(
                            email = search,
                            user = user
                    )
                } catch (failure: Throwable) {
                    ThreePidUser(email = search, user = User(foundThreePid.matrixId))
                }
            }
        }.execute {
            copy(matchingEmail = it)
        }
    }

    private suspend fun executeSearchDirectory(state: UserListViewState, search: String) {
        suspend {
                val openZipperResult = searchOpenZipperUsers(search)
                if (search.isBlank()) {
                    openZipperResult
                } else {
                    val combinedResult = openZipperResult
                            .filter { it.userId.endsWith(":oz.openzippers.com") }
                            .groupBy { it.userId }
                            .map { (_, users) ->
                                users.find { !it.displayName.isNullOrBlank() } ?: users.first()
                            }
                            .sortedBy { it.displayName?.lowercase() }
                    combinedResult
                }
        }.execute {
            copy(directoryUsers = it)
        }
    }

    private fun handleSelectUser(action: UserListAction.AddPendingSelection) = withState { state ->
        val canSelectUser = !state.isE2EByDefault || state.pendingSelections.isEmpty() || !state.single3pidSelection ||
                (action.pendingSelection is PendingSelection.UserPendingSelection &&
                        state.pendingSelections.last() is PendingSelection.UserPendingSelection)
        if (canSelectUser) {
            if (action.pendingSelection is PendingSelection.UserPendingSelection) {
                action.pendingSelection.isUnknownUser = action.pendingSelection.getMxId() == state.unknownUserId
            }
            val selections = state.pendingSelections.toggle(action.pendingSelection, singleElement = state.singleSelection)
            setState { copy(pendingSelections = selections) }
        }
    }

    private fun handleRemoveSelectedUser(action: UserListAction.RemovePendingSelection) = withState { state ->
        val selections = state.pendingSelections.minus(action.pendingSelection)
        setState { copy(pendingSelections = selections) }
    }
}

private fun UserListViewState.hasNoIdentityServerConfigured() = matchingEmail is Fail && matchingEmail.error == IdentityServiceError.NoIdentityServerConfigured

/**
 * Wrapper class to allow identical search terms to be re-emitted.
 */
private data class UserSearch(val searchTerm: String, val cacheBuster: Long = 0)
