package attendance.help.device.control

import kotlin.math.hypot

/**
 * Classifies a completed pointer sequence as TAP or SWIPE using Android-style touch slop.
 * Pure logic — unit-testable without Android framework.
 */
object RemoteGestureClassifier {

    enum class Kind { TAP, SWIPE, CANCEL }

    data class Result(
        val kind: Kind,
        /** Preferred for TAP: original DOWN normalized point. */
        val tapPoint: Pair<Float, Float>?,
        /** Path for SWIPE (normalized). Empty for TAP. */
        val swipePoints: List<Pair<Float, Float>>,
        val maxDisplacementPx: Float,
        val durationMs: Long,
        val touchSlopPx: Float
    )

    /**
     * @param downViewX/Y finger-down in view pixels
     * @param pathNormalized samples collected while pointer was down (may include down)
     * @param upNormalized final UP in normalized video space, or null if outside / cancel
     * @param maxDisplacementPx max distance from DOWN in view pixels during the gesture
     */
    fun classify(
        cancelled: Boolean,
        downNormalized: Pair<Float, Float>?,
        upNormalized: Pair<Float, Float>?,
        pathNormalized: List<Pair<Float, Float>>,
        maxDisplacementPx: Float,
        durationMs: Long,
        touchSlopPx: Float
    ): Result {
        if (cancelled || downNormalized == null) {
            return Result(
                kind = Kind.CANCEL,
                tapPoint = null,
                swipePoints = emptyList(),
                maxDisplacementPx = maxDisplacementPx,
                durationMs = durationMs,
                touchSlopPx = touchSlopPx
            )
        }
        val duration = durationMs.coerceAtLeast(1L)
        if (maxDisplacementPx <= touchSlopPx) {
            return Result(
                kind = Kind.TAP,
                tapPoint = downNormalized,
                swipePoints = emptyList(),
                maxDisplacementPx = maxDisplacementPx,
                durationMs = duration,
                touchSlopPx = touchSlopPx
            )
        }
        val swipe = buildList {
            add(downNormalized)
            pathNormalized.forEach { p ->
                if (lastOrNull() != p) add(p)
            }
            if (upNormalized != null && lastOrNull() != upNormalized) {
                add(upNormalized)
            }
        }
        if (swipe.size < 2) {
            // Degenerate: treat as tap at down.
            return Result(
                kind = Kind.TAP,
                tapPoint = downNormalized,
                swipePoints = emptyList(),
                maxDisplacementPx = maxDisplacementPx,
                durationMs = duration,
                touchSlopPx = touchSlopPx
            )
        }
        return Result(
            kind = Kind.SWIPE,
            tapPoint = null,
            swipePoints = swipe,
            maxDisplacementPx = maxDisplacementPx,
            durationMs = duration,
            touchSlopPx = touchSlopPx
        )
    }

    fun displacementPx(x0: Float, y0: Float, x1: Float, y1: Float): Float =
        hypot((x1 - x0).toDouble(), (y1 - y0).toDouble()).toFloat()
}
