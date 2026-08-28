package attendance.help.device.presentation.remotelist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import attendance.help.device.domain.model.AppLinkSnapshot
import attendance.help.device.domain.model.HubDevice
import attendance.help.device.webrtc.SessionController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class RemoteListViewModel @Inject constructor(
    private val sessionController: SessionController
) : ViewModel() {
    val ui: StateFlow<AppLinkSnapshot> = sessionController.uiState.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AppLinkSnapshot()
    )

    fun refresh() {
        sessionController.announcePresence()
        sessionController.refreshRemoteList()
    }
    fun select(remote: HubDevice) = sessionController.selectRemote(remote)
}
