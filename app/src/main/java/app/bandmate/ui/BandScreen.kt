package app.bandmate.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.bandmate.Actions
import app.bandmate.Alarm
import app.bandmate.Band
import app.bandmate.BandService
import app.bandmate.BandSettings
import app.bandmate.Clock
import app.bandmate.Cmd
import app.bandmate.Place
import app.bandmate.Saved
import app.bandmate.WeatherApi
import app.bandmate.WeatherPrefs
import kotlinx.coroutines.launch

@Composable
fun BandScreen() {
    val ctx = LocalContext.current
    var settings by remember { mutableStateOf(Saved.settings(ctx)) }
    fun update(s: BandSettings) { settings = s; Actions.saveSettings(ctx, s) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Band", style = T.Title, modifier = Modifier.padding(top = 8.dp))
        DeviceCard()
        AlarmsSection()

        SectionHeader("Screen")
        Card(Modifier.fillMaxWidth()) {
            ToggleRow("24-hour clock", checked = settings.time24h) { update(settings.copy(time24h = it)) }
            ToggleRow("Show date on clock", checked = settings.showDate) { update(settings.copy(showDate = it)) }
            if (settings.showDate) {
                Segmented(listOf("dd/MM/yyyy" to "31/12", "MM/dd/yyyy" to "12/31", "dd.MM.yyyy" to "31.12"), settings.dateFormat) {
                    update(settings.copy(dateFormat = it))
                }
            }
            Divider()
            ModeSetting("Lift wrist to wake", settings.liftWrist, "Off", "Always", settings.liftStart, settings.liftEnd,
                onMode = { update(settings.copy(liftWrist = it)) },
                onStart = { update(settings.copy(liftStart = it)) }, onEnd = { update(settings.copy(liftEnd = it)) })
            if (settings.liftWrist != Cmd.Mode.OFF) {
                ToggleRow("Faster response", "Wakes the screen on smaller movements", settings.liftSensitive) { update(settings.copy(liftSensitive = it)) }
            }
            Divider()
            ModeSetting("Night mode (dimmer screen)", settings.nightMode, "Off", "From sunset", settings.nightStart, settings.nightEnd,
                onMode = { update(settings.copy(nightMode = it)) },
                onStart = { update(settings.copy(nightStart = it)) }, onEnd = { update(settings.copy(nightEnd = it)) })
            Divider()
            ToggleRow("Rotate wrist to switch screens", checked = settings.rotateWrist) { update(settings.copy(rotateWrist = it)) }
        }

        SectionHeader("Screens on the band")
        ScreensCard(settings.screens) { update(settings.copy(screens = it)) }

        SectionHeader("Health")
        Card(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Daily step goal", style = T.Body); Text("Shown on the band and here", style = T.Label) }
                Stepper("%,d".format(settings.stepGoal),
                    onMinus = { update(settings.copy(stepGoal = (settings.stepGoal - 1000).coerceAtLeast(1000))) },
                    onPlus = { update(settings.copy(stepGoal = (settings.stepGoal + 1000).coerceAtMost(50000))) })
            }
            ToggleRow("Celebrate the goal", "Buzz when you reach it", settings.goalCelebration) { update(settings.copy(goalCelebration = it)) }
            Divider()
            ToggleRow("Time to move", "Buzz after sitting too long", settings.idleAlert) { update(settings.copy(idleAlert = it)) }
            AnimatedVisibility(settings.idleAlert, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Segmented(listOf(30 to "30 min", 45 to "45 min", 60 to "1 hour", 90 to "90 min"), settings.idleMinutes) { update(settings.copy(idleMinutes = it)) }
                    TimeRange(settings.idleStart, settings.idleEnd, onStart = { update(settings.copy(idleStart = it)) }, onEnd = { update(settings.copy(idleEnd = it)) })
                }
            }
            Divider()
            Text("All-day heart rate", style = T.Body)
            Segmented(listOf(0 to "Off", 1 to "1 min", 10 to "10 min", 30 to "30 min"), settings.hrInterval, accent = C.Heart) {
                update(settings.copy(hrInterval = it))
            }
            Text("More often = better history, but more battery use.", style = T.Label)
            ToggleRow("Heart rate helps sleep tracking", checked = settings.sleepHrAssist, accent = C.Sleep) { update(settings.copy(sleepHrAssist = it)) }
        }

        SectionHeader("Do not disturb")
        Card(Modifier.fillMaxWidth()) {
            ModeSetting("Silence the band", settings.dnd, "Off", "When asleep", settings.dndStart, settings.dndEnd, accent = C.Sleep,
                onMode = { update(settings.copy(dnd = it)) },
                onStart = { update(settings.copy(dndStart = it)) }, onEnd = { update(settings.copy(dndEnd = it)) })
        }

        SectionHeader("Weather on the band")
        WeatherCard()

        SectionHeader("More")
        Card(Modifier.fillMaxWidth()) {
            Text("Band is worn on", style = T.Body)
            Segmented(listOf(false to "Left wrist", true to "Right wrist"), settings.rightWrist) { update(settings.copy(rightWrist = it)) }
            ToggleRow("Buzz when phone disconnects", checked = settings.disconnectAlert) { update(settings.copy(disconnectAlert = it)) }
            ToggleRow("Reconnect after phone restart", checked = Band.autoStart(ctx)) { Band.setAutoStart(ctx, it) }
            ToggleRow("Count phone steps too", "From Google Fit via Health Connect", app.bandmate.PhoneSteps.enabled(ctx), accent = C.Sky) {
                app.bandmate.PhoneSteps.setEnabled(ctx, it); Band.dataVersion.value++
            }
        }
        LogCard()
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Divider() = HorizontalDivider(color = C.Line, thickness = 1.dp)

