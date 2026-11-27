package app.bandmate

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject

/** Everything the user can change on the band itself. Stored on the phone, pushed to the band. */
data class BandSettings(
    val time24h: Boolean = true,
    val showDate: Boolean = true,
    val dateFormat: String = "dd/MM/yyyy",
    val liftWrist: Cmd.Mode = Cmd.Mode.ON,
    val liftStart: Clock = Clock.of(7, 0),
    val liftEnd: Clock = Clock.of(23, 0),
    val liftSensitive: Boolean = false,
    val nightMode: Cmd.Mode = Cmd.Mode.ON,
    val nightStart: Clock = Clock.of(22, 0),
    val nightEnd: Clock = Clock.of(7, 0),
    val dnd: Cmd.Mode = Cmd.Mode.OFF,
    val dndStart: Clock = Clock.of(23, 0),
    val dndEnd: Clock = Clock.of(7, 0),
    val idleAlert: Boolean = false,
    val idleMinutes: Int = 60,
    val idleStart: Clock = Clock.of(9, 0),
    val idleEnd: Clock = Clock.of(21, 0),
    val goalCelebration: Boolean = true,
    val stepGoal: Int = 8000,
    val hrInterval: Int = 0,
    val sleepHrAssist: Boolean = true,
    val rotateWrist: Boolean = false,
    val disconnectAlert: Boolean = false,
    val rightWrist: Boolean = false,
    val screens: List<Cmd.Screen> = listOf(
        Cmd.Screen.STATUS, Cmd.Screen.HEART, Cmd.Screen.WORKOUT, Cmd.Screen.WEATHER,
        Cmd.Screen.NOTIFICATIONS, Cmd.Screen.MORE,
    ),
) {
    /** Every write needed to make the band match these settings, as (characteristic, bytes). */
    fun commands(): List<Pair<String, ByteArray>> = listOf(
        "config" to Cmd.timeFormat24h(time24h),
        "config" to Cmd.showDate(showDate),
        "config" to Cmd.dateFormat(dateFormat),
        "config" to Cmd.liftWrist(liftWrist, liftStart, liftEnd),
        "config" to Cmd.liftWristSensitive(liftSensitive),
        "config" to Cmd.nightMode(nightMode, nightStart, nightEnd),
        "config" to Cmd.doNotDisturb(dnd, dndStart, dndEnd),
        "config" to Cmd.idleAlert(idleAlert, idleMinutes, idleStart, idleEnd),
        "config" to Cmd.goalCelebration(goalCelebration),
        "config" to Cmd.rotateWristToSwitch(rotateWrist),
        "config" to Cmd.disconnectAlert(disconnectAlert),
        "config" to Cmd.screens(screens),
        "user" to Cmd.stepGoal(stepGoal),
        "user" to Cmd.wearOnRightWrist(rightWrist),
        "hr" to Cmd.heartRateInterval(hrInterval),
        "hr" to Cmd.sleepHeartRateAssist(sleepHrAssist),
    )

    fun toJson(): String = JSONObject().apply {
        put("time24h", time24h); put("showDate", showDate); put("dateFormat", dateFormat)
        put("liftWrist", liftWrist.name); put("liftStart", liftStart.minutes); put("liftEnd", liftEnd.minutes)
        put("liftSensitive", liftSensitive)
        put("nightMode", nightMode.name); put("nightStart", nightStart.minutes); put("nightEnd", nightEnd.minutes)
        put("dnd", dnd.name); put("dndStart", dndStart.minutes); put("dndEnd", dndEnd.minutes)
        put("idleAlert", idleAlert); put("idleMinutes", idleMinutes)
        put("idleStart", idleStart.minutes); put("idleEnd", idleEnd.minutes)
        put("goalCelebration", goalCelebration); put("stepGoal", stepGoal)
        put("hrInterval", hrInterval); put("sleepHrAssist", sleepHrAssist)
        put("rotateWrist", rotateWrist); put("disconnectAlert", disconnectAlert); put("rightWrist", rightWrist)
        put("screens", JSONArray(screens.map { it.name }))
    }.toString()

    companion object {
        fun fromJson(s: String?): BandSettings {
            val d = BandSettings()
            if (s.isNullOrBlank()) return d
            return runCatching {
                val o = JSONObject(s)
                fun mode(k: String, def: Cmd.Mode) = runCatching { Cmd.Mode.valueOf(o.getString(k)) }.getOrDefault(def)
                fun clock(k: String, def: Clock) = if (o.has(k)) Clock(o.getInt(k)) else def
                BandSettings(
                    time24h = o.optBoolean("time24h", d.time24h),
                    showDate = o.optBoolean("showDate", d.showDate),
                    dateFormat = o.optString("dateFormat", d.dateFormat),
                    liftWrist = mode("liftWrist", d.liftWrist),
                    liftStart = clock("liftStart", d.liftStart), liftEnd = clock("liftEnd", d.liftEnd),
                    liftSensitive = o.optBoolean("liftSensitive", d.liftSensitive),
                    nightMode = mode("nightMode", d.nightMode),
                    nightStart = clock("nightStart", d.nightStart), nightEnd = clock("nightEnd", d.nightEnd),
                    dnd = mode("dnd", d.dnd), dndStart = clock("dndStart", d.dndStart), dndEnd = clock("dndEnd", d.dndEnd),
                    idleAlert = o.optBoolean("idleAlert", d.idleAlert), idleMinutes = o.optInt("idleMinutes", d.idleMinutes),
                    idleStart = clock("idleStart", d.idleStart), idleEnd = clock("idleEnd", d.idleEnd),
                    goalCelebration = o.optBoolean("goalCelebration", d.goalCelebration),
                    stepGoal = o.optInt("stepGoal", d.stepGoal),
                    hrInterval = o.optInt("hrInterval", d.hrInterval),
                    sleepHrAssist = o.optBoolean("sleepHrAssist", d.sleepHrAssist),
                    rotateWrist = o.optBoolean("rotateWrist", d.rotateWrist),
                    disconnectAlert = o.optBoolean("disconnectAlert", d.disconnectAlert),
                    rightWrist = o.optBoolean("rightWrist", d.rightWrist),
                    screens = o.optJSONArray("screens")?.let { a ->
                        (0 until a.length()).mapNotNull { runCatching { Cmd.Screen.valueOf(a.getString(it)) }.getOrNull() }
                    } ?: d.screens,
                )
            }.getOrDefault(d)
        }
    }
}

