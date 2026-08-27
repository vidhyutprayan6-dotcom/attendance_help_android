package attendance.help.device.domain.usecase

import attendance.help.device.domain.model.DeviceRole
import attendance.help.device.domain.repository.SessionRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveDeviceRoleUseCase @Inject constructor(
    private val sessionRepository: SessionRepository
) {
    operator fun invoke(): Flow<DeviceRole?> = sessionRepository.deviceRole
}
