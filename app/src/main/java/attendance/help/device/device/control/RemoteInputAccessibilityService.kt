package attendance.help.device.device.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import timber.log.Timber

/**
 * Injects taps/swipes on the Remote phone so Control can fully operate it.
 * User must enable this service in Android Accessibility settings.
 */
class RemoteInputAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        Timber.i("Remote input accessibility connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    fun tapNormalized(x: Float, y: Float) {
        val dm = resources.displayMetrics
        val px = (x.coerceIn(0f, 1f) * dm.widthPixels)
        val py = (y.coerceIn(0f, 1f) * dm.heightPixels)
        val path = Path().apply { moveTo(px, py) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    fun swipeNormalized(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 200) {
        val dm = resources.displayMetrics
        val path = Path().apply {
            moveTo(x1.coerceIn(0f, 1f) * dm.widthPixels, y1.coerceIn(0f, 1f) * dm.heightPixels)
            lineTo(x2.coerceIn(0f, 1f) * dm.widthPixels, y2.coerceIn(0f, 1f) * dm.heightPixels)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun pressRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    companion object {
        @Volatile
        var instance: RemoteInputAccessibilityService? = null
            private set

        fun isEnabled(): Boolean = instance != null
    }
}
