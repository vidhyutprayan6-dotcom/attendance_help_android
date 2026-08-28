package attendance.help.device.device.command

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlMessagesTest {

    @Test
    fun isValidNormalized_acceptsZeroToOne() {
        assertTrue(ControlMessages.isValidNormalized(0f))
        assertTrue(ControlMessages.isValidNormalized(1f))
        assertTrue(ControlMessages.isValidNormalized(0.5f))
    }

    @Test
    fun isValidNormalized_rejectsOutOfRange() {
        assertFalse(ControlMessages.isValidNormalized(-0.1f))
        assertFalse(ControlMessages.isValidNormalized(1.1f))
        assertFalse(ControlMessages.isValidNormalized(Float.NaN))
    }

    @Test
    fun downsample_reducesLargePaths() {
        val points = (0..100).map { ControlMessages.Point(it / 100f, 0.5f) }
        val sampled = ControlMessages.downsample(points, 10)
        assertTrue(sampled.size <= 10)
    }
}
