package attendance.help.device

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

/**
 * Application entry point.
 *
 * Product rules:
 * 1. Connect / disconnect from the hub server.
 * 2. Mode: Remote / Control / Nothing.
 * 3. When bound: Control fully operates Remote (screen + input).
 * 4. When cameras on: both cameras on; Control video shown on both phones' camera feeds.
 * 5. Control can bind / unbind a Remote; busy Remote is exclusive to one Control.
 */
@HiltAndroidApp
class AttendanceHelpApp : Application() {

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            Timber.e(error, "Uncaught on %s", thread.name)
            defaultHandler?.uncaughtException(thread, error)
        }
        Timber.i("Attendance Help started (version %s)", BuildConfig.VERSION_NAME)
    }
}
