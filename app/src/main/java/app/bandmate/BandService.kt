package app.bandmate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Keeps the Bluetooth connection alive in the background (shown as a quiet ongoing notification). */
class BandService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var calls: CallWatcher? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Band connection", NotificationManager.IMPORTANCE_MIN)
        )
        startInForeground(Band.state.value.label)
        scope.launch {
            Band.state.collectLatest { s ->
                val battery = Band.battery.value?.let { " · ${it.percent}%" } ?: ""
                nm.notify(NOTIF_ID, buildNotification(s.label + battery))
            }
        }
        // Step summaries: check once a minute while connected.
        scope.launch {
            Band.state.collectLatest { s ->
                if (s != Band.State.Ready) return@collectLatest
                delay(10_000) // let the connect-time sync finish first
                while (true) {
                    runCatching { StepSummary.tick(applicationContext) }
                        .onFailure { Band.log("Step summary failed: ${it.message}") }
                    delay(60_000L - System.currentTimeMillis() % 60_000L)
                }
            }
        }
        // After each (re)connect: push settings/alarms, pull history, send weather; then hourly.
        scope.launch {
            Band.state.collectLatest { s ->
                if (s != Band.State.Ready) return@collectLatest
                delay(2_000)
                Actions.housekeeping(applicationContext, firstAfterConnect = true)
                while (true) {
                    delay(60 * 60 * 1000L)
                    Actions.housekeeping(applicationContext, firstAfterConnect = false)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val address = Band.address(this)
        val key = Proto.parseKey(Band.keyText(this))
        if (address == null || key == null) {
            Band.log("Set the band and auth key first")
            stopSelf()
            return START_NOT_STICKY
        }
        if (Band.client == null) {
            Band.client = BandClient(applicationContext, scope, address, key).also { it.connect() }
        }
        if (calls == null) {
            // Retried on every start command, so granting the permission later still turns call alerts on.
            val w = CallWatcher(applicationContext, scope)
            if (w.start()) calls = w
        }
        return START_STICKY
    }

    override fun onDestroy() {
        calls?.stop()
        calls = null
        Band.client?.close()
        Band.client = null
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground(text: String) {
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("BandMate")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "connection"
        private const val NOTIF_ID = 1

        fun start(ctx: Context) =
            ContextCompat.startForegroundService(ctx, Intent(ctx, BandService::class.java))

        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, BandService::class.java))
    }
}

/** Reconnects after a phone restart or app update, if the user turned that on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val ok = intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (ok && Band.autoStart(ctx) && Band.address(ctx) != null && Proto.parseKey(Band.keyText(ctx)) != null) {
            BandService.start(ctx)
        }
    }
}
