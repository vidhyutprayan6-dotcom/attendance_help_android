package attendance.help.device.device.command

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

data class OpenCameraCommand(val requestedByDeviceId: String) : DeviceCommand {
    override val type: String = CommandTypes.OPEN_CAMERA
    override fun toPayload(): String =
        """{"type":"$type","by":"$requestedByDeviceId"}"""
}

data class CloseCameraCommand(val requestedByDeviceId: String) : DeviceCommand {
    override val type: String = CommandTypes.CLOSE_CAMERA
    override fun toPayload(): String =
        """{"type":"$type","by":"$requestedByDeviceId"}"""
}

data class PingCommand(val requestedByDeviceId: String) : DeviceCommand {
    override val type: String = CommandTypes.PING
    override fun toPayload(): String =
        """{"type":"$type","by":"$requestedByDeviceId"}"""
}

data class PongCommand(val requestedByDeviceId: String) : DeviceCommand {
    override val type: String = CommandTypes.PONG
    override fun toPayload(): String =
        """{"type":"$type","by":"$requestedByDeviceId"}"""
}

data class StatusCommand(
    val requestedByDeviceId: String,
    val cameraOn: Boolean,
    val role: String
) : DeviceCommand {
    override val type: String = CommandTypes.DEVICE_STATUS
    override fun toPayload(): String =
        """{"type":"$type","by":"$requestedByDeviceId","cameraOn":$cameraOn,"role":"$role"}"""
}

object CommandParser {
    fun typeOf(payload: String): String? =
        Regex(""""type"\s*:\s*"([^"]+)"""").find(payload)?.groupValues?.getOrNull(1)
}
