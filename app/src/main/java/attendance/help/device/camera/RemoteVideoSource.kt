package attendance.help.device.camera

import javax.inject.Inject

/**
 * Represents the inbound Controller video on the Remote device (Step 7).
 * Remote UI binds to this source for display — not to its own camera preview.
 */
class RemoteVideoSource @Inject constructor() : CameraSource {
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
