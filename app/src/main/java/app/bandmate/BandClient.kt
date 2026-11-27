package app.bandmate

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar
import java.util.UUID

/**
 * One Bluetooth LE connection to the band. Android only allows one GATT operation at a time,
 * so every read/write goes through [op], which waits for the matching callback.
 * Permissions (BLUETOOTH_CONNECT) are checked by the UI before the service starts.
 */
@SuppressLint("MissingPermission")
class BandClient(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val address: String,
    private val key: ByteArray,
) {
    private var gatt: BluetoothGatt? = null
    private var wanted = true
    private var mtu = 23
    private val opLock = Mutex()
    @Volatile private var pending: CompletableDeferred<ByteArray?>? = null
    private var reconnectJob: Job? = null
    private val syncLock = Mutex()
    private val control = Channel<ByteArray>(Channel.UNLIMITED)
    private val fetchBuf = java.io.ByteArrayOutputStream()
    @Volatile private var fetchCounter = -1
    @Volatile private var fetchOk = true
    @Volatile private var fetchExpected = 1
    private var findJob: Job? = null

    fun connect(autoConnect: Boolean = false) {
        wanted = true
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            Band.setState(Band.State.BluetoothOff)
            scheduleReconnect(15_000)
            return
        }
        val device: BluetoothDevice = adapter.getRemoteDevice(address)
        Band.setState(Band.State.Connecting)
        Band.log("Connecting to $address…")
        gatt = device.connectGatt(ctx, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun close() {
        wanted = false
        reconnectJob?.cancel()
        findJob?.cancel()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        pending?.complete(null)
        Band.setState(Band.State.Disconnected)
    }

    private fun scheduleReconnect(ms: Long) {
        if (!wanted) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(ms)
            if (wanted) connect(autoConnect = true)
        }
    }

    // --- GATT callbacks ------------------------------------------------------------------

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Band.log("Connected, discovering services")
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Band.log("Disconnected (status $status)")
                pending?.complete(null)
                g.close()
                if (gatt == g) gatt = null
                Band.clearLive()
                if (Band.state.value != Band.State.WrongKey) {
                    Band.setState(Band.State.Disconnected)
                    scheduleReconnect(5_000)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Band.log("Service discovery failed: $status"); g.disconnect(); return
            }
            scope.launch { startAuth() }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            pending?.complete(ByteArray(0))
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pending?.complete(if (status == BluetoothGatt.GATT_SUCCESS) ByteArray(0) else null)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pending?.complete(if (status == BluetoothGatt.GATT_SUCCESS) ByteArray(0) else null)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            pending?.complete(if (status == BluetoothGatt.GATT_SUCCESS) value else null)
        }

        @Deprecated("Android 12 and older")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION")
                pending?.complete(if (status == BluetoothGatt.GATT_SUCCESS) c.value else null)
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            onNotify(c.uuid, value)
        }

        @Deprecated("Android 12 and older")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 33) onNotify(c.uuid, c.value ?: return)
        }
    }

    // --- Serialized GATT operations ------------------------------------------------------

    private suspend fun op(name: String, start: (BluetoothGatt) -> Boolean): ByteArray? = opLock.withLock {
        val g = gatt ?: return null
        val d = CompletableDeferred<ByteArray?>()
        pending = d
        val started = try { start(g) } catch (e: Exception) { Band.log("$name error: ${e.message}"); false }
        if (!started) {
            pending = null
            Band.log("$name could not start")
            return null
        }
        val r = withTimeoutOrNull(8_000) { d.await() }
        pending = null
        if (r == null) Band.log("$name failed")
        r
    }

    private fun find(uuid: UUID, service: UUID? = null): BluetoothGattCharacteristic? {
        val g = gatt ?: return null
        if (service != null) g.getService(service)?.getCharacteristic(uuid)?.let { return it }
        return g.services.firstNotNullOfOrNull { it.getCharacteristic(uuid) }
    }

    private suspend fun write(uuid: UUID, value: ByteArray, service: UUID? = null): Boolean {
        val c = find(uuid, service) ?: run { Band.log("No characteristic $uuid"); return false }
        return op("write ${short(uuid)}") { g ->
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, value, c.writeType) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.value = value
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
        } != null
    }

    private suspend fun read(uuid: UUID): ByteArray? {
        val c = find(uuid) ?: return null
        return op("read ${short(uuid)}") { g -> g.readCharacteristic(c) }
    }

    private suspend fun subscribe(uuid: UUID): Boolean {
        val c = find(uuid) ?: run { Band.log("No characteristic ${short(uuid)} to subscribe"); return false }
        val g = gatt ?: return false
        if (!g.setCharacteristicNotification(c, true)) return false
        val d = c.getDescriptor(Uuids.CCCD) ?: return false
        val v = if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        return op("subscribe ${short(uuid)}") { gg ->
            if (Build.VERSION.SDK_INT >= 33) {
                gg.writeDescriptor(d, v) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                d.value = v
                @Suppress("DEPRECATION")
                gg.writeDescriptor(d)
            }
        } != null
    }

    // --- Auth + setup --------------------------------------------------------------------

    private suspend fun startAuth() {
        Band.setState(Band.State.Authenticating)
        // MTU stays at the default 23 on purpose: Gadgetbridge never raises it for the
        // Mi Band 4, so the band's chunk handling is only known to work at that size.
        if (!subscribe(Uuids.AUTH)) { Band.log("Auth channel missing — is this a Mi Band 4?"); return }
        write(Uuids.AUTH, Proto.requestChallenge())
    }

    private fun onNotify(uuid: UUID, v: ByteArray) {
        when (uuid) {
            Uuids.AUTH -> scope.launch { onAuth(v) }
            Uuids.BATTERY -> Proto.parseBattery(v)?.let { Band.battery.value = it }
            Uuids.STEPS -> Proto.parseActivity(v)?.let { Band.activity.value = it }
            Uuids.HR_MEASURE -> Proto.parseHeartRate(v)?.let {
                Band.heartRate.value = it
                Band.measuringHr.value = false
                Band.log("Heart rate: $it bpm")
            }
            Uuids.ACTIVITY_CONTROL -> control.trySend(v)
            Uuids.ACTIVITY_DATA -> onHistoryPacket(v)
            // Replies echo the command; they are not error codes, so only keep them in logcat.
            Uuids.CONFIG -> android.util.Log.d("BandMate", "config reply ${Proto.hex(v)}")
            Uuids.DEVICE_EVENT -> when (v.firstOrNull()) {
                Proto.EVENT_CALL_REJECT -> { Band.log("Reject tapped on band"); Band.onRejectCall?.invoke() }
                Proto.EVENT_CALL_IGNORE -> Band.log("Ignore tapped on band")
                else -> Band.log("Band event: ${Proto.hex(v)}")
            }
            else -> Band.log("Notify ${short(uuid)}: ${Proto.hex(v)}")
        }
    }

    private suspend fun onAuth(v: ByteArray) {
        when (val r = Proto.parseAuth(v)) {
            is AuthReply.Challenge -> {
                Band.log("Got challenge, answering")
                write(Uuids.AUTH, Proto.answerChallenge(r.random, key))
            }
            AuthReply.Success -> {
                Band.log("Authenticated ✓")
                afterAuth()
            }
            AuthReply.WrongKey -> {
                Band.log("Band rejected the auth key")
                Band.setState(Band.State.WrongKey)
                wanted = false
                gatt?.disconnect()
            }
            AuthReply.Unknown -> Band.log("Auth reply: ${Proto.hex(v)}")
        }
    }

    private suspend fun afterAuth() {
        // Diagnostic: which operations the band's step counter allows (read/write/notify).
        find(Uuids.STEPS)?.let { c ->
            val p = c.properties
            val names = listOf(0x02 to "read", 0x04 to "write-no-response", 0x08 to "write", 0x10 to "notify", 0x20 to "indicate")
                .filter { p and it.first != 0 }.joinToString(", ") { it.second }
            android.util.Log.d("BandMate", "Step counter (0007) allows: $names")
        }
        syncTime()
        subscribe(Uuids.BATTERY)
        subscribe(Uuids.STEPS)
        subscribe(Uuids.HR_MEASURE)
        subscribe(Uuids.DEVICE_EVENT)
        subscribe(Uuids.CONFIG)
        read(Uuids.BATTERY)?.let { Proto.parseBattery(it) }?.let { Band.battery.value = it }
        read(Uuids.STEPS)?.let { Proto.parseActivity(it) }?.let { Band.activity.value = it }
        Band.setState(Band.State.Ready)
    }

    // --- Commands (called from UI / notification listener) -------------------------------

    private val ready get() = Band.state.value == Band.State.Ready

    fun syncTimeAsync() = scope.launch { if (ready) syncTime() }

    private suspend fun syncTime() {
        if (write(Uuids.CURRENT_TIME, Proto.timeBytes(Calendar.getInstance()))) Band.log("Time synced")
    }

    fun refresh() = scope.launch {
        if (!ready) return@launch
        read(Uuids.BATTERY)?.let { Proto.parseBattery(it) }?.let { Band.battery.value = it }
        read(Uuids.STEPS)?.let { Proto.parseActivity(it) }?.let { Band.activity.value = it }
    }

    fun measureHeartRate() = scope.launch {
        if (!ready) return@launch
        Band.measuringHr.value = true
        Band.log("Measuring heart rate (keep the band on your wrist)…")
        write(Uuids.HR_CONTROL, Proto.HR_STOP_CONTINUOUS)
        write(Uuids.HR_CONTROL, Proto.HR_STOP_MANUAL)
        write(Uuids.HR_CONTROL, Proto.HR_START_MANUAL)
        delay(45_000)
        if (Band.measuringHr.value) {
            Band.measuringHr.value = false
            Band.log("No heart-rate reading — wear the band snugly and try again")
        }
    }

    fun findBand(on: Boolean) {
        findJob?.cancel()
        Band.finding.value = on
        if (!on) {
            scope.launch { write(Uuids.ALERT_LEVEL, Proto.ALERT_OFF, Uuids.IMMEDIATE_ALERT_SERVICE) }
            return
        }
        // The band buzzes briefly per command, so repeat until stopped (max ~1 min).
        findJob = scope.launch {
            repeat(20) {
                if (!ready) return@repeat
                write(Uuids.ALERT_LEVEL, Proto.FIND_START, Uuids.IMMEDIATE_ALERT_SERVICE)
                delay(3_000)
            }
            Band.finding.value = false
        }
    }

    fun sendNotification(pkg: String, appName: String, title: String, body: String) = scope.launch {
        if (!ready) return@launch
        val payload = Proto.notification(Proto.targetFor(pkg), appName, title, body)
        val ok = sendChunked(payload)
        Band.log(if (ok) "Sent: $appName — ${title.take(24)}" else "Failed to send $appName notification")
    }

    private suspend fun sendChunked(payload: ByteArray, type: Int = 0): Boolean {
        var ok = true
        for (chunk in Proto.chunks(type, payload, mtu)) ok = ok && write(Uuids.CHUNKED, chunk)
        return ok
    }

    // --- Settings, alarms, weather -----------------------------------------------------------

    /** Pushes every band setting. Returns false if any write failed. */
    suspend fun applySettings(settings: BandSettings): Boolean {
        if (!ready) return false
        var failed = 0
        for ((target, bytes) in settings.commands()) {
            val uuid = when (target) { "user" -> Uuids.USER_SETTINGS; "hr" -> Uuids.HR_CONTROL; else -> Uuids.CONFIG }
            if (!write(uuid, bytes)) failed++
        }
        Band.log(if (failed == 0) "Band settings applied" else "$failed band settings failed")
        return failed == 0
    }

    /** Writes all 10 alarm slots, so the band holds exactly [alarms]. */
    suspend fun applyAlarms(alarms: List<Alarm>): Boolean {
        if (!ready) return false
        var ok = true
        for (slot in 0 until 10) {
            val a = alarms.firstOrNull { it.slot == slot }
            val bytes = if (a != null) Cmd.alarm(slot, a.enabled, a.time, a.days)
            else Cmd.alarm(slot, false, Clock(0), 0, used = false)
            ok = write(Uuids.CONFIG, bytes) && ok
        }
        Band.log(if (ok) "Alarms saved on band (${alarms.count { it.enabled }} on)" else "Some alarms failed to save")
        return ok
    }

    suspend fun sendWeather(f: Forecast): Boolean {
        if (!ready) return false
        var ok = true
        for (p in WeatherApi.payloads(f)) ok = sendChunked(p, type = 1) && ok
        Band.log(if (ok) "Weather sent: ${f.tempNow}° ${WeatherApi.label(f.codeNow)}, ${f.place}" else "Weather send failed")
        return ok
    }

    // --- Step & sleep history ----------------------------------------------------------------

    private fun onHistoryPacket(v: ByteArray) {
        if (v.isEmpty()) return
        synchronized(fetchBuf) {
            val counter = v[0].toInt() and 0xff
            if (counter == ((fetchCounter + 1) and 0xff)) {
                fetchCounter = counter
                fetchBuf.write(v, 1, v.size - 1)
                Band.syncing.value = (fetchBuf.size().toFloat() / fetchExpected).coerceIn(0f, 1f)
            } else {
                fetchOk = false
            }
        }
    }

    /**
     * Downloads minute-by-minute data (steps, sleep, heart rate) recorded since the last sync.
     * Data is left on the band (no "delete" ack), so Zepp Life could still read it later.
     */
    suspend fun syncHistory(): Boolean = syncLock.withLock {
        if (!ready) return false
        Band.syncing.value = 0f
        try {
            if (!subscribe(Uuids.ACTIVITY_CONTROL) || !subscribe(Uuids.ACTIVITY_DATA)) return false
            val store = Store.get(ctx)
            val now = System.currentTimeMillis()
            var from = Saved.lastSync(ctx).takeIf { it > 0 } ?: (now - 30L * 24 * 3600 * 1000)
            var total = 0
            repeat(12) {
                while (control.tryReceive().isSuccess) { /* drop stale replies */ }
                if (!write(Uuids.ACTIVITY_CONTROL, Fetch.request(from))) return false
                val reply = withTimeoutOrNull(15_000) { control.receive() } ?: run { Band.log("Sync: no reply"); return false }
                val start = Fetch.parseStart(reply) ?: run { Band.log("Sync refused: ${Proto.hex(reply)}"); return false }
                if (start.bytes == 0) {
                    Band.log(if (total == 0) "History up to date" else "Synced $total minutes of history")
                    return true
                }
                synchronized(fetchBuf) { fetchBuf.reset(); fetchCounter = -1; fetchOk = true; fetchExpected = start.bytes }
                if (!write(Uuids.ACTIVITY_CONTROL, Fetch.START_TRANSFER)) return false
                val end = withTimeoutOrNull(180_000) { control.receive() } ?: run { Band.log("Sync timed out"); return false }
                val data = synchronized(fetchBuf) { fetchBuf.toByteArray() }
                if (!fetchOk || Fetch.isFailure(end)) { Band.log("Sync broke off: ${Proto.hex(end)}"); return false }
                val samples = Fetch.parseSamples(data)
                val startMinute = start.startMillis / 60_000
                store.insert(startMinute, samples)
                total += samples.size
                from = (startMinute + samples.size) * 60_000
                Saved.setLastSync(ctx, from)
                Band.dataVersion.value++
                if (samples.isEmpty() || from >= System.currentTimeMillis() - 60_000) {
                    Band.log("Synced $total minutes of history")
                    return true
                }
            }
            Band.log("Synced $total minutes of history")
            true
        } finally {
            Band.syncing.value = null
        }
    }

    fun sendCall(caller: String) = scope.launch {
        if (!ready) return@launch
        write(Uuids.NEW_ALERT, Proto.callAlert(caller))
        Band.log("Call alert: $caller")
    }

    fun stopCall() = scope.launch {
        if (!ready) return@launch
        write(Uuids.NEW_ALERT, Proto.CALL_STOP)
        Band.log("Call screen cleared on band")
    }

    private fun short(u: UUID) = u.toString().substring(4, 8)
}
