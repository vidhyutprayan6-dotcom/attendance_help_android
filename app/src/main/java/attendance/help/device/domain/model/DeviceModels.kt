package attendance.help.device.domain.model

/**
 * Phone operating mode after connecting to the virtual server.
 * NONE = no mode / cleared back to initial state.
 */
enum class DeviceMode {
    NONE,
    REMOTE,
    CONTROL
}

enum class ServerLinkState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    HOSTING_AND_CONNECTED,
    ERROR
}

enum class SessionLinkState {
    IDLE,
    WAITING_FOR_CONTROL,   // remote registered, available in list
    SELECTING_REMOTE,      // control browsing list
    BINDING,
    BOUND,                 // control+remote paired as one
    STREAMING,
    ERROR
}

data class DeviceIdentity(
    val deviceId: String,
    val displayName: String
)

data class HubDevice(
    val deviceId: String,
    val displayName: String,
    val mode: DeviceMode,
    val available: Boolean = true
)

data class DualCameraSessionState(
    val isActive: Boolean = false,
    val bothCamerasOn: Boolean = false,
    /** Control phone shows remote camera feed. */
    val controlShowsRemoteFeed: Boolean = true,
    /** Remote phone shows control camera feed. */
    val remoteShowsControlFeed: Boolean = true
)

data class AppLinkSnapshot(
    val serverHost: String = "",
    val serverPort: Int = 8765,
    val hostingHubLocally: Boolean = false,
    val serverLinkState: ServerLinkState = ServerLinkState.DISCONNECTED,
    val mode: DeviceMode = DeviceMode.NONE,
    val sessionLinkState: SessionLinkState = SessionLinkState.IDLE,
    val boundPeer: HubDevice? = null,
    val availableRemotes: List<HubDevice> = emptyList(),
    val dualCamera: DualCameraSessionState = DualCameraSessionState(),
    val statusMessage: String = "",
    val lastError: String? = null,
    val localDeviceId: String = "",
    val webrtcState: String = "NEW"
)
