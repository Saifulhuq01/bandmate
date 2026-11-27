package app.bandmate

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle

private val Ink = Color(0xFF0D0F12)
private val Card = Color(0xFF181B20)
private val Mint = Color(0xFF3DDC97)
private val Muted = Color(0xFF8A9099)
private val Warn = Color(0xFFFF6B6B)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Mint, onPrimary = Ink, background = Ink, surface = Card)) {
                Box(Modifier.fillMaxSize().background(Ink).safeDrawingPadding()) { Screen() }
            }
        }
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

private fun hasNotificationAccess(ctx: Context): Boolean {
    val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
    val me = ComponentName(ctx, NotifListener::class.java).flattenToString()
    return flat.split(':').any { it == me }
}

@Composable
private fun Screen() {
    val ctx = LocalContext.current
    val state by Band.state.collectAsStateWithLifecycle()
    var perms by remember { mutableStateOf(hasPermissions(ctx)) }
    var notifAccess by remember { mutableStateOf(hasNotificationAccess(ctx)) }
    var address by remember { mutableStateOf(Band.address(ctx)) }
    var keyText by remember { mutableStateOf(Band.keyText(ctx)) }

    // Re-check permissions every time the user comes back from Settings.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            perms = hasPermissions(ctx); notifAccess = hasNotificationAccess(ctx)
            if (perms && Band.client != null) BandService.start(ctx) // picks up newly granted permissions
        }
    }
    val askPerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        perms = hasPermissions(ctx)
        if (perms && Band.client != null) BandService.start(ctx)
    }

    val keyOk = Proto.parseKey(keyText) != null
    val running = state != Band.State.Disconnected || Band.client != null

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("BandMate", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.weight(1f))
            StatusChip(state)
        }

        if (!perms) {
            Panel("Step 1 · Permissions") {
                Text("Bluetooth, notification and phone (for call alerts) permissions are needed.", color = Muted)
                Button(onClick = { askPerms.launch(runtimePermissions()) }) { Text("Allow") }
            }
        }

        if (state == Band.State.Ready) Dashboard() else if (perms) {
            Setup(
                address = address, keyText = keyText,
                onPick = { addr, name -> Band.saveDevice(ctx, addr, name); address = addr },
                onKey = { keyText = it; Band.saveKey(ctx, it) },
            )
        }

        if (perms) {
            Panel("Connection") {
                if (state == Band.State.WrongKey) {
                    Text("The band rejected this key. Get a fresh key (don't unpair in Zepp Life) and paste it again.", color = Warn)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = address != null && keyOk && !running,
                        onClick = { Band.setState(Band.State.Connecting); BandService.start(ctx) },
                    ) { Text("Connect") }
                    OutlinedButton(enabled = running, onClick = { BandService.stop(ctx) }) { Text("Disconnect") }
                }
                if (!running && (address == null || !keyOk)) {
                    Text(
                        if (address == null) "Choose your band above to enable Connect."
                        else "Paste your auth key in Step 3 to enable Connect.",
                        color = Warn, fontSize = 13.sp,
                    )
                }
                Text("Close Zepp Life first — the band talks to only one app at a time.", color = Muted, fontSize = 13.sp)
                ToggleRow("Reconnect after phone restart", Band.autoStart(ctx)) { Band.setAutoStart(ctx, it) }
            }

            Panel("Phone notifications on band") {
                if (!notifAccess) {
                    Text("Turn on notification access so BandMate can forward WhatsApp, SMS, calls, etc.", color = Muted)
                    Button(onClick = {
                        ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }) { Text("Open notification access") }
                } else {
                    ToggleRow("Forward notifications", Band.forwardNotifications(ctx)) { Band.setForward(ctx, it) }
                }
            }
        }

        LogPanel()
    }
}

