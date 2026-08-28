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
    const val TOUCH = "TOUCH"
    const val KEY_BACK = "KEY_BACK"
    const val KEY_HOME = "KEY_HOME"
    const val KEY_RECENTS = "KEY_RECENTS"
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

/** Normalized touch (0..1) from Control screen onto Remote device. */
data class TouchCommand(
    val action: String, // down | move | up
    val x: Float,
    val y: Float
) : DeviceCommand {
    override val type: String = CommandTypes.TOUCH
    override fun toPayload(): String =
        """{"type":"$type","action":"$action","x":$x,"y":$y}"""
}

data class KeyCommand(val typeName: String) : DeviceCommand {
    override val type: String = typeName
    override fun toPayload(): String = """{"type":"$type"}"""
}

object CommandParser {
    fun typeOf(payload: String): String? =
        Regex(""""type"\s*:\s*"([^"]+)"""").find(payload)?.groupValues?.getOrNull(1)

    fun parseTouch(payload: String): TouchCommand? {
        if (typeOf(payload) != CommandTypes.TOUCH) return null
        val action = Regex(""""action"\s*:\s*"([^"]+)"""").find(payload)?.groupValues?.getOrNull(1)
            ?: return null
        val x = Regex(""""x"\s*:\s*([0-9.]+)""").find(payload)?.groupValues?.getOrNull(1)?.toFloatOrNull()
            ?: return null
        val y = Regex(""""y"\s*:\s*([0-9.]+)""").find(payload)?.groupValues?.getOrNull(1)?.toFloatOrNull()
            ?: return null
        return TouchCommand(action = action, x = x, y = y)
    }
}
