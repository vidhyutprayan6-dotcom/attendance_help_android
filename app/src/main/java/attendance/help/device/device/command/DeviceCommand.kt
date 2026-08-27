package attendance.help.device.device.command

/**
 * Command pattern root for unrestricted remote control.
 * Concrete commands (camera, status, future device actions) plug in here
 * without rewriting network / WebRTC layers.
 */
interface DeviceCommand {
    val type: String
    fun toPayload(): String
}

object CommandTypes {
    const val OPEN_CAMERA = "OPEN_CAMERA"
    const val CLOSE_CAMERA = "CLOSE_CAMERA"
    const val DEVICE_STATUS = "DEVICE_STATUS"
    const val PING = "PING"
    const val PONG = "PONG"
}
