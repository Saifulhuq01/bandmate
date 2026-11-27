package app.bandmate

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.bandmate.ui.AppsScreen
import app.bandmate.ui.BandScreen
import app.bandmate.ui.BandTheme
import app.bandmate.ui.C
import app.bandmate.ui.Card
import app.bandmate.ui.HistoryKind
import app.bandmate.ui.HistoryScreen
import app.bandmate.ui.Ic
import app.bandmate.ui.PrimaryButton
import app.bandmate.ui.T
import app.bandmate.ui.TodayScreen
import app.bandmate.ui.hasNotificationAccess

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Band.forecast.value == null) Band.forecast.value = WeatherPrefs.last(this)
        setContent { BandTheme { Box(Modifier.fillMaxSize().background(C.Bg)) { App() } } }
    }
}

private fun runtimePermissions(): Array<String> = buildList {
    if (Build.VERSION.SDK_INT >= 31) { add(Manifest.permission.BLUETOOTH_CONNECT); add(Manifest.permission.BLUETOOTH_SCAN) }
    else add(Manifest.permission.ACCESS_FINE_LOCATION)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    add(Manifest.permission.READ_PHONE_STATE) // to show incoming calls on the band
    if (Build.VERSION.SDK_INT >= 28) add(Manifest.permission.ANSWER_PHONE_CALLS) // reject from the band
}.toTypedArray()

private fun hasPermissions(ctx: Context) = runtimePermissions().all {
    ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
}

private enum class Tab(val label: String, val icon: ImageVector) {
    TODAY("Today", Ic.Today), HISTORY("History", Ic.History), BAND("Band", Ic.Band), APPS("Apps", Ic.Apps)
}

@Composable
private fun App() {
    val ctx = LocalContext.current
    val state by Band.state.collectAsStateWithLifecycle()
    var perms by remember { mutableStateOf(hasPermissions(ctx)) }
    var notifAccess by remember { mutableStateOf(hasNotificationAccess(ctx)) }
    var configured by remember { mutableStateOf(Band.address(ctx) != null && Proto.parseKey(Band.keyText(ctx)) != null) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            perms = hasPermissions(ctx); notifAccess = hasNotificationAccess(ctx)
            if (perms && Band.client != null) BandService.start(ctx) // picks up newly granted permissions
        }
    }
    // Once set up, connect automatically when the app opens.
    LaunchedEffect(perms, configured) {
        if (perms && configured && Band.client == null && Band.state.value != Band.State.WrongKey) {
            Band.setState(Band.State.Connecting); BandService.start(ctx)
        }
    }

    if (!perms || !configured || state == Band.State.WrongKey) {
        Setup(perms, onPerms = { perms = hasPermissions(ctx) }, onDone = { configured = true })
        return
    }

    var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }
    var historyKind by rememberSaveable { mutableStateOf(HistoryKind.STEPS) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        AnimatedContent(
            tab,
            transitionSpec = {
                val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                (slideInHorizontally(tween(260)) { it / 6 * dir } + fadeIn(tween(220))) togetherWith
                    (slideOutHorizontally(tween(200)) { -it / 8 * dir } + fadeOut(tween(160)))
            },
            modifier = Modifier.weight(1f),
            label = "tabs",
        ) { t ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                when (t) {
                    Tab.TODAY -> TodayScreen(
                        onOpenSleep = { historyKind = HistoryKind.SLEEP; tab = Tab.HISTORY },
                        onOpenBand = { tab = Tab.BAND },
                    )
                    Tab.HISTORY -> HistoryScreen(historyKind) { historyKind = it }
                    Tab.BAND -> BandScreen()
                    Tab.APPS -> AppsScreen(notifAccess)
                }
            }
        }
        BottomBar(tab) { tab = it }
    }
}

@Composable
private fun BottomBar(selected: Tab, onSelect: (Tab) -> Unit) {
    BoxWithConstraints(
        Modifier.fillMaxWidth().background(C.Bg).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)
            .height(62.dp).clip(RoundedCornerShape(22.dp)).background(C.Surface).padding(5.dp)
    ) {
        val w = maxWidth / Tab.entries.size
        val x by animateDpAsState(w * selected.ordinal, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow), label = "tab")
        Box(Modifier.offset(x = x).width(w).fillMaxHeight().clip(RoundedCornerShape(17.dp)).background(C.Surface2))
        Row(Modifier.fillMaxSize()) {
            Tab.entries.forEach { t ->
                val on = t == selected
                Column(
                    Modifier.weight(1f).fillMaxHeight().clickable(remember { MutableInteractionSource() }, null) { onSelect(t) },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Icon(t.icon, t.label, tint = if (on) C.Text else C.Faint, modifier = Modifier.size(22.dp))
                    Text(t.label, style = T.Small.copy(color = if (on) C.Text else C.Faint))
                }
            }
        }
    }
}

