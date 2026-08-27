package attendance.help.device.domain.repository

import attendance.help.device.domain.model.ConnectionState
import attendance.help.device.domain.model.DeviceRole
import attendance.help.device.domain.model.PeerDevice
import kotlinx.coroutines.flow.Flow

interface SessionRepository {
    val setupComplete: Flow<Boolean>
    val deviceRole: Flow<DeviceRole?>
    val connectionState: Flow<ConnectionState>
    val peerDevice: Flow<PeerDevice?>
    val pairingCode: Flow<String?>
    val lastError: Flow<String?>

    suspend fun setSetupComplete(complete: Boolean)
    suspend fun setDeviceRole(role: DeviceRole)
    suspend fun setConnectionState(state: ConnectionState)
    suspend fun setPeerDevice(peer: PeerDevice?)
    suspend fun setPairingCode(code: String?)
    suspend fun setLastError(message: String?)
    suspend fun clearPairing()
}
