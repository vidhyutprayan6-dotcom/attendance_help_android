package attendance.help.device.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import attendance.help.device.domain.model.AppLinkSnapshot
import attendance.help.device.webrtc.SessionController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val sessionController: SessionController
) : ViewModel() {
    val ui: StateFlow<AppLinkSnapshot> = sessionController.uiState.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AppLinkSnapshot()
    )

    fun disconnect() {
        viewModelScope.launch { sessionController.disconnectServer() }
    }
}
