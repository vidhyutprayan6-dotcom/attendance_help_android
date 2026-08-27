package attendance.help.device.device.command

/**
 * Starts dual-camera session:
 * both devices turn cameras on together;
 * Controller shows local preview; Remote shows Controller stream.
 */
data class OpenCameraCommand(
    val requestedByDeviceId: String
) : DeviceCommand {
    override val type: String = CommandTypes.OPEN_CAMERA
    override fun toPayload(): String =
        """{"type":"$type","by":"$requestedByDeviceId"}"""
}

data class CloseCameraCommand(
    val requestedByDeviceId: String
) : DeviceCommand {
    override val type: String = CommandTypes.CLOSE_CAMERA
    override fun toPayload(): String =
        """{"type":"$type","by":"$requestedByDeviceId"}"""
}
