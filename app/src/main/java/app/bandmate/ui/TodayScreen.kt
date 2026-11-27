package app.bandmate.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.health.connect.client.PermissionController
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.bandmate.Actions
import app.bandmate.Band
import app.bandmate.Night
import app.bandmate.Saved
import app.bandmate.PhoneSteps
import app.bandmate.Steps
import app.bandmate.Store
import app.bandmate.WeatherApi
import app.bandmate.WeatherPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun TodayScreen(onOpenSleep: () -> Unit, onOpenBand: () -> Unit) {
    val ctx = LocalContext.current
    val state by Band.state.collectAsStateWithLifecycle()
    val battery by Band.battery.collectAsStateWithLifecycle()
    val activity by Band.activity.collectAsStateWithLifecycle()
    val hr by Band.heartRate.collectAsStateWithLifecycle()
    val measuring by Band.measuringHr.collectAsStateWithLifecycle()
    val syncing by Band.syncing.collectAsStateWithLifecycle()
    val version by Band.dataVersion.collectAsStateWithLifecycle()
    val forecast by Band.forecast.collectAsStateWithLifecycle()
    val goal = Saved.settings(ctx).stepGoal

    val night by produceState<Night?>(null, version) {
        value = withContext(Dispatchers.IO) { Store.get(ctx).night(LocalDate.now()) }
    }
    val hrPoints by produceState(emptyList<Pair<Float, Int>>(), version) {
        value = withContext(Dispatchers.IO) {
            val start = Store.dayStartMinute(LocalDate.now())
            Store.get(ctx).samples(start, start + 1440).map { (it.minute - start) / 1440f to it.heartRate }
        }
    }
    val weather = forecast ?: WeatherPrefs.last(ctx)

    // Phone steps (Health Connect), refreshed every minute while this screen is open.
    var phoneGranted by remember { mutableStateOf<Boolean?>(null) }
    var askTick by remember { mutableIntStateOf(0) }
    val askPhone = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { askTick++ }
    LaunchedEffect(askTick) { phoneGranted = PhoneSteps.granted(ctx) }
    val tick by produceState(0) { while (true) { delay(60_000); value++ } }
    val hours by produceState<Triple<IntArray, IntArray, IntArray>?>(null, version, tick, phoneGranted) {
        value = Steps.hoursOf(ctx, LocalDate.now())
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Header
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMM", Locale.getDefault())), style = T.Label)
                Text("Today", style = T.Title)
            }
            SyncButton(syncing, enabled = state == Band.State.Ready) { Actions.syncNow() }
        }
        ConnectionPill(state, battery?.percent, onClick = onOpenBand)

        // Steps hero
        // The band's live counter is newer than its synced history, so top up the current hour with it.
        val bandLive = activity?.steps ?: 0
        val h = hours
        val bandHours = h?.first?.copyOf() ?: IntArray(24)
        val gap = bandLive - bandHours.sum()
        if (gap > 0) bandHours[java.time.LocalTime.now().hour] += gap
        val phoneSteps = h?.second?.sum() ?: 0
        val steps = Steps.merge(bandHours, h?.second ?: IntArray(24)).sum()
        val shown by animateIntAsState(steps, tween(900), label = "steps")
        Card(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Ring(steps / goal.toFloat(), C.Steps, size = 230.dp, stroke = 16.dp) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Ic.Steps, null, tint = C.Steps, modifier = Modifier.size(22.dp))
                        Text("%,d".format(shown), style = T.Hero)
                        Text("of %,d steps".format(goal), style = T.Label)
                    }
                }
            }
            if (phoneSteps > 0) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Dot(C.Steps); Spacer(Modifier.width(6.dp)); Text("Band %,d".format(bandHours.sum()), style = T.Label)
                    Spacer(Modifier.width(16.dp))
                    Dot(C.Sky); Spacer(Modifier.width(6.dp)); Text("Phone %,d".format(phoneSteps), style = T.Label)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // The band only knows its own steps; when the phone counted more, estimate from the total
                // (about 0.75 m and 0.04 kcal per step).
                val meters = maxOf(activity?.meters ?: 0, (steps * 0.75).toInt())
                val kcal = maxOf(activity?.kcal ?: 0, (steps * 0.04).toInt())
                MiniStat(Ic.Route, C.Sky, if (steps > 0 || activity != null) "%.2f".format(meters / 1000.0) else "—", "km", Modifier.weight(1f))
                MiniStat(Ic.Flame, C.Awake, if (steps > 0 || activity != null) kcal.toString() else "—", "kcal", Modifier.weight(1f))
                MiniStat(if (battery?.charging == true) Ic.Bolt else Ic.Battery, C.Text, battery?.percent?.toString() ?: "—", "%", Modifier.weight(1f))
            }
        }

        if (phoneGranted == false && PhoneSteps.available(ctx) && PhoneSteps.enabled(ctx)) {
            Card(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Ic.Phone, C.Sky)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Add your phone's steps", style = T.Body)
                        Text("Counts steps when you're not wearing the band, from Google Fit via Health Connect.", style = T.Label)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("Allow", C.Sky, modifier = Modifier.weight(1f)) { askPhone.launch(PhoneSteps.PERMISSIONS) }
                    GhostButton("Not now", modifier = Modifier.weight(1f)) { PhoneSteps.setEnabled(ctx, false); askTick++ }
                }
            }
        }

        // Heart
        Card(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Beat(measuring)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Heart rate", style = T.Label)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(if (measuring) "··" else hr?.toString() ?: "—", style = T.Big)
                        Text(" bpm", style = T.Label, modifier = Modifier.padding(bottom = 6.dp))
                    }
                }
                PrimaryButton(if (measuring) "Measuring" else "Measure", C.Heart, enabled = !measuring && state == Band.State.Ready) {
                    Band.client?.measureHeartRate()
                }
            }
            HeartLine(hrPoints, height = 70.dp)
        }

        // Sleep
        Card(Modifier.fillMaxWidth(), onClick = onOpenSleep) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Ic.Moon, C.Sleep)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Last night", style = T.Label)
                    Text(night?.let { formatMinutes(it.asleep) } ?: "No sleep yet", style = if (night != null) T.Num else T.Body.copy(color = C.Muted))
                }
                night?.let { ScoreBadge(it.score) }
            }
            night?.let {
                SleepStrip(it)
                Row {
                    Text(clockOf(it.startMinute), style = T.Small, modifier = Modifier.weight(1f))
                    Text(clockOf(it.endMinute), style = T.Small)
                }
            } ?: Text("Sync after a night with the band on to see sleep.", style = T.Label)
        }

        // Weather
        if (weather != null && WeatherPrefs.enabled(ctx)) {
            Card(Modifier.fillMaxWidth(), onClick = onOpenBand) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(weatherIcon(weather.codeNow), C.Sky)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(weather.place, style = T.Label)
                        Text("${weather.tempNow}°  ${WeatherApi.label(weather.codeNow)}", style = T.Num)
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    weather.days.take(5).forEachIndexed { i, (code, hi, lo) ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (i == 0) "Today" else LocalDate.now().plusDays(i.toLong()).dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }, style = T.Small)
                            Icon(weatherIcon(code), null, tint = C.Muted, modifier = Modifier.size(20.dp).padding(vertical = 2.dp))
                            Text("$hi°", style = T.Label.copy(color = C.Text))
                            Text("$lo°", style = T.Small)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun MiniStat(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: androidx.compose.ui.graphics.Color, value: String, unit: String, modifier: Modifier) {
    Row(modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).background(C.Surface2).padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(value, style = T.Body.copy(fontFeatureSettings = "tnum"))
        Text(" $unit", style = T.Small)
    }
}

