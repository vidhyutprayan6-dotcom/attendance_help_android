package attendance.help.device.camera

/**
 * Swappable video source so Local vs Remote rendering stays decoupled.
 */
interface CameraSource {
    suspend fun start()
    suspend fun stop()
    val isRunning: Boolean
}
