package attendance.help.device.domain.usecase

import attendance.help.device.domain.model.DeviceRole
import attendance.help.device.domain.repository.SessionRepository
import javax.inject.Inject

class SetDeviceRoleUseCase @Inject constructor(
    private val sessionRepository: SessionRepository
) {
    suspend operator fun invoke(role: DeviceRole) {
        sessionRepository.setDeviceRole(role)
        sessionRepository.setSetupComplete(true)
    }
}
