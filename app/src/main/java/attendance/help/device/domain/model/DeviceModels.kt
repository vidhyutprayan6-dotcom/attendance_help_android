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

/** Debug / UI snapshot of WebRTC transport state. */
data class WebRtcTransportDiagnostics(
    val localDeviceId: String = "",
    val remoteDeviceId: String = "",
    val sessionId: String = "",
    val role: String = "",
    val peerGeneration: Int = 0,
    val signalingState: String = "NEW",
    val iceGatheringState: String = "NEW",
    val iceConnectionState: String = "NEW",
    val connectionState: String = "NEW",
    val dataChannelState: String = "CLOSED",
    val localHostCandidates: Int = 0,
    val localSrflxCandidates: Int = 0,
    val localRelayCandidates: Int = 0,
    val remoteHostCandidates: Int = 0,
    val remoteSrflxCandidates: Int = 0,
    val remoteRelayCandidates: Int = 0,
    val turnConfigured: Boolean = false,
    val turnRelayAvailable: Boolean = false,
    val forceRelayOnly: Boolean = false,
    val remoteDescriptionSet: Boolean = false,
    val localDescriptionSet: Boolean = false,
    val queuedRemoteCandidates: Int = 0,
    val appliedRemoteCandidates: Int = 0,
    val failedAddCandidateCalls: Int = 0,
    val remoteVideoReceived: Boolean = false,
    val captureActive: Boolean = false,
    val transportConnected: Boolean = false,
    val lastIceError: String = "",
    val lastDiagnosticEvent: String = ""
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
    val captureGeometry: CaptureGeometry = CaptureGeometry(),
    val webrtcDiagnostics: WebRtcTransportDiagnostics = WebRtcTransportDiagnostics(),
    val transportConnected: Boolean = false
)
