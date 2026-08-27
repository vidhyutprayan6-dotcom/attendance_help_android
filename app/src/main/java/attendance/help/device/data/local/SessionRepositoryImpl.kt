package attendance.help.device.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import attendance.help.device.domain.model.ConnectionState
import attendance.help.device.domain.model.DeviceRole
import attendance.help.device.domain.model.PeerDevice
import attendance.help.device.domain.repository.SessionRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "session_prefs"
)

/**
 * Lightweight local store for Step 1–2.
 * Pairing fields and encrypted peer secrets expand in Step 3.
 */
@Singleton
class SessionRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : SessionRepository {

    private object Keys {
        val ROLE = stringPreferencesKey("device_role")
    }

    override val deviceRole: Flow<DeviceRole?> = context.sessionDataStore.data.map { prefs ->
        prefs[Keys.ROLE]?.let { runCatching { DeviceRole.valueOf(it) }.getOrNull() }
    }

    override val connectionState: Flow<ConnectionState> = context.sessionDataStore.data.map {
        ConnectionState.NOT_PAIRED
    }

    override val peerDevice: Flow<PeerDevice?> = context.sessionDataStore.data.map { null }

    override suspend fun setDeviceRole(role: DeviceRole) {
        context.sessionDataStore.edit { prefs ->
            prefs[Keys.ROLE] = role.name
        }
    }

    override suspend fun clearPairing() {
        // Implemented when pairing persistence is added.
    }
}
