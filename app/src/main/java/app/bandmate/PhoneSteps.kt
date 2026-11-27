package app.bandmate

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

/**
 * The phone's own step count, read from Android's Health Connect (Google Fit and OnePlus Health
 * write there). Read-only: BandMate never writes to Health Connect.
 */
object PhoneSteps {
    val PERMISSIONS = setOf(HealthPermission.getReadPermission(StepsRecord::class))

    fun available(ctx: Context) = HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE

    suspend fun granted(ctx: Context): Boolean = available(ctx) && runCatching {
        HealthConnectClient.getOrCreate(ctx).permissionController.getGrantedPermissions().containsAll(PERMISSIONS)
    }.getOrDefault(false)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("band", Context.MODE_PRIVATE)
    fun enabled(ctx: Context) = prefs(ctx).getBoolean("phoneSteps", true)
    fun setEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("phoneSteps", on).apply()

    /** Phone steps per hour for [hours] hours starting at the beginning of [first]. Zeros if unavailable. */
    suspend fun hourly(ctx: Context, first: LocalDate, hours: Int, zone: ZoneId = ZoneId.systemDefault()): IntArray =
        withContext(Dispatchers.IO) {
            val out = IntArray(hours)
            if (!enabled(ctx) || !granted(ctx)) return@withContext out
            val start = first.atStartOfDay(zone).toInstant()
            val end = start.plus(Duration.ofHours(hours.toLong()))
            runCatching {
                HealthConnectClient.getOrCreate(ctx).aggregateGroupByDuration(
                    AggregateGroupByDurationRequest(
                        metrics = setOf(StepsRecord.COUNT_TOTAL),
                        timeRangeFilter = TimeRangeFilter.between(start, end),
                        timeRangeSlicer = Duration.ofHours(1),
                    )
                ).forEach { bucket ->
                    val i = Duration.between(start, bucket.startTime).toHours().toInt()
                    if (i in 0 until hours) out[i] = (bucket.result[StepsRecord.COUNT_TOTAL] ?: 0L).toInt()
                }
            }.onFailure { Band.log("Phone steps unavailable: ${it.message}") }
            out
        }
}

/** Band and phone steps side by side, merged without counting the same step twice. */
data class StepTotals(val band: Int, val phone: Int, val merged: Int)

object Steps {
    /**
     * When you carry the phone AND wear the band, both count the same walk, so for each hour we keep
     * whichever counted more instead of adding them up.
     */
    fun merge(band: IntArray, phone: IntArray) = IntArray(maxOf(band.size, phone.size)) { i ->
        maxOf(band.getOrElse(i) { 0 }, phone.getOrElse(i) { 0 })
    }

    /** Per-hour (band, phone, merged) for the 24 hours of [day]. */
    suspend fun hoursOf(ctx: Context, day: LocalDate): Triple<IntArray, IntArray, IntArray> {
        val band = withContext(Dispatchers.IO) { Store.get(ctx).stepsByHour(day) }
        val phone = PhoneSteps.hourly(ctx, day, 24)
        return Triple(band, phone, merge(band, phone))
    }

    /** Per-day totals for [days] days from [first]; each day = sum of hourly maxima. */
    suspend fun daysOf(ctx: Context, first: LocalDate, days: Int): List<StepTotals> {
        val band = withContext(Dispatchers.IO) { Store.get(ctx).stepsByHourRange(first, days * 24) }
        val phone = PhoneSteps.hourly(ctx, first, days * 24)
        val merged = merge(band, phone)
        return List(days) { d ->
            val r = d * 24 until d * 24 + 24
            StepTotals(r.sumOf { band[it] }, r.sumOf { phone[it] }, r.sumOf { merged[it] })
        }
    }
}
