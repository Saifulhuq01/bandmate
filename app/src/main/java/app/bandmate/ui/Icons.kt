package app.bandmate.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** BandMate's own line icons, drawn on a 24-unit grid with a 1.8 stroke. Tinted by the caller. */
object Ic {
    private fun line(name: String, vararg paths: String, filled: Set<Int> = emptySet()) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEachIndexed { i, d ->
                if (i in filled) {
                    addPath(addPathNodes(d), fill = SolidColor(Color.White))
                } else {
                    addPath(
                        addPathNodes(d), stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
                        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
                    )
                }
            }
        }.build()

    // Tabs
    val Today = line("today", "M12 3a9 9 0 1 1-9 9", "M12 7.5a4.5 4.5 0 1 1-4.5 4.5", "M12 12h0.01")
    val History = line("history", "M5 20V11", "M10 20V5", "M15 20v-7", "M20 20V9")
    val Band = line("band", "M9 2.5h6", "M9 21.5h6", "M8.5 6h7a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-7a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z", "M10 12h1.2l.9-2 1.6 4 .9-2H15")
    val Apps = line("apps", "M6 9a6 6 0 0 1 12 0c0 6.5 2.5 8 2.5 8h-17S6 15.5 6 9z", "M10 20.5a2.2 2.2 0 0 0 4 0")

    // Data
    val Steps = line(
        "steps",
        "M8.2 3.2c1.8 0 2.8 2 2.6 4.6-.2 2.2-.9 3.7-2.6 3.7S5.6 10 5.5 7.8C5.4 5.2 6.4 3.2 8.2 3.2z",
        "M6 13.4h4.6l-.2 1.6a2.2 2.2 0 0 1-4.2 0z",
        "M15.8 8.2c1.8 0 2.8 2 2.7 4.6-.1 2.2-.9 3.7-2.7 3.7s-2.6-1.5-2.7-3.7c-.1-2.6.9-4.6 2.7-4.6z",
        "M13.6 18.4h4.6l-.2 1.6a2.2 2.2 0 0 1-4.2 0z",
        filled = setOf(0, 1, 2, 3),
    )
    val Moon = line("moon", "M20 14.6A8.2 8.2 0 1 1 9.4 4a6.6 6.6 0 0 0 10.6 10.6z")
    val Heart = line("heart", "M12 20.3s-7.8-4.6-7.8-10.6A4.4 4.4 0 0 1 12 6.9a4.4 4.4 0 0 1 7.8 2.8c0 6-7.8 10.6-7.8 10.6z")
    val HeartFill = line("heartFill", "M12 20.3s-7.8-4.6-7.8-10.6A4.4 4.4 0 0 1 12 6.9a4.4 4.4 0 0 1 7.8 2.8c0 6-7.8 10.6-7.8 10.6z", filled = setOf(0))
    val Flame = line("flame", "M12 21a6 6 0 0 0 6-6c0-4.5-4-6.5-5-11-2.5 2.5-3 5-2.5 7.5C9.5 11 8.5 10 8.3 8.6 6.8 10 6 12.3 6 15a6 6 0 0 0 6 6z")
    val Route = line("route", "M6 20.5a2 2 0 1 0 0-4 2 2 0 0 0 0 4z", "M18 7.5a2 2 0 1 0 0-4 2 2 0 0 0 0 4z", "M8 18.5h7.5a3 3 0 0 0 0-6h-7a3 3 0 0 1 0-6H16")
    val Bolt = line("bolt", "M13.5 2.5 5 13.5h6.2l-1 8 8.5-11h-6.2z")
    val Battery = line("battery", "M3 8.5a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z", "M21.5 10.5v3")
    val Cloud = line("cloud", "M7.5 18.5h9.5a4 4 0 0 0 .6-7.95A5.6 5.6 0 0 0 6.9 11.6a3.5 3.5 0 0 0 .6 6.9z")
    val Sun = line("sun", "M12 16.5a4.5 4.5 0 1 0 0-9 4.5 4.5 0 0 0 0 9z", "M12 2v1.5", "M12 20.5V22", "M2 12h1.5", "M20.5 12H22", "M4.9 4.9l1 1", "M18.1 18.1l1 1", "M4.9 19.1l1-1", "M18.1 5.9l1-1")
    val Rain = line("rain", "M7.5 15h9.5a4 4 0 0 0 .6-7.95A5.6 5.6 0 0 0 6.9 8.1a3.5 3.5 0 0 0 .6 6.9z", "M8.5 18l-1 2.5", "M12.5 18l-1 2.5", "M16.5 18l-1 2.5")
    val Storm = line("storm", "M7.5 15h9.5a4 4 0 0 0 .6-7.95A5.6 5.6 0 0 0 6.9 8.1a3.5 3.5 0 0 0 .6 6.9z", "M12.5 15.5l-2 3.5h3l-2 3.5")
    val Fog = line("fog", "M4 9h16", "M6 13h12", "M4 17h16")

    // Actions
    val Sync = line("sync", "M19.5 10.5A7.7 7.7 0 0 0 5.3 7.7", "M4.5 13.5a7.7 7.7 0 0 0 14.2 2.8", "M5 3.5v4.4h4.4", "M19 20.5v-4.4h-4.4")
    val Alarm = line("alarm", "M12 21a7.5 7.5 0 1 0 0-15 7.5 7.5 0 0 0 0 15z", "M12 10v3.6l2.4 1.6", "M4.5 3.5 2.5 5.5", "M19.5 3.5l2 2")
    val Vibrate = line("vibrate", "M9 4.5h6a1.5 1.5 0 0 1 1.5 1.5v12a1.5 1.5 0 0 1-1.5 1.5H9A1.5 1.5 0 0 1 7.5 18V6A1.5 1.5 0 0 1 9 4.5z", "M4 8.5v7", "M20 8.5v7", "M1.5 10.5v3", "M22.5 10.5v3")
    val Plus = line("plus", "M12 5v14", "M5 12h14")
    val Close = line("close", "M6 6l12 12", "M18 6 6 18")
    val Left = line("left", "M14.5 6 8.5 12l6 6")
    val Right = line("right", "M9.5 6l6 6-6 6")
    val Up = line("up", "M6 14.5l6-6 6 6")
    val Down = line("down", "M6 9.5l6 6 6-6")
    val Search = line("search", "M10.5 17.5a7 7 0 1 0 0-14 7 7 0 0 0 0 14z", "M15.5 15.5 20.5 20.5")
    val Pin = line("pin", "M12 21s-6.5-5.6-6.5-11a6.5 6.5 0 0 1 13 0c0 5.4-6.5 11-6.5 11z", "M12 12.2a2.2 2.2 0 1 0 0-4.4 2.2 2.2 0 0 0 0 4.4z")
    val Phone = line("phone", "M5 4h3.5l1.6 4-2.2 1.4a11 11 0 0 0 6.7 6.7l1.4-2.2 4 1.6V19a1.5 1.5 0 0 1-1.6 1.5A16.5 16.5 0 0 1 3.5 5.6 1.5 1.5 0 0 1 5 4z")
    val Check = line("check", "M5 12.5l4.5 4.5L19 7.5")
}

/** The right weather icon for a WMO code. */
fun weatherIcon(wmo: Int): ImageVector = when (wmo) {
    0, 1 -> Ic.Sun
    2, 3 -> Ic.Cloud
    45, 48 -> Ic.Fog
    95, 96, 99 -> Ic.Storm
    in 51..67, in 80..82 -> Ic.Rain
    else -> Ic.Cloud
}
