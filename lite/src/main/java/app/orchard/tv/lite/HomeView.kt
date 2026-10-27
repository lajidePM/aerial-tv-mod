package app.orchard.tv.lite

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import app.orchard.tv.lite.Ui.textPx

/**
 * Home, laid out as the full app's HomeScreen with its defaults (one row, the next peeking, the
 * glass plate, the focused app's name only off the plate): the clock top left, the status pill
 * top right, the tiles over the window's background (the baked wallpaper, the only full-screen
 * draw).
 *
 * Every tile is its own View, laid out once and placed by translation; focus scales it. These are
 * View properties, so with hardware acceleration they change without recording any drawing again,
 * which keeps Dalvik's main thread nearly idle while the focus moves. Nothing animates when no key
 * is pressed.
 */
class HomeView(context: Context, private val host: Host) : FrameLayout(context) {
    interface Host {
        fun launch(app: AppEntry)
        fun openMenu(app: AppEntry, tile: View)
        fun openControlCenter()
        fun startSaver()
        fun saveOrder(keys: List<String>)
        /** Home went down into the grid (the frosted background) or back to the top row. */
        fun inGrid(grid: Boolean)
    }

    private val layer = FrameLayout(context)
    private val plate = PlateView(context)
    private val tiles = ArrayList<TileView>()
    private val name = TextView(context)
    val clock = ClockView(context)
    val pill = StatusPill(context)
    private val press = Press()
    private val handler = Handler(Looper.getMainLooper())

    /** The focused tile; [PILL] for the status pill. */
    private var focus = 0
    private var row = 0
    private var moving = false

    // The full app's GridMetrics, in canvas units.
    private val canvasH = Ui.canvasH
    private val topRowY = canvasH - PITCH - 12f
    private val restY = canvasH * 0.37f

