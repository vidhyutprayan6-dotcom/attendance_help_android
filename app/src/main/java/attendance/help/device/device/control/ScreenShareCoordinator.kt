package attendance.help.device.device.control

import android.content.Intent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Delivers MediaProjection permission result from the permission Activity
 * to [attendance.help.device.webrtc.SessionController].
 */
@Singleton
class ScreenShareCoordinator @Inject constructor() {
    private val _permissionResults = MutableSharedFlow<Intent>(extraBufferCapacity = 1)
    val permissionResults: SharedFlow<Intent> = _permissionResults.asSharedFlow()

    private val _denied = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val denied: SharedFlow<Unit> = _denied.asSharedFlow()

    fun emitGranted(resultData: Intent) {
        _permissionResults.tryEmit(resultData)
    }

    fun emitDenied() {
        _denied.tryEmit(Unit)
    }
}