@Composable
private fun Dashboard() {
    val battery by Band.battery.collectAsStateWithLifecycle()
    val activity by Band.activity.collectAsStateWithLifecycle()
    val hr by Band.heartRate.collectAsStateWithLifecycle()
    val measuring by Band.measuringHr.collectAsStateWithLifecycle()
    val finding by Band.finding.collectAsStateWithLifecycle()
    val c = Band.client

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Stat("Steps", activity?.steps?.let { "%,d".format(it) } ?: "—", Modifier.weight(1f))
        Stat("Battery", battery?.let { "${it.percent}%" + if (it.charging) " ⚡" else "" } ?: "—", Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Stat("Distance", activity?.let { "%.2f km".format(it.meters / 1000.0) } ?: "—", Modifier.weight(1f))
        Stat("Calories", activity?.let { "${it.kcal} kcal" } ?: "—", Modifier.weight(1f))
    }
    Panel("Heart rate") {
        Text(if (measuring) "Measuring…" else hr?.let { "$it bpm" } ?: "—", fontSize = 32.sp, color = Color.White, fontWeight = FontWeight.Bold)
        Button(enabled = !measuring, onClick = { c?.measureHeartRate() }) { Text("Measure now") }
    }
    Panel("Tools") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { c?.findBand(!finding) },
                colors = if (finding) ButtonDefaults.buttonColors(containerColor = Warn) else ButtonDefaults.buttonColors(),
            ) { Text(if (finding) "Stop" else "Find band") }
            OutlinedButton(onClick = { c?.refresh() }) { Text("Refresh") }
            OutlinedButton(onClick = { c?.syncTimeAsync() }) { Text("Sync time") }
        }
        OutlinedButton(onClick = {
            c?.sendNotification("app.bandmate", "BandMate", "Hello", "Test notification from your own app!")
        }) { Text("Send test notification") }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun Setup(address: String?, keyText: String, onPick: (String, String?) -> Unit, onKey: (String) -> Unit) {
    val ctx = LocalContext.current
    val adapter = remember { ctx.getSystemService(BluetoothManager::class.java)?.adapter }
    val found = remember { mutableStateListOf<Pair<String, String>>() }
    var scanning by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // Bands already paired with this phone (e.g. by Zepp Life) show up straight away.
        adapter?.bondedDevices?.forEach { d ->
            val n = d.name ?: return@forEach
            if (found.none { it.first == d.address }) found += d.address to n
        }
    }

    Panel("Step 2 · Choose your band") {
        if (address != null) Text("Selected: ${Band.deviceName(ctx) ?: ""} $address", color = Mint)
        found.sortedByDescending { it.second.contains("band", true) }.forEach { (addr, name) ->
            Text(
                "$name  ·  $addr",
                color = if (addr == address) Mint else Color.White,
                modifier = Modifier.fillMaxWidth().clickable { onPick(addr, name) }.padding(vertical = 8.dp),
            )
        }
        OutlinedButton(enabled = !scanning && adapter?.isEnabled == true, onClick = {
            val scanner = adapter?.bluetoothLeScanner ?: return@OutlinedButton
            scanning = true
            val cb = object : ScanCallback() {
                override fun onScanResult(type: Int, r: ScanResult) {
                    val n = r.device.name ?: r.scanRecord?.deviceName ?: return
                    if (found.none { it.first == r.device.address }) found += r.device.address to n
                }
            }
            scanner.startScan(cb)
            Handler(Looper.getMainLooper()).postDelayed({ runCatching { scanner.stopScan(cb) }; scanning = false }, 10_000)
        }) { Text(if (scanning) "Scanning…" else "Scan for band") }
        if (adapter?.isEnabled != true) Text("Turn Bluetooth on to scan.", color = Warn)
    }

    Panel("Step 3 · Auth key") {
        OutlinedTextField(
            value = keyText, onValueChange = onKey, singleLine = true,
            label = { Text("0x + 32 characters") },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            isError = keyText.isNotEmpty() && Proto.parseKey(keyText) == null,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            if (Proto.parseKey(keyText) != null) "Key looks valid ✓" else "Paste the key you got from the huami-token script.",
            color = if (Proto.parseKey(keyText) != null) Mint else Muted, fontSize = 13.sp,
        )
    }
}

@Composable
private fun LogPanel() {
    val lines by Band.logLines.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    Panel("Log") {
        Text(if (open) "Hide" else "Show (${lines.size})", color = Mint, modifier = Modifier.clickable { open = !open })
        if (open) lines.forEach { Text(it, color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
    }
}

@Composable
private fun StatusChip(state: Band.State) {
    val color = when (state) {
        Band.State.Ready -> Mint
        Band.State.WrongKey, Band.State.BluetoothOff -> Warn
        else -> Muted
    }
    Row(
        Modifier.background(Card, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.size(6.dp))
        Text(state.label, color = Color.White, fontSize = 13.sp)
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.background(Card, RoundedCornerShape(16.dp)).padding(16.dp)) {
        Text(label, color = Muted, fontSize = 13.sp)
        Text(value, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Panel(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Card, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        content()
    }
}

@Composable
private fun ToggleRow(label: String, initial: Boolean, onChange: (Boolean) -> Unit) {
    var on by remember { mutableStateOf(initial) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = Color.White, modifier = Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = { on = it; onChange(it) })
    }
}
