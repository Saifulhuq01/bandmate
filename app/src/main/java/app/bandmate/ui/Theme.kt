package app.bandmate.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** One palette: near-black surfaces, one accent per kind of data. */
object C {
    val Bg = Color(0xFF0A0B0D)
    val Surface = Color(0xFF131519)
    val Surface2 = Color(0xFF1B1E23)
    val Line = Color(0xFF262A31)
    val Text = Color(0xFFF3F4F6)
    val Muted = Color(0xFF8A9099)
    val Faint = Color(0xFF555B64)

    val Steps = Color(0xFF9BE15D)
    val Sleep = Color(0xFF8F7CFF)
    val Deep = Color(0xFF5B46E0)
    val Light = Color(0xFFB0A4FF)
    val Awake = Color(0xFFF2B45C)
    val Heart = Color(0xFFFF5A6E)
    val Sky = Color(0xFF5CC8FF)
    val Warn = Color(0xFFFF6B6B)
}

/** Type: heavy, tight numbers; quiet labels. */
object T {
    private val tnum = "tnum"
    val Hero = TextStyle(fontSize = 52.sp, fontWeight = FontWeight.Black, letterSpacing = (-2).sp, fontFeatureSettings = tnum, color = C.Text)
    val Big = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Black, letterSpacing = (-1.2).sp, fontFeatureSettings = tnum, color = C.Text)
    val Num = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp, fontFeatureSettings = tnum, color = C.Text)
    val Title = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.8).sp, color = C.Text)
    val Section = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp, color = C.Text)
    val Body = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = C.Text)
    val Label = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = C.Muted)
    val Small = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, color = C.Faint, fontFeatureSettings = tnum)
}

@Composable
fun BandTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.Steps, onPrimary = C.Bg, background = C.Bg, surface = C.Surface,
            surfaceVariant = C.Surface2, onSurface = C.Text, onSurfaceVariant = C.Muted, outline = C.Line,
            surfaceContainerHigh = C.Surface2, surfaceContainerHighest = C.Surface2,
        ),
        content = content,
    )
}
