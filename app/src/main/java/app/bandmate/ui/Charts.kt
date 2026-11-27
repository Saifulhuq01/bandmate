package app.bandmate.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.bandmate.Night
import app.bandmate.Stage

/** Progress ring that sweeps to [progress] (0..1, may exceed 1 for a second lap). */
@Composable
fun Ring(progress: Float, color: Color, size: Dp, stroke: Dp = 14.dp, content: @Composable () -> Unit) {
    val p by animateFloatAsState(progress, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessVeryLow), label = "ring")
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val sw = stroke.toPx()
            val arc = Size(this.size.width - sw, this.size.height - sw)
            val tl = Offset(sw / 2, sw / 2)
            drawArc(C.Surface2, 0f, 360f, false, tl, arc, style = Stroke(sw))
            val first = p.coerceIn(0f, 1f)
            if (first > 0f) drawArc(color, -90f, 360f * first, false, tl, arc, style = Stroke(sw, cap = StrokeCap.Round))
            val second = (p - 1f).coerceIn(0f, 1f)
            if (second > 0f) drawArc(Color.White.copy(alpha = 0.55f), -90f, 360f * second, false, tl, arc, style = Stroke(sw * 0.45f, cap = StrokeCap.Round))
        }
        content()
    }
}

/**
 * Bars that grow in whenever [values] change. Tap a bar to select it.
 * [labels] are drawn under some bars (null = no label).
 */
@Composable
fun BarChart(
    values: List<Int>,
    color: Color,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    labels: List<String?> = emptyList(),
    goal: Int? = null,
    height: Dp = 170.dp,
) {
    val grow = remember { Animatable(0f) }
    LaunchedEffect(values) {
        grow.snapTo(0f)
        grow.animateTo(1f, tween(650, easing = FastOutSlowInEasing))
    }
    val max = maxOf(values.maxOrNull() ?: 0, goal ?: 0, 1)
    Column(modifier) {
        Canvas(
            Modifier.fillMaxWidth().height(height).pointerInput(values) {
                detectTapGestures { pos ->
                    val slot = size.width / values.size.coerceAtLeast(1)
                    val i = (pos.x / slot).toInt().coerceIn(0, values.size - 1)
                    onSelect(if (i == selected) null else i)
                }
            }
        ) {
            val n = values.size.coerceAtLeast(1)
            val slot = size.width / n
            val bw = (slot * 0.62f).coerceAtMost(28.dp.toPx())
            if (goal != null && goal > 0) {
                val y = size.height * (1 - goal.toFloat() / max)
                var x = 0f
                while (x < size.width) { drawLine(C.Line, Offset(x, y), Offset(x + 6.dp.toPx(), y), 1.5.dp.toPx()); x += 11.dp.toPx() }
            }
            values.forEachIndexed { i, v ->
                // stagger: later bars start a little later
                val t = ((grow.value * 1.35f) - i * (0.35f / n)).coerceIn(0f, 1f)
                val h = (size.height * v / max * t).coerceAtLeast(if (v > 0) 3.dp.toPx() else 2.dp.toPx())
                val c = when {
                    v == 0 -> C.Surface2
                    selected == null || selected == i -> color
                    else -> color.copy(alpha = 0.32f)
                }
                drawRoundRect(c, Offset(i * slot + (slot - bw) / 2, size.height - h), Size(bw, h), CornerRadius(bw / 2.6f))
            }
        }
        if (labels.isNotEmpty()) {
            Row(Modifier.fillMaxWidth()) {
                labels.forEach { l ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { if (l != null) Text(l, style = T.Small) }
                }
            }
        }
    }
}

/** Sleep stages over the night: deep at the bottom, light in the middle, awake on top. */
@Composable
fun Hypnogram(night: Night, modifier: Modifier = Modifier, height: Dp = 150.dp) {
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(night) { reveal.snapTo(0f); reveal.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    Canvas(modifier.fillMaxWidth().height(height)) {
        val total = (night.endMinute - night.startMinute).toFloat().coerceAtLeast(1f)
        val rowH = size.height / 3
        for (r in 0..2) drawRoundRect(C.Surface2.copy(alpha = 0.5f), Offset(0f, r * rowH + rowH * 0.15f), Size(size.width, rowH * 0.7f), CornerRadius(8.dp.toPx()))
        val visibleEnd = night.startMinute + total * reveal.value
        night.segments.forEach { s ->
            if (s.startMinute >= visibleEnd) return@forEach
            val (row, color) = when (s.stage) { Stage.AWAKE -> 0 to C.Awake; Stage.LIGHT -> 1 to C.Light; Stage.DEEP -> 2 to C.Deep }
            val x0 = (s.startMinute - night.startMinute) / total * size.width
            val x1 = (minOf(s.endMinute.toFloat(), visibleEnd) - night.startMinute) / total * size.width
            drawRoundRect(color, Offset(x0, row * rowH + rowH * 0.15f), Size((x1 - x0).coerceAtLeast(2f), rowH * 0.7f), CornerRadius(5.dp.toPx()))
        }
    }
}

/** A thin single-line view of a night, used on the Today card. */
@Composable
fun SleepStrip(night: Night, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(12.dp)) {
        val total = (night.endMinute - night.startMinute).toFloat().coerceAtLeast(1f)
        drawRoundRect(C.Surface2, Offset.Zero, size, CornerRadius(6.dp.toPx()))
        night.segments.forEach { s ->
            val c = when (s.stage) { Stage.AWAKE -> C.Awake; Stage.LIGHT -> C.Light; Stage.DEEP -> C.Deep }
            val x0 = (s.startMinute - night.startMinute) / total * size.width
            val w = s.length / total * size.width
            drawRect(c, Offset(x0, 0f), Size(w.coerceAtLeast(1f), size.height))
        }
    }
}

