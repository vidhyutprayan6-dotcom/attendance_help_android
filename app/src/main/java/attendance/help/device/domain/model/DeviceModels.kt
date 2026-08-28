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

/** Authoritative remote-control session lifecycle (REMOTE side focus). */
enum class RemoteSessionState {
    DISCONNECTED,
    CONNECTED,
    WAITING,
    REQUESTING_SCREEN_PERMISSION,
    SCREEN_PERMISSION_GRANTED,
    STARTING_STREAM,
    STREAMING,
    STOPPING,
    ERROR
}

data class CaptureGeometry(
    val sessionId: String = "",
    val captureWidth: Int = 0,
    val captureHeight: Int = 0,
    val rotation: Int = 0
)

data class TurnServerConfig(
    val urls: List<String> = emptyList(),
    val username: String = "",
    val credential: String = ""
)

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
    /** Both phone cameras must be ON together when the camera session is active. */
    val bothCamerasOn: Boolean = false,
    /** Control UI also shows Remote screen for full control. */
    val controlShowsRemoteFeed: Boolean = true,
    /** Both phones' camera feeds show the Control phone video. */
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
    val webrtcState: String = "NEW",
    /** Remote must grant MediaProjection before Control can see/control its screen. */
    val needsScreenSharePermission: Boolean = false,
    val screenShareActive: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val remoteSessionState: RemoteSessionState = RemoteSessionState.DISCONNECTED,
    val sessionId: String = "",
    val captureGeometry: CaptureGeometry = CaptureGeometry()
)
