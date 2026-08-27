package attendance.help.device.camera

import attendance.help.device.webrtc.PeerConnectionManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local capture via WebRTC Camera2 capturer (front camera).
 * CameraX remains in the project for future alternate pipelines / preview tooling;
 * WebRTC capturer owns the single camera session to avoid dual-open conflicts.
 */
@Singleton
class LocalCameraSource @Inject constructor(
    private val peerConnectionManager: PeerConnectionManager
) : CameraSource {
    override val isRunning: Boolean
        get() = peerConnectionManager.isCameraRunning()

    override suspend fun start() {
        peerConnectionManager.startCamera(preferFront = true)
    }

    override suspend fun stop() {
        peerConnectionManager.stopCamera()
    }
}
