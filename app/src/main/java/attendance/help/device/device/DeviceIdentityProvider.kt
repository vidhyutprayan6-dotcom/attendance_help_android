package attendance.help.device.device

/**
 * Supplies a stable local device id for pairing (Step 3).
 */
interface DeviceIdentityProvider {
    fun getOrCreateDeviceId(): String
}
