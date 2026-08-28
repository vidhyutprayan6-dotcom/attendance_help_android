package attendance.help.device.presentation.session

import androidx.camera.view.PreviewView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import attendance.help.device.camera.RemotePhysicalCamera
import attendance.help.device.domain.model.AppLinkSnapshot
import attendance.help.device.domain.model.DeviceMode
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
    private val remotePhysicalCamera: RemotePhysicalCamera
) : ViewModel() {

    val ui: StateFlow<AppLinkSnapshot> = sessionController.uiState.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AppLinkSnapshot()
    )

    val mode: DeviceMode get() = ui.value.mode

    fun bindRenderers(remote: SurfaceViewRenderer?, localCameraFeed: SurfaceViewRenderer?) {
        sessionController.bindRenderers(remote, localCameraFeed)
    }

    fun unbindRenderers() = sessionController.unbindRenderers()

    fun releaseRemote() = sessionController.releaseRemoteControl()

    fun requestScreenShare() = sessionController.requestScreenSharePermission()

    fun refreshAccessibility() = sessionController.refreshAccessibilityState()

    fun sendTouch(action: String, x: Float, y: Float) =
        sessionController.sendTouch(action, x, y)

    fun sendRemoteKey(type: String) = sessionController.sendRemoteKey(type)

    fun startRemotePhysicalCamera(previewView: PreviewView, lifecycleOwner: androidx.lifecycle.LifecycleOwner) {
        viewModelScope.launch {
            remotePhysicalCamera.start(lifecycleOwner, previewView)
        }
    }

    fun stopRemotePhysicalCamera() = remotePhysicalCamera.stop()

    override fun onCleared() {
        remotePhysicalCamera.stop()
        super.onCleared()
    }
}
