package app.bandmate

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Calendar
import java.util.TimeZone

/** Minutes since midnight, e.g. 22:30 = 1350. */
@JvmInline
value class Clock(val minutes: Int) {
    val hour get() = minutes / 60
    val minute get() = minutes % 60
    override fun toString() = "%02d:%02d".format(hour, minute)
    companion object { fun of(h: Int, m: Int) = Clock(h * 60 + m) }
}

/**
 * Settings commands for the Mi Band 4. Most go to the configuration characteristic (0003);
 * the ones marked otherwise go to user settings (0008) or the heart-rate control point.
 * Byte layouts were learned from Gadgetbridge's Huami support.
 */
object Cmd {
    private const val DISPLAY: Byte = 0x06
    private fun flag(on: Boolean): Byte = if (on) 1 else 0

    fun timeFormat24h(on: Boolean) = byteArrayOf(DISPLAY, 0x02, 0x00, flag(on))

    /** Show the date under the clock on the band's home screen. */
    fun showDate(on: Boolean) = byteArrayOf(DISPLAY, 0x0a, 0x00, if (on) 0x03 else 0x00)

    /** One of "dd/MM/yyyy", "MM/dd/yyyy", "dd.MM.yyyy". */
    fun dateFormat(format: String): ByteArray {
        require(format.length == 10) { "date format must be 10 characters" }
        return byteArrayOf(DISPLAY, 30, 0x00) + format.toByteArray(Charsets.US_ASCII)
    }

    enum class Mode { OFF, ON, SCHEDULED }

    fun liftWrist(mode: Mode, start: Clock, end: Clock) = when (mode) {
        Mode.OFF -> byteArrayOf(DISPLAY, 0x05, 0x00, 0x00)
        Mode.ON -> byteArrayOf(DISPLAY, 0x05, 0x00, 0x01, 0, 0, 0, 0)
        Mode.SCHEDULED -> byteArrayOf(DISPLAY, 0x05, 0x00, 0x01,
            start.hour.toByte(), start.minute.toByte(), end.hour.toByte(), end.minute.toByte())
    }

    fun liftWristSensitive(on: Boolean) = byteArrayOf(DISPLAY, 0x23, 0x00, flag(on))

    /** Night mode dims the screen. ON here means "from sunset". */
    fun nightMode(mode: Mode, start: Clock, end: Clock) = when (mode) {
        Mode.OFF -> byteArrayOf(0x1a, 0x00)
        Mode.ON -> byteArrayOf(0x1a, 0x02)
        Mode.SCHEDULED -> byteArrayOf(0x1a, 0x01,
            start.hour.toByte(), start.minute.toByte(), end.hour.toByte(), end.minute.toByte())
    }

    fun rotateWristToSwitch(on: Boolean) = byteArrayOf(DISPLAY, 0x0d, 0x00, flag(on))
    fun goalCelebration(on: Boolean) = byteArrayOf(DISPLAY, 0x06, 0x00, flag(on))
    fun disconnectAlert(on: Boolean) = byteArrayOf(DISPLAY, 0x0c, 0x00, flag(on), 0, 0, 0, 0)

    /** Do Not Disturb. ON here means "automatic" (when the band thinks you're asleep). */
    fun doNotDisturb(mode: Mode, start: Clock, end: Clock) = when (mode) {
        Mode.OFF -> byteArrayOf(0x09, 0x82.toByte())
        Mode.ON -> byteArrayOf(0x09, 0x83.toByte())
        Mode.SCHEDULED -> byteArrayOf(0x09, 0x81.toByte(),
            start.hour.toByte(), start.minute.toByte(), end.hour.toByte(), end.minute.toByte())
    }