/** Stacked deep/light bars, one per night. Null = no data that night. */
@Composable
fun SleepBars(nights: List<Night?>, labels: List<String?>, selected: Int?, onSelect: (Int?) -> Unit, modifier: Modifier = Modifier) {
    val grow = remember { Animatable(0f) }
    LaunchedEffect(nights) { grow.snapTo(0f); grow.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
    val max = maxOf(nights.maxOfOrNull { it?.asleep ?: 0 } ?: 0, 8 * 60)
    Column(modifier) {
        Canvas(
            Modifier.fillMaxWidth().height(150.dp).pointerInput(nights) {
                detectTapGestures { pos ->
                    val i = (pos.x / (size.width / nights.size.coerceAtLeast(1))).toInt().coerceIn(0, nights.size - 1)
                    onSelect(if (i == selected) null else i)
                }
            }
        ) {
            val slot = size.width / nights.size.coerceAtLeast(1)
            val bw = (slot * 0.55f).coerceAtMost(26.dp.toPx())
            nights.forEachIndexed { i, n ->
                val x = i * slot + (slot - bw) / 2
                val fade = if (selected == null || selected == i) 1f else 0.35f
                if (n == null) {
                    drawRoundRect(C.Surface2, Offset(x, size.height - 3.dp.toPx()), Size(bw, 3.dp.toPx()), CornerRadius(2f))
                    return@forEachIndexed
                }
                val deepH = size.height * n.deep / max * grow.value
                val lightH = size.height * n.light / max * grow.value
                drawRoundRect(C.Light.copy(alpha = fade), Offset(x, size.height - deepH - lightH), Size(bw, lightH + deepH), CornerRadius(bw / 2.6f))
                drawRoundRect(C.Deep.copy(alpha = fade), Offset(x, size.height - deepH), Size(bw, deepH), CornerRadius(bw / 2.6f))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            labels.forEach { l -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { if (l != null) Text(l, style = T.Small) } }
        }
    }
}

/** Heart rate over a day: readings averaged into 15-minute blocks and drawn as a smooth line. */
@Composable
fun HeartLine(points: List<Pair<Float, Int>>, modifier: Modifier = Modifier, height: Dp = 90.dp) {
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(points) { reveal.snapTo(0f); reveal.animateTo(1f, tween(900)) }
    val bins = 96
    val avg = remember(points) {
        val sum = IntArray(bins); val n = IntArray(bins)
        points.forEach { (x, v) ->
            if (v in 30..220) { val b = (x * bins).toInt().coerceIn(0, bins - 1); sum[b] += v; n[b]++ }
        }
        List(bins) { if (n[it] > 0) sum[it].toFloat() / n[it] else null }
    }
    val present = avg.filterNotNull()
    if (present.size < 3) return // not enough readings yet
    val mid = (present.min() + present.max()) / 2f
    val half = maxOf(25f, (present.max() - present.min()) / 2f + 5)
    Canvas(modifier.fillMaxWidth().height(height)) {
        fun pt(i: Int, v: Float) = Offset((i + 0.5f) / bins * size.width, size.height * (1 - (v - (mid - half)) / (2 * half)))
        var prev: Pair<Int, Float>? = null
        avg.forEachIndexed { i, v ->
            if (v == null || (i + 0.5f) / bins > reveal.value) return@forEachIndexed
            val p = prev
            if (p != null && i - p.first <= 2) drawLine(C.Heart, pt(p.first, p.second), pt(i, v), 2.5.dp.toPx(), StrokeCap.Round)
            else drawCircle(C.Heart, 2.dp.toPx(), pt(i, v))
            prev = i to v
        }
    }
}
