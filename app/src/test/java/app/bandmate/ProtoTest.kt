package app.bandmate

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ProtoTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test fun parseKey_acceptsCommonForms() {
        val k = "000102030405060708090a0b0c0d0e0f"
        assertArrayEquals(unhex(k), Proto.parseKey(k))
        assertArrayEquals(unhex(k), Proto.parseKey("0x$k"))
        assertArrayEquals(unhex(k), Proto.parseKey("  0X" + k.uppercase() + "\n"))
        assertNull(Proto.parseKey("0x1234"))
        assertNull(Proto.parseKey("zz0102030405060708090a0b0c0d0e0f"))
    }

    @Test fun answerChallenge_isAesEcb() {
        // FIPS-197 AES-128 test vector
        val key = unhex("000102030405060708090a0b0c0d0e0f")
        val plain = unhex("00112233445566778899aabbccddeeff")
        val out = Proto.answerChallenge(plain, key)
        assertArrayEquals(bytes(0x83, 0x00) + unhex("69c4e0d86a7b0430d8cdb78070b4c55a"), out)
    }

    @Test fun parseAuth_steps() {
        val challenge = bytes(0x10, 0x82, 0x01) + ByteArray(16) { it.toByte() }
        val r = Proto.parseAuth(challenge)
        assertTrue(r is AuthReply.Challenge)
        assertArrayEquals(ByteArray(16) { it.toByte() }, (r as AuthReply.Challenge).random)
        assertEquals(AuthReply.Success, Proto.parseAuth(bytes(0x10, 0x83, 0x01)))
        assertEquals(AuthReply.WrongKey, Proto.parseAuth(bytes(0x10, 0x83, 0x04)))
        assertEquals(AuthReply.Unknown, Proto.parseAuth(bytes(0x01, 0x02)))
    }

    @Test fun requestChallenge_matchesMiBand4() {
        assertArrayEquals(bytes(0x82, 0x00, 0x02, 0x01, 0x00), Proto.requestChallenge())
    }

    @Test fun timeBytes_layout() {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"))
        cal.set(2026, Calendar.NOVEMBER, 29, 13, 5, 9) // Sunday
        assertArrayEquals(bytes(0xea, 0x07, 11, 29, 13, 5, 9, 7, 0, 0, 22), Proto.timeBytes(cal))
    }

    @Test fun battery_and_activity_and_hr() {
        assertEquals(Battery(76, false), Proto.parseBattery(bytes(0x0f, 76, 0)))
        assertEquals(Battery(40, true), Proto.parseBattery(bytes(0x0f, 40, 1)))
        val act = bytes(0x0c, 0x10, 0x27, 0, 0, 0xe8, 0x03, 0, 0, 0x2c, 0x01, 0, 0)
        assertEquals(Activity(10000, 1000, 300), Proto.parseActivity(act))
        assertNull(Proto.parseActivity(bytes(0x0c, 1, 2)))
        assertEquals(72, Proto.parseHeartRate(bytes(0x00, 72)))
        assertNull(Proto.parseHeartRate(bytes(0x00, 0)))
    }

    @Test fun chunks_flagsAndSizes() {
        val one = Proto.chunks(0, ByteArray(10) { 1 })
        assertEquals(1, one.size)
        assertArrayEquals(bytes(0, 0xc0, 0), one[0].copyOfRange(0, 3))

        val data = ByteArray(40) { it.toByte() }
        val c = Proto.chunks(0, data)
        assertEquals(3, c.size) // 17 + 17 + 6
        assertArrayEquals(bytes(0, 0x00, 0), c[0].copyOfRange(0, 3))
        assertArrayEquals(bytes(0, 0x40, 1), c[1].copyOfRange(0, 3))
        assertArrayEquals(bytes(0, 0x80, 2), c[2].copyOfRange(0, 3))
        assertTrue(c.all { it.size <= 20 })
        assertArrayEquals(data, c.flatMap { it.drop(3) }.toByteArray())
    }

    @Test fun notification_customApp() {
        val p = Proto.notification(Proto.Target(Proto.Category.CUSTOM, Proto.Icon.WHATSAPP), "WhatsApp", "Alex", "Call me")
        assertArrayEquals(bytes(0xfa, 0, 0, 0, 0, 1, 7), p.copyOfRange(0, 7))
        assertEquals("Alex\u0000Call me\u0000WhatsApp\u0000", String(p, 7, p.size - 7))
    }

    @Test fun notification_genericIconPutsTitleInBody() {
        val p = Proto.notification(Proto.Target(Proto.Category.CUSTOM, Proto.Icon.APP), "Kharcha", "Spent", "₹160")
        assertEquals("-\u0000Spent\n₹160\u0000Kharcha\u0000", String(p, 7, p.size - 7))
    }

    @Test fun notification_smsAndLengthLimit() {
        val p = Proto.notification(Proto.Target(Proto.Category.SMS, 0), "Messages", "Bank", "x".repeat(1000))
        assertArrayEquals(bytes(5, 0, 0, 0, 0, 1), p.copyOfRange(0, 6))
        assertEquals(Proto.MAX_MESSAGE + 3, p.size) // message capped, then "\0 \0"
    }

    @Test fun utf8Truncate_neverSplitsCharacters() {
        val s = "ab₹₹" // ₹ is 3 bytes
        assertEquals("ab₹", String(Proto.utf8Truncate(s, 6)))
        assertEquals("ab", String(Proto.utf8Truncate(s, 4)))
    }

    @Test fun targetFor_mapsApps() {
        assertEquals(Proto.Icon.WHATSAPP, Proto.targetFor("com.whatsapp").icon)
        assertEquals(Proto.Category.SMS, Proto.targetFor("com.google.android.apps.messaging").category)
        assertEquals(Proto.Category.EMAIL, Proto.targetFor("com.google.android.gm").category)
        assertEquals(Proto.Icon.APP, Proto.targetFor("app.kharcha").icon)
    }

    @Test fun callAlert_format() {
        assertArrayEquals(bytes(3, 1) + "Alex".toByteArray(), Proto.callAlert("Alex"))
        assertArrayEquals(bytes(3, 0), Proto.CALL_STOP)
        assertEquals(2 + 40, Proto.callAlert("x".repeat(100)).size)
    }
}
