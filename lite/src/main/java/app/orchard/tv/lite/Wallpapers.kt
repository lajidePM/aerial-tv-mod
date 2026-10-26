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

    /** The backdrop for [key]. Off the main thread. */
    fun load(context: Context, key: String): Bitmap {
        val prefs = context.getSharedPreferences("wallpaper", Context.MODE_PRIVATE)
        val f = file(context)
        if (prefs.getString("baked", null) == bakedFor(key) && f.exists()) {
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
                @Suppress("DEPRECATION")
                inDither = true
            }
            BitmapFactory.decodeFile(f.path, opts)?.let { return it }
        }
        val preset = Gradients.byKey(key) ?: Gradients.byKey(LiteSettings.DEFAULT_WALLPAPER)!!
        val full = Gradients.render(preset, Ui.screenW, Ui.screenH)
        Images.shadeForHome(full)
        runCatching {
            val tmp = File(f.path + ".part")
            FileOutputStream(tmp).use { full.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (tmp.renameTo(f)) prefs.edit().putString("baked", bakedFor(key)).apply()
        }
        val small = full.copy(Bitmap.Config.RGB_565, false)
        full.recycle()
        return small
    }

    val keys: List<String> get() = Gradients.presets.map { it.key }

    fun name(key: String): String {
        val p = Gradients.byKey(key) ?: return key
        return if (Locale.getDefault().language == "zh") p.nameZh else p.nameEn
    }
}
