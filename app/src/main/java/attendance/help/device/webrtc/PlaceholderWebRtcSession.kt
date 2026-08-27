package attendance.help.device.webrtc

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaceholderWebRtcSession @Inject constructor() : WebRtcSession {
    override suspend fun connect(peerSignalingUrl: String) = Unit
    override suspend fun disconnect() = Unit
}
