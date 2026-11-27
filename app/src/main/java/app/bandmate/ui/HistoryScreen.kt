package app.bandmate.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.bandmate.Band
import app.bandmate.Night
import app.bandmate.Saved
import app.bandmate.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

enum class HistoryKind { STEPS, SLEEP }
private enum class Span { DAY, WEEK, MONTH }

@Composable
fun HistoryScreen(kind: HistoryKind, onKind: (HistoryKind) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("History", style = T.Title, modifier = Modifier.padding(top = 8.dp))
        Segmented(
            listOf(HistoryKind.STEPS to "Steps", HistoryKind.SLEEP to "Sleep"), kind,
            accent = if (kind == HistoryKind.STEPS) C.Steps else C.Sleep,
        ) { onKind(it) }
        AnimatedContent(kind, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(150)) }, label = "hist") { k ->
            if (k == HistoryKind.STEPS) StepsHistory() else SleepHistory()
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun StepsHistory() {
    val ctx = LocalContext.current
    val version by Band.dataVersion.collectAsStateWithLifecycle()
    var span by rememberSaveable { mutableStateOf(Span.WEEK) }
    var anchor by remember { mutableStateOf(LocalDate.now()) }
    var selected by remember(span, anchor) { mutableStateOf<Int?>(null) }
    val goal = Saved.settings(ctx).stepGoal

    val first = when (span) {
        Span.DAY -> anchor
        Span.WEEK -> anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        Span.MONTH -> anchor.withDayOfMonth(1)
    }
    val count = when (span) { Span.DAY -> 24; Span.WEEK -> 7; Span.MONTH -> first.lengthOfMonth() }
    val values by produceState(List(count) { 0 }, span, first, version) {
        value = withContext(Dispatchers.IO) {
            val s = Store.get(ctx)
            if (span == Span.DAY) s.stepsByHour(first).toList() else s.stepsByDay(first, count).toList()
        }
    }
    val labels: List<String?> = when (span) {
        Span.DAY -> List(24) { if (it % 6 == 0) "%02d".format(it) else null }
        Span.WEEK -> List(7) { first.plusDays(it.toLong()).dayOfWeek.name.take(1) }
        Span.MONTH -> List(count) { if (it == 0 || (it + 1) % 7 == 0) "${it + 1}" else null }
    }
    val total = values.sum()
    val activeDays = values.count { it > 0 }.coerceAtLeast(1)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Segmented(listOf(Span.DAY to "Day", Span.WEEK to "Week", Span.MONTH to "Month"), span, accent = C.Steps) { span = it }
        Card(Modifier.fillMaxWidth()) {
            Navigator(title(span, first), canNext = !next(span, anchor).isAfter(LocalDate.now()),
                onPrev = { anchor = prev(span, anchor) }, onNext = { anchor = next(span, anchor) })
            val sel = selected
            Row(verticalAlignment = Alignment.Bottom) {
                val big = if (sel != null) values.getOrElse(sel) { 0 } else if (span == Span.DAY) total else total / activeDays
                Text("%,d".format(big), style = T.Big)
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        sel != null && span == Span.DAY -> "steps at %02d:00".format(sel)
                        sel != null -> "steps on " + first.plusDays(sel.toLong()).format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
                        span == Span.DAY -> "steps"
                        else -> "steps a day on average"
                    },
                    style = T.Label, modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            BarChart(values, C.Steps, selected, { selected = it }, labels = labels, goal = if (span == Span.DAY) null else goal)
            if (span != Span.DAY) {
                val hit = values.count { it >= goal }
                Text("Total %,d · goal reached on %d day%s".format(total, hit, if (hit == 1) "" else "s"), style = T.Label)
            }
        }
        if (total == 0) EmptyHint("No steps stored for this period yet. History builds up each time the band syncs.")
    }
}

@Composable
private fun SleepHistory() {
    val ctx = LocalContext.current
    val version by Band.dataVersion.collectAsStateWithLifecycle()
    var day by remember { mutableStateOf(LocalDate.now()) }
    val weekStart = day.minusDays(6)
    val nights by produceState(List<Night?>(7) { null }, day, version) {
        value = withContext(Dispatchers.IO) { List(7) { Store.get(ctx).night(weekStart.plusDays(it.toLong())) } }
    }
    var selected by remember(day) { mutableStateOf<Int?>(null) }
    val night = nights.getOrNull(selected ?: 6)
    val shownDay = weekStart.plusDays((selected ?: 6).toLong())

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Navigator(
                "Night to " + shownDay.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())),
                canNext = day.isBefore(LocalDate.now()),
                onPrev = { day = day.minusDays(1) }, onNext = { day = day.plusDays(1) },
            )
            if (night == null) {
                EmptyHint("No sleep found for this night.")
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(formatMinutes(night.asleep), style = T.Big)
                        Text("${clockOf(night.startMinute)} – ${clockOf(night.endMinute)}", style = T.Label)
                    }
                    ScoreBadge(night.score)
                }
                Row {
                    Column(Modifier.padding(end = 8.dp).height(150.dp), verticalArrangement = Arrangement.SpaceAround) {
                        Text("Awake", style = T.Small); Text("Light", style = T.Small); Text("Deep", style = T.Small)
                    }
                    Hypnogram(night, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StageStat("Deep", night.deep, C.Deep, Modifier.weight(1f))
                    StageStat("Light", night.light, C.Light, Modifier.weight(1f))
                    StageStat("Awake", night.awake, C.Awake, Modifier.weight(1f))
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Text("Last 7 nights", style = T.Label)
            SleepBars(nights, List(7) { weekStart.plusDays(it.toLong()).dayOfWeek.name.take(1) }, selected, { selected = it })
            val got = nights.filterNotNull()
            if (got.isNotEmpty()) Text("Average ${formatMinutes(got.sumOf { it.asleep } / got.size)} a night", style = T.Label)
        }
    }
}

@Composable
private fun StageStat(name: String, minutes: Int, color: Color, modifier: Modifier) {
    Column(modifier.background(C.Surface2, androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Dot(color); Spacer(Modifier.width(6.dp)); Text(name, style = T.Label) }
        Text(formatMinutes(minutes), style = T.Body.copy(fontFeatureSettings = "tnum"))
    }
}

@Composable
private fun Navigator(title: String, canNext: Boolean, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RoundIcon(Ic.Left, true, onPrev)
        Text(title, style = T.Body, modifier = Modifier.weight(1f).padding(horizontal = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        RoundIcon(Ic.Right, canNext, onNext)
    }
}

@Composable
fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = if (enabled) C.Text else C.Faint, modifier = Modifier.size(20.dp)) }
}

@Composable
fun EmptyHint(text: String) = Text(text, style = T.Label, modifier = Modifier.padding(vertical = 6.dp))

private fun title(span: Span, first: LocalDate): String {
    val f = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
    return when (span) {
        Span.DAY -> if (first == LocalDate.now()) "Today" else first.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
        Span.WEEK -> "${first.format(f)} – ${first.plusDays(6).format(f)}"
        Span.MONTH -> first.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))
    }
}

private fun prev(span: Span, d: LocalDate) = when (span) { Span.DAY -> d.minusDays(1); Span.WEEK -> d.minusWeeks(1); Span.MONTH -> d.minusMonths(1) }
private fun next(span: Span, d: LocalDate) = when (span) { Span.DAY -> d.plusDays(1); Span.WEEK -> d.plusWeeks(1); Span.MONTH -> d.plusMonths(1) }
