package app.bandmate

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class BandCommandsTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun alarm_layout() {
        // Weekdays (Mon–Fri) at 06:30 in slot 2, enabled
        assertArrayEquals(bytes(2, 0x82, 6, 30, 0x1f), Cmd.alarm(2, true, Clock.of(6, 30), 0x1f))
        // One-off alarm → 128
        assertArrayEquals(bytes(2, 0x80, 7, 0, 128), Cmd.alarm(0, true, Clock.of(7, 0), 0))
        // Disabled keeps its days but drops the enable bit
        assertArrayEquals(bytes(2, 0x01, 7, 0, 0x60), Cmd.alarm(1, false, Clock.of(7, 0), 0x60))
        // Unused slot
        assertArrayEquals(bytes(2, 0x09, 0, 0, 0), Cmd.alarm(9, false, Clock(0), 0, used = false))
    }

    @Test fun screens_putShownFirstInOrder() {
        val cmd = Cmd.screens(listOf(Cmd.Screen.HEART, Cmd.Screen.STATUS, Cmd.Screen.MORE))
        assertEquals(12, cmd.size)
        assertEquals(0x0a, cmd[0].toInt())
        val mask = (cmd[1].toInt() and 0xff) or ((cmd[2].toInt() and 0xff) shl 8)
        assertEquals(0x01 or (1 shl 6) or (1 shl 5) or (1 shl 4), mask)
        assertEquals(1, cmd[3 + 6].toInt()) // heart first
        assertEquals(2, cmd[3 + 5].toInt()) // status second
        assertEquals(3, cmd[3 + 4].toInt()) // more third
        // every slot gets a unique position 1..8
        assertEquals((1..8).toSet(), (4..11).map { cmd[it].toInt() }.toSet())
    }

    @Test fun settings_bytes() {
        assertArrayEquals(bytes(6, 2, 0, 1), Cmd.timeFormat24h(true))
        assertArrayEquals(bytes(6, 30, 0) + "dd/MM/yyyy".toByteArray(), Cmd.dateFormat("dd/MM/yyyy"))
        assertArrayEquals(bytes(6, 5, 0, 1, 7, 0, 23, 30), Cmd.liftWrist(Cmd.Mode.SCHEDULED, Clock.of(7, 0), Clock.of(23, 30)))
        assertArrayEquals(bytes(9, 0x81, 23, 0, 7, 0), Cmd.doNotDisturb(Cmd.Mode.SCHEDULED, Clock.of(23, 0), Clock.of(7, 0)))
        assertArrayEquals(bytes(0x10, 0, 0, 0x40, 0x1f, 0, 0), Cmd.stepGoal(8000))
        assertArrayEquals(bytes(0x20, 0, 0, 0x82), Cmd.wearOnRightWrist(true))
        assertEquals(16, BandSettings().commands().size)
    }

    @Test fun settings_roundTripJson() {
        val s = BandSettings(time24h = false, dnd = Cmd.Mode.SCHEDULED, dndStart = Clock.of(22, 15),
            stepGoal = 12000, screens = listOf(Cmd.Screen.WEATHER, Cmd.Screen.MORE))
        assertEquals(s, BandSettings.fromJson(s.toJson()))
        assertEquals(BandSettings(), BandSettings.fromJson("not json"))
    }

    @Test fun fetch_requestAndStart() {
        val ist = TimeZone.getTimeZone("Asia/Kolkata")
        // 2026-11-27 18:05 IST
        val millis = java.time.ZonedDateTime.of(2026, 11, 27, 18, 5, 0, 0, ist.toZoneId()).toInstant().toEpochMilli()
        assertArrayEquals(bytes(1, 1, 0xea, 0x07, 11, 27, 18, 5, 0, 22), Fetch.request(millis, ist))

        val reply = bytes(0x10, 1, 1, 0x40, 0x0b, 0, 0, 0xea, 0x07, 11, 27, 18, 5, 0, 22)
        val start = Fetch.parseStart(reply)
        assertNotNull(start)
        assertEquals(0x0b40, start!!.bytes)
        assertEquals(millis, start.startMillis)
        assertNull(Fetch.parseStart(bytes(0x10, 1, 4)))
        assertTrue(Fetch.isTransferEnd(bytes(0x10, 2, 1)))
    }

    @Test fun fetch_samples() {
        val s = Fetch.parseSamples(bytes(1, 20, 35, 80, 9, 0, 0, 255))
        assertEquals(listOf(RawSample(1, 20, 35, 80), RawSample(9, 0, 0, 255)), s)
    }

    @Test fun weather_payloads() {
        val cur = WeatherPayload.current(0x01020304, 22, 1, 28, "Clear")
        assertArrayEquals(bytes(2, 4, 3, 2, 1, 22, 1, 28) + "Clear".toByteArray() + bytes(0), cur)
        val fc = WeatherPayload.forecast(0, 22, listOf(WeatherDay(0, 31, 22, "Sun"), WeatherDay(7, 29, 21, "Rain")))
        assertArrayEquals(
            bytes(1, 0, 0, 0, 0, 22, 2, 0, 0, 31, 22) + "Sun".toByteArray() + bytes(0, 7, 7, 29, 21) + "Rain".toByteArray() + bytes(0),
            fc,
        )
        assertArrayEquals(bytes(8) + "London".toByteArray() + bytes(0), WeatherPayload.location("London"))
        assertEquals(9, WeatherApi.bandCode(65))
        assertEquals(4, WeatherApi.bandCode(95))
    }

    private fun minutes(start: Long, kinds: List<Int>) = kinds.mapIndexed { i, k -> Sample(start + i, k, 0, 0, 0) }

    @Test fun sleep_findsNightAndStages() {
        val t0 = 1_000_000L
        // 30 min awake, 60 light, 90 deep (with "no change" 0s), 10 awake blip, 120 light, then up.
        val kinds = List(30) { 1 } + List(60) { 9 } + listOf(11) + List(89) { 0 } + List(10) { 1 } +
            List(120) { 9 } + List(60) { 1 }
        val night = Sleep.night(minutes(t0, kinds), t0, t0 + 2000)
        assertNotNull(night)
        night!!
        assertEquals(t0 + 30, night.startMinute)
        assertEquals(t0 + 30 + 60 + 90 + 10 + 120, night.endMinute)
        assertEquals(90, night.deep)
        assertEquals(180, night.light)
        assertEquals(10, night.awake)
        assertEquals(listOf(Stage.LIGHT, Stage.DEEP, Stage.AWAKE, Stage.LIGHT), night.segments.map { it.stage })
        assertTrue(night.score in 40..80)
    }

    @Test fun sleep_ignoresNaps() {
        val t0 = 0L
        assertNull(Sleep.night(minutes(t0, List(20) { 9 } + List(100) { 1 }), t0, t0 + 500))
    }
}
