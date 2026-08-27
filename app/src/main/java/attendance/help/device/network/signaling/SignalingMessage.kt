package attendance.help.device.network.signaling

import com.google.gson.Gson
import com.google.gson.JsonObject

/**
 * JSON signaling messages exchanged over WebSocket on the Tailscale IP.
 */
sealed class SignalingMessage {
    abstract val type: String

    data class Hello(
        val deviceId: String,
        val role: String,
        val pairingCode: String,
        val displayName: String = "Device"
    ) : SignalingMessage() {
        override val type: String = TYPE_HELLO
    }

    data class Welcome(
        val deviceId: String,
        val role: String,
        val displayName: String = "Device"
    ) : SignalingMessage() {
        override val type: String = TYPE_WELCOME
    }

    data class Reject(val reason: String) : SignalingMessage() {
        override val type: String = TYPE_REJECT
    }

    data class Offer(val sdp: String) : SignalingMessage() {
        override val type: String = TYPE_OFFER
    }

    data class Answer(val sdp: String) : SignalingMessage() {
        override val type: String = TYPE_ANSWER
    }

    data class Ice(
        val candidate: String,
        val sdpMid: String?,
        val sdpMLineIndex: Int?
    ) : SignalingMessage() {
        override val type: String = TYPE_ICE
    }

    data class Hangup(val reason: String = "hangup") : SignalingMessage() {
        override val type: String = TYPE_HANGUP
    }

    data class CameraStart(val byDeviceId: String) : SignalingMessage() {
        override val type: String = TYPE_CAMERA_START
    }

    data class CameraStop(val byDeviceId: String) : SignalingMessage() {
        override val type: String = TYPE_CAMERA_STOP
    }

    companion object {
        const val TYPE_HELLO = "hello"
        const val TYPE_WELCOME = "welcome"
        const val TYPE_REJECT = "reject"
        const val TYPE_OFFER = "offer"
        const val TYPE_ANSWER = "answer"
        const val TYPE_ICE = "ice"
        const val TYPE_HANGUP = "hangup"
        const val TYPE_CAMERA_START = "camera_start"
        const val TYPE_CAMERA_STOP = "camera_stop"
    }
}

class SignalingCodec(
    private val gson: Gson = Gson()
) {
    fun encode(message: SignalingMessage): String {
        val json = JsonObject()
        json.addProperty("type", message.type)
        when (message) {
            is SignalingMessage.Hello -> {
                json.addProperty("deviceId", message.deviceId)
                json.addProperty("role", message.role)
                json.addProperty("pairingCode", message.pairingCode)
                json.addProperty("displayName", message.displayName)
            }
            is SignalingMessage.Welcome -> {
                json.addProperty("deviceId", message.deviceId)
                json.addProperty("role", message.role)
                json.addProperty("displayName", message.displayName)
            }
            is SignalingMessage.Reject -> json.addProperty("reason", message.reason)
            is SignalingMessage.Offer -> json.addProperty("sdp", message.sdp)
            is SignalingMessage.Answer -> json.addProperty("sdp", message.sdp)
            is SignalingMessage.Ice -> {
                json.addProperty("candidate", message.candidate)
                message.sdpMid?.let { json.addProperty("sdpMid", it) }
                message.sdpMLineIndex?.let { json.addProperty("sdpMLineIndex", it) }
            }
            is SignalingMessage.Hangup -> json.addProperty("reason", message.reason)
            is SignalingMessage.CameraStart -> json.addProperty("byDeviceId", message.byDeviceId)
            is SignalingMessage.CameraStop -> json.addProperty("byDeviceId", message.byDeviceId)
        }
        return gson.toJson(json)
    }

    fun decode(raw: String): SignalingMessage? {
        return runCatching {
            val json = gson.fromJson(raw, JsonObject::class.java)
            when (json.get("type")?.asString) {
                SignalingMessage.TYPE_HELLO -> SignalingMessage.Hello(
                    deviceId = json.get("deviceId").asString,
                    role = json.get("role").asString,
                    pairingCode = json.get("pairingCode").asString,
                    displayName = json.get("displayName")?.asString ?: "Device"
                )
                SignalingMessage.TYPE_WELCOME -> SignalingMessage.Welcome(
                    deviceId = json.get("deviceId").asString,
                    role = json.get("role").asString,
                    displayName = json.get("displayName")?.asString ?: "Device"
                )
                SignalingMessage.TYPE_REJECT -> SignalingMessage.Reject(
                    reason = json.get("reason")?.asString ?: "rejected"
                )
                SignalingMessage.TYPE_OFFER -> SignalingMessage.Offer(sdp = json.get("sdp").asString)
                SignalingMessage.TYPE_ANSWER -> SignalingMessage.Answer(sdp = json.get("sdp").asString)
                SignalingMessage.TYPE_ICE -> SignalingMessage.Ice(
                    candidate = json.get("candidate").asString,
                    sdpMid = json.get("sdpMid")?.asString,
                    sdpMLineIndex = json.get("sdpMLineIndex")?.asInt
                )
                SignalingMessage.TYPE_HANGUP -> SignalingMessage.Hangup(
                    reason = json.get("reason")?.asString ?: "hangup"
                )
                SignalingMessage.TYPE_CAMERA_START -> SignalingMessage.CameraStart(
                    byDeviceId = json.get("byDeviceId")?.asString ?: ""
                )
                SignalingMessage.TYPE_CAMERA_STOP -> SignalingMessage.CameraStop(
                    byDeviceId = json.get("byDeviceId")?.asString ?: ""
                )
                else -> null
            }
        }.getOrNull()
    }
}
