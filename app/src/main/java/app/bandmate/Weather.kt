package app.bandmate

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.LocationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

data class Place(val name: String, val lat: Double, val lon: Double, val region: String = "")

data class Forecast(
    val place: String,
    val tempNow: Int,
    val codeNow: Int,          // WMO weather code
    val days: List<Triple<Int, Int, Int>>, // (WMO code, max, min), today first
    val fetchedAt: Long,
) {
    fun toJson(): String = JSONObject().apply {
        put("place", place); put("tempNow", tempNow); put("codeNow", codeNow); put("fetchedAt", fetchedAt)
        put("days", org.json.JSONArray(days.map { org.json.JSONArray(listOf(it.first, it.second, it.third)) }))
    }.toString()

    companion object {
        fun fromJson(s: String?): Forecast? = runCatching {
            val o = JSONObject(s ?: return null)
            val d = o.getJSONArray("days")
            Forecast(o.getString("place"), o.getInt("tempNow"), o.getInt("codeNow"),
                (0 until d.length()).map { val a = d.getJSONArray(it); Triple(a.getInt(0), a.getInt(1), a.getInt(2)) },
                o.getLong("fetchedAt"))
        }.getOrNull()
    }
}

/**
 * Weather from Open-Meteo (free, no account, no API key). Only the chosen place's
 * coordinates are sent to open-meteo.com.
 */
object WeatherApi {
    private suspend fun get(url: String): JSONObject = withContext(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        c.setRequestProperty("User-Agent", "BandMate")
        try {
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }

    suspend fun search(query: String): List<Place> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val r = get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=6&language=en&format=json")
        val a = r.optJSONArray("results") ?: return emptyList()
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Place(o.getString("name"), o.getDouble("latitude"), o.getDouble("longitude"),
                listOfNotNull(o.optString("admin1").ifBlank { null }, o.optString("country").ifBlank { null }).joinToString(", "))
        }
    }

    suspend fun forecast(place: Place): Forecast {
        val r = get(
            "https://api.open-meteo.com/v1/forecast?latitude=${"%.3f".format(Locale.US, place.lat)}" +
                "&longitude=${"%.3f".format(Locale.US, place.lon)}&current=temperature_2m,weather_code" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=7"
        )
        val cur = r.getJSONObject("current")
        val d = r.getJSONObject("daily")
        val codes = d.getJSONArray("weather_code")
        val max = d.getJSONArray("temperature_2m_max")
        val min = d.getJSONArray("temperature_2m_min")
        val days = (0 until codes.length()).map {
            Triple(codes.getInt(it), Math.round(max.getDouble(it)).toInt(), Math.round(min.getDouble(it)).toInt())
        }
        return Forecast(place.name, Math.round(cur.getDouble("temperature_2m")).toInt(), cur.getInt("weather_code"),
            days, System.currentTimeMillis())
    }

    /** Rough current place from the phone's last known location (no GPS fix needed). */
    @SuppressLint("MissingPermission")
    suspend fun myPlace(ctx: Context): Place? = withContext(Dispatchers.IO) {
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return@withContext null
        val loc = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time } ?: return@withContext null
        @Suppress("DEPRECATION")
        val name = runCatching {
            Geocoder(ctx, Locale.getDefault()).getFromLocation(loc.latitude, loc.longitude, 1)
                ?.firstOrNull()?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
        }.getOrNull() ?: "My location"
        Place(name, loc.latitude, loc.longitude)
    }

    /** WMO weather code → the band's own icon code. */
    fun bandCode(wmo: Int): Int = when (wmo) {
        0 -> 0                    // clear
        1, 2 -> 1                 // partly cloudy
        3 -> 2                    // cloudy
        45, 48 -> 18              // fog / mist
        51, 53, 55, 56, 57 -> 19  // drizzle
        61 -> 7; 63 -> 8; 65 -> 9 // light / moderate / heavy rain
        66, 67 -> 6               // freezing rain
        71, 77 -> 14; 73 -> 15; 75 -> 16
        80, 81 -> 3               // showers
        82 -> 10                  // violent showers
        85, 86 -> 13              // snow showers
        95 -> 4                   // thunderstorm
        96, 99 -> 5               // thunderstorm with hail
        else -> 2
    }

    fun label(wmo: Int): String = when (wmo) {
        0 -> "Clear"; 1 -> "Mostly clear"; 2 -> "Partly cloudy"; 3 -> "Cloudy"
        45, 48 -> "Fog"; 51, 53, 55, 56, 57 -> "Drizzle"
        61 -> "Light rain"; 63 -> "Rain"; 65 -> "Heavy rain"; 66, 67 -> "Freezing rain"
        71, 73, 75, 77 -> "Snow"; 80, 81 -> "Showers"; 82 -> "Heavy showers"; 85, 86 -> "Snow showers"
        95 -> "Thunderstorm"; 96, 99 -> "Storm & hail"
        else -> "—"
    }

    /** The three chunked payloads that put [f] on the band. */
    fun payloads(f: Forecast, nowMillis: Long = System.currentTimeMillis()): List<ByteArray> {
        val ts = nowMillis / 1000
        val tz = java.util.TimeZone.getDefault().getOffset(nowMillis) / (15 * 60 * 1000)
        return listOf(
            WeatherPayload.current(ts, tz, bandCode(f.codeNow), f.tempNow, label(f.codeNow)),
            WeatherPayload.forecast(ts, tz, f.days.map { (c, hi, lo) -> WeatherDay(bandCode(c), hi, lo, label(c)) }),
            WeatherPayload.location(f.place),
        )
    }
}

object WeatherPrefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("weather", Context.MODE_PRIVATE)
    fun enabled(ctx: Context) = p(ctx).getBoolean("enabled", false)
    fun setEnabled(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("enabled", on).apply()
    fun place(ctx: Context): Place? = p(ctx).getString("name", null)?.let {
        Place(it, p(ctx).getFloat("lat", 0f).toDouble(), p(ctx).getFloat("lon", 0f).toDouble())
    }
    fun setPlace(ctx: Context, place: Place) = p(ctx).edit().putString("name", place.name)
        .putFloat("lat", place.lat.toFloat()).putFloat("lon", place.lon.toFloat()).apply()
    fun last(ctx: Context) = Forecast.fromJson(p(ctx).getString("last", null))
    fun setLast(ctx: Context, f: Forecast) = p(ctx).edit().putString("last", f.toJson()).apply()
}
