package im.vector.app.features.home.callhometab

import com.airbnb.mvrx.MavericksState

data class CallHomeViewState(
        val uiState: CallHomeUiState = CallHomeUiState.Idle,
        val isPaginating: Boolean = false
) : MavericksState

sealed class CallHomeUiState {
    object Idle : CallHomeUiState()
    object Loading : CallHomeUiState()
    data class Success(val items: List<CallScreenItem>) : CallHomeUiState()
    object Empty : CallHomeUiState()
    data class Error(val message: String?) : CallHomeUiState()
}