/** An alarm that lives on the band. [days]: bit 0 = Monday … bit 6 = Sunday; 0 = once. */
data class Alarm(val slot: Int, val time: Clock, val days: Int, val enabled: Boolean)

object Saved {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("band", Context.MODE_PRIVATE)

    fun settings(ctx: Context) = BandSettings.fromJson(prefs(ctx).getString("settings", null))
    fun saveSettings(ctx: Context, s: BandSettings) = prefs(ctx).edit().putString("settings", s.toJson()).apply()

    /** True once the user has changed a band setting here, so we re-apply them after reconnecting. */
    fun settingsOwned(ctx: Context) = prefs(ctx).contains("settings")

    fun alarms(ctx: Context): List<Alarm> = runCatching {
        val a = JSONArray(prefs(ctx).getString("alarms", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Alarm(o.getInt("slot"), Clock(o.getInt("time")), o.getInt("days"), o.getBoolean("enabled"))
        }.sortedBy { it.time.minutes }
    }.getOrDefault(emptyList())

    fun saveAlarms(ctx: Context, list: List<Alarm>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("slot", it.slot).put("time", it.time.minutes).put("days", it.days).put("enabled", it.enabled)) }
        prefs(ctx).edit().putString("alarms", a.toString()).apply()
    }

    fun lastSync(ctx: Context): Long = prefs(ctx).getLong("lastSync", 0)
    fun setLastSync(ctx: Context, millis: Long) = prefs(ctx).edit().putLong("lastSync", millis).apply()
}

/** Which apps may buzz the band. Unknown apps follow a sensible default. */
object AppFilter {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("apps", Context.MODE_PRIVATE)

    private val DEFAULT_ON = listOf(
        "com.whatsapp", "org.telegram", "org.thunderdog.challegram", "com.instagram.android", "com.facebook.orca",
        "com.snapchat.android", "org.thoughtcrime.securesms", "com.google.android.gm", "com.microsoft.office.outlook",
        "com.google.android.apps.messaging", "com.android.mms", "com.android.contacts", "com.google.android.dialer",
        "com.android.dialer", "com.google.android.calendar", "com.android.calendar", "com.google.android.deskclock",
        "com.oneplus.deskclock", "com.coloros.alarmclock", "com.truecaller", "com.viber.voip", "jp.naver.line.android",
        "com.discord", "com.slack", "com.google.android.apps.dynamite", "com.skype",
    )

    fun defaultFor(pkg: String, info: ApplicationInfo?): Boolean =
        DEFAULT_ON.any { pkg == it || pkg.startsWith("$it.") } ||
            (info != null && info.category == ApplicationInfo.CATEGORY_SOCIAL)

    fun isAllowed(ctx: Context, pkg: String): Boolean {
        val p = prefs(ctx)
        if (p.contains("on:$pkg")) return p.getBoolean("on:$pkg", true)
        val info = runCatching { ctx.packageManager.getApplicationInfo(pkg, 0) }.getOrNull()
        return defaultFor(pkg, info)
    }

    fun set(ctx: Context, pkg: String, on: Boolean) = prefs(ctx).edit().putBoolean("on:$pkg", on).apply()

    /** Remembers that [pkg] posted a notification (only the package name and a count — never content). */
    fun recordSeen(ctx: Context, pkg: String, forwarded: Boolean) {
        val p = prefs(ctx)
        val dayKey = (if (forwarded) "sent:" else "blocked:") + java.time.LocalDate.now()
        p.edit()
            .putInt("count:$pkg", p.getInt("count:$pkg", 0) + 1)
            .putLong("seen:$pkg", System.currentTimeMillis())
            .putInt(dayKey, p.getInt(dayKey, 0) + 1)
            .apply()
    }

    fun todayCounts(ctx: Context): Pair<Int, Int> {
        val p = prefs(ctx)
        val d = java.time.LocalDate.now()
        return p.getInt("sent:$d", 0) to p.getInt("blocked:$d", 0)
    }

    fun lastSeen(ctx: Context, pkg: String) = prefs(ctx).getLong("seen:$pkg", 0)
    fun seenCount(ctx: Context, pkg: String) = prefs(ctx).getInt("count:$pkg", 0)

    data class AppRow(val pkg: String, val label: String, val info: ApplicationInfo)

    /** Launchable apps, minus this one. */
    fun installedApps(ctx: Context): List<AppRow> {
        val pm = ctx.packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val list = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        return list.map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != ctx.packageName }
            .map { AppRow(it.packageName, pm.getApplicationLabel(it).toString(), it) }
            .sortedBy { it.label.lowercase() }
    }
}
