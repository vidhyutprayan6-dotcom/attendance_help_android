package attendance.help.device.device.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import timber.log.Timber

/**
 * Injects taps/swipes on the Remote phone so Control can fully operate it.
 * User must enable this service manually in Android Accessibility settings.
 */
class RemoteInputAccessibilityService : AccessibilityService() {

    @Volatile
    var captureWidth: Int = 0

    @Volatile
    var captureHeight: Int = 0

    override fun onServiceConnected() {
        instance = this
        connected = true
        Timber.tag("REMOTE_CONTROL").i("Accessibility service connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) {
            instance = null
            connected = false
        }
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    fun updateCaptureGeometry(width: Int, height: Int) {
        captureWidth = width.coerceAtLeast(1)
        captureHeight = height.coerceAtLeast(1)
    }

    fun tapNormalized(x: Float, y: Float): Boolean {
        val (px, py) = toCapturePixels(x, y)
        val path = Path().apply { moveTo(px, py) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        return dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            null,
            null
        )
    }

    fun longPressNormalized(x: Float, y: Float, durationMs: Long): Boolean {
        val (px, py) = toCapturePixels(x, y)
        val path = Path().apply { moveTo(px, py) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(200L, 3_000L))
        return dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            null,
            null
        )
    }

    fun swipeNormalizedPoints(points: List<Pair<Float, Float>>, durationMs: Long): Boolean {
        if (points.size < 2) return false
        val path = Path()
        val first = toCapturePixels(points.first().first, points.first().second)
        path.moveTo(first.first, first.second)
        points.drop(1).forEach { (nx, ny) ->
            val p = toCapturePixels(nx, ny)
            path.lineTo(p.first, p.second)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(50L, 5_000L))
        return dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            null,
            null
        )
    }

    fun swipeNormalized(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 200): Boolean =
        swipeNormalizedPoints(listOf(x1 to y1, x2 to y2), durationMs)

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun pressRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    fun setTextOnFocusedField(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        if (focused == null || !focused.isEditable) {
            focused?.recycle()
            root.recycle()
            return false
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text.take(512))
        }
        val ok = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        focused.recycle()
        root.recycle()
        return ok
    }

    private fun toCapturePixels(normalizedX: Float, normalizedY: Float): Pair<Float, Float> {
        val w = captureWidth.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val h = captureHeight.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        return (normalizedX.coerceIn(0f, 1f) * w) to (normalizedY.coerceIn(0f, 1f) * h)
    }

    companion object {
        @Volatile
        var instance: RemoteInputAccessibilityService? = null
            private set

        @Volatile
        var connected: Boolean = false
            private set

        fun isEnabled(): Boolean = connected && instance != null
    }
}
