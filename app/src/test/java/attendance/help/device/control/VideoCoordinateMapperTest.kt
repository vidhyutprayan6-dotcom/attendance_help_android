package attendance.help.device.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoCoordinateMapperTest {

    @Test
    fun computeRenderedVideoRect_letterboxesVertically() {
        val rect = VideoCoordinateMapper.computeRenderedVideoRect(
            viewWidth = 400,
            viewHeight = 800,
            videoWidth = 1280,
            videoHeight = 720
        )
        assertEquals(400f, rect.width, 0.01f)
        assert(rect.top > 0f)
    }

    @Test
    fun touchToNormalized_insideVideoArea() {
        val rendered = VideoCoordinateMapper.RectF(0f, 100f, 400f, 300f)
        val result = VideoCoordinateMapper.touchToNormalized(200f, 250f, rendered)
        assertEquals(0.5f, result!!.first, 0.01f)
        assertEquals(0.5f, result.second, 0.01f)
    }

    @Test
    fun touchToNormalized_outsideVideoArea_returnsNull() {
        val rendered = VideoCoordinateMapper.RectF(0f, 100f, 400f, 300f)
        assertNull(VideoCoordinateMapper.touchToNormalized(10f, 10f, rendered))
    }

    @Test
    fun normalizedToCapturePixels_mapsCorrectly() {
        val (x, y) = VideoCoordinateMapper.normalizedToCapturePixels(0.5f, 0.25f, 1280, 720)
        assertEquals(640f, x, 0.01f)
        assertEquals(180f, y, 0.01f)
    }
}
