package app.orchard.tv.lite

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The 960×540 design canvas, as in the full app, but scaled by hand: Views take pixels, so every
 * size goes through [px]. 4.x TVs report 720p or 1080p UIs at 160-320 dpi, and this keeps the
 * layout the same on all of them.
 */
object Ui {
    /** Pixels per canvas unit. */
    var u = 1f
        private set
    var screenW = 960
        private set
    var screenH = 540
        private set

    fun init(context: Context) {
        // The whole screen, not the part left by a navigation bar (Android-x86, a few boxes).
        val dm = if (Build.VERSION.SDK_INT >= 17) {
            DisplayMetrics().also { (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(it) }
        } else {
            context.resources.displayMetrics
        }
        screenW = max(dm.widthPixels, dm.heightPixels)
        screenH = minOf(dm.widthPixels, dm.heightPixels)
        u = screenW / 960f
    }

    fun px(v: Float): Float = v * u
    fun pxi(v: Float): Int = (v * u).roundToInt()

    const val WHITE = 0xFFFFFFFF.toInt()
    const val DIM = 0xB3FFFFFF.toInt()
    const val FAINT = 0x80FFFFFF.toInt()
    const val INK = 0xFF111114.toInt()
    /** Panels are opaque: blur and a dim layer would each cost another full-screen pass. */
    const val PANEL = 0xF2232328.toInt()
    const val PILL = 0x66000000

    val bold: Typeface = Typeface.DEFAULT_BOLD
    val regular: Typeface = Typeface.DEFAULT

    fun TextView.textPx(units: Float) = setTextSize(TypedValue.COMPLEX_UNIT_PX, px(units))
}
