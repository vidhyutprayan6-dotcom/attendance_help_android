package attendance.help.device.webrtc

import org.webrtc.PeerConnection
import timber.log.Timber

/** Structured WebRTC signaling logs (sessionId + deviceId + signalingState on every line). */
object WebRtcSignalingLog {
    fun log(
        step: String,
        sessionId: String,
        deviceId: String,
        signalingState: PeerConnection.SignalingState?
    ) {
        Timber.tag("WEBRTC").i(
            "%s sessionId=%s deviceId=%s signalingState=%s",
            step,
            sessionId,
            deviceId,
            signalingState?.name ?: "null"
        )
    }
}
