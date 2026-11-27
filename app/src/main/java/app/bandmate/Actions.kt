package app.bandmate

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Save-then-push helpers the UI calls. Saving always works; pushing waits for a connection. */
object Actions {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun saveSettings(ctx: Context, s: BandSettings) {
        Saved.saveSettings(ctx, s)
        scope.launch { Band.client?.applySettings(s) }
    }

    fun saveAlarms(ctx: Context, list: List<Alarm>) {
        Saved.saveAlarms(ctx, list)
        ctx.getSharedPreferences("band", Context.MODE_PRIVATE).edit().putBoolean("alarmsOwned", true).apply()
        scope.launch { Band.client?.applyAlarms(list) }
    }

    fun syncNow() = scope.launch { Band.client?.syncHistory() }

    /** Fetches fresh weather for the saved place and sends it to the band. */
    fun refreshWeather(ctx: Context) = scope.launch { updateWeather(ctx) }

    suspend fun updateWeather(ctx: Context): Boolean {
        val place = WeatherPrefs.place(ctx) ?: return false
        val f = runCatching { WeatherApi.forecast(place) }
            .onFailure { Band.log("Weather fetch failed: ${it.message}") }
            .getOrNull() ?: return false
        WeatherPrefs.setLast(ctx, f)
        Band.forecast.value = f
        return if (WeatherPrefs.enabled(ctx)) Band.client?.sendWeather(f) == true else true
    }

    /** Everything to do right after the band connects, and then every hour. */
    suspend fun housekeeping(ctx: Context, firstAfterConnect: Boolean) {
        val c = Band.client ?: return
        if (firstAfterConnect) {
            if (Saved.settingsOwned(ctx)) c.applySettings(Saved.settings(ctx))
            if (ctx.getSharedPreferences("band", Context.MODE_PRIVATE).getBoolean("alarmsOwned", false)) {
                c.applyAlarms(Saved.alarms(ctx))
            }
        }
        c.syncHistory()
        if (WeatherPrefs.enabled(ctx)) updateWeather(ctx)
    }
}