    /** "Time to move" buzz after [afterMinutes] of sitting, only between [start] and [end]. */
    fun idleAlert(on: Boolean, afterMinutes: Int, start: Clock, end: Clock): ByteArray =
        if (!on) byteArrayOf(0x08, 0x00, 0x3c, 0x00, 0x04, 0x00, 0x15, 0x00, 0, 0, 0, 0)
        else byteArrayOf(0x08, 0x01, afterMinutes.coerceIn(15, 120).toByte(), 0x00,
            start.hour.toByte(), start.minute.toByte(), end.hour.toByte(), end.minute.toByte(), 0, 0, 0, 0)

    /** Heart-rate control point: all-day measuring every N minutes (0 = off). */
    fun heartRateInterval(minutes: Int) = byteArrayOf(0x14, minutes.coerceIn(0, 60).toByte())

    /** Heart-rate control point: use heart rate to improve sleep detection. */
    fun sleepHeartRateAssist(on: Boolean) = byteArrayOf(0x15, 0x00, flag(on))

    /** User-settings characteristic (0008). */
    fun wearOnRightWrist(right: Boolean) = byteArrayOf(0x20, 0x00, 0x00, if (right) 0x82.toByte() else 0x02)

    /** User-settings characteristic (0008). */
    fun stepGoal(steps: Int): ByteArray {
        val g = steps.coerceIn(1000, 50000)
        return byteArrayOf(0x10, 0x00, 0x00, (g and 0xff).toByte(), (g shr 8).toByte(), 0x00, 0x00)
    }

    /** The band's swipe-through screens, with their protocol slot ids. */
    enum class Screen(val id: Int, val label: String) {
        STATUS(5, "Status"), HEART(6, "Heart rate"), WORKOUT(3, "Workout"), WEATHER(2, "Weather"),
        NOTIFICATIONS(1, "Notifications"), MORE(4, "More"), TIMER(7, "Timer"), NFC(8, "NFC"),
    }

    /**
     * Which screens show, in [shown] order. Layout: [0x0a, mask lo, mask hi, clock pos, pos(id1)..pos(id8)].
     * Shown screens get the first positions in order; hidden ones follow.
     */
    fun screens(shown: List<Screen>): ByteArray {
        val cmd = ByteArray(Screen.entries.size + 4)
        cmd[0] = 0x0a
        var mask = 0x01 // the clock is always shown
        var pos = 1
        for (s in shown.distinct()) { mask = mask or (1 shl s.id); cmd[3 + s.id] = (pos++).toByte() }
        for (s in Screen.entries) if (s !in shown) cmd[3 + s.id] = (pos++).toByte()
        cmd[1] = (mask and 0xff).toByte()
        cmd[2] = (mask shr 8).toByte()
        return cmd
    }

    /**
     * One alarm slot (0..9). [days] bit 0 = Monday … bit 6 = Sunday; 0 = ring once.
     * Layout: [2, enabled<<7 | slot, hour, minute, days (128 = once)].
     */
    fun alarm(slot: Int, enabled: Boolean, time: Clock, days: Int, used: Boolean = true): ByteArray {
        val action = if (used && enabled) 0x80 else 0
        val dayMask = if (!used) 0 else if (days and 0x7f == 0) 128 else days and 0x7f
        return byteArrayOf(0x02, (action or slot).toByte(), time.hour.toByte(), time.minute.toByte(), dayMask.toByte())
    }
}

// --- Step & sleep history ------------------------------------------------------------------

data class RawSample(val kind: Int, val intensity: Int, val steps: Int, val heartRate: Int)
data class FetchStart(val bytes: Int, val startMillis: Long)

object Fetch {
    /** Ask for minute-by-minute activity since [fromMillis]: [1, 1, year LE, month, day, h, m, 0, tz]. */
    fun request(fromMillis: Long, tz: TimeZone = TimeZone.getDefault()): ByteArray {
        val c = Calendar.getInstance(tz).apply { timeInMillis = fromMillis }
        val year = c.get(Calendar.YEAR)
        val q = tz.getOffset(fromMillis) / (15 * 60 * 1000)
        return byteArrayOf(0x01, 0x01, (year and 0xff).toByte(), (year shr 8).toByte(),
            (c.get(Calendar.MONTH) + 1).toByte(), c.get(Calendar.DAY_OF_MONTH).toByte(),
            c.get(Calendar.HOUR_OF_DAY).toByte(), c.get(Calendar.MINUTE).toByte(), 0, q.toByte())
    }

