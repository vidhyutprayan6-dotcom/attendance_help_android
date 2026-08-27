package attendance.help.device.domain.usecase

import attendance.help.device.domain.model.DeviceMode
import attendance.help.device.webrtc.SessionController
import javax.inject.Inject

class SetDeviceModeUseCase @Inject constructor(
    private val sessionController: SessionController
) {
    suspend operator fun invoke(mode: DeviceMode) = sessionController.setMode(mode)
}
