package attendance.help.device.presentation.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import attendance.help.device.domain.model.DeviceRole
import attendance.help.device.domain.repository.SessionRepository
import attendance.help.device.webrtc.LiveSessionUi
import attendance.help.device.webrtc.SessionController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.webrtc.SurfaceViewRenderer
import javax.inject.Inject

@HiltViewModel
class SessionViewModel @Inject constructor(
    private val sessionController: SessionController,
    sessionRepository: SessionRepository
) : ViewModel() {

    val role: StateFlow<DeviceRole?> = sessionRepository.deviceRole.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        null
    )

    val uiState: StateFlow<LiveSessionUi> = sessionController.uiState.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        LiveSessionUi()
    )

    fun refreshFromController() = Unit

    fun bindRenderers(local: SurfaceViewRenderer?, remote: SurfaceViewRenderer?) {
        sessionController.bindRenderers(local, remote)
    }

    fun unbindRenderers() {
        sessionController.unbindRenderers()
    }

    fun openCameras() = sessionController.openDualCamera()

    fun closeCameras() = sessionController.closeDualCamera()

    fun ping() = sessionController.sendPing()

    fun disconnect() {
        viewModelScope.launch { sessionController.disconnect() }
    }
}
