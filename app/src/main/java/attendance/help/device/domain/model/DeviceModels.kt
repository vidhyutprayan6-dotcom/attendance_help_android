package attendance.help.device.domain.model

/**
 * Role of this installation. Same APK; user picks once (Step 2 UI).
 */
enum class DeviceRole {
    CONTROLLER,
    REMOTE
}

/**
 * High-level link state between the two phones.
 * Expanded in pairing / network steps.
 */
enum class ConnectionState {
    UNKNOWN,
    NOT_PAIRED,
    PAIRING,
    PAIRED_DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

/**
 * Local identity for this device. Generated once and stored securely later.
 */
data class DeviceIdentity(
    val deviceId: String,
    val displayName: String
)

/**
 * Paired peer information (Tailscale IP is the primary locator in v1).
 */
data class PeerDevice(
    val deviceId: String,
    val displayName: String,
    val tailscaleIp: String,
    val lastConnectedAtEpochMs: Long? = null
)
