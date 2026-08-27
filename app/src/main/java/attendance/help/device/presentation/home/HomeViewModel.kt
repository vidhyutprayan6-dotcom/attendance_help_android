package attendance.help.device.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import attendance.help.device.device.DeviceIdentityProvider
import attendance.help.device.domain.model.ConnectionState
import attendance.help.device.domain.model.DeviceRole
import attendance.help.device.domain.model.PeerDevice
import attendance.help.device.domain.repository.SessionRepository
import attendance.help.device.webrtc.SessionController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class HomeUiState(
    val role: DeviceRole? = null,
    val connectionState: ConnectionState = ConnectionState.NOT_PAIRED,
    val peer: PeerDevice? = null,
    val deviceId: String = "",
    val liveStatus: String = ""
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    sessionRepository: SessionRepository,
    deviceIdentityProvider: DeviceIdentityProvider,
    sessionController: SessionController
) : ViewModel() {

    private val localDeviceId = deviceIdentityProvider.getOrCreateDeviceId()

    val uiState: StateFlow<HomeUiState> = combine(
        sessionRepository.deviceRole,
        sessionRepository.connectionState,
        sessionRepository.peerDevice,
        sessionController.uiState
    ) { role, connection, peer, live ->
        HomeUiState(
            role = role,
            connectionState = connection,
            peer = peer,
            deviceId = localDeviceId,
            liveStatus = live.statusMessage
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(deviceId = localDeviceId)
    )
}
