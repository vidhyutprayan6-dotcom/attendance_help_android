package attendance.help.device.network.hub

import attendance.help.device.domain.model.DeviceMode
import attendance.help.device.domain.model.HubDevice
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Central virtual-server protocol (WebSocket JSON).
 * All phones connect here; Control selects a Remote from the registry.
 */
sealed class HubMessage {
    abstract val type: String

    data class Register(
        val deviceId: String,
        val displayName: String,
        val mode: String
    ) : HubMessage() {
        override val type = TYPE_REGISTER
    }

    data class RegisterAck(val ok: Boolean, val message: String = "") : HubMessage() {
        override val type = TYPE_REGISTER_ACK
    }

    data class RemotesList(val remotes: List<HubDevice>) : HubMessage() {
        override val type = TYPE_REMOTES_LIST
    }

    data class RequestRemotes(val controlDeviceId: String) : HubMessage() {
        override val type = TYPE_REQUEST_REMOTES
    }

    data class SelectRemote(
        val controlDeviceId: String,
        val remoteDeviceId: String
    ) : HubMessage() {
        override val type = TYPE_SELECT_REMOTE
    }

    data class SessionBound(
        val controlDeviceId: String,
        val remoteDeviceId: String,
        val controlName: String,
        val remoteName: String
    ) : HubMessage() {
        override val type = TYPE_SESSION_BOUND
    }

    data class SessionUnbind(
        val fromId: String,
        val peerId: String
    ) : HubMessage() {
        override val type = TYPE_SESSION_UNBIND
    }

    data class SessionUnbound(
        val reason: String = "released"
    ) : HubMessage() {
        override val type = TYPE_SESSION_UNBOUNDED
    }

    data class Unregister(val deviceId: String) : HubMessage() {
        override val type = TYPE_UNREGISTER
    }

    data class RelayOffer(val fromId: String, val toId: String, val sdp: String) : HubMessage() {
        override val type = TYPE_OFFER
    }

    data class RelayAnswer(val fromId: String, val toId: String, val sdp: String) : HubMessage() {
        override val type = TYPE_ANSWER
    }

    data class RelayIce(
        val fromId: String,
        val toId: String,
        val candidate: String,
        val sdpMid: String?,
        val sdpMLineIndex: Int?
    ) : HubMessage() {
        override val type = TYPE_ICE
    }

    data class CameraStart(val fromId: String, val toId: String) : HubMessage() {
        override val type = TYPE_CAMERA_START
    }

    data class CameraStop(val fromId: String, val toId: String) : HubMessage() {
        override val type = TYPE_CAMERA_STOP
    }

    /** Remote → hub → Control: screen capture is active; safe to start WebRTC offer. */
    data class ScreenReady(val fromId: String, val toId: String) : HubMessage() {
        override val type = TYPE_SCREEN_READY
    }

    data class ErrorMsg(val message: String) : HubMessage() {
        override val type = TYPE_ERROR
    }

    companion object {
        const val TYPE_REGISTER = "register"
        const val TYPE_REGISTER_ACK = "register_ack"
        const val TYPE_REMOTES_LIST = "remotes_list"
        const val TYPE_REQUEST_REMOTES = "request_remotes"
        const val TYPE_SELECT_REMOTE = "select_remote"
        const val TYPE_SESSION_BOUND = "session_bound"
        const val TYPE_SESSION_UNBIND = "session_unbind"
        const val TYPE_SESSION_UNBOUNDED = "session_unbound"
        const val TYPE_UNREGISTER = "unregister"
        const val TYPE_OFFER = "offer"
        const val TYPE_ANSWER = "answer"
        const val TYPE_ICE = "ice"
        const val TYPE_CAMERA_START = "camera_start"
        const val TYPE_CAMERA_STOP = "camera_stop"
        const val TYPE_SCREEN_READY = "screen_ready"
        const val TYPE_ERROR = "error"
    }
}

class HubCodec(private val gson: Gson = Gson()) {

