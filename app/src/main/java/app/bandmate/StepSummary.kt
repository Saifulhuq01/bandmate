package app.bandmate

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Puts today's combined (band + phone) step count on the band as a message, because the band's
 * own counter can't be changed from the phone. Sent on demand, every evening, and optionally hourly.
 */
object StepSummary {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("summary", Context.MODE_PRIVATE)

    fun eveningOn(ctx: Context) = prefs(ctx).getBoolean("evening", true)
    fun setEveningOn(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("evening", on).apply()
    fun eveningTime(ctx: Context) = Clock(prefs(ctx).getInt("eveningTime", 21 * 60))
    fun setEveningTime(ctx: Context, c: Clock) = prefs(ctx).edit().putInt("eveningTime", c.minutes).apply()
    fun hourlyOn(ctx: Context) = prefs(ctx).getBoolean("hourly", true)
    fun setHourlyOn(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("hourly", on).apply()

    /** Hourly updates only during waking hours, and only if you walked at least this much since the last one. */
    val HOURLY_FROM: LocalTime = LocalTime.of(8, 0)
    val HOURLY_TO: LocalTime = LocalTime.of(22, 0)
    const val MIN_NEW_STEPS = 100

    // --- Reading phone steps while BandMate is in the background -------------------------------

    val BACKGROUND_PERMISSION = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

    /** True if this phone's Health Connect lets apps read in the background at all. */
    fun backgroundSupported(ctx: Context): Boolean = PhoneSteps.available(ctx) && runCatching {
        HealthConnectClient.getOrCreate(ctx).features.getFeatureStatus(
            HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND
        ) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    }.getOrDefault(false)

    suspend fun backgroundGranted(ctx: Context): Boolean = backgroundSupported(ctx) && runCatching {
        BACKGROUND_PERMISSION in HealthConnectClient.getOrCreate(ctx).permissionController.getGrantedPermissions()
    }.getOrDefault(false)

    // --- The message --------------------------------------------------------------------------

    /** Today's totals, topping up the current hour with the band's live counter (newer than its synced history). */
    suspend fun today(ctx: Context): StepTotals {
        val (bandHours, phoneHours, _) = Steps.hoursOf(ctx, LocalDate.now())
        val live = Band.activity.value?.steps ?: 0
        val gap = live - bandHours.sum()
        if (gap > 0) bandHours[LocalTime.now().hour] += gap
        val merged = Steps.merge(bandHours, phoneHours)
        return StepTotals(bandHours.sum(), phoneHours.sum(), merged.sum())
    }

    fun message(t: StepTotals, goal: Int): Pair<String, String> {
        val pct = if (goal > 0) t.merged * 100 / goal else 0
        val km = t.merged * 0.75 / 1000
        val title = "%,d steps today".format(t.merged)
        val body = buildString {
            append("%.1f km · %d%% of %,d goal".format(km, pct, goal))
            if (t.phone > 0) append("\nBand %,d · Phone %,d".format(t.band, t.phone))
            if (t.merged >= goal) append("\nGoal reached!")
        }
        return title to body
    }

    /** Sends the summary now. Returns false if the band isn't connected. */
    suspend fun send(ctx: Context, reason: String): Boolean {
        val client = Band.client ?: return false
        if (Band.state.value != Band.State.Ready) return false
        val t = today(ctx)
        val (title, body) = message(t, Saved.settings(ctx).stepGoal)
        client.sendNotification(ctx.packageName, "BandMate", title, body).join()
        prefs(ctx).edit().putInt("lastSentSteps", t.merged).putString("lastSentDay", LocalDate.now().toString()).apply()
        Band.log("Step summary ($reason): ${t.merged} steps")
        return true
    }

    /** Called once a minute by the service: sends the evening and hourly summaries when they're due. */
    suspend fun tick(ctx: Context, now: LocalDateTime = LocalDateTime.now()) {
        val p = prefs(ctx)
        val today = now.toLocalDate().toString()

        val evening = eveningTime(ctx)
        val minuteOfDay = now.hour * 60 + now.minute
        if (eveningOn(ctx) && p.getString("eveningDay", null) != today && minuteOfDay >= evening.minutes) {
            if (send(ctx, "evening")) p.edit().putString("eveningDay", today).apply()
            return
        }

        // First check in each new hour (the phone may sleep through minute :00 exactly).
        if (hourlyOn(ctx)) {
            val t = now.toLocalTime()
            if (t.isBefore(HOURLY_FROM) || t.isAfter(HOURLY_TO)) return
            val hourKey = "$today-${now.hour}"
            if (p.getString("hourKey", null) == hourKey) return
            val last = if (p.getString("lastSentDay", null) == today) p.getInt("lastSentSteps", 0) else 0
            val totals = today(ctx)
            p.edit().putString("hourKey", hourKey).apply()
            if (totals.merged - last >= MIN_NEW_STEPS) send(ctx, "hourly")
        }
    }
}
