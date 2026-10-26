package app.orchard.tv.lite

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.orchard.tv.lite.Ui.textPx
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Home: the clock, the settings button and the grid of tiles, over the window's background (the
 * baked wallpaper), which is the only full-screen draw.
 *
 * Every tile is its own View, laid out once at (0, 0) and placed by translation; focus scales it.
 * Translation, scale and alpha are View properties, so with hardware acceleration they change
 * without the app recording any drawing again, which keeps Dalvik's main thread nearly idle while
 * the focus moves. Nothing animates when no key is pressed.
 */
class HomeView(context: Context, private val host: Host) : FrameLayout(context) {
    interface Host {
        fun launch(app: AppEntry)
        fun openMenu(app: AppEntry)
        fun openSettings()
        fun startSaver()
        fun saveOrder(keys: List<String>)
    }

    private val layer = FrameLayout(context)
    private val tiles = ArrayList<TileView>()
    private val name = TextView(context)
    /**
     * Fades by alpha when Home scrolls. Not overlapping: HWUI then applies the alpha to each draw
     * rather than to an offscreen layer it would allocate for every frame of the fade (Android 4.1+;
     * 4.0 never asks).
     */
    private val clock = object : LinearLayout(context) {
        override fun hasOverlappingRendering() = false
    }
    private val date = TextView(context)
    private val time = TextView(context)
    private val pill = TextView(context)
    private val press = Press()
    private val handler = Handler(Looper.getMainLooper())

    /** The focused tile; [PILL] for the settings button. */
    private var focus = 0
    private var row = 0
    private var moving = false

