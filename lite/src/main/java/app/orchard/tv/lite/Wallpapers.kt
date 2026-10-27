package app.orchard.tv.lite

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Home's backdrop: one of the built-in wallpapers, shaded and baked at the screen's size, kept on
 * disk so a cold start decodes one file instead of drawing and dithering a picture on a 4.x CPU.
 * Held as RGB_565: it is opaque, and half the bytes is half the texture upload and fill on these GPUs.
 */
object Wallpapers {
    private fun file(context: Context) = File(context.filesDir, "backdrop.png")

    private fun bakedFor(key: String) = "$key@${Ui.screenW}x${Ui.screenH}"

    /**
     * Home's backdrop, shaded and baked at the screen's size, and the same picture frosted (before
     * the shading, as in the full app) for the glass plate, panels and the grid's background.
     */
    class Backdrop(val home: Bitmap, val frost: Bitmap)

    private fun frostFile(context: Context) = File(context.filesDir, "frost.png")

    /** Bing's photo of the day, the full app's default wallpaper ("daily:latest" there). */
    const val DAILY = "daily"

    private fun dailyDir(context: Context) = File(context.filesDir, "daily").apply { mkdirs() }

    /** The newest daily photo on hand, if any. */
    fun dailyFile(context: Context): File? = dailyDir(context).listFiles()?.filter { it.name.endsWith(".jpg") }?.maxByOrNull { it.name }

    /**
     * Fetches today's photo from cn.bing.com (reachable from China without a proxy), at most every
     * three hours; the full app's market rule, Chinese captions for a Chinese TV. Old Androids do
     * not trust its certificate's root; it answers the same over plain HTTP.
     */
    private fun fetchDaily(context: Context) {
        val prefs = context.getSharedPreferences("wallpaper", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("daily_checked", 0) < 3 * 60 * 60 * 1000L && dailyFile(context) != null) return
        runCatching {
            val market = if (Locale.getDefault().language == "zh") "zh-CN" else "en-US"
            val json = org.json.JSONObject(bing("/HPImageArchive.aspx?format=js&idx=0&n=1&mkt=$market"))
            val img = json.getJSONArray("images").getJSONObject(0)
            val out = File(dailyDir(context), img.getString("startdate") + ".jpg")
            if (!out.exists()) {
                val tmp = File(out.path + ".part")
                val c = open("https://cn.bing.com" + img.getString("url"))
                try { c.inputStream.use { input -> FileOutputStream(tmp).use { input.copyTo(it) } } } finally { c.disconnect() }
                if (!tmp.renameTo(out)) tmp.delete()
            }
            // Only the newest is kept: Lite has no row of past days.
            dailyDir(context).listFiles()?.forEach { if (it.name != out.name) it.delete() }
            prefs.edit().putLong("daily_checked", now).apply()
        }.onFailure { android.util.Log.w("AerialLite", "daily photo unavailable", it) }
    }

    private fun open(url: String): java.net.HttpURLConnection = try {
        Net.open(url).also { it.responseCode }
    } catch (e: javax.net.ssl.SSLException) {
        Net.open(url.replaceFirst("https://", "http://"))
    }

    private fun bing(path: String): String {
        val c = open("https://cn.bing.com$path")
        try { return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } } finally { c.disconnect() }
    }

    /** The backdrop for [key]. Off the main thread. */
    fun load(context: Context, key: String): Backdrop {
        val prefs = context.getSharedPreferences("wallpaper", Context.MODE_PRIVATE)
        val f = file(context)
        val ff = frostFile(context)
        if (key == DAILY) fetchDaily(context)
        val daily = if (key == DAILY) dailyFile(context) else null
        val bakedKey = if (daily != null) bakedFor("daily/" + daily.name) else bakedFor(if (key == DAILY) LiteSettings.FALLBACK_WALLPAPER else key)
        if (prefs.getString("baked", null) == bakedKey && f.exists() && ff.exists()) {
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
                @Suppress("DEPRECATION")
                inDither = true
            }
            val home = BitmapFactory.decodeFile(f.path, opts)
            val frost = BitmapFactory.decodeFile(ff.path)
            if (home != null && frost != null) return Backdrop(home, frost)
        }
        val full = daily?.let { Images.decodeCover(it, Ui.screenW, Ui.screenH) }
            ?: Gradients.render(Gradients.byKey(key) ?: Gradients.byKey(LiteSettings.FALLBACK_WALLPAPER)!!, Ui.screenW, Ui.screenH)
        val frost = Images.frost(full)
        Images.shadeForHome(full)
        runCatching {
            val tmp = File(f.path + ".part")
            FileOutputStream(tmp).use { full.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val ftmp = File(ff.path + ".part")
            FileOutputStream(ftmp).use { frost.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (tmp.renameTo(f) && ftmp.renameTo(ff)) prefs.edit().putString("baked", bakedKey).apply()
        }
        val small = full.copy(Bitmap.Config.RGB_565, false)
        full.recycle()
        return Backdrop(small, frost)
    }

    val keys: List<String> get() = listOf(DAILY) + Gradients.presets.map { it.key }

    fun name(context: Context, key: String): String {
        if (key == DAILY) return context.getString(R.string.wp_daily_latest)
        val p = Gradients.byKey(key) ?: return key
        return if (Locale.getDefault().language == "zh") p.nameZh else p.nameEn
    }
}
