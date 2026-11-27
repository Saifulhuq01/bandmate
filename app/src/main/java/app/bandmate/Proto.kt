package app.bandmate

import java.util.Calendar
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Mi Band 4 Bluetooth protocol: UUIDs plus pure encode/decode helpers.
 * No Android calls in here, so everything is covered by plain unit tests.
 * Protocol facts were learned from Gadgetbridge's Huami support (no code copied).
 */
object Uuids {
    private fun std(short: String) = UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")
    private fun huami(n: String) = UUID.fromString("0000$n-0000-3512-2118-0009af100700")

    val AUTH: UUID = huami("0009")
    val BATTERY: UUID = huami("0006")
    val STEPS: UUID = huami("0007")
    val DEVICE_EVENT: UUID = huami("0010")
    val CHUNKED: UUID = huami("0020")

    val HR_MEASURE: UUID = std("2a37")
    val HR_CONTROL: UUID = std("2a39")
    val CURRENT_TIME: UUID = std("2a2b")
    val ALERT_LEVEL: UUID = std("2a06")
    val NEW_ALERT: UUID = std("2a46")
    val IMMEDIATE_ALERT_SERVICE: UUID = std("1802")
    val CCCD: UUID = std("2902")
}

data class Battery(val percent: Int, val charging: Boolean)
data class Activity(val steps: Int, val meters: Int, val kcal: Int)

sealed interface AuthReply {
    /** Band sent a 16-byte challenge to encrypt with our key. */
    class Challenge(val random: ByteArray) : AuthReply
    data object Success : AuthReply
    data object WrongKey : AuthReply
    data object Unknown : AuthReply
}

object Proto {
    private const val AUTH_RESPONSE: Byte = 0x10
    private const val CRYPT_FLAGS = 0x80 // Mi Band 4 specific

    /** "Give me a random number" — first auth step for a band that already knows our key. */
    fun requestChallenge(): ByteArray = byteArrayOf((CRYPT_FLAGS or 0x02).toByte(), 0x00, 0x02, 0x01, 0x00)

    fun parseAuth(v: ByteArray): AuthReply {
        if (v.size < 3 || v[0] != AUTH_RESPONSE) return AuthReply.Unknown
        val step = v[1].toInt() and 0x0f
        val ok = v[2].toInt() == 0x01
        return when {
            step == 0x02 && ok && v.size >= 19 -> AuthReply.Challenge(v.copyOfRange(3, 19))
            step == 0x03 && ok -> AuthReply.Success
            step == 0x03 && v[2].toInt() == 0x04 -> AuthReply.WrongKey
            step == 0x02 && !ok -> AuthReply.WrongKey
            else -> AuthReply.Unknown
        }
    }

    fun answerChallenge(random: ByteArray, key: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/ECB/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return byteArrayOf((CRYPT_FLAGS or 0x03).toByte(), 0x00) + c.doFinal(random)
    }

