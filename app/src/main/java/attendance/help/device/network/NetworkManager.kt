package attendance.help.device.network

import attendance.help.device.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

interface NetworkManager {
    suspend fun isPeerReachable(ipAddress: String, port: Int = BuildConfig.SIGNALING_PORT): Boolean
    suspend fun sendHttpProbe(ipAddress: String): Result<Unit>
    fun signalingWsUrl(ipAddress: String, port: Int = BuildConfig.SIGNALING_PORT): String
}

@Singleton
class TailscaleNetworkManager @Inject constructor() : NetworkManager {

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    override suspend fun isPeerReachable(ipAddress: String, port: Int): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(ipAddress, port), 2500)
                    true
                }
            }.getOrDefault(false)
        }

    override suspend fun sendHttpProbe(ipAddress: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                // Lightweight TCP reachability via signaling port.
                if (!isPeerReachable(ipAddress)) error("Peer not reachable on Tailscale IP")
            }
        }

    override fun signalingWsUrl(ipAddress: String, port: Int): String {
        val parsed = attendance.help.device.utils.ServerAddressParser.parse(ipAddress, port)
            .getOrElse { return "ws://$ipAddress:$port" }
        return attendance.help.device.utils.ServerAddressParser.toSignalingWsUrl(parsed)
    }
}
