package attendance.help.device.domain.repository

import attendance.help.device.domain.model.DeviceMode
import attendance.help.device.domain.model.ServerLinkState
import attendance.help.device.domain.model.SessionLinkState
import kotlinx.coroutines.flow.Flow

interface SessionRepository {
    val serverHost: Flow<String>
    val hostingHubLocally: Flow<Boolean>
    val serverLinkState: Flow<ServerLinkState>
    val deviceMode: Flow<DeviceMode>
    val sessionLinkState: Flow<SessionLinkState>
    val displayName: Flow<String>
    val lastError: Flow<String?>

    suspend fun setServerHost(host: String)
    suspend fun setHostingHubLocally(hosting: Boolean)
    suspend fun setServerLinkState(state: ServerLinkState)
    suspend fun setDeviceMode(mode: DeviceMode)
    suspend fun setSessionLinkState(state: SessionLinkState)
    suspend fun setDisplayName(name: String)
    suspend fun setLastError(message: String?)
    /** Clears mode and session binding; keeps server host for reconnect convenience. */
    suspend fun clearModeSettings()
    /** Full factory reset of persisted link settings. */
    suspend fun resetAll()
}
