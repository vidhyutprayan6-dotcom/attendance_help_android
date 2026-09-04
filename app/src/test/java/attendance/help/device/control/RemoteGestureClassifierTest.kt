package attendance.help.device.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteGestureClassifierTest {

    private val slop = 16f

    @Test
    fun zeroMovement_isTap() {
        val down = 0.5f to 0.5f
        val result = RemoteGestureClassifier.classify(
            cancelled = false,
            downNormalized = down,
            upNormalized = down,
            pathNormalized = listOf(down),
            maxDisplacementPx = 0f,
            durationMs = 80L,
            touchSlopPx = slop
        )
        assertEquals(RemoteGestureClassifier.Kind.TAP, result.kind)
        assertEquals(down, result.tapPoint)
        assertTrue(result.swipePoints.isEmpty())
    }

    @Test
    fun jitterBelowSlop_isTap() {
        val down = 0.4f to 0.6f
        val result = RemoteGestureClassifier.classify(
            cancelled = false,
            downNormalized = down,
            upNormalized = 0.41f to 0.61f,
            pathNormalized = listOf(down, 0.405f to 0.605f),
            maxDisplacementPx = slop - 1f,
            durationMs = 100L,
            touchSlopPx = slop
        )
        assertEquals(RemoteGestureClassifier.Kind.TAP, result.kind)
        assertEquals(down, result.tapPoint)
    }

    @Test
    fun movementAboveSlop_isSwipe() {
        val down = 0.2f to 0.5f
        val mid = 0.5f to 0.5f
        val up = 0.8f to 0.5f
        val result = RemoteGestureClassifier.classify(
            cancelled = false,
            downNormalized = down,
            upNormalized = up,
            pathNormalized = listOf(down, mid),
            maxDisplacementPx = slop + 20f,
            durationMs = 250L,
            touchSlopPx = slop
        )
        assertEquals(RemoteGestureClassifier.Kind.SWIPE, result.kind)
        assertNull(result.tapPoint)
        assertEquals(3, result.swipePoints.size)
        assertEquals(down, result.swipePoints.first())
        assertEquals(up, result.swipePoints.last())
    }

    @Test
    fun cancelled_emitsNothing() {
        val result = RemoteGestureClassifier.classify(
            cancelled = true,
            downNormalized = 0.5f to 0.5f,
            upNormalized = 0.5f to 0.5f,
            pathNormalized = listOf(0.5f to 0.5f),
            maxDisplacementPx = 0f,
            durationMs = 50L,
            touchSlopPx = slop
        )
        assertEquals(RemoteGestureClassifier.Kind.CANCEL, result.kind)
    }

    @Test
    fun swipeDoesNotAlsoEmitTap() {
        val result = RemoteGestureClassifier.classify(
            cancelled = false,
            downNormalized = 0.1f to 0.1f,
            upNormalized = 0.9f to 0.1f,
            pathNormalized = listOf(0.1f to 0.1f, 0.5f to 0.1f),
            maxDisplacementPx = 100f,
            durationMs = 300L,
            touchSlopPx = slop
        )
        assertEquals(RemoteGestureClassifier.Kind.SWIPE, result.kind)
        assertNull(result.tapPoint)
    }
}
