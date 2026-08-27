package attendance.help.device.network.hub

import attendance.help.device.domain.model.DeviceMode
import attendance.help.device.domain.model.HubDevice
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import timber.log.Timber
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * In-app virtual server (registry + signaling relay).
 * One phone can host this; others connect via its Tailscale/LAN IP.
 */
class HubServer(
    port: Int,
    private val codec: HubCodec = HubCodec()
) : WebSocketServer(InetSocketAddress(port)) {

    private data class ClientInfo(
        val socket: WebSocket,
        var deviceId: String = "",
        var displayName: String = "",
        var mode: DeviceMode = DeviceMode.NONE,
        var boundPeerId: String? = null
    )

    private val clients = ConcurrentHashMap<WebSocket, ClientInfo>()
    private val byDeviceId = ConcurrentHashMap<String, ClientInfo>()

    init {
        isReuseAddr = true
        connectionLostTimeout = 45
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        clients[conn] = ClientInfo(conn)
        Timber.i("Hub client connected: %s", conn.remoteSocketAddress)
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        val info = clients.remove(conn) ?: return
        if (info.deviceId.isNotBlank()) {
            byDeviceId.remove(info.deviceId)
            info.boundPeerId?.let { peerId ->
                byDeviceId[peerId]?.boundPeerId = null
            }
            broadcastRemotes()
        }
        Timber.i("Hub client closed: %s", reason)
    }

    override fun onMessage(conn: WebSocket, message: String) {
        val msg = codec.decode(message) ?: return
        val info = clients[conn] ?: return
        when (msg) {
            is HubMessage.Register -> {
                // Replace previous socket for same device id.
                byDeviceId[msg.deviceId]?.let { old ->
                    if (old.socket !== conn) {
                        clients.remove(old.socket)
                        runCatching { old.socket.close() }
                    }
                }
                val mode = runCatching { DeviceMode.valueOf(msg.mode) }.getOrDefault(DeviceMode.NONE)
                info.deviceId = msg.deviceId
                info.displayName = msg.displayName
                info.mode = mode
                info.boundPeerId = null
                byDeviceId[msg.deviceId] = info
                send(conn, HubMessage.RegisterAck(true, "registered as $mode"))
                broadcastRemotes()
            }

            is HubMessage.RequestRemotes -> {
                send(conn, HubMessage.RemotesList(currentRemotes()))
            }

            is HubMessage.SelectRemote -> {
                val control = byDeviceId[msg.controlDeviceId]
                val remote = byDeviceId[msg.remoteDeviceId]
                if (control == null || remote == null || remote.mode != DeviceMode.REMOTE) {
                    send(conn, HubMessage.ErrorMsg("Remote not available"))
                    return
                }
                if (remote.boundPeerId != null && remote.boundPeerId != control.deviceId) {
                    send(conn, HubMessage.ErrorMsg("Remote already bound"))
                    return
                }
                control.boundPeerId = remote.deviceId
                remote.boundPeerId = control.deviceId
                val bound = HubMessage.SessionBound(
                    controlDeviceId = control.deviceId,
                    remoteDeviceId = remote.deviceId,
                    controlName = control.displayName,
                    remoteName = remote.displayName
                )
                send(control.socket, bound)
                send(remote.socket, bound)
                broadcastRemotes()
            }

            is HubMessage.Unregister -> {
                info.mode = DeviceMode.NONE
                info.boundPeerId = null
                broadcastRemotes()
            }

            is HubMessage.RelayOffer -> relay(msg.toId, msg)
            is HubMessage.RelayAnswer -> relay(msg.toId, msg)
            is HubMessage.RelayIce -> relay(msg.toId, msg)
            is HubMessage.CameraStart -> relay(msg.toId, msg)
            is HubMessage.CameraStop -> relay(msg.toId, msg)

            else -> Unit
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Timber.e(ex, "Hub server error")
    }

    override fun onStart() {
        Timber.i("Virtual hub started on port %s", address.port)
    }

    private fun relay(toId: String, message: HubMessage) {
        val target = byDeviceId[toId] ?: return
        send(target.socket, message)
    }

    private fun currentRemotes(): List<HubDevice> =
        byDeviceId.values
            .filter { it.mode == DeviceMode.REMOTE && it.deviceId.isNotBlank() }
            .map {
                HubDevice(
                    deviceId = it.deviceId,
                    displayName = it.displayName,
                    mode = DeviceMode.REMOTE,
                    available = it.boundPeerId == null
                )
            }

    private fun broadcastRemotes() {
        val list = HubMessage.RemotesList(currentRemotes())
        clients.keys.forEach { send(it, list) }
    }

    private fun send(socket: WebSocket, message: HubMessage) {
        if (socket.isOpen) socket.send(codec.encode(message))
    }
}
