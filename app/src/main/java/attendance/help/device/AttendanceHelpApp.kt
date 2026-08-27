package attendance.help.device

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

/**
 * Application entry point.
 *
 * Product rule (display — confirmed):
 * - Both phones start cameras together when the Controller opens the camera session.
 * - Controller screen shows the Controller's own live camera (local preview).
 * - Remote screen shows the Controller's live camera stream.
 * - Login / access restrictions are deferred; v1 focuses on unrestricted peer control.
 */
@HiltAndroidApp
class AttendanceHelpApp : Application() {

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        Timber.i("Attendance Help started (version %s)", BuildConfig.VERSION_NAME)
    }
}
