package attendance.help.device.presentation.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import attendance.help.device.domain.model.AppLinkSnapshot
import attendance.help.device.domain.model.DeviceMode
import attendance.help.device.webrtc.SessionController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import org.webrtc.SurfaceViewRenderer
import javax.inject.Inject

@HiltViewModel
class SessionViewModel @Inject constructor(
    private val sessionController: SessionController
) : ViewModel() {

    val ui: StateFlow<AppLinkSnapshot> = sessionController.uiState.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AppLinkSnapshot()
    )

    val mode: DeviceMode get() = ui.value.mode

    fun bindRenderer(renderer: SurfaceViewRenderer?) = sessionController.bindRenderer(renderer)
    fun unbindRenderer() = sessionController.unbindRenderer()
    fun releaseRemote() = sessionController.releaseRemoteControl()
    fun requestScreenShare() = sessionController.requestScreenSharePermission()
    fun stopScreenShare() = sessionController.stopRemoteScreenShare()
    fun refreshAccessibility() = sessionController.refreshAccessibilityState()
    fun sendTap(x: Float, y: Float) = sessionController.sendTap(x, y)
    fun sendSwipe(points: List<Pair<Float, Float>>, durationMs: Long) =
        sessionController.sendSwipe(points, durationMs)
    fun sendRemoteKey(type: String) = sessionController.sendRemoteKey(type)
}