@Composable
private fun DeviceCard() {
    val ctx = LocalContext.current
    val state by Band.state.collectAsStateWithLifecycle()
    val battery by Band.battery.collectAsStateWithLifecycle()
    val finding by Band.finding.collectAsStateWithLifecycle()
    Card(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BandGlyph(state == Band.State.Ready)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(Band.deviceName(ctx) ?: "Mi Smart Band 4", style = T.Section)
                Text(state.label + (battery?.let { " · ${it.percent}%" + if (it.charging) " charging" else "" } ?: ""), style = T.Label)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(if (finding) "Stop" else "Find band", if (finding) C.Warn else C.Steps, enabled = state == Band.State.Ready,
                icon = Ic.Vibrate, modifier = Modifier.weight(1f)) { Band.client?.findBand(!finding) }
            if (Band.client != null) GhostButton("Disconnect", modifier = Modifier.weight(1f)) { BandService.stop(ctx) }
            else PrimaryButton("Connect", modifier = Modifier.weight(1f)) { Band.setState(Band.State.Connecting); BandService.start(ctx) }
        }
    }
}

/** A tiny drawing of the band; the screen glows when connected. */
@Composable
private fun BandGlyph(on: Boolean) {
    Canvas(Modifier.size(width = 44.dp, height = 64.dp)) {
        val w = size.width; val h = size.height
        drawRoundRect(C.Surface2, Offset(w * 0.22f, 0f), Size(w * 0.56f, h), CornerRadius(w * 0.2f))
        drawRoundRect(C.Line, Offset(w * 0.08f, h * 0.2f), Size(w * 0.84f, h * 0.6f), CornerRadius(w * 0.3f))
        drawRoundRect(if (on) C.Steps.copy(alpha = 0.9f) else C.Faint, Offset(w * 0.24f, h * 0.3f), Size(w * 0.52f, h * 0.4f), CornerRadius(w * 0.16f), style = Stroke(2.dp.toPx()))
    }
}

// --- Alarms -------------------------------------------------------------------------------

private val DAY_NAMES = listOf("M", "T", "W", "T", "F", "S", "S")

private fun daysLabel(days: Int) = when (days and 0x7f) {
    0 -> "Once"
    0x7f -> "Every day"
    0x1f -> "Weekdays"
    0x60 -> "Weekends"
    else -> listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").filterIndexed { i, _ -> days and (1 shl i) != 0 }.joinToString(" ")
}

