package im.vector.app.features.home.callhometab

import com.airbnb.mvrx.MavericksViewModelFactory
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import im.vector.app.core.di.MavericksAssistedViewModelFactory
import im.vector.app.core.di.hiltMavericksViewModelFactory
import im.vector.app.core.platform.VectorViewModel
import im.vector.app.features.call.webrtc.WebRtcCallManager
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.getRoom
import timber.log.Timber

class CallHomeViewModel @AssistedInject constructor(
        @Assisted initialState: CallHomeViewState,
        private val callHistoryRepository: CallHistoryRepository,
        private val session: Session,
        private val callManager: WebRtcCallManager
) : VectorViewModel<CallHomeViewState, CallHomeActions, CallHomeViewEvents>(initialState) {

    init {
        handle(CallHomeActions.LoadCallLogs)
    }

    override fun handle(action: CallHomeActions) {
        when (action) {
            CallHomeActions.LoadCallLogs -> loadInitialCalls()
            CallHomeActions.LoadMoreCallLogs -> loadMoreCalls()
            is CallHomeActions.CallUser -> handleCallUser(action)
        }
    }

    private fun loadInitialCalls() {
        setState { copy(uiState = CallHomeUiState.Loading) }

        viewModelScope.launch {
            try {
                val items = callHistoryRepository.loadInitialCallEvents()
                val itemsWithCallbacks = items.map { item ->
                    item.copy(onCallBackClick = {
                        handle(CallHomeActions.CallUser(item.callRoomId, item.userId, item.callKind.isVideoCall))
                    })
                }
                if (items.isEmpty()) {
                    Timber.w("🚫 No call items found.")
                    setState { copy(uiState = CallHomeUiState.Empty) }
                } else {
                    Timber.i("✅ Loaded ${items.size} initial call items.")
                    setState { copy(uiState = CallHomeUiState.Success(itemsWithCallbacks)) }
                }
            } catch (e: Exception) {
                Timber.e(e, "💥 Error loading call history")
                setState { copy(uiState = CallHomeUiState.Error(e.localizedMessage ?: "Unexpected error")) }
            }
        }
    }

    private fun loadMoreCalls() {
        // 👇 prevent if already paginating or no more to load
        withState { state ->
            if (state.isPaginating || !callHistoryRepository.hasMore()) return@withState
        }

        // ✅ Set paginating = true
        setState { copy(isPaginating = true) }

        viewModelScope.launch {
            try {
                val newItems = callHistoryRepository.loadMoreCallEvents()
                val newItemsWithCallbacks = newItems.map { item ->
                    item.copy(onCallBackClick = {
                        handle(CallHomeActions.CallUser(item.callRoomId, item.userId, item.callKind.isVideoCall))
                    })
                }
                if (newItems.isEmpty()) {
                    Timber.d("📴 No more call events to load.")
                    setState { copy(isPaginating = false) }
                    return@launch
                }

                setState {
                    val currentItems = (uiState as? CallHomeUiState.Success)?.items.orEmpty()
                    copy(
                            uiState = CallHomeUiState.Success(currentItems + newItemsWithCallbacks),
                            isPaginating = false
                    )
                }

                Timber.d("📦 Loaded ${newItems.size} more call items.")
            } catch (e: Exception) {
                Timber.e(e, "⚠️ Pagination failed")
                setState { copy(isPaginating = false) }
            }
        }
    }

    private fun handleCallUser(action: CallHomeActions.CallUser) {
        viewModelScope.launch {
            val room =session.getRoom(action.roomId)
            if (room != null) {
                callManager.startOutgoingCall(room.roomId, action.userId, action.isVideo)
            } else {
                Timber.e("❌ Room ${action.roomId} not found.")
            }
        }
    }



    @AssistedFactory
    interface Factory : MavericksAssistedViewModelFactory<CallHomeViewModel, CallHomeViewState> {
        override fun create(initialState: CallHomeViewState): CallHomeViewModel
    }

    companion object : MavericksViewModelFactory<CallHomeViewModel, CallHomeViewState> by hiltMavericksViewModelFactory()
}
