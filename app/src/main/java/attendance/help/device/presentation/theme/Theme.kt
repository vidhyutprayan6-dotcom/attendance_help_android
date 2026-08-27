package attendance.help.device.presentation.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val GreenDeep = Color(0xFF0B3D2E)
private val GoldAccent = Color(0xFFC4A35A)
private val MistBg = Color(0xFFF3F6F4)
private val Ink = Color(0xFF12201A)

private val LightColors = lightColorScheme(
    primary = GreenDeep,
    onPrimary = Color.White,
    secondary = GoldAccent,
    onSecondary = Ink,
    background = MistBg,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink
)

@Composable
fun AttendanceHelpTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        content = content
    )
}
