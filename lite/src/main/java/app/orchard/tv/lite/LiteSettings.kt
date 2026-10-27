package app.orchard.tv.lite

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/** Lite's own preferences ("settings"), and Home's order and hidden apps ("layout"). */
class LiteSettings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val layout: SharedPreferences = context.getSharedPreferences("layout", Context.MODE_PRIVATE)

    var wallpaper: String
        get() = prefs.getString("wallpaper", null) ?: DEFAULT_WALLPAPER
        set(v) = prefs.edit().putString("wallpaper", v).apply()

    var saver: Saver
        get() = Saver.entries.firstOrNull { it.key == prefs.getString("saver", null) }?.takeIf { it != Saver.Off } ?: Saver.Aerial
        set(v) = prefs.edit().putString("saver", v.key).apply()

    /** Minutes without a key press before the screensaver starts; 0 for never (the full app's "从不"). */
    var saverMinutes: Int
        get() = if (prefs.getString("saver", null) == Saver.Off.key) 0 else prefs.getInt("saver_minutes", 5)
        set(v) = prefs.edit().putInt("saver_minutes", v).apply()

    /** The large clock (date, big time, weather) instead of the small line; the full app's default is small. */
    var largeClock: Boolean
        get() = prefs.getBoolean("large_clock", false)
        set(v) = prefs.edit().putBoolean("large_clock", v).apply()

    /** Home's tiles in the owner's order; apps not in it go at the end. */
    var order: List<String>
        get() = runCatching {
            val a = JSONArray(layout.getString("order", "[]"))
            List(a.length()) { a.getString(it) }
        }.getOrDefault(emptyList())
        set(v) = layout.edit().putString("order", JSONArray(v).toString()).apply()

    var hidden: Set<String>
        get() = layout.getStringSet("hidden", null)?.toSet() ?: emptySet()
        set(v) = layout.edit().putStringSet("hidden", HashSet(v)).apply()

    enum class Saver(val key: String) { Aerial("aerial"), Clock("clock"), Off("off") }

    companion object {
        /** The full app's default: Bing's photo of the day. */
        const val DEFAULT_WALLPAPER = Wallpapers.DAILY
        /** Until a daily photo is here (no network yet), and for a key no longer known. */
        const val FALLBACK_WALLPAPER = "hills_dusk"
        val SAVER_MINUTES = listOf(5, 10, 15, 30)
    }
}