    init {
        clipChildren = false
        layer.clipChildren = false
        addView(layer, LayoutParams(Ui.screenW, Ui.screenH))

        clock.orientation = LinearLayout.VERTICAL
        date.apply {
            setTextColor(Ui.WHITE)
            textPx(19f)
            typeface = Ui.bold
            includeFontPadding = false
            setShadowLayer(Ui.px(6f), 0f, Ui.px(1f), 0x66000000)
        }
        time.apply {
            setTextColor(Ui.WHITE)
            textPx(96f)
            typeface = Ui.bold
            includeFontPadding = false
            setShadowLayer(Ui.px(8f), 0f, Ui.px(2f), 0x55000000)
        }
        clock.addView(date)
        clock.addView(time, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Ui.pxi(6f)
        })
        addView(clock, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            leftMargin = Ui.pxi(MARGIN)
            topMargin = Ui.pxi(60f)
        })

        pill.apply {
            text = context.getString(R.string.settings)
            textPx(15f)
            typeface = Ui.bold
            gravity = Gravity.CENTER
            setPadding(Ui.pxi(16f), 0, Ui.pxi(16f), 0)
        }
        stylePill(false)
        addView(pill, LayoutParams(LayoutParams.WRAP_CONTENT, Ui.pxi(32f), Gravity.END or Gravity.TOP).apply {
            topMargin = Ui.pxi(24f)
            rightMargin = Ui.pxi(MARGIN)
        })

        // Fixed size, so changing its text only redraws it rather than laying Home out again.
        name.apply {
            setTextColor(Ui.WHITE)
            textPx(15f)
            typeface = Ui.bold
            gravity = Gravity.CENTER_HORIZONTAL
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setShadowLayer(Ui.px(4f), 0f, Ui.px(1f), 0x99000000.toInt())
        }
        layer.addView(name, LayoutParams(Ui.pxi(TILE_W + 60f), Ui.pxi(24f)))
    }

    var apps: List<AppEntry> = emptyList()
        private set

    /** Shows [list], keeping the focus on the same app when it is still there. */
    fun setApps(list: List<AppEntry>) {
        val focusedKey = apps.getOrNull(focus)?.key
        val old = HashMap<String, TileView>()
        for (t in tiles) old[t.entry.key] = t
        tiles.clear()
        for (app in list) {
            val t = old.remove(app.key) ?: TileView(context).also {
                layer.addView(it, 0, LayoutParams(Ui.pxi(TILE_W + 2 * PAD), Ui.pxi(TILE_H + 2 * PAD)))
            }
            t.entry = app
            tiles += t
        }
        for (t in old.values) layer.removeView(t)
        apps = list
        if (focus != PILL) {
            focus = list.indexOfFirst { it.key == focusedKey }.takeIf { it >= 0 } ?: focus.coerceAtMost(list.size - 1).coerceAtLeast(0)
        }
        place(animate = false)
        showFocus(animate = false)
    }

    /** Back to the first tile and the top, as after the Home key. */
    fun reset() {
        moving = false
        press.reset()
        focus = 0
        place(animate = false)
        showFocus(animate = false)
    }

    private fun stylePill(focused: Boolean) {
        pill.setTextColor(if (focused) Ui.INK else Ui.WHITE)
        @Suppress("DEPRECATION")
        pill.setBackgroundDrawable(GradientDrawable().apply {
            setColor(if (focused) Ui.WHITE else Ui.PILL)
            cornerRadius = Ui.px(16f)
        })
    }

    private fun x(i: Int) = MARGIN + (i % COLS) * (TILE_W + GAP)
    private fun y(i: Int) = ROW0 + (i / COLS) * PITCH

    /**
     * Without hardware acceleration (4.x boxes with no GPU driver for it, Android-x86) every frame
     * of an animation is drawn by the CPU and the 150 ms ones come out as two or three frames.
     * There the focus and the scroll jump, so each key press costs one frame.
     */
    private val smooth get() = isHardwareAccelerated

    private fun place(animate: Boolean) {
        tiles.forEachIndexed { i, t ->
            val tx = Ui.px(x(i) - PAD)
            val ty = Ui.px(y(i) - PAD)
            if (animate && smooth) t.animate().translationX(tx).translationY(ty).setDuration(180).setInterpolator(EASE).start()
            else { t.translationX = tx; t.translationY = ty }
        }
    }

    private fun showFocus(animate: Boolean) {
        stylePill(focus == PILL)
        tiles.forEachIndexed { i, t ->
            val on = i == focus
            t.ring = on
            t.lifted = on && moving
            val s = if (!on) 1f else if (moving) 1.18f else FOCUS_SCALE
            if (animate && smooth) t.animate().scaleX(s).scaleY(s).setDuration(150).setInterpolator(EASE).start()
            else { t.scaleX = s; t.scaleY = s }
        }
        val app = apps.getOrNull(focus)
        if (focus == PILL || app == null) {
            name.visibility = INVISIBLE
        } else {
            name.visibility = VISIBLE
            name.text = if (moving) context.getString(R.string.move_hint) else app.label
            name.translationX = Ui.px(x(focus) + TILE_W / 2) - name.layoutParams.width / 2f
            name.translationY = Ui.px(y(focus) + TILE_H + TILE_H * (FOCUS_SCALE - 1) / 2 + 10f)
        }
        val newRow = if (focus == PILL) 0 else focus / COLS
        if (newRow != row || !animate) {
            row = newRow
            val to = if (row == 0) 0f else Ui.px(SCROLLED_ROW_Y - y(focus))
            val clockAlpha = if (row == 0) 1f else 0f
            if (animate && smooth) {
                layer.animate().translationY(to).setDuration(220).setInterpolator(EASE).start()
                clock.animate().alpha(clockAlpha).setDuration(180).start()
            } else {
                layer.translationY = to
                clock.alpha = clockAlpha
            }
        }
    }

    fun onKey(e: KeyEvent): Boolean {
        val app = apps.getOrNull(focus)
        if (press.onKey(e, click = {
                when {
                    moving -> finishMove()
                    focus == PILL -> host.openSettings()
                    app != null -> host.launch(app)
                }
            }, longPress = { if (focus != PILL && app != null && !moving) host.openMenu(app) })) return true
        when (e.keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (e.action == KeyEvent.ACTION_UP) back()
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                if (e.action == KeyEvent.ACTION_UP && focus != PILL && app != null && !moving) host.openMenu(app)
                return true
            }
        }
        val dir = when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1
            KeyEvent.KEYCODE_DPAD_UP -> -COLS
            KeyEvent.KEYCODE_DPAD_DOWN -> COLS
            else -> return false
        }
        if (e.action == KeyEvent.ACTION_DOWN) step(dir)
        return true
    }

    private fun back() {
        when {
            moving -> finishMove()
            focus == PILL -> { focus = 0; showFocus(animate = true) }
            row > 0 -> { focus %= COLS; showFocus(animate = true) }
            else -> host.startSaver()
        }
    }

    private fun step(dir: Int) {
        if (apps.isEmpty()) return
        if (focus == PILL) {
            if (dir == COLS) { focus = minOf(COLS - 1, apps.size - 1); showFocus(animate = true) }
            return
        }
        // Left and right stay in the row, as on tvOS.
        if ((dir == -1 && focus % COLS == 0) || (dir == 1 && (focus % COLS == COLS - 1 || focus == apps.size - 1))) return
        var to = focus + dir
        if (dir == -COLS && to < 0) {
            if (moving) return
            focus = PILL
            showFocus(animate = true)
            return
        }
        // Down onto a shorter last row lands on its last tile.
        if (dir == COLS && to >= apps.size) {
            if (focus / COLS == (apps.size - 1) / COLS) return
            to = apps.size - 1
        }
        if (moving) {
            val list = apps.toMutableList()
            val t = tiles.removeAt(focus)
            tiles.add(to, t)
            list.add(to, list.removeAt(focus))
            apps = list
            focus = to
            place(animate = true)
        } else {
            focus = to
        }
        showFocus(animate = true)
    }

    fun startMove(app: AppEntry) {
        focus = apps.indexOf(app).takeIf { it >= 0 } ?: return
        moving = true
        press.reset()
        showFocus(animate = true)
    }

    private fun finishMove() {
        moving = false
        host.saveOrder(apps.map { it.key })
        showFocus(animate = true)
    }

    /** The clock, redrawn once a minute on the minute and at no other time. */
    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val locale = Locale.getDefault()
            val cal = Calendar.getInstance()
            time.text = SimpleDateFormat(if (DateFormat.is24HourFormat(context)) "H:mm" else "h:mm", locale).format(Date(now))
            val d = SimpleDateFormat(datePattern(locale), locale).format(Date(now))
            date.text = if (locale.language == "zh") Lunar.text(cal)?.let { "$d  $it" } ?: d else d
            handler.postDelayed(this, 60_000 - now % 60_000 + 50)
        }
    }

    /** Starts the clock, or brings it up to date after the TV slept. */
    fun refreshClock() {
        handler.removeCallbacks(tick)
        tick.run()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    /** DateFormat.getBestDateTimePattern is Android 4.3; the languages Aerial speaks, by hand. */
    private fun datePattern(locale: Locale) = when (locale.language) {
        "zh" -> "M月d日 EEEE"
        "en" -> "EEEE, MMMM d"
        else -> "EEEE, d MMMM"
    }

    /** One tile: its bitmap and, when focused, a white ring drawn inside the View's padding. */
    class TileView(context: Context) : View(context) {
        lateinit var entry: AppEntry
        var ring = false
            set(v) { if (field != v) { field = v; invalidate() } }
        var lifted = false
            set(v) { if (field != v) { field = v; invalidate() } }
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = Ui.px(2.5f)
        }
        private val dst = Rect()
        private val ringRect = RectF()

        override fun onDraw(canvas: Canvas) {
            val p = Ui.pxi(PAD)
            dst.set(p, p, width - p, height - p)
            canvas.drawBitmap(entry.tile, null, dst, paint)
            if (ring) {
                val o = Ui.px(4f)
                ringRect.set(dst.left - o, dst.top - o, dst.right + o, dst.bottom + o)
                stroke.color = if (lifted) 0xFFFFD60A.toInt() else Ui.WHITE
                canvas.drawRoundRect(ringRect, Ui.px(RADIUS + 4f), Ui.px(RADIUS + 4f), stroke)
            }
        }
    }

    companion object {
        const val PILL = -1
        const val COLS = 5
        const val MARGIN = 56f
        const val GAP = 28f
        const val TILE_W = (960f - 2 * MARGIN - (COLS - 1) * GAP) / COLS
        const val TILE_H = TILE_W * 9 / 16
        /** Room around a tile's picture for the focus ring. */
        const val PAD = 8f
        const val RADIUS = 10f
        const val FOCUS_SCALE = 1.12f
        /** The first row sits low, under the clock, as in the full app. */
        const val ROW0 = 300f
        const val PITCH = TILE_H + 48f
        /** Where the focused row sits once Home has scrolled past the first. */
        const val SCROLLED_ROW_Y = 200f
        private val EASE = DecelerateInterpolator(1.6f)
    }
}
