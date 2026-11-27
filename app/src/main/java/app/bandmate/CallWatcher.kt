package app.bandmate

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Watches the phone's call state (needs READ_PHONE_STATE) and shows incoming calls on the band.
 * The caller's name comes from the dialer's call notification when [NotifListener] sees one.
 */
class CallWatcher(private val ctx: Context, private val scope: CoroutineScope) {
    private val tm = ctx.getSystemService(TelephonyManager::class.java)
    private var callback: Any? = null
    private var ringJob: Job? = null
    private var ringing = false

    /** Returns false when the phone permission is missing, so the caller can try again later. */
    fun start(): Boolean {
        if (ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            Band.log("Call alerts off: phone permission not granted")
            return false
        }
        if (Build.VERSION.SDK_INT >= 31) {
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) = onState(state)
            }
            tm.registerTelephonyCallback(ctx.mainExecutor, cb)
            callback = cb
        } else {
            @Suppress("DEPRECATION")
            val l = object : PhoneStateListener() {
                @Deprecated("Android 11 and older")
                override fun onCallStateChanged(state: Int, number: String?) = onState(state)
            }
            @Suppress("DEPRECATION")
            tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
            callback = l
        }
        Band.onRejectCall = ::rejectCall
        Band.log("Call alerts on")
        return true
    }

    fun stop() {
        when (val cb = callback) {
            is TelephonyCallback -> if (Build.VERSION.SDK_INT >= 31) tm.unregisterTelephonyCallback(cb)
            is PhoneStateListener -> @Suppress("DEPRECATION") tm.listen(cb, PhoneStateListener.LISTEN_NONE)
        }
        callback = null
        ringJob?.cancel()
        Band.onRejectCall = null
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun rejectCall() {
        if (Build.VERSION.SDK_INT < 28 ||
            ctx.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED
        ) {
            Band.log("Can't end the call: 'manage calls' permission not granted")
            return
        }
        @Suppress("DEPRECATION")
        val ended = runCatching { ctx.getSystemService(TelecomManager::class.java).endCall() }.getOrDefault(false)
        Band.log(if (ended) "Call rejected from band" else "Phone refused to end the call")
    }

    private fun onState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                if (ringing) return
                ringing = true
                ringJob?.cancel()
                ringJob = scope.launch {
                    // Give the dialer a moment to post its notification so we know who is calling.
                    repeat(15) {
                        if (Band.callerName(maxAgeMs = 5_000) != null) return@repeat
                        delay(100)
                    }
                    val name = Band.callerName(maxAgeMs = 5_000) ?: "Incoming call"
                    Band.client?.sendCall(name)
                }
            }
            else -> { // answered (OFFHOOK) or ended (IDLE)
                if (!ringing) return
                ringing = false
                ringJob?.cancel()
                Band.client?.stopCall()
                Band.log("Call ${if (state == TelephonyManager.CALL_STATE_OFFHOOK) "answered" else "ended"}")
            }
        }
    }
}
