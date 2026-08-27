package attendance.help.device.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import attendance.help.device.device.DeviceIdentityProvider
import attendance.help.device.domain.model.ConnectionState
import attendance.help.device.domain.model.DeviceRole
import attendance.help.device.domain.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class HomeUiState(
    val role: DeviceRole? = null,
    val connectionState: ConnectionState = ConnectionState.NOT_PAIRED,
    val deviceId: String = ""
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    sessionRepository: SessionRepository,
    deviceIdentityProvider: DeviceIdentityProvider
) : ViewModel() {

    private val localDeviceId = deviceIdentityProvider.getOrCreateDeviceId()

    val uiState: StateFlow<HomeUiState> = combine(
        sessionRepository.deviceRole,
        sessionRepository.connectionState
    ) { role, connection ->
        HomeUiState(
            role = role,
            connectionState = connection,
            deviceId = localDeviceId
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(deviceId = localDeviceId)
    )
}
