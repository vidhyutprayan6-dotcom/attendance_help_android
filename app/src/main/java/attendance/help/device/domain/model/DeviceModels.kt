package attendance.help.device.domain.model

enum class DeviceRole {
    CONTROLLER,
    REMOTE
}

enum class ConnectionState {
    UNKNOWN,
    NOT_PAIRED,
    WAITING_FOR_PEER,
    PAIRING,
    PAIRED_DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ERROR
}

data class DeviceIdentity(
    val deviceId: String,
    val displayName: String
)

data class PeerDevice(
    val deviceId: String,
    val displayName: String,
    val tailscaleIp: String,
    val lastConnectedAtEpochMs: Long? = null
)

data class PairingSession(
    val pairingCode: String,
    val controllerIp: String,
    val signalingPort: Int
)

/**
 * Dual-camera product rules:
 * - Both cameras start/stop together.
 * - Controller UI shows local (own) camera preview.
 * - Remote UI shows the Controller's inbound video stream.
 */
data class DualCameraSessionState(
    val isActive: Boolean = false,
    val controllerCameraOn: Boolean = false,
    val remoteCameraOn: Boolean = false,
    val controllerShowsLocalPreview: Boolean = true,
    val remoteShowsControllerStream: Boolean = true
)

data class AppSessionSnapshot(
    val setupComplete: Boolean = false,
    val role: DeviceRole? = null,
    val connectionState: ConnectionState = ConnectionState.NOT_PAIRED,
    val peer: PeerDevice? = null,
    val localDeviceId: String = "",
    val pairingCode: String? = null,
    val statusMessage: String = "",
    val dualCamera: DualCameraSessionState = DualCameraSessionState(),
    val lastError: String? = null
)