    fun encode(message: HubMessage): String {
        val o = JsonObject()
        o.addProperty("type", message.type)
        when (message) {
            is HubMessage.Register -> {
                o.addProperty("deviceId", message.deviceId)
                o.addProperty("displayName", message.displayName)
                o.addProperty("mode", message.mode)
            }
            is HubMessage.RegisterAck -> {
                o.addProperty("ok", message.ok)
                o.addProperty("message", message.message)
            }
            is HubMessage.RemotesList -> {
                val arr = JsonArray()
                message.remotes.forEach { d ->
                    arr.add(JsonObject().apply {
                        addProperty("deviceId", d.deviceId)
                        addProperty("displayName", d.displayName)
                        addProperty("mode", d.mode.name)
                        addProperty("available", d.available)
                    })
                }
                o.add("remotes", arr)
            }
            is HubMessage.RequestRemotes -> o.addProperty("controlDeviceId", message.controlDeviceId)
            is HubMessage.SelectRemote -> {
                o.addProperty("controlDeviceId", message.controlDeviceId)
                o.addProperty("remoteDeviceId", message.remoteDeviceId)
            }
            is HubMessage.SessionBound -> {
                o.addProperty("controlDeviceId", message.controlDeviceId)
                o.addProperty("remoteDeviceId", message.remoteDeviceId)
                o.addProperty("controlName", message.controlName)
                o.addProperty("remoteName", message.remoteName)
            }
            is HubMessage.SessionUnbind -> {
                o.addProperty("fromId", message.fromId)
                o.addProperty("peerId", message.peerId)
            }
            is HubMessage.SessionUnbound -> o.addProperty("reason", message.reason)
            is HubMessage.Unregister -> o.addProperty("deviceId", message.deviceId)
            is HubMessage.RelayOffer -> {
                o.addProperty("fromId", message.fromId)
                o.addProperty("toId", message.toId)
                o.addProperty("sdp", message.sdp)
            }
            is HubMessage.RelayAnswer -> {
                o.addProperty("fromId", message.fromId)
                o.addProperty("toId", message.toId)
                o.addProperty("sdp", message.sdp)
            }
            is HubMessage.RelayIce -> {
                o.addProperty("fromId", message.fromId)
                o.addProperty("toId", message.toId)
                o.addProperty("candidate", message.candidate)
                message.sdpMid?.let { o.addProperty("sdpMid", it) }
                message.sdpMLineIndex?.let { o.addProperty("sdpMLineIndex", it) }
            }
            is HubMessage.CameraStart -> {
                o.addProperty("fromId", message.fromId)
                o.addProperty("toId", message.toId)
            }
            is HubMessage.CameraStop -> {
                o.addProperty("fromId", message.fromId)
                o.addProperty("toId", message.toId)
            }
            is HubMessage.ScreenReady -> {
                o.addProperty("fromId", message.fromId)
                o.addProperty("toId", message.toId)
            }
            is HubMessage.ErrorMsg -> o.addProperty("message", message.message)
        }
        return gson.toJson(o)
    }

    fun decode(raw: String): HubMessage? = runCatching {
        val o = gson.fromJson(raw, JsonObject::class.java)
        when (o.get("type")?.asString) {
            HubMessage.TYPE_REGISTER -> HubMessage.Register(
                deviceId = o.get("deviceId").asString,
                displayName = o.get("displayName").asString,
                mode = o.get("mode").asString
            )
            HubMessage.TYPE_REGISTER_ACK -> HubMessage.RegisterAck(
                ok = o.get("ok").asBoolean,
                message = o.get("message")?.asString.orEmpty()
            )
            HubMessage.TYPE_REMOTES_LIST -> {
                val list = mutableListOf<HubDevice>()
                o.getAsJsonArray("remotes")?.forEach { el ->
                    val r = el.asJsonObject
                    list += HubDevice(
                        deviceId = r.get("deviceId").asString,
                        displayName = r.get("displayName").asString,
                        mode = runCatching { DeviceMode.valueOf(r.get("mode").asString) }
                            .getOrDefault(DeviceMode.REMOTE),
                        available = r.get("available")?.asBoolean ?: true
                    )
                }
                HubMessage.RemotesList(list)
            }
            HubMessage.TYPE_REQUEST_REMOTES -> HubMessage.RequestRemotes(
                controlDeviceId = o.get("controlDeviceId").asString
            )
            HubMessage.TYPE_SELECT_REMOTE -> HubMessage.SelectRemote(
                controlDeviceId = o.get("controlDeviceId").asString,
                remoteDeviceId = o.get("remoteDeviceId").asString
            )
            HubMessage.TYPE_SESSION_BOUND -> HubMessage.SessionBound(
                controlDeviceId = o.get("controlDeviceId").asString,
                remoteDeviceId = o.get("remoteDeviceId").asString,
                controlName = o.get("controlName")?.asString ?: "Control",
                remoteName = o.get("remoteName")?.asString ?: "Remote"
            )
            HubMessage.TYPE_SESSION_UNBIND -> HubMessage.SessionUnbind(
                fromId = o.get("fromId").asString,
                peerId = o.get("peerId").asString
            )
            HubMessage.TYPE_SESSION_UNBOUNDED -> HubMessage.SessionUnbound(
                reason = o.get("reason")?.asString ?: "released"
            )
            HubMessage.TYPE_UNREGISTER -> HubMessage.Unregister(o.get("deviceId").asString)
            HubMessage.TYPE_OFFER -> HubMessage.RelayOffer(
                fromId = o.get("fromId").asString,
                toId = o.get("toId").asString,
                sdp = o.get("sdp").asString
            )
            HubMessage.TYPE_ANSWER -> HubMessage.RelayAnswer(
                fromId = o.get("fromId").asString,
                toId = o.get("toId").asString,
                sdp = o.get("sdp").asString
            )
            HubMessage.TYPE_ICE -> HubMessage.RelayIce(
                fromId = o.get("fromId").asString,
                toId = o.get("toId").asString,
                candidate = o.get("candidate").asString,
                sdpMid = o.get("sdpMid")?.asString,
                sdpMLineIndex = o.get("sdpMLineIndex")?.asInt
            )
            HubMessage.TYPE_CAMERA_START -> HubMessage.CameraStart(
                fromId = o.get("fromId").asString,
                toId = o.get("toId").asString
            )
            HubMessage.TYPE_CAMERA_STOP -> HubMessage.CameraStop(
                fromId = o.get("fromId").asString,
                toId = o.get("toId").asString
            )
            HubMessage.TYPE_SCREEN_READY -> HubMessage.ScreenReady(
                fromId = o.get("fromId").asString,
                toId = o.get("toId").asString
            )
            HubMessage.TYPE_ERROR -> HubMessage.ErrorMsg(o.get("message")?.asString ?: "error")
            else -> null
        }
    }.getOrNull()
}