@Composable
private fun AlarmsSection() {
    val ctx = LocalContext.current
    val alarms = remember { mutableStateListOf<Alarm>().apply { addAll(Saved.alarms(ctx)) } }
    var editing by remember { mutableStateOf<Alarm?>(null) }
    var adding by remember { mutableStateOf(false) }
    fun save() = Actions.saveAlarms(ctx, alarms.toList())

    SectionHeader("Alarms") {
        if (alarms.size < 10) Pill("Add", C.Bg, C.Steps, Ic.Plus) { adding = true }
    }
    Card(Modifier.fillMaxWidth().animateContentSize()) {
        if (alarms.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Ic.Alarm, C.Awake)
                Spacer(Modifier.width(12.dp))
                Text("No alarms. The band buzzes on your wrist — no sound, even if your phone is far away.", style = T.Label)
            }
        }
        alarms.sortedBy { it.time.minutes }.forEachIndexed { i, a ->
            if (i > 0) Divider()
            Row(Modifier.fillMaxWidth().clickable { editing = a }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(a.time.toString(), style = T.Big.copy(color = if (a.enabled) C.Text else C.Faint))
                    Text(daysLabel(a.days), style = T.Label)
                }
                Toggle(a.enabled, { on ->
                    val idx = alarms.indexOfFirst { it.slot == a.slot }
                    if (idx >= 0) { alarms[idx] = a.copy(enabled = on); save() }
                }, accent = C.Awake)
            }
        }
        if (alarms.isNotEmpty()) Text("Alarms set here replace the ones saved on the band.", style = T.Small)
    }
    if (adding) {
        val free = (0 until 10).first { s -> alarms.none { it.slot == s } }
        AlarmEditor(Alarm(free, Clock.of(7, 0), 0x1f, true), isNew = true, onDismiss = { adding = false }, onDelete = {}) {
            alarms += it; save(); adding = false
        }
    }
    editing?.let { a ->
        AlarmEditor(a, isNew = false, onDismiss = { editing = null }, onDelete = {
            alarms.removeAll { it.slot == a.slot }; save(); editing = null
        }) { updated ->
            val idx = alarms.indexOfFirst { it.slot == a.slot }
            if (idx >= 0) alarms[idx] = updated
            save(); editing = null
        }
    }
}

@Composable
private fun AlarmEditor(initial: Alarm, isNew: Boolean, onDismiss: () -> Unit, onDelete: () -> Unit, onSave: (Alarm) -> Unit) {
    var time by remember { mutableStateOf(initial.time) }
    var days by remember { mutableStateOf(initial.days) }
    var picking by remember { mutableStateOf(isNew) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.Surface,
        title = { Text(if (isNew) "New alarm" else "Edit alarm", style = T.Section) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(time.toString(), style = T.Hero.copy(color = C.Awake), modifier = Modifier.clickable { picking = true })
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DAY_NAMES.forEachIndexed { i, d ->
                        val on = days and (1 shl i) != 0
                        Box(
                            Modifier.size(34.dp).clip(CircleShape).background(if (on) C.Awake else C.Surface2)
                                .clickable { days = days xor (1 shl i) },
                            contentAlignment = Alignment.Center,
                        ) { Text(d, style = T.Label.copy(color = if (on) C.Bg else C.Muted)) }
                    }
                }
                Text(daysLabel(days), style = T.Label)
            }
        },
        confirmButton = { androidx.compose.material3.TextButton({ onSave(initial.copy(time = time, days = days, enabled = true)) }) { Text("Save", color = C.Awake) } },
        dismissButton = {
            Row {
                if (!isNew) androidx.compose.material3.TextButton(onDelete) { Text("Delete", color = C.Warn) }
                androidx.compose.material3.TextButton(onDismiss) { Text("Cancel", color = C.Muted) }
            }
        },
    )
    if (picking) TimeDialog(time, C.Awake, onDismiss = { picking = false }) { time = it; picking = false }
}

// --- Settings widgets ---------------------------------------------------------------------

@Composable
private fun ModeSetting(
    title: String, mode: Cmd.Mode, offLabel: String, onLabel: String, start: Clock, end: Clock,
    accent: androidx.compose.ui.graphics.Color = C.Steps,
    onMode: (Cmd.Mode) -> Unit, onStart: (Clock) -> Unit, onEnd: (Clock) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.animateContentSize()) {
        Text(title, style = T.Body)
        Segmented(listOf(Cmd.Mode.OFF to offLabel, Cmd.Mode.ON to onLabel, Cmd.Mode.SCHEDULED to "Scheduled"), mode, accent = accent, onSelect = onMode)
        if (mode == Cmd.Mode.SCHEDULED) TimeRange(start, end, accent, onStart, onEnd)
    }
}

@Composable
private fun Stepper(value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(14.dp)).background(C.Surface2), verticalAlignment = Alignment.CenterVertically) {
        RoundIcon(Ic.Close.let { Ic.Down }, true, onMinus)
        Text(value, style = T.Body.copy(fontFeatureSettings = "tnum"), modifier = Modifier.padding(horizontal = 4.dp))
        RoundIcon(Ic.Up, true, onPlus)
    }
}

