package attendance.help.device.device

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.deviceIdDataStore by preferencesDataStore(name = "device_identity")

@Singleton
class DefaultDeviceIdentityProvider @Inject constructor(
    @ApplicationContext private val context: Context
) : DeviceIdentityProvider {

    private val key = stringPreferencesKey("device_id")

    /**
     * Blocking read is intentional for rare first-launch identity bootstrap.
     * Later steps can migrate callers to a suspend API if needed.
     */
    override fun getOrCreateDeviceId(): String = runBlocking {
        val existing = context.deviceIdDataStore.data.first()[key]
        if (existing != null) return@runBlocking existing

        val created = "AH-" + UUID.randomUUID().toString().replace("-", "").take(12).uppercase()
        context.deviceIdDataStore.edit { it[key] = created }
        created
    }
}
