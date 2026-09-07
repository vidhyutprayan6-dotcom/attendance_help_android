package attendance.help.device.device.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import timber.log.Timber

/**
 * Injects taps/swipes on the Remote phone so Control can fully operate it.
 *
 * Tap strategy (Home launchers + tiny UI):
 * 1) [ACTION_CLICK] only on **small** clickable nodes under the point (icon / button sized).
 *    Candidates are tried smallest-first. Large workspace nodes are ignored — they often
 *    return true for ACTION_CLICK but do nothing, which previously blocked gesture fallback.
 * 2) If none succeed → stationary [dispatchGesture] tap at the mapped display pixel.
 */
class RemoteInputAccessibilityService : AccessibilityService() {

    @Volatile
    var captureWidth: Int = 0

    @Volatile
    var captureHeight: Int = 0

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        instance = this
        connected = true
        serviceInfo = serviceInfo?.apply {
            flags = flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
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
        val (px, py) = toDisplayPixels(x, y)
        val duration = tapDurationMs()
        val (dw, dh) = displaySize()
        Timber.tag(TAP_TAG).i(
            "REMOTE_TAP_START norm_x=%.4f norm_y=%.4f remote_x=%.1f remote_y=%.1f " +
                "duration=%d display=%dx%d capture=%dx%d",
            x,
            y,
            px,
            py,
            duration,
            dw,
            dh,
            captureWidth,
            captureHeight
        )

        if (clickSmallTargetsAt(px, py)) {
            Timber.tag(TAP_TAG).i(
                "REMOTE_TAP_COMPLETED remote_x=%.1f remote_y=%.1f via=node_click",
                px,
                py
            )
            return true
        }

        // Stationary press — a 1px drag can be treated as a micro-swipe by some launchers.
        val path = Path().apply { moveTo(px, py) }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        return dispatchLoggedGesture(stroke, isTap = true, px = px, py = py)
    }

    fun longPressNormalized(x: Float, y: Float, durationMs: Long): Boolean {
        val (px, py) = toDisplayPixels(x, y)
        val path = Path().apply { moveTo(px, py) }
        val stroke = GestureDescription.StrokeDescription(
            path,
            0,
            durationMs.coerceIn(200L, 3_000L)
        )
        return dispatchLoggedGesture(stroke, isTap = false, px = px, py = py)
    }

    fun swipeNormalizedPoints(points: List<Pair<Float, Float>>, durationMs: Long): Boolean {
        if (points.size < 2) return false
        val path = Path()
        val first = toDisplayPixels(points.first().first, points.first().second)
        path.moveTo(first.first, first.second)
        points.drop(1).forEach { (nx, ny) ->
            val p = toDisplayPixels(nx, ny)
            path.lineTo(p.first, p.second)
        }
        val stroke = GestureDescription.StrokeDescription(
            path,
            0,
            durationMs.coerceIn(50L, 5_000L)
        )
        return dispatchLoggedGesture(stroke, isTap = false, px = first.first, py = first.second)
    }