    val START_TRANSFER = byteArrayOf(0x02)

    /** Band's answer to [request]: [0x10, 1, 1, length u32, year LE, month, day, h, m, s, tz?]. */
    fun parseStart(v: ByteArray): FetchStart? {
        if (v.size < 14 || v[0].toInt() != 0x10 || v[1].toInt() != 0x01 || v[2].toInt() != 0x01) return null
        val len = (v[3].toInt() and 0xff) or ((v[4].toInt() and 0xff) shl 8) or
            ((v[5].toInt() and 0xff) shl 16) or ((v[6].toInt() and 0xff) shl 24)
        val year = (v[7].toInt() and 0xff) or ((v[8].toInt() and 0xff) shl 8)
        val tzQuarters = if (v.size > 14) v[14].toInt() else TimeZone.getDefault().rawOffset / 900_000
        val ldt = LocalDateTime.of(year, v[9].toInt(), v[10].toInt(), v[11].toInt(), v[12].toInt(), v[13].toInt())
        val millis = ldt.toEpochSecond(ZoneOffset.ofTotalSeconds(tzQuarters * 900)) * 1000
        return FetchStart(len, millis)
    }

    /** True when the band says it has finished sending (or failed). */
    fun isTransferEnd(v: ByteArray) = v.size >= 3 && v[0].toInt() == 0x10 && v[1].toInt() == 0x02

    fun isFailure(v: ByteArray) = v.size >= 3 && v[0].toInt() == 0x10 && v[2].toInt() != 0x01

    /** 4 bytes per minute: kind, intensity, steps, heart rate. */
    fun parseSamples(data: ByteArray): List<RawSample> = (0 until data.size / 4).map { i ->
        val o = i * 4
        RawSample(data[o].toInt() and 0xff, data[o + 1].toInt() and 0xff,
            data[o + 2].toInt() and 0xff, data[o + 3].toInt() and 0xff)
    }
}

// --- Weather -------------------------------------------------------------------------------

data class WeatherDay(val code: Int, val max: Int, val min: Int, val text: String)

/** Weather payloads, sent through the chunked characteristic with type 1. */
object WeatherPayload {
    private fun header(type: Int, tsSec: Long, tzQuarters: Int, extra: Int) =
        ByteBuffer.allocate(6 + extra).order(ByteOrder.LITTLE_ENDIAN)
            .put(type.toByte()).putInt(tsSec.toInt()).put(tzQuarters.toByte())

    fun current(tsSec: Long, tzQuarters: Int, code: Int, temp: Int, text: String): ByteArray {
        val t = text.toByteArray()
        return header(2, tsSec, tzQuarters, 2 + t.size + 1)
            .put(code.toByte()).put(temp.toByte()).put(t).put(0).array()
    }

    /** [days] starts with today; the band shows up to 7. */
    fun forecast(tsSec: Long, tzQuarters: Int, days: List<WeatherDay>): ByteArray {
        val d = days.take(7)
        val texts = d.map { it.text.toByteArray() }
        val buf = header(1, tsSec, tzQuarters, 1 + d.size * 5 + texts.sumOf { it.size })
        buf.put(d.size.toByte())
        d.forEachIndexed { i, day ->
            buf.put(day.code.toByte()).put(day.code.toByte()).put(day.max.toByte()).put(day.min.toByte())
                .put(texts[i]).put(0)
        }
        return buf.array()
    }

    fun location(name: String) = byteArrayOf(8) + name.take(30).toByteArray() + byteArrayOf(0)
}
