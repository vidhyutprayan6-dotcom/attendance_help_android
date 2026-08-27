package attendance.help.device.camera

import javax.inject.Inject

/**
 * CameraX-backed local capture (Step 6).
 * On Controller this feeds both local preview and the outbound WebRTC track.
 */
class LocalCameraSource @Inject constructor() : CameraSource {
    @Volatile
    override var isRunning: Boolean = false
        private set

    override suspend fun start() {
        isRunning = true
    }

    override suspend fun stop() {
        isRunning = false
    }
}