    fun swipeNormalized(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 200): Boolean =
        swipeNormalizedPoints(listOf(x1 to y1, x2 to y2), durationMs)

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun pressRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    fun setTextOnFocusedField(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
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

    /**
     * Click icon/button-sized targets under [x],[y]. Large containers are skipped so a
     * false ACTION_CLICK success cannot block the gesture fallback.
     */
    private fun clickSmallTargetsAt(x: Float, y: Float): Boolean {
        val root = rootForPoint(x, y) ?: run {
            Timber.tag(TAP_TAG).i("REMOTE_TAP_NODE_CLICK miss reason=no_root x=%.0f y=%.0f", x, y)
            return false
        }
        val candidates = findSmallClickableCandidates(root, x, y)
        if (candidates.isEmpty()) {
            Timber.tag(TAP_TAG).i("REMOTE_TAP_NODE_CLICK miss reason=no_small_node x=%.0f y=%.0f", x, y)
            runCatching { root.recycle() }
            return false
        }

        var clicked = false
        candidates.forEachIndexed { index, node ->
            if (clicked) {
                node.recycle()
                return@forEachIndexed
            }
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            var ok = node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (!ok && node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            Timber.tag(TAP_TAG).i(
                "REMOTE_TAP_NODE_CLICK x=%.0f y=%.0f try=%d ok=%s bounds=%s cls=%s desc=%s",
                x,
                y,
                index,
                ok,
                bounds.toShortString(),
                node.className,
                node.contentDescription ?: node.text
            )
            node.recycle()
            if (ok) clicked = true
        }
        runCatching { root.recycle() }
        return clicked
    }

    private fun rootForPoint(x: Float, y: Float): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val wins = windows
            if (!wins.isNullOrEmpty()) {
                val ordered = wins.sortedByDescending { it.layer }
                for (w in ordered) {
                    if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION &&
                        w.type != AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER &&
                        w.type != AccessibilityWindowInfo.TYPE_SYSTEM
                    ) {
                        continue
                    }
                    val wb = Rect()
                    w.getBoundsInScreen(wb)
                    if (wb.contains(x.toInt(), y.toInt())) {
                        val root = w.root
                        if (root != null) return root
                    }
                }
            }
        }
        return rootInActiveWindow
    }

    /**
     * All small clickable nodes containing the point, sorted by area ascending
     * (tightest icon/button first).
     */
    private fun findSmallClickableCandidates(
        root: AccessibilityNodeInfo,
        x: Float,
        y: Float
    ): List<AccessibilityNodeInfo> {
        val density = resources.displayMetrics.density
        // Typical launcher icon cell ≈ 80–120dp wide, taller with label.
        val maxW = (140f * density).toInt().coerceAtLeast(96)
        val maxH = (180f * density).toInt().coerceAtLeast(96)
        val maxArea = maxW * maxH
        val ix = x.toInt()
        val iy = y.toInt()

        data class Cand(val node: AccessibilityNodeInfo, val area: Int, val score: Int)

        val found = ArrayList<Cand>()

        fun score(node: AccessibilityNodeInfo, area: Int): Int {
            var s = 1_000_000 - area
            if (node.contentDescription?.isNotBlank() == true || node.text?.isNotBlank() == true) {
                s += 50_000
            }
            if (node.isClickable) s += 20_000
            return s
        }

        fun consider(node: AccessibilityNodeInfo) {
            if (!node.isEnabled) return
            if (!node.isClickable && !node.isLongClickable) return
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (!bounds.contains(ix, iy)) return
            if (bounds.width() > maxW || bounds.height() > maxH) return
            val area = bounds.width().coerceAtLeast(1) * bounds.height().coerceAtLeast(1)
            if (area > maxArea) return
            found.add(Cand(AccessibilityNodeInfo.obtain(node), area, score(node, area)))
        }

        fun visit(node: AccessibilityNodeInfo) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (!bounds.contains(ix, iy)) return
            consider(node)
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    visit(child)
                    child.recycle()
                }
            }
        }

        visit(root)

        // Deduplicate by bounds+class, keep highest score per area bucket.
        found.sortWith(compareBy<Cand> { it.area }.thenByDescending { it.score })
        return found.map { it.node }
    }

    private fun dispatchLoggedGesture(
        stroke: GestureDescription.StrokeDescription,
        isTap: Boolean,
        px: Float,
        py: Float
    ): Boolean {
        val ok = dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (isTap) {
                        Timber.tag(TAP_TAG).i(
                            "REMOTE_TAP_COMPLETED remote_x=%.1f remote_y=%.1f via=gesture",
                            px,
                            py
                        )
                    }
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (isTap) {
                        Timber.tag(TAP_TAG).w(
                            "REMOTE_TAP_FAILED remote_x=%.1f remote_y=%.1f reason=callback_cancelled",
                            px,
                            py
                        )
                    }
                }
            },
            mainHandler
        )
        if (!ok && isTap) {
            Timber.tag(TAP_TAG).w(
                "REMOTE_TAP_FAILED remote_x=%.1f remote_y=%.1f reason=dispatch_rejected",
                px,
                py
            )
        }
        return ok
    }

    private fun tapDurationMs(): Long {
        val platform = runCatching {
            ViewConfiguration.getTapTimeout().toLong()
        }.getOrDefault(100L)
        return platform.coerceIn(90L, 120L)
    }

    private fun toDisplayPixels(normalizedX: Float, normalizedY: Float): Pair<Float, Float> {
        val (w, h) = displaySize()
        val x = (normalizedX.coerceIn(0f, 1f) * (w - 1).coerceAtLeast(1))
            .coerceIn(1f, (w - 2).toFloat().coerceAtLeast(1f))
        val y = (normalizedY.coerceIn(0f, 1f) * (h - 1).coerceAtLeast(1))
            .coerceIn(1f, (h - 2).toFloat().coerceAtLeast(1f))
        return x to y
    }

    private fun displaySize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = getSystemService(WindowManager::class.java)
                ?.currentWindowMetrics
                ?.bounds
            if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
                return bounds.width() to bounds.height()
            }
        }
        val dm = resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    companion object {
        private const val TAP_TAG = "REMOTE_TAP"

        @Volatile
        var instance: RemoteInputAccessibilityService? = null
            private set

        @Volatile
        var connected: Boolean = false
            private set

        fun isEnabled(): Boolean = connected && instance != null
    }
}
