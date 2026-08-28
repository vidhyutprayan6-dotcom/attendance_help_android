package attendance.help.device.device.control

import android.app.Activity
import android.content.Intent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Delivers MediaProjection permission result and holds the foreground Activity
 * needed to launch the system screen-capture dialog reliably.
 */
@Singleton
class ScreenShareCoordinator @Inject constructor() {
    private val _permissionResults = MutableSharedFlow<Intent>(extraBufferCapacity = 1)
    val permissionResults: SharedFlow<Intent> = _permissionResults.asSharedFlow()

    private val _denied = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val denied: SharedFlow<Unit> = _denied.asSharedFlow()

    @Volatile
    private var activityRef: WeakReference<Activity>? = null

    fun bindActivity(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    fun unbindActivity(activity: Activity) {
        if (activityRef?.get() === activity) {
            activityRef = null
        }
    }

    fun requestScreenCapture(): Boolean {
        val activity = activityRef?.get()
        if (activity == null) {
            return false
        }
        activity.startActivity(ScreenCapturePermissionActivity.intent(activity))
        return true
    }

    fun emitGranted(resultData: Intent) {
        _permissionResults.tryEmit(resultData)
    }

    fun emitDenied() {
        _denied.tryEmit(Unit)
    }
}
