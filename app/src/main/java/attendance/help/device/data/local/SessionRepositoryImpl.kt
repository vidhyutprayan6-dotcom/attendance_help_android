package attendance.help.device.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import attendance.help.device.domain.model.ConnectionState
import attendance.help.device.domain.model.DeviceRole
import attendance.help.device.domain.model.PeerDevice
import attendance.help.device.domain.repository.SessionRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypted local store for role, peer IP/id, pairing code, connection snapshot.
 */
@Singleton
class SessionRepositoryImpl @Inject constructor(
    @ApplicationContext context: Context
) : SessionRepository {

    private val mutex = Mutex()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "ah_secure_session",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val _setupComplete = MutableStateFlow(prefs.getBoolean(KEY_SETUP, false))
    private val _role = MutableStateFlow(prefs.getString(KEY_ROLE, null)?.let {
        runCatching { DeviceRole.valueOf(it) }.getOrNull()
    })
    private val _connection = MutableStateFlow(
        prefs.getString(KEY_CONNECTION, ConnectionState.NOT_PAIRED.name)?.let {
            runCatching { ConnectionState.valueOf(it) }.getOrDefault(ConnectionState.NOT_PAIRED)
        } ?: ConnectionState.NOT_PAIRED
    )
    private val _peer = MutableStateFlow(readPeer())
    private val _pairingCode = MutableStateFlow(prefs.getString(KEY_PAIRING_CODE, null))
    private val _lastError = MutableStateFlow(prefs.getString(KEY_LAST_ERROR, null))

    override val setupComplete: Flow<Boolean> = _setupComplete.asStateFlow()
    override val deviceRole: Flow<DeviceRole?> = _role.asStateFlow()
    override val connectionState: Flow<ConnectionState> = _connection.asStateFlow()
    override val peerDevice: Flow<PeerDevice?> = _peer.asStateFlow()
    override val pairingCode: Flow<String?> = _pairingCode.asStateFlow()
    override val lastError: Flow<String?> = _lastError.asStateFlow()

    override suspend fun setSetupComplete(complete: Boolean) = mutex.withLock {
        prefs.edit().putBoolean(KEY_SETUP, complete).apply()
        _setupComplete.value = complete
    }

    override suspend fun setDeviceRole(role: DeviceRole) = mutex.withLock {
        prefs.edit().putString(KEY_ROLE, role.name).apply()
        _role.value = role
    }

    override suspend fun setConnectionState(state: ConnectionState) = mutex.withLock {
        prefs.edit().putString(KEY_CONNECTION, state.name).apply()
        _connection.value = state
    }

    override suspend fun setPeerDevice(peer: PeerDevice?) = mutex.withLock {
        if (peer == null) {
            prefs.edit()
                .remove(KEY_PEER_ID)
                .remove(KEY_PEER_NAME)
                .remove(KEY_PEER_IP)
                .remove(KEY_PEER_LAST)
                .apply()
        } else {
            prefs.edit()
                .putString(KEY_PEER_ID, peer.deviceId)
                .putString(KEY_PEER_NAME, peer.displayName)
                .putString(KEY_PEER_IP, peer.tailscaleIp)
                .putLong(KEY_PEER_LAST, peer.lastConnectedAtEpochMs ?: System.currentTimeMillis())
                .apply()
        }
        _peer.value = peer
    }

    override suspend fun setPairingCode(code: String?) = mutex.withLock {
        prefs.edit().putString(KEY_PAIRING_CODE, code).apply()
        _pairingCode.value = code
    }

    override suspend fun setLastError(message: String?) = mutex.withLock {
        if (message == null) prefs.edit().remove(KEY_LAST_ERROR).apply()
        else prefs.edit().putString(KEY_LAST_ERROR, message).apply()
        _lastError.value = message
    }

    override suspend fun clearPairing() = mutex.withLock {
        prefs.edit()
            .remove(KEY_PEER_ID)
            .remove(KEY_PEER_NAME)
            .remove(KEY_PEER_IP)
            .remove(KEY_PEER_LAST)
            .remove(KEY_PAIRING_CODE)
            .putString(KEY_CONNECTION, ConnectionState.NOT_PAIRED.name)
            .apply()
        _peer.value = null
        _pairingCode.value = null
        _connection.value = ConnectionState.NOT_PAIRED
    }

    private fun readPeer(): PeerDevice? {
        val id = prefs.getString(KEY_PEER_ID, null) ?: return null
        val ip = prefs.getString(KEY_PEER_IP, null) ?: return null
        return PeerDevice(
            deviceId = id,
            displayName = prefs.getString(KEY_PEER_NAME, "Peer") ?: "Peer",
            tailscaleIp = ip,
            lastConnectedAtEpochMs = prefs.getLong(KEY_PEER_LAST, 0L).takeIf { it > 0L }
        )
    }

    private companion object {
        const val KEY_SETUP = "setup_complete"
        const val KEY_ROLE = "device_role"
        const val KEY_CONNECTION = "connection_state"
        const val KEY_PEER_ID = "peer_id"
        const val KEY_PEER_NAME = "peer_name"
        const val KEY_PEER_IP = "peer_ip"
        const val KEY_PEER_LAST = "peer_last"
        const val KEY_PAIRING_CODE = "pairing_code"
        const val KEY_LAST_ERROR = "last_error"
    }
}
