package app.orchard.tv.lite

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import kotlin.math.roundToInt
import kotlin.random.Random

/** Bitmap helpers, from the full app's wallpaper/Images.kt. All of it runs off the main thread. */
object Images {
    /** The home shading, the same as the full app's, baked in so the TV draws one full-screen layer. */
    private val SHADE = floatArrayOf(0.12f, 0.04f, 0.22f, 0.5f)
    private val SHADE_AT = floatArrayOf(0f, 0.3f, 0.66f, 1f)
    private const val SHADE_LEFT = 0.2f

    fun shadeForHome(b: Bitmap) {
        val c = Canvas(b)
        val w = b.width.toFloat()
        val h = b.height.toFloat()
        c.drawRect(0f, 0f, w, h, Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, h, IntArray(SHADE.size) { black(SHADE[it]) }, SHADE_AT, Shader.TileMode.CLAMP)
        })
        c.drawRect(0f, 0f, w, h, Paint().apply {
            shader = LinearGradient(0f, 0f, w * 0.5f, 0f, black(SHADE_LEFT), 0, Shader.TileMode.CLAMP)
        })
    }

    /** Fine noise, so long gradients do not band on 8-bit panels. */
    fun dither(b: Bitmap) {
        val w = b.width
        val h = b.height
        val row = IntArray(w)
        val rnd = Random(7)
        for (y in 0 until h) {
            b.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val c = row[x]
                val n = rnd.nextInt(5) - 2
                val r = clamp(((c shr 16) and 0xFF) + n)
                val g = clamp(((c shr 8) and 0xFF) + n)
                val bl = clamp((c and 0xFF) + n)
                row[x] = (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or bl
            }
            b.setPixels(row, 0, w, 0, y, w, 1)
        }
    }

    private fun clamp(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v

    fun boxBlur(px: IntArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(px.size)
        pass(px, tmp, w, h, r, horizontal = true)
        pass(tmp, px, w, h, r, horizontal = false)
    }

    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        for (l in 0 until lines) {
            for (i in 0 until len) {
                var a = 0; var rr = 0; var g = 0; var b = 0; var n = 0
                for (k in -r..r) {
                    val j = (i + k).coerceIn(0, len - 1)
                    val c = if (horizontal) src[l * w + j] else src[j * w + l]
                    a += c ushr 24; rr += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
                }
                val idx = if (horizontal) l * w + i else i * w + l
                dst[idx] = ((a / n) shl 24) or ((rr / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
    }

    private fun black(alpha: Float) = android.graphics.Color.argb((alpha * 255).roundToInt(), 0, 0, 0)
}