    init {
        clipChildren = false
        layer.clipChildren = false
        addView(layer, LayoutParams(Ui.screenW, Ui.screenH))
        layer.addView(plate, LayoutParams(Ui.screenW, Ui.pxi(TILE_H + 2 * PLATE_PAD_V)))
        plate.translationY = Ui.px(topRowY - PLATE_PAD_V)
        plate.visibility = INVISIBLE

        addView(clock, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            leftMargin = Ui.pxi(MARGIN)
        })
        placeClock()
        addView(pill, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.TOP).apply {
            topMargin = Ui.pxi(28f)
            rightMargin = Ui.pxi(MARGIN - 14f)
        })

        // Type.Headline, centred under the tile, with the full app's soft shadow. Fixed size, so
        // changing its text only redraws it rather than laying Home out again.
        name.apply {
            setTextColor(Ui.WHITE)
            textPx(15f)
            typeface = Ui.font(600)
            gravity = Gravity.CENTER_HORIZONTAL
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setShadowLayer(Ui.px(4f), 0f, 0f, 0x99000000.toInt())
            visibility = INVISIBLE
        }
        layer.addView(name, LayoutParams(Ui.pxi(TILE_W + 36f), Ui.pxi(24f)))
    }

    var apps: List<AppEntry> = emptyList()
        private set

    /** The large clock (date, big time, weather) instead of the small line; from Personalize. */
    fun setLargeClock(large: Boolean) {
        clock.large = large
        placeClock()
    }

    private fun placeClock() {
        (clock.layoutParams as LayoutParams).topMargin = Ui.pxi(if (clock.large) CLOCK_Y else 28f)
        clock.requestLayout()
    }

    /** The glass for the plate: the frosted wallpaper, tinted as the full app's PlateTint. */
    fun setGlass(frost: Bitmap?) {
        plate.glass = frost
        plate.rebake()
    }

    /** Shows [list], keeping the focus on the same app when it is still there. */
    fun setApps(list: List<AppEntry>) {
        val focusedKey = apps.getOrNull(focus)?.key
        val old = HashMap<String, TileView>()
        for (t in tiles) old[t.entry.key] = t
        tiles.clear()
        for (app in list) {
            val t = old.remove(app.key) ?: TileView(context).also {
                layer.addView(it, 1, LayoutParams(Ui.pxi(TILE_W + 2 * TILE_EXT), Ui.pxi(TILE_H + 2 * TILE_EXT)))
            }
            t.entry = app
            t.invalidate()
            tiles += t
        }
        for (t in old.values) layer.removeView(t)
        apps = list
        plate.visibility = if (list.isEmpty()) INVISIBLE else VISIBLE
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

    private fun x(i: Int) = MARGIN + (i % COLS) * (TILE_W + GAP)
    private fun y(i: Int) = topRowY + (i / COLS) * PITCH

    /** The full app's GridMetrics.scrollFor: rows rest at 37% of the screen, the row above in view. */
    private fun scrollFor(row: Int): Float {
        if (row <= 0) return 0f
        val rows = (apps.size + COLS - 1) / COLS
        val wanted = topRowY + PITCH * row - restY
        val bottom = topRowY + PITCH * rows + 36f - canvasH
        return minOf(wanted, maxOf(bottom, topRowY + PITCH - restY))
    }

    /**
     * Without hardware acceleration (4.x boxes with no GPU driver for it, Android-x86) every frame
     * of an animation is drawn by the CPU and the 150 ms ones come out as two or three frames.
     * There the focus and the scroll jump, so each key press costs one frame.
     */
    private val smooth get() = isHardwareAccelerated

    private fun place(animate: Boolean) {
        plate.translationY = Ui.px(topRowY - PLATE_PAD_V)
        tiles.forEachIndexed { i, t ->
            val tx = Ui.px(x(i) - TILE_EXT)
            val ty = Ui.px(y(i) - TILE_EXT)
            if (animate && smooth) t.animate().translationX(tx).translationY(ty).setDuration(180).setInterpolator(EASE).start()
            else { t.translationX = tx; t.translationY = ty }
        }
    }

    private fun showFocus(animate: Boolean) {
        pill.focusedLook = focus == PILL
        tiles.forEachIndexed { i, t ->
            val on = i == focus
            t.lifted = on
            t.moving = on && moving
            val s = if (on) FOCUS_SCALE else 1f
            if (animate && smooth) t.animate().scaleX(s).scaleY(s).setDuration(160).setInterpolator(EASE).start()
            else { t.scaleX = s; t.scaleY = s }
        }
        val newRow = if (focus == PILL) 0 else focus / COLS
        // Names: only the focused app's, and none on the plate (the full app's default).
        val app = apps.getOrNull(focus)
        if (focus == PILL || app == null || (newRow == 0 && !moving)) {
            name.visibility = INVISIBLE
        } else {
            name.visibility = VISIBLE
            name.text = if (moving) context.getString(R.string.move_hint) else app.label
            name.translationX = Ui.px(x(focus) + TILE_W / 2) - name.layoutParams.width / 2f
            name.translationY = Ui.px(y(focus) + TILE_H + 10f + TILE_H * (FOCUS_SCALE - 1) / 2 + (RING_GAP + RING_WIDTH) * FOCUS_SCALE)
        }
        if (newRow != row || !animate) {
            val wasGrid = row > 0
            row = newRow
            val to = -Ui.px(scrollFor(row))
            val top = if (row == 0) 1f else 0f
            if (animate && smooth) {
                layer.animate().translationY(to).setDuration(260).setInterpolator(EASE).start()
                clock.animate().alpha(top).setDuration(180).start()
                plate.animate().alpha(top).setDuration(200).start()
            } else {
                layer.translationY = to
                clock.alpha = top
                plate.alpha = top
            }
            pill.showTime = row > 0
            if (wasGrid != (row > 0) || !animate) host.inGrid(row > 0)
        }
    }

    fun onKey(e: KeyEvent): Boolean {
        val app = apps.getOrNull(focus)
        if (press.onKey(e, click = {
                when {
                    moving -> finishMove()
                    focus == PILL -> host.openControlCenter()
                    app != null -> host.launch(app)
                }
            }, longPress = { if (focus != PILL && app != null && !moving) host.openMenu(app, tiles[focus]) })) return true
        when (e.keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (e.action == KeyEvent.ACTION_UP) back()
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                if (e.action == KeyEvent.ACTION_UP && focus != PILL && app != null && !moving) host.openMenu(app, tiles[focus])
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

    /** Back: in the grid, to the top row; on the top row, the screensaver (tvOS). */
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
            clock.refresh()
            pill.requestLayout()
            pill.invalidate()
            val now = System.currentTimeMillis()
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

    /**
     * The Liquid Glass plate under the top row (tvOS 26), the row's full width: the frosted
     * wallpaper behind it, tinted milky and cut to its outline with a lit edge, baked once.
     */
    private class PlateView(context: Context) : View(context) {
        var glass: Bitmap? = null
        private var baked: Bitmap? = null
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val left = Ui.px(MARGIN - PLATE_PAD_H)

        fun rebake() {
            val g = glass ?: run { baked = null; invalidate(); return }
            val top = Ui.px(Ui.canvasH - PITCH - 12f - PLATE_PAD_V)
            val at = RectF(left, top, Ui.px(MARGIN + (COLS - 1) * (TILE_W + GAP) + TILE_W + PLATE_PAD_H), top + Ui.px(TILE_H + 2 * PLATE_PAD_V))
            baked = Shapes.glass(g, Ui.screenW, Ui.screenH, at, Ui.px(32f), Ui.px(1.2f), PLATE_TINT)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            baked?.let { canvas.drawBitmap(it, left, 0f, paint) }
        }

        companion object {
            /** The plate's tint: the frosted picture a little darker, lifted with white, so it reads as milky glass. */
            val PLATE_TINT = android.graphics.ColorMatrixColorFilter(floatArrayOf(
                0.8f, 0f, 0f, 0f, 30f,
                0f, 0.8f, 0f, 0f, 30f,
                0f, 0f, 0.8f, 0f, 30f,
                0f, 0f, 0f, 1f, 0f,
            ))
        }
    }

    /**
     * One tile: its face (the picture cut to the continuous outline, made at focused size), and
     * when focused the full app's baked shadow under it and its high-contrast ring around it, all
     * inside the View's margin so the focus scale carries them.
     */
    class TileView(context: Context) : View(context) {
        lateinit var entry: AppEntry
        var lifted = false
            set(v) { if (field != v) { field = v; invalidate() } }
        var moving = false
            set(v) { if (field != v) { field = v; invalidate() } }
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val dst = Rect()
        private val tmp = RectF()

        override fun onDraw(canvas: Canvas) {
            val ext = Ui.pxi(TILE_EXT)
            val w = width - 2 * ext
            val h = height - 2 * ext
            dst.set(ext, ext, ext + w, ext + h)
            if (lifted) {
                val s = Shapes.shadow(w, h, Ui.px(CORNER), Ui.px(14f))
                val drop = Ui.px(12f)
                tmp.set(ext - s.ext, ext - s.ext + drop, ext + w + s.ext, ext + h + s.ext + drop)
                canvas.drawBitmap(s.image, null, tmp, paint)
            }
            canvas.drawBitmap(entry.tile, null, dst, paint)
            if (lifted) {
                val r = Shapes.ring(w, h, Ui.px(CORNER), Ui.px(RING_GAP), Ui.px(RING_WIDTH), Ui.px(1f), if (moving) 0xFFFFD60A.toInt() else Ui.WHITE)
                tmp.set(ext - r.ext, ext - r.ext, ext + w + r.ext, ext + h + r.ext)
                canvas.drawBitmap(r.image, null, tmp, paint)
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
        const val PITCH = TILE_H + 50f
        const val FOCUS_SCALE = 1.15f
        const val CORNER = 12f
        const val RING_GAP = 5f
        const val RING_WIDTH = 3f
        /** Room around a tile for its shadow and ring. */
        const val TILE_EXT = 36f
        const val PLATE_PAD_H = 26f
        const val PLATE_PAD_V = 20f
        /** Top of the large clock: the date's capitals, just under the status pill. */
        const val CLOCK_Y = 80f
        private val EASE = DecelerateInterpolator(1.6f)
    }
}
