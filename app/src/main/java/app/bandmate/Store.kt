package app.bandmate

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.LocalDate
import java.time.ZoneId

/** Minute-by-minute band history, kept only on this phone. */
class Store private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "bandmate.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE samples (minute INTEGER PRIMARY KEY, kind INTEGER NOT NULL, " +
                "intensity INTEGER NOT NULL, steps INTEGER NOT NULL, hr INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Add real migrations here when the schema changes — never drop user data.
    }

    fun insert(startMinute: Long, samples: List<RawSample>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val cv = ContentValues()
            samples.forEachIndexed { i, s ->
                cv.clear()
                cv.put("minute", startMinute + i)
                cv.put("kind", s.kind)
                cv.put("intensity", s.intensity)
                cv.put("steps", s.steps)
                cv.put("hr", s.heartRate)
                db.insertWithOnConflict("samples", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun samples(fromMinute: Long, toMinute: Long): List<Sample> {
        val out = ArrayList<Sample>()
        readableDatabase.rawQuery(
            "SELECT minute, kind, intensity, steps, hr FROM samples WHERE minute >= ? AND minute < ? ORDER BY minute",
            arrayOf(fromMinute.toString(), toMinute.toString())
        ).use { c ->
            while (c.moveToNext()) out += Sample(c.getLong(0), c.getInt(1), c.getInt(2), c.getInt(3), c.getInt(4))
        }
        return out
    }

    /** Steps in each of the 24 hours of [day] (local time). */
    fun stepsByHour(day: LocalDate, zone: ZoneId = ZoneId.systemDefault()): IntArray {
        val start = dayStartMinute(day, zone)
        val out = IntArray(24)
        readableDatabase.rawQuery(
            "SELECT (minute - ?) / 60 AS h, SUM(steps) FROM samples WHERE minute >= ? AND minute < ? GROUP BY h",
            arrayOf(start.toString(), start.toString(), (start + 1440).toString())
        ).use { c -> while (c.moveToNext()) c.getInt(0).takeIf { it in 0..23 }?.let { out[it] = c.getInt(1) } }
        return out
    }

    /** Steps in each hour for [hours] hours from the start of [first]. */
    fun stepsByHourRange(first: LocalDate, hours: Int, zone: ZoneId = ZoneId.systemDefault()): IntArray {
        val start = dayStartMinute(first, zone)
        val out = IntArray(hours)
        readableDatabase.rawQuery(
            "SELECT (minute - ?) / 60 AS h, SUM(steps) FROM samples WHERE minute >= ? AND minute < ? GROUP BY h",
            arrayOf(start.toString(), start.toString(), (start + hours * 60L).toString())
        ).use { c -> while (c.moveToNext()) c.getInt(0).takeIf { it in 0 until hours }?.let { out[it] = c.getInt(1) } }
        return out
    }

    /** Total steps for each of [days] days starting at [first]. */
    fun stepsByDay(first: LocalDate, days: Int, zone: ZoneId = ZoneId.systemDefault()): IntArray =
        IntArray(days) { i ->
            val s = dayStartMinute(first.plusDays(i.toLong()), zone)
            readableDatabase.rawQuery(
                "SELECT COALESCE(SUM(steps), 0) FROM samples WHERE minute >= ? AND minute < ?",
                arrayOf(s.toString(), (s + 1440).toString())
            ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        }

    /** The night that ends on the morning of [day]: looks from 18:00 the evening before. */
    fun night(day: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Night? {
        val from = dayStartMinute(day, zone) - 6 * 60
        val to = dayStartMinute(day, zone) + 14 * 60
        return Sleep.night(samples(from, to + 12 * 60), from, to)
    }

    fun latestMinute(): Long? = readableDatabase.rawQuery("SELECT MAX(minute) FROM samples", null)
        .use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }

    companion object {
        @Volatile private var instance: Store? = null
        fun get(ctx: Context) = instance ?: synchronized(this) {
            instance ?: Store(ctx.applicationContext).also { instance = it }
        }

        fun dayStartMinute(day: LocalDate, zone: ZoneId = ZoneId.systemDefault()) =
            day.atStartOfDay(zone).toEpochSecond() / 60
    }
}