// --- First-run setup ------------------------------------------------------------------------

@SuppressLint("MissingPermission")
@Composable
private fun Setup(perms: Boolean, onPerms: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val state by Band.state.collectAsStateWithLifecycle()
    val askPerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onPerms() }
    var address by remember { mutableStateOf(Band.address(ctx)) }
    var keyText by remember { mutableStateOf(Band.keyText(ctx)) }
    val adapter = remember { ctx.getSystemService(BluetoothManager::class.java)?.adapter }
    val found = remember { mutableStateListOf<Pair<String, String>>() }
    var scanning by remember { mutableStateOf(false) }

    LaunchedEffect(perms) {
        if (perms) adapter?.bondedDevices?.forEach { d ->
            val n = d.name ?: return@forEach
            if (found.none { it.first == d.address }) found += d.address to n
        }
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("BandMate", style = T.Title)
        Text("Your Mi Band 4, without Zepp Life.", style = T.Label)
        if (state == Band.State.WrongKey) {
            Card(Modifier.fillMaxWidth()) {
                Text("The band rejected this key", style = T.Section.copy(color = C.Warn))
                Text("Get a fresh key (don't unpair the band in Zepp Life) and paste it below.", style = T.Label)
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Text("1 · Permissions", style = T.Section)
            Text("Bluetooth, notifications and phone (for call alerts).", style = T.Label)
            if (perms) Text("Done ✓", style = T.Body.copy(color = C.Steps))
            else PrimaryButton("Allow") { askPerms.launch(runtimePermissions()) }
        }
        if (perms) {
            Card(Modifier.fillMaxWidth()) {
                Text("2 · Choose your band", style = T.Section)
                found.sortedByDescending { it.second.contains("band", true) }.forEach { (addr, name) ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (addr == address) C.Steps.copy(alpha = 0.12f) else C.Surface2)
                            .clickable { Band.saveDevice(ctx, addr, name); address = addr }.padding(12.dp),
                    ) {
                        Text(name, style = T.Body.copy(color = if (addr == address) C.Steps else C.Text), modifier = Modifier.weight(1f))
                        Text(addr.takeLast(5), style = T.Small)
                    }
                }
                PrimaryButton(if (scanning) "Scanning…" else "Scan for band", enabled = !scanning && adapter?.isEnabled == true) {
                    val scanner = adapter?.bluetoothLeScanner ?: return@PrimaryButton
                    scanning = true
                    val cb = object : ScanCallback() {
                        override fun onScanResult(type: Int, r: ScanResult) {
                            val n = r.device.name ?: r.scanRecord?.deviceName ?: return
                            if (found.none { it.first == r.device.address }) found += r.device.address to n
                        }
                    }
                    scanner.startScan(cb)
                    Handler(Looper.getMainLooper()).postDelayed({ runCatching { scanner.stopScan(cb) }; scanning = false }, 10_000)
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Text("3 · Auth key", style = T.Section)
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Surface2).padding(12.dp)) {
                    if (keyText.isEmpty()) Text("0x + 32 characters", style = T.Body.copy(color = C.Faint, fontFamily = FontFamily.Monospace))
                    BasicTextField(keyText, { keyText = it; Band.saveKey(ctx, it) }, singleLine = true,
                        textStyle = T.Body.copy(fontFamily = FontFamily.Monospace), cursorBrush = SolidColor(C.Steps), modifier = Modifier.fillMaxWidth())
                }
                val ok = Proto.parseKey(keyText) != null
                Text(if (ok) "Key looks valid ✓" else "Paste the key from the get-key script.", style = T.Label.copy(color = if (ok) C.Steps else C.Muted))
            }
            PrimaryButton("Connect", enabled = address != null && Proto.parseKey(keyText) != null, modifier = Modifier.fillMaxWidth()) {
                // Restart the connection so a newly pasted key is used.
                BandService.stop(ctx)
                Handler(Looper.getMainLooper()).postDelayed({
                    Band.setState(Band.State.Connecting); BandService.start(ctx)
                }, 600)
                onDone()
            }
            Text("Close Zepp Life first — the band talks to only one app at a time.", style = T.Small)
        }
        Spacer(Modifier.height(20.dp))
    }
}
