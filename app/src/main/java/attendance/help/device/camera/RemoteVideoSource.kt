package attendance.help.device.camera

import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Marks that the Remote UI should render the inbound Controller video track.
 */
@Singleton
class RemoteVideoSource @Inject constructor() : CameraSource {
    private val running = AtomicBoolean(false)
    override val isRunning: Boolean get() = running.get()

    override suspend fun start() {
        running.set(true)
    }

    override suspend fun stop() {
        running.set(false)
    }
}
