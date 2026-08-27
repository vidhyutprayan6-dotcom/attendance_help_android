package attendance.help.device.camera

/**
 * Abstraction so video sources can be swapped without changing UI / session logic.
 */
interface CameraSource {
    val isRunning: Boolean
    suspend fun start()
    suspend fun stop()
}
