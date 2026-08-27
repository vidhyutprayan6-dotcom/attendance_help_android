package attendance.help.device.network

/**
 * Abstraction over private-network connectivity (Tailscale IPs).
 * Concrete implementation arrives in Step 4.
 */
interface NetworkManager {
    suspend fun isPeerReachable(ipAddress: String): Boolean
    suspend fun sendMessage(ipAddress: String, payload: String): Result<Unit>
}
