package app.orchard.tv.lite

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A tile drawn from an app's icon, in the full app's unified look without its costly parts: the
 * icon on a diagonal gradient of its own color, corners rounded into the bitmap so Home never clips
 * at draw time. Apps on 4.x have no TV banners, so the icon is all there is. Off the main thread.
 */
object TileArt {
    fun fromIcon(icon: Drawable, w: Int, h: Int, radius: Float): Bitmap {
        val (top, bottom) = colors(icon)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val plate = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, w * 0.5f, h.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        }
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radius, radius, plate)
        val size = (h * 0.58f).toInt()
        val l = (w - size) / 2
        val t = (h - size) / 2
        icon.setBounds(l, t, l + size, t + size)
        icon.draw(c)
        return out
    }

    /**
     * The plate's two colors from the icon's hue: the saturation-weighted mean over a small render,
     * brought into one range of saturation and brightness so no tile shouts. Graphite for gray icons.
     */
    private fun colors(icon: Drawable): Pair<Int, Int> {
        val n = 24
        val small = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, n, n)
        icon.draw(Canvas(small))
        val px = IntArray(n * n)
        small.getPixels(px, 0, n, 0, 0, n, n)
        small.recycle()
        val hsv = FloatArray(3)
        var x = 0.0
        var y = 0.0
        var weight = 0.0
        var count = 0
        for (p in px) {
            if (Color.alpha(p) < 128) continue
            Color.colorToHSV(p, hsv)
            count++
            val wgt = (hsv[1] * hsv[2]).toDouble()
            val rad = Math.toRadians(hsv[0].toDouble())
            x += cos(rad) * wgt
            y += sin(rad) * wgt
            weight += wgt
        }
        if (count == 0 || weight / count < 0.12) return GRAPHITE_TOP to GRAPHITE_BOTTOM
        val hue = ((Math.toDegrees(atan2(y, x)) + 360) % 360).toFloat()
        val sat = (hypot(x, y) / count * 1.6).toFloat().coerceIn(0.45f, 0.78f)
        return Color.HSVToColor(floatArrayOf(hue, sat * 0.85f, 0.70f)) to Color.HSVToColor(floatArrayOf(hue, sat, 0.42f))
    }

    private const val GRAPHITE_TOP = 0xFF5A5A60.toInt()
    private const val GRAPHITE_BOTTOM = 0xFF2C2C30.toInt()
}
