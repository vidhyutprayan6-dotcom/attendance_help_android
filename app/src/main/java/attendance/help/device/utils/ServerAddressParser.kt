package attendance.help.device.utils

/**
 * Parses user-entered hub server addresses for local LAN or cloud (Render, etc.).
 *
 * Local LAN examples:
 * - 172.20.1.51
 * - 172.20.1.51:8765
 * - ws://172.20.1.51:8765
 *
 * Cloud examples (secure WebSocket on port 443):
 * - your-app.onrender.com
 * - https://your-app.onrender.com
 * - wss://your-app.onrender.com
 */
object ServerAddressParser {

    data class Endpoint(
        val host: String,
        val port: Int,
        /** true = wss:// (TLS), false = ws:// (LAN) */
        val secure: Boolean
    )

    fun parse(rawInput: String, defaultPort: Int): Result<Endpoint> {
        return runCatching {
            var raw = rawInput.trim()
            require(raw.isNotEmpty()) { "Server address is empty" }

            val explicitSecure = raw.startsWith("wss://", ignoreCase = true) ||
                raw.startsWith("https://", ignoreCase = true)
            val explicitInsecure = raw.startsWith("ws://", ignoreCase = true) ||
                raw.startsWith("http://", ignoreCase = true)

            raw = raw.removePrefix("wss://")
                .removePrefix("WSS://")
                .removePrefix("ws://")
                .removePrefix("WS://")
                .removePrefix("https://")
                .removePrefix("HTTPS://")
                .removePrefix("http://")
                .removePrefix("HTTP://")

            raw = raw.substringBefore('/').substringBefore('?').trim()
            require(raw.isNotEmpty()) { "Invalid server address" }

            val host: String
            val port: Int
            if (raw.contains(':')) {
                val idx = raw.lastIndexOf(':')
                host = raw.substring(0, idx).trim()
                val portPart = raw.substring(idx + 1).trim()
                port = portPart.toIntOrNull()
                    ?: error("Port must be a number (example: 172.20.1.51:8765)")
                require(port in 1..65535) { "Port out of range" }
            } else {
                host = raw
                port = defaultPort
            }

            require(host.isNotBlank()) { "Host is empty" }
            require(!host.contains(' ')) { "Host cannot contain spaces" }
            require(!host.contains(':')) { "Invalid host. Enter host only, or host:port once." }

            val cloudHost = isCloudHostname(host)
            val secure = when {
                explicitSecure -> true
                explicitInsecure -> false
                port == 443 -> true
                cloudHost -> true
                else -> false
            }
            val effectivePort = when {
                secure && port == defaultPort -> 443
                else -> port
            }

            Endpoint(host = host, port = effectivePort, secure = secure)
        }
    }

    /** WebSocket URL for hub signaling (OkHttp). */
    fun toSignalingWsUrl(endpoint: Endpoint): String {
        val scheme = if (endpoint.secure) "wss" else "ws"
        return "$scheme://${endpoint.host}:${endpoint.port}"
    }

    /** @deprecated use [toSignalingWsUrl] */
    fun toWsUrl(endpoint: Endpoint): String = toSignalingWsUrl(endpoint)

    /** Domain name (not IPv4) — treated as cloud / public hub → wss on 443. */
    fun isCloudHostname(host: String): Boolean {
        if (host.equals("localhost", ignoreCase = true)) return false
        if (isIpv4(host)) return false
        return host.contains('.')
    }

    private fun isIpv4(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            val n = part.toIntOrNull() ?: return false
            n in 0..255
        }
    }
}