@Composable
private fun Beat(active: Boolean) {
    val t = rememberInfiniteTransition(label = "beat")
    val s by t.animateFloat(1f, 1.18f, infiniteRepeatable(tween(420), RepeatMode.Reverse), label = "s")
    Box(Modifier.size(42.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(13.dp)).background(C.Heart.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
        Icon(Ic.HeartFill, null, tint = C.Heart, modifier = Modifier.size(22.dp).scale(if (active) s else 1f))
    }
}

@Composable
fun ScoreBadge(score: Int) {
    val color = when { score >= 80 -> C.Steps; score >= 60 -> C.Light; else -> C.Awake }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Ring(score / 100f, color, size = 50.dp, stroke = 5.dp) { Text("$score", style = T.Body.copy(fontFeatureSettings = "tnum")) }
    }
}

@Composable
private fun SyncButton(progress: Float?, enabled: Boolean, onClick: () -> Unit) {
    val t = rememberInfiniteTransition(label = "sync")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "a")
    Box(
        Modifier.size(46.dp).clip(CircleShape).background(C.Surface).clickable(enabled = enabled && progress == null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (progress != null) {
            Canvas(Modifier.size(46.dp)) {
                val sw = 3.dp.toPx()
                drawArc(C.Steps, -90f, 360f * progress.coerceAtLeast(0.04f), false, Offset(sw, sw), Size(size.width - 2 * sw, size.height - 2 * sw), style = Stroke(sw, cap = StrokeCap.Round))
            }
        }
        Icon(Ic.Sync, "Sync", tint = if (enabled) C.Text else C.Faint, modifier = Modifier.size(20.dp).rotate(if (progress != null) angle else 0f))
    }
}

@Composable
private fun ConnectionPill(state: Band.State, battery: Int?, onClick: () -> Unit) {
    val color = when (state) { Band.State.Ready -> C.Steps; Band.State.WrongKey, Band.State.BluetoothOff -> C.Warn; else -> C.Awake }
    Row(
        Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(50)).background(C.Surface).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val t = rememberInfiniteTransition(label = "dot")
        val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a")
        Box(Modifier.size(8.dp).clip(CircleShape).background(color.copy(alpha = if (state == Band.State.Ready) 1f else a)))
        Spacer(Modifier.width(8.dp))
        Text("Mi Smart Band 4 · ${state.label}" + (battery?.let { " · $it%" } ?: ""), style = T.Label.copy(color = C.Text))
    }
}

fun clockOf(epochMinute: Long): String =
    java.time.Instant.ofEpochSecond(epochMinute * 60).atZone(java.time.ZoneId.systemDefault())
        .toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))
