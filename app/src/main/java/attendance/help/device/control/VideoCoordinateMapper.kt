package attendance.help.device.control

/**
 * Maps touch coordinates from a letterboxed video view to normalized remote-screen space (0..1).
 */
object VideoCoordinateMapper {

    data class RectF(val left: Float, val top: Float, val width: Float, val height: Float) {
        fun contains(x: Float, y: Float): Boolean =
            x >= left && x <= left + width && y >= top && y <= top + height
    }

    /** Aspect-fit rectangle of video content inside the view. */
    fun computeRenderedVideoRect(
        viewWidth: Int,
        viewHeight: Int,
        videoWidth: Int,
        videoHeight: Int
    ): RectF {
        if (viewWidth <= 0 || viewHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) {
            return RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        }
        val viewAspect = viewWidth.toFloat() / viewHeight
        val videoAspect = videoWidth.toFloat() / videoHeight
        return if (videoAspect > viewAspect) {
            val renderedHeight = viewWidth / videoAspect
            val top = (viewHeight - renderedHeight) / 2f
            RectF(0f, top, viewWidth.toFloat(), renderedHeight)
        } else {
            val renderedWidth = viewHeight * videoAspect
            val left = (viewWidth - renderedWidth) / 2f
            RectF(left, 0f, renderedWidth, viewHeight.toFloat())
        }
    }

    /** Returns normalized (x,y) in 0..1 or null if outside the rendered video area. */
    fun touchToNormalized(
        touchX: Float,
        touchY: Float,
        rendered: RectF
    ): Pair<Float, Float>? {
        if (!rendered.contains(touchX, touchY) || rendered.width <= 0f || rendered.height <= 0f) {
            return null
        }
        val nx = ((touchX - rendered.left) / rendered.width).coerceIn(0f, 1f)
        val ny = ((touchY - rendered.top) / rendered.height).coerceIn(0f, 1f)
        if (nx.isNaN() || ny.isNaN()) return null
        return nx to ny
    }

    /** Convert normalized coords to pixel coords on the captured display. */
    fun normalizedToCapturePixels(
        normalizedX: Float,
        normalizedY: Float,
        captureWidth: Int,
        captureHeight: Int
    ): Pair<Float, Float> {
        val w = captureWidth.coerceAtLeast(1)
        val h = captureHeight.coerceAtLeast(1)
        return (normalizedX.coerceIn(0f, 1f) * w) to (normalizedY.coerceIn(0f, 1f) * h)
    }
}
