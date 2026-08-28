package attendance.help.device.device.command

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import kotlin.math.min

/**
 * Structured control messages sent over the WebRTC "control" data channel.
 * All coordinates are normalized 0..1 against the captured remote display.
 */
object ControlMessages {
    const val TAP = "tap"
    const val SWIPE = "swipe"
    const val LONG_PRESS = "long_press"
    const val GLOBAL_ACTION = "global_action"
    const val SET_TEXT = "set_text"
    const val CAPTURE_GEOMETRY = "capture_geometry"

    private val gson = Gson()

    data class Point(@SerializedName("x") val x: Float, @SerializedName("y") val y: Float)

    data class TapMessage(
        @SerializedName("type") val type: String = TAP,
        @SerializedName("x") val x: Float,
        @SerializedName("y") val y: Float
    )

    data class SwipeMessage(
        @SerializedName("type") val type: String = SWIPE,
        @SerializedName("points") val points: List<Point>,
        @SerializedName("durationMs") val durationMs: Long
    )

    data class LongPressMessage(
        @SerializedName("type") val type: String = LONG_PRESS,
        @SerializedName("x") val x: Float,
        @SerializedName("y") val y: Float,
        @SerializedName("durationMs") val durationMs: Long
    )

    data class GlobalActionMessage(
        @SerializedName("type") val type: String = GLOBAL_ACTION,
        @SerializedName("action") val action: String
    )

    data class SetTextMessage(
        @SerializedName("type") val type: String = SET_TEXT,
        @SerializedName("text") val text: String
    )

    data class CaptureGeometryMessage(
        @SerializedName("type") val type: String = CAPTURE_GEOMETRY,
        @SerializedName("sessionId") val sessionId: String,
        @SerializedName("captureWidth") val captureWidth: Int,
        @SerializedName("captureHeight") val captureHeight: Int,
        @SerializedName("rotation") val rotation: Int = 0
    )

    fun encodeTap(x: Float, y: Float): String = gson.toJson(TapMessage(x = x, y = y))
    fun encodeSwipe(points: List<Point>, durationMs: Long): String =
        gson.toJson(SwipeMessage(points = points, durationMs = durationMs.coerceIn(50L, 5_000L)))

    fun encodeLongPress(x: Float, y: Float, durationMs: Long): String =
        gson.toJson(LongPressMessage(x = x, y = y, durationMs = durationMs.coerceIn(200L, 3_000L)))

    fun encodeGlobalAction(action: String): String = gson.toJson(GlobalActionMessage(action = action))
    fun encodeSetText(text: String): String =
        gson.toJson(SetTextMessage(text = text.take(512)))

    fun encodeCaptureGeometry(
        sessionId: String,
        captureWidth: Int,
        captureHeight: Int,
        rotation: Int
    ): String = gson.toJson(
        CaptureGeometryMessage(
            sessionId = sessionId,
            captureWidth = captureWidth,
            captureHeight = captureHeight,
            rotation = rotation
        )
    )

    fun parseType(payload: String): String? = runCatching {
        gson.fromJson(payload, JsonObject::class.java).get("type")?.asString
    }.getOrNull()

    fun isValidNormalized(value: Float): Boolean = !value.isNaN() && value in 0f..1f

    /** Downsample a path to at most [maxPoints] entries. */
    fun downsample(points: List<Point>, maxPoints: Int = 32): List<Point> {
        if (points.size <= maxPoints) return points
        val step = points.size.toFloat() / maxPoints
        return (0 until maxPoints).map { i -> points[min((i * step).toInt(), points.lastIndex)] }
    }
}
