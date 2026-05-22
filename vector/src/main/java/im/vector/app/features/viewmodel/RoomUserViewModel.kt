package im.vector.app.features.viewmodel

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import im.vector.app.data.RoomUserUiModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.collections.map
import kotlin.text.equals
import kotlin.text.removePrefix
import kotlin.text.substringBefore

@HiltViewModel
class RoomUserViewModel @Inject constructor(

        @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _roomUsers = MutableLiveData<List<RoomUserUiModel>>()
    val roomUsers: LiveData<List<RoomUserUiModel>> = _roomUsers

    private val _loading = MutableLiveData(false)
    val loading: LiveData<Boolean> = _loading

    fun loadRoomUsers(
            roomId: String,
            matrixUserIds: List<String>
    ) {
        if (matrixUserIds.isEmpty()) return

        _loading.value = true

        viewModelScope.launch {
            try {
                val users = matrixUserIds.map { matrixUserId ->
                    async {
                        val username = matrixUserId
                                .removePrefix("@")
                                .substringBefore(":")


                        // ✅ save role per room
                        RoomUserUiModel(
                                matrixUsername = username,
                                displayName = username, // Shows the extracted username
                                // No role needed for simple search
                        )
                    }
                }.awaitAll()

                _roomUsers.value = users
            } finally {
                _loading.value = false
            }
        }
    }

}


