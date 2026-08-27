package attendance.help.device.domain.repository

import attendance.help.device.domain.model.ConnectionState
import attendance.help.device.domain.model.DeviceRole
import attendance.help.device.domain.model.PeerDevice
import kotlinx.coroutines.flow.Flow

/**
 * Contract for local session preferences (role, language, pairing snapshot).
 * Implementation lands in the data layer in later steps.
 */
interface SessionRepository {
    val deviceRole: Flow<DeviceRole?>
    val connectionState: Flow<ConnectionState>
    val peerDevice: Flow<PeerDevice?>

    suspend fun setDeviceRole(role: DeviceRole)
    suspend fun clearPairing()
}
