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
 * Delivers MediaProjection permission results.
 * The system dialog is launched from [MainActivity] (stable foreground Activity).
 */
@Singleton
class ScreenShareCoordinator @Inject constructor() {
    private val _permissionResults = MutableSharedFlow<Intent>(extraBufferCapacity = 1)
    val permissionResults: SharedFlow<Intent> = _permissionResults.asSharedFlow()

    private val _denied = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val denied: SharedFlow<Unit> = _denied.asSharedFlow()

    @Volatile
    private var activityRef: WeakReference<Activity>? = null

    @Volatile
    private var captureLauncher: (() -> Unit)? = null

    fun bindActivity(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    fun unbindActivity(activity: Activity) {
        if (activityRef?.get() === activity) {
            activityRef = null
        }
    }

    fun setCaptureLauncher(launcher: () -> Unit) {
        captureLauncher = launcher
    }

    fun clearCaptureLauncher() {
        captureLauncher = null
    }

    fun requestScreenCapture(): Boolean {
        val launcher = captureLauncher
        if (launcher != null) {
            launcher.invoke()
            return true
        }
        val activity = activityRef?.get() ?: return false
        activity.startActivity(ScreenCapturePermissionActivity.intent(activity))
        return true
    }

    fun emitGranted(resultData: Intent) {
        _permissionResults.tryEmit(Intent(resultData))
    }

    fun emitDenied() {
        _denied.tryEmit(Unit)
    }
}
