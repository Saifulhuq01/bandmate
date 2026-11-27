package app.bandmate

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** App-wide band state the UI observes, plus saved settings. The live connection is owned by [BandService]. */
object Band {
    enum class State(val label: String) {
        Disconnected("Not connected"), BluetoothOff("Bluetooth is off"), Connecting("Connecting…"),
        Authenticating("Checking key…"), Ready("Connected"), WrongKey("Wrong auth key"),
    }

    val state = MutableStateFlow(State.Disconnected)
    val battery = MutableStateFlow<Battery?>(null)
    val activity = MutableStateFlow<Activity?>(null)
    val heartRate = MutableStateFlow<Int?>(null)
    val measuringHr = MutableStateFlow(false)
    val finding = MutableStateFlow(false)
    val logLines = MutableStateFlow<List<String>>(emptyList())
    /** History download progress 0..1, or null when not syncing. */
    val syncing = MutableStateFlow<Float?>(null)
    /** Bumped whenever new history lands in the database, so screens reload. */
    val dataVersion = MutableStateFlow(0)
    val forecast = MutableStateFlow<Forecast?>(null)

    @Volatile var client: BandClient? = null

    /** Set by CallWatcher: ends the ringing call when Reject is tapped on the band. */
    @Volatile var onRejectCall: (() -> Unit)? = null

    fun setState(s: State) { state.value = s }

    @Volatile private var callerName: String? = null
    @Volatile private var callerNameAt = 0L
    fun setCallerName(name: String) { callerName = name; callerNameAt = System.currentTimeMillis() }
    fun callerName(maxAgeMs: Long): String? =
        callerName.takeIf { System.currentTimeMillis() - callerNameAt <= maxAgeMs }

    fun clearLive() { measuringHr.value = false; finding.value = false }

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun log(msg: String) {
        Log.d("BandMate", msg)
        val line = "${timeFmt.format(Date())}  $msg"
        logLines.value = (listOf(line) + logLines.value).take(60)
    }

    // --- Saved settings ------------------------------------------------------------------

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("band", Context.MODE_PRIVATE)

    fun address(ctx: Context): String? = prefs(ctx).getString("address", null)
    fun deviceName(ctx: Context): String? = prefs(ctx).getString("name", null)
    fun keyText(ctx: Context): String = prefs(ctx).getString("key", "") ?: ""
    fun forwardNotifications(ctx: Context) = prefs(ctx).getBoolean("forward", true)
    fun autoStart(ctx: Context) = prefs(ctx).getBoolean("autostart", false)

    fun saveDevice(ctx: Context, address: String, name: String?) =
        prefs(ctx).edit().putString("address", address).putString("name", name).apply()
    fun saveKey(ctx: Context, key: String) = prefs(ctx).edit().putString("key", key.trim()).apply()
    fun setForward(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("forward", on).apply()
    fun setAutoStart(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("autostart", on).apply()
}
