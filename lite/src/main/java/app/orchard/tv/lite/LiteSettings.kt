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
        get() = Saver.entries.firstOrNull { it.key == prefs.getString("saver", null) } ?: Saver.Aerial
        set(v) = prefs.edit().putString("saver", v.key).apply()

    /** Minutes without a key press before the screensaver starts. */
    var saverMinutes: Int
        get() = prefs.getInt("saver_minutes", 10)
        set(v) = prefs.edit().putInt("saver_minutes", v).apply()

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
        const val DEFAULT_WALLPAPER = "hills_dusk"
        val SAVER_MINUTES = intArrayOf(5, 10, 15, 30, 60)
    }
}
