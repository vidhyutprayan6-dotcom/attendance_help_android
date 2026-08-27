package attendance.help.device.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import attendance.help.device.domain.model.DeviceMode
import attendance.help.device.domain.model.ServerLinkState
import attendance.help.device.domain.model.SessionLinkState
import attendance.help.device.domain.repository.SessionRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionRepositoryImpl @Inject constructor(
    @ApplicationContext context: Context
) : SessionRepository {

    private val mutex = Mutex()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "ah_secure_session_v2",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val _serverHost = MutableStateFlow(prefs.getString(KEY_HOST, "") ?: "")
    private val _hosting = MutableStateFlow(prefs.getBoolean(KEY_HOSTING, false))
    private val _serverLink = MutableStateFlow(enumOr(KEY_SERVER_LINK, ServerLinkState.DISCONNECTED))
    private val _mode = MutableStateFlow(enumOr(KEY_MODE, DeviceMode.NONE))
    private val _session = MutableStateFlow(enumOr(KEY_SESSION, SessionLinkState.IDLE))
    private val _displayName = MutableStateFlow(prefs.getString(KEY_NAME, "Phone") ?: "Phone")
    private val _lastError = MutableStateFlow(prefs.getString(KEY_ERROR, null))

    override val serverHost: Flow<String> = _serverHost.asStateFlow()
    override val hostingHubLocally: Flow<Boolean> = _hosting.asStateFlow()
    override val serverLinkState: Flow<ServerLinkState> = _serverLink.asStateFlow()
    override val deviceMode: Flow<DeviceMode> = _mode.asStateFlow()
    override val sessionLinkState: Flow<SessionLinkState> = _session.asStateFlow()
    override val displayName: Flow<String> = _displayName.asStateFlow()
    override val lastError: Flow<String?> = _lastError.asStateFlow()

    override suspend fun setServerHost(host: String) = mutex.withLock {
        prefs.edit().putString(KEY_HOST, host).apply()
        _serverHost.value = host
    }

    override suspend fun setHostingHubLocally(hosting: Boolean) = mutex.withLock {
        prefs.edit().putBoolean(KEY_HOSTING, hosting).apply()
        _hosting.value = hosting
    }

    override suspend fun setServerLinkState(state: ServerLinkState) = mutex.withLock {
        prefs.edit().putString(KEY_SERVER_LINK, state.name).apply()
        _serverLink.value = state
    }

    override suspend fun setDeviceMode(mode: DeviceMode) = mutex.withLock {
        prefs.edit().putString(KEY_MODE, mode.name).apply()
        _mode.value = mode
    }

    override suspend fun setSessionLinkState(state: SessionLinkState) = mutex.withLock {
        prefs.edit().putString(KEY_SESSION, state.name).apply()
        _session.value = state
    }

    override suspend fun setDisplayName(name: String) = mutex.withLock {
        prefs.edit().putString(KEY_NAME, name).apply()
        _displayName.value = name
    }

    override suspend fun setLastError(message: String?) = mutex.withLock {
        if (message == null) prefs.edit().remove(KEY_ERROR).apply()
        else prefs.edit().putString(KEY_ERROR, message).apply()
        _lastError.value = message
    }

    override suspend fun clearModeSettings() = mutex.withLock {
        prefs.edit()
            .putString(KEY_MODE, DeviceMode.NONE.name)
            .putString(KEY_SESSION, SessionLinkState.IDLE.name)
            .apply()
        _mode.value = DeviceMode.NONE
        _session.value = SessionLinkState.IDLE
    }

    override suspend fun resetAll() = mutex.withLock {
        prefs.edit().clear().apply()
        _serverHost.value = ""
        _hosting.value = false
        _serverLink.value = ServerLinkState.DISCONNECTED
        _mode.value = DeviceMode.NONE
        _session.value = SessionLinkState.IDLE
        _displayName.value = "Phone"
        _lastError.value = null
    }

    private fun <T : Enum<T>> enumOr(key: String, default: T): T {
        val raw = prefs.getString(key, default.name) ?: return default
        return runCatching {
            java.lang.Enum.valueOf(default.declaringJavaClass, raw)
        }.getOrDefault(default)
    }

    private companion object {
        const val KEY_HOST = "server_host"
        const val KEY_HOSTING = "hosting_hub"
        const val KEY_SERVER_LINK = "server_link"
        const val KEY_MODE = "device_mode"
        const val KEY_SESSION = "session_link"
        const val KEY_NAME = "display_name"
        const val KEY_ERROR = "last_error"
    }
}