@Composable
private fun ScreensCard(shown: List<Cmd.Screen>, onChange: (List<Cmd.Screen>) -> Unit) {
    val choices = listOf(Cmd.Screen.STATUS, Cmd.Screen.HEART, Cmd.Screen.WORKOUT, Cmd.Screen.WEATHER, Cmd.Screen.NOTIFICATIONS, Cmd.Screen.MORE)
    val order = shown.filter { it in choices } + choices.filter { it !in shown }
    Card(Modifier.fillMaxWidth().animateContentSize()) {
        Text("Turn screens on or off and set their order. The clock always comes first; More holds the band's settings, so it stays on.", style = T.Label)
        order.forEachIndexed { i, s ->
            val on = s in shown
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${if (on) shown.indexOf(s) + 1 else "–"}", style = T.Small, modifier = Modifier.width(18.dp))
                Text(s.label, style = T.Body.copy(color = if (on) C.Text else C.Faint), modifier = Modifier.weight(1f))
                if (on) {
                    val idx = shown.indexOf(s)
                    RoundIcon(Ic.Up, idx > 0) { onChange(shown.toMutableList().apply { add(idx - 1, removeAt(idx)) }) }
                    RoundIcon(Ic.Down, idx < shown.size - 1) { onChange(shown.toMutableList().apply { add(idx + 1, removeAt(idx)) }) }
                }
                if (s == Cmd.Screen.MORE) Spacer(Modifier.width(52.dp))
                else Toggle(on, { v -> onChange(if (v) shown + s else shown - s) })
            }
        }
    }
}

// --- Weather ------------------------------------------------------------------------------

@Composable
private fun WeatherCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(WeatherPrefs.enabled(ctx)) }
    var place by remember { mutableStateOf(WeatherPrefs.place(ctx)) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val forecast by Band.forecast.collectAsStateWithLifecycle()
    val last = forecast ?: WeatherPrefs.last(ctx)

    fun choose(p: Place) {
        WeatherPrefs.setPlace(ctx, p); place = p; results = emptyList(); query = ""
        Actions.refreshWeather(ctx)
    }
    val locPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) scope.launch {
            busy = true
            val p = WeatherApi.myPlace(ctx)
            busy = false
            if (p != null) choose(p) else error = "Couldn't get your location. Search for your city instead."
        }
    }

    Card(Modifier.fillMaxWidth().animateContentSize()) {
        ToggleRow("Send weather to the band", "Updated every hour from open-meteo.com", enabled, accent = C.Sky) {
            enabled = it; WeatherPrefs.setEnabled(ctx, it)
            if (it && place != null) Actions.refreshWeather(ctx)
        }
        if (enabled) {
            if (place != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Ic.Pin, null, tint = C.Sky, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(place!!.name, style = T.Body, modifier = Modifier.weight(1f))
                    if (last != null) Text("${last.tempNow}° ${WeatherApi.label(last.codeNow)}", style = T.Label)
                }
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Surface2).padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Ic.Search, null, tint = C.Muted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(if (place == null) "Search your city" else "Change city", style = T.Body.copy(color = C.Faint))
                    BasicTextField(query, { query = it }, singleLine = true, textStyle = T.Body, cursorBrush = SolidColor(C.Sky),
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = {
                            scope.launch {
                                busy = true; error = null
                                results = runCatching { WeatherApi.search(query) }.onFailure { error = "No internet or city not found" }.getOrDefault(emptyList())
                                busy = false
                            }
                        }))
                }
            }
            results.forEach { p ->
                Row(Modifier.fillMaxWidth().clickable { choose(p) }.padding(vertical = 6.dp)) {
                    Text(p.name, style = T.Body); Spacer(Modifier.width(8.dp)); Text(p.region, style = T.Label)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(if (busy) "Working…" else "Use my location", C.Sky, icon = Ic.Pin) {
                    val granted = ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    if (granted) locPerm.launch(Manifest.permission.ACCESS_COARSE_LOCATION) // re-runs the lookup
                    else locPerm.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                }
                if (place != null) Pill("Send now", C.Text, icon = Ic.Sync) { Actions.refreshWeather(ctx) }
            }
            error?.let { Text(it, style = T.Label.copy(color = C.Warn)) }
            Text("Only the chosen place's coordinates are sent to open-meteo.com. Nothing else leaves your phone.", style = T.Small)
        }
    }
}

@Composable
private fun LogCard() {
    val lines by Band.logLines.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().animateContentSize(), onClick = { open = !open }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Connection log", style = T.Body, modifier = Modifier.weight(1f))
            Icon(if (open) Ic.Up else Ic.Down, null, tint = C.Muted, modifier = Modifier.size(18.dp))
        }
        if (open) lines.take(40).forEach { Text(it, style = T.Small.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)) }
    }
}