    /** Accepts "0x" + 32 hex chars, or 32 hex chars, spaces ignored. Returns 16 bytes or null. */
    fun parseKey(text: String): ByteArray? {
        var s = text.filterNot { it.isWhitespace() }
        if (s.startsWith("0x", ignoreCase = true)) s = s.substring(2)
        if (s.length != 32 || s.any { Character.digit(it, 16) < 0 }) return null
        return ByteArray(16) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    /** Current Time characteristic value: year LE, month, day, h, m, s, weekday(Mon=1..Sun=7), 0, 0, tz in 15-min units. */
    fun timeBytes(cal: Calendar): ByteArray {
        val year = cal.get(Calendar.YEAR)
        val dow = cal.get(Calendar.DAY_OF_WEEK).let { if (it == Calendar.SUNDAY) 7 else it - 1 }
        val tz = cal.timeZone.getOffset(cal.timeInMillis) / (15 * 60 * 1000)
        return byteArrayOf(
            (year and 0xff).toByte(), (year shr 8).toByte(),
            (cal.get(Calendar.MONTH) + 1).toByte(),
            cal.get(Calendar.DAY_OF_MONTH).toByte(),
            cal.get(Calendar.HOUR_OF_DAY).toByte(),
            cal.get(Calendar.MINUTE).toByte(),
            cal.get(Calendar.SECOND).toByte(),
            dow.toByte(),
            0, 0, tz.toByte(),
        )
    }

    fun parseBattery(v: ByteArray): Battery? =
        if (v.size >= 3) Battery(v[1].toInt() and 0xff, v[2].toInt() == 1) else null

    fun parseActivity(v: ByteArray): Activity? {
        if (v.size < 13) return null
        return Activity(u32(v, 1), u32(v, 5), u32(v, 9))
    }

    /** Heart Rate Measurement (standard GATT): flags bit0 = 16-bit value. 0 bpm means "no reading". */
    fun parseHeartRate(v: ByteArray): Int? {
        if (v.size < 2) return null
        val bpm = if (v[0].toInt() and 1 == 0) v[1].toInt() and 0xff
        else if (v.size >= 3) (v[1].toInt() and 0xff) or ((v[2].toInt() and 0xff) shl 8) else return null
        return bpm.takeIf { it > 0 }
    }

    // HR control point commands
    val HR_STOP_CONTINUOUS = byteArrayOf(0x15, 0x01, 0x00)
    val HR_STOP_MANUAL = byteArrayOf(0x15, 0x02, 0x00)
    val HR_START_MANUAL = byteArrayOf(0x15, 0x02, 0x01)

    /** Incoming call screen for the New Alert characteristic: [3 (call), 1] + caller name. */
    fun callAlert(caller: String): ByteArray =
        byteArrayOf(Category.CALL.toByte(), 1) + utf8Truncate(caller.trim().ifEmpty { "Incoming call" }, 40)

    /** Clears the call screen and stops the band vibrating: [3 (call), 0 alerts]. */
    val CALL_STOP = byteArrayOf(Category.CALL.toByte(), 0)

    // Device events the band sends on characteristic 0010
    const val EVENT_CALL_REJECT: Byte = 0x07
    const val EVENT_CALL_IGNORE: Byte = 0x09

    val FIND_START = byteArrayOf(0x03)
    val ALERT_OFF = byteArrayOf(0x00)

    /**
     * Splits [data] into chunks for the "chunked transfer" characteristic.
     * Each chunk: [0, flags|type, index] + up to (mtu - 6) bytes.
     */
    fun chunks(type: Int, data: ByteArray, mtu: Int = 23): List<ByteArray> {
        val max = minOf(512, maxOf(23, mtu) - 3) - 3
        val out = ArrayList<ByteArray>()
        var offset = 0
        var index = 0
        while (offset < data.size) {
            val n = minOf(max, data.size - offset)
            val last = offset + n >= data.size
            var flags = 0
            if (last) {
                flags = 0x80
                if (index == 0) flags = flags or 0x40
            } else if (index > 0) {
                flags = 0x40
            }
            out += byteArrayOf(0, (flags or type).toByte(), (index and 0xff).toByte()) +
                data.copyOfRange(offset, offset + n)
            offset += n
            index++
        }
        return out
    }

    // --- Phone notifications -------------------------------------------------------------

    object Category { const val EMAIL = 1; const val CALL = 3; const val SMS = 5; const val CUSTOM = 0xfa }

    object Icon {
        const val WECHAT = 0; const val FACEBOOK = 3; const val TWITTER = 4; const val SNAPCHAT = 6
        const val WHATSAPP = 7; const val ALARM = 10; const val APP = 11; const val INSTAGRAM = 12
        const val CALENDAR = 21; const val MESSENGER = 22; const val VIBER = 23; const val LINE = 24
        const val TELEGRAM = 25; const val SKYPE = 27; const val EMAIL = 34

        /** Icons that show a sender/title next to the icon; others show the app name there. */
        val SHOWS_SENDER = setOf(WECHAT, 1, 2, SNAPCHAT, WHATSAPP, 8, INSTAGRAM, 13, 14, 20,
            MESSENGER, VIBER, LINE, TELEGRAM, 28, 32, EMAIL, 35)
    }

    data class Target(val category: Int, val icon: Int)

    fun targetFor(pkg: String): Target = when {
        pkg.startsWith("com.whatsapp") -> Target(Category.CUSTOM, Icon.WHATSAPP)
        pkg == "com.instagram.android" -> Target(Category.CUSTOM, Icon.INSTAGRAM)
        pkg.startsWith("org.telegram") || pkg.startsWith("org.thunderdog.challegram") -> Target(Category.CUSTOM, Icon.TELEGRAM)
        pkg == "com.facebook.orca" -> Target(Category.CUSTOM, Icon.MESSENGER)
        pkg == "com.facebook.katana" -> Target(Category.CUSTOM, Icon.FACEBOOK)
        pkg == "com.twitter.android" -> Target(Category.CUSTOM, Icon.TWITTER)
        pkg == "com.snapchat.android" -> Target(Category.CUSTOM, Icon.SNAPCHAT)
        pkg == "com.viber.voip" -> Target(Category.CUSTOM, Icon.VIBER)
        pkg == "jp.naver.line.android" -> Target(Category.CUSTOM, Icon.LINE)
        pkg.startsWith("com.skype") -> Target(Category.CUSTOM, Icon.SKYPE)
        pkg.contains("calendar") -> Target(Category.CUSTOM, Icon.CALENDAR)
        pkg.contains("deskclock") || pkg.contains(".clock") -> Target(Category.CUSTOM, Icon.ALARM)
        pkg == "com.google.android.gm" || pkg.contains("email") || pkg.contains(".mail") -> Target(Category.EMAIL, Icon.EMAIL)
        pkg == "com.google.android.apps.messaging" || pkg.endsWith(".mms") || pkg.contains("messaging") ->
            Target(Category.SMS, Icon.APP)
        else -> Target(Category.CUSTOM, Icon.APP)
    }

    const val MAX_MESSAGE = 230

    /**
     * Notification payload for the chunked characteristic (type 0). Mi Band 4 wants a 4-byte
     * extra header after the category byte.
     * CUSTOM: [0xfa, 0,0,0,0, 1, icon] + text + "\0" + appName + "\0"
     * others: [cat,  0,0,0,0, 1]       + text + "\0 \0"
     * text = title "\0" body   (or "-\0" title "\n" body when the icon shows the app name instead)
     */
    fun notification(target: Target, appName: String, title: String, body: String): ByteArray {
        val showsSender = target.category != Category.CUSTOM || target.icon in Icon.SHOWS_SENDER
        val t = title.trim().take(32)
        val b = body.trim().ifEmpty { " " }
        val text = when {
            showsSender -> "$t\u0000$b"
            t.isEmpty() || t == appName -> "-\u0000$b"
            else -> "-\u0000$t\n$b"
        }
        val prefix = if (target.category == Category.CUSTOM)
            byteArrayOf(Category.CUSTOM.toByte(), 0, 0, 0, 0, 1, target.icon.toByte())
        else byteArrayOf(target.category.toByte(), 0, 0, 0, 0, 1)
        val suffix = if (target.category == Category.CUSTOM) "\u0000$appName\u0000" else "\u0000 \u0000"
        return prefix + utf8Truncate(text, MAX_MESSAGE - prefix.size) + suffix.toByteArray()
    }

    /** UTF-8 bytes of [s], cut to at most [max] bytes without splitting a character. */
    fun utf8Truncate(s: String, max: Int): ByteArray {
        val all = s.toByteArray()
        if (all.size <= max) return all
        var end = max
        while (end > 0 && (all[end].toInt() and 0xC0) == 0x80) end-- // back off continuation bytes
        return all.copyOfRange(0, end)
    }

    private fun u32(v: ByteArray, i: Int) =
        (v[i].toInt() and 0xff) or ((v[i + 1].toInt() and 0xff) shl 8) or
            ((v[i + 2].toInt() and 0xff) shl 16) or ((v[i + 3].toInt() and 0xff) shl 24)

    fun hex(v: ByteArray) = v.joinToString(" ") { "%02x".format(it) }
}
