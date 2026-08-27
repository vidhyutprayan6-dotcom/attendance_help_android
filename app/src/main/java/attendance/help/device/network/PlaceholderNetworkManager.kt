package attendance.help.device.network

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Placeholder so Hilt graph and call sites can compile before Step 4.
 */
@Singleton
class PlaceholderNetworkManager @Inject constructor() : NetworkManager {
    override suspend fun isPeerReachable(ipAddress: String): Boolean = false
    override suspend fun sendMessage(ipAddress: String, payload: String): Result<Unit> =
        Result.failure(IllegalStateException("NetworkManager not implemented yet"))
}
