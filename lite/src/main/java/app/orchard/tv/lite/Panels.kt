package app.orchard.tv.lite

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.orchard.tv.lite.Ui.textPx

/**
 * The full app's Liquid Glass for panels: the frosted wallpaper behind the panel, at half its light
 * so white text reads on snow (LiquidGlass's PanelTint), cut to the panel's outline with its lit
 * edge. Baked once the panel knows where it sits, so a panel is one image draw.
 */
class GlassBox(context: Context, private val radius: Float, private val bottomCorners: Boolean = true) : FrameLayout(context) {
    private var baked: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()

    init { setWillNotDraw(false) }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed || baked == null) post { bake() }
    }

    /**
     * Baked from where layout puts the panel, not where it is drawn this frame: a sheet sliding in
     * or a menu growing out of its tile is moved and scaled by View properties, and baked in
     * passing it took its glass from the wrong part of the wallpaper (off the screen: clear).
     */
    private fun bake() {
        val frost = Glass.frost ?: return invalidate()
        var x = 0f; var y = 0f
        var v: View? = this
        while (v != null) { x += v.left - v.scrollX; y += v.top - v.scrollY; v = v.parent as? View }
        val at = RectF(x, y, x + width, y + height)
        baked = Shapes.glass(frost, Ui.screenW, Ui.screenH, at, Ui.px(radius), Ui.px(1.2f), Glass.PANEL_TINT, bottomCorners)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val b = baked
        if (b != null) canvas.drawBitmap(b, 0f, 0f, paint)
        else {
            paint.color = Ui.GLASS
            val r = Ui.px(radius)
            box.set(0f, 0f, width.toFloat(), height.toFloat() + if (bottomCorners) 0f else r)
            canvas.drawRoundRect(box, r, r, paint)
        }
    }
}

object Glass {
    /** The frosted wallpaper panels are made from; null in no wallpaper's light. */
    @Volatile var frost: Bitmap? = null

    val PANEL_TINT = ColorMatrixColorFilter(floatArrayOf(
        0.48f, 0f, 0f, 0f, 14f,
        0f, 0.48f, 0f, 0f, 14f,
        0f, 0f, 0.48f, 0f, 14f,
        0f, 0f, 0f, 1f, 0f,
    ))
}

/** A line of text in one of the full app's Type styles. */
fun Context.label(text: String, size: Float, weight: Int, color: Int = Ui.WHITE, display: Boolean = false, spacingEm: Float = 0f): TextView =
    TextView(this).apply {
        this.text = text
        setTextColor(color)
        textPx(size)
        typeface = Ui.font(weight, if (display) 32f else 14f)
        includeFontPadding = false
        setSingleLine()
        ellipsize = TextUtils.TruncateAt.END
        if (spacingEm != 0f && Build.VERSION.SDK_INT >= 21) letterSpacing = spacingEm
    }

private fun space(context: Context, h: Float) = View(context).apply { layoutParams = LinearLayout.LayoutParams(1, Ui.pxi(h)) }

private fun LinearLayout.add(v: View, w: Int = LinearLayout.LayoutParams.MATCH_PARENT, h: Int = LinearLayout.LayoutParams.WRAP_CONTENT, top: Float = 0f): View {
    addView(v, LinearLayout.LayoutParams(w, h).apply { topMargin = Ui.pxi(top) })
    return v
}

/**
 * Control Center, as the full app's ControlCenter main page: the time and date with the network,
 * three round buttons (inputs, network, settings), volume and brightness, Personalize, and whether
 * Aerial is the TV's Home. Home stays sharp behind it under a dark veil.
 */
class ControlCenter(
    context: Context,
    close: () -> Unit,
    private val open: (Intent) -> Unit,
    private val personalize: () -> Unit,
    private val noInputs: () -> Unit,
) : Sheet(context, close) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    init {
        // The veil over Home: the full app's 45% black.
        addView(View(context).apply { setBackgroundColor(0x73000000) }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val panel = GlassBox(context, 24f)
        addView(panel, LayoutParams(Ui.pxi(300f), LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            topMargin = Ui.pxi(24f); rightMargin = Ui.pxi(32f)
        })
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.pxi(16f), Ui.pxi(16f), Ui.pxi(16f), Ui.pxi(16f)) }
        panel.addView(col)

        // The time (Type.SmallClock at LargeTitle × 1.05) and date (Callout); the network on the right.
        val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        val times = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        times.addView(context.label(timeText(context), 27.3f, 600, spacingEm = 0.05f))
        times.add(context.label(dateText(), 13f, 500, Ui.SECONDARY), top = 4f)
        head.addView(times, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val (netGlyph, netName, netLevel) = network(context)
        val net = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        net.addView(GlyphView(context, netGlyph, 14f, Ui.SECONDARY, netLevel))
        net.addView(context.label(netName, 11f, 500, Ui.SECONDARY).apply { maxWidth = Ui.pxi(110f) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = Ui.pxi(6f) })
        head.addView(net, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = Ui.pxi(2f) })
        col.add(head)

        // The round buttons, spread across the panel.
        val buttons = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val inputs = RoundButton(context, Glyph.Input, context.getString(R.string.cc_inputs)).apply {
            action = { if (!SystemIntents.openInputPicker(context)) noInputs() }
        }
        val netButton = RoundButton(context, netGlyph, context.getString(R.string.cc_network)).apply {
            level = netLevel
            action = { open(SystemIntents.network(context, netGlyph == Glyph.Ethernet)) }
        }
        val settings = RoundButton(context, Glyph.Gear, context.getString(R.string.cc_settings)).apply {
            action = { open(SystemIntents.settings(context)) }
        }
        for ((i, b) in listOf(inputs, netButton, settings).withIndex()) {
            if (i > 0) buttons.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            buttons.addView(b)
        }
        col.add(buttons, top = 12f)
        row(inputs, netButton, settings)

        val volume = SliderRow(
            context,
            glyph = { if (it <= 0f) Glyph.SpeakerMuted else Glyph.Speaker },
            read = { audio.getStreamVolume(AudioManager.STREAM_MUSIC) to audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) },
            write = { audio.setStreamVolume(AudioManager.STREAM_MUSIC, it, AudioManager.FLAG_PLAY_SOUND) },
            shown = { v, _ -> if (v == 0) context.getString(R.string.cc_muted) else v.toString() },
        )
        col.add(volume, top = 12f)
        row(volume)

        // Brightness where Lite may write the standard setting (granted at install before Android 6.0).
        val canWrite = Build.VERSION.SDK_INT < 23 || Settings.System.canWrite(context)
        if (canWrite && readBrightness(context) != null) {
            val brightness = SliderRow(
                context,
                glyph = { Glyph.Sun },
                // In twentieths, shown as percent: the system's 0-255 is too fine for a remote.
                read = { ((readBrightness(context) ?: 0) * 20 + 127) / 255 to 20 },
                write = { runCatching { Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, (it * 255 + 10) / 20) } },
                shown = { v, _ -> (v * 5).toString() },
            )
            col.add(brightness, top = 6f)
            row(brightness)
        } else {
            val brightness = MenuRow(context, Glyph.Sun, context.getString(R.string.cc_brightness), context.getString(R.string.cc_brightness_in_settings) + " ›")
                .apply { action = { open(Intent(Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            col.add(brightness, top = 6f)
            row(brightness)
        }

        val pers = MenuRow(context, Glyph.Photo, context.getString(R.string.cc_personalize)).apply { action = personalize }
        col.add(pers, top = 6f)
        row(pers)

        // Whether Lite is the TV's Home, and the version.
        val status = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(Ui.pxi(14f), 0, Ui.pxi(14f), 0) }
        val home = isHome(context)
        status.addView(Dot(context, if (home) Ui.GREEN else 0xFFFF9F0A.toInt()), LinearLayout.LayoutParams(Ui.pxi(7f), Ui.pxi(7f)))
        status.addView(context.label(context.getString(if (home) R.string.cc_home_default else R.string.cc_home_not_default), 11f, 500, Ui.SECONDARY),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = Ui.pxi(8f) })
        status.addView(context.label("Aerial Lite " + version(context), 11f, 500, Ui.TERTIARY))
        col.add(status, top = 8f)

        focusFirst()
    }

    companion object {
        /** The network's glyph, name and Wi-Fi level, as the full app's status pill shows them. */
        fun network(context: Context): Triple<Glyph, String, Float> {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            @Suppress("DEPRECATION")
            val info = runCatching { cm.activeNetworkInfo }.getOrNull()
            @Suppress("DEPRECATION")
            return when {
                info == null || !info.isConnected -> Triple(Glyph.Offline, context.getString(R.string.net_offline), 0f)
                info.type == ConnectivityManager.TYPE_ETHERNET -> Triple(Glyph.Ethernet, context.getString(R.string.net_ethernet), 1f)
                else -> {
                    val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    val conn = runCatching { wifi?.connectionInfo }.getOrNull()
                    val level = conn?.let { WifiManager.calculateSignalLevel(it.rssi, 3) / 2f } ?: 1f
                    val ssid = conn?.ssid?.trim('"')?.takeIf { it.isNotEmpty() && !it.contains("unknown ssid") }
                    Triple(Glyph.Wifi, ssid ?: context.getString(R.string.net_wifi), level)
                }
            }
        }

        fun readBrightness(context: Context): Int? = runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrNull()

        fun isHome(context: Context): Boolean = runCatching {
            context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName == context.packageName
        }.getOrDefault(false)

        fun version(context: Context): String = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
    }
}

/** A glyph as a plain view. */
class GlyphView(context: Context, private val glyph: Glyph, private val size: Float, private val color: Int, private val level: Float = 1f) : View(context) {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(Ui.pxi(size), Ui.pxi(size))
    override fun onDraw(canvas: Canvas) = canvas.drawBitmap(Glyphs.image(glyph, Ui.pxi(size), color, level), 0f, 0f, paint)
}

private class Dot(context: Context, private val color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        paint.color = color
        canvas.drawCircle(width / 2f, height / 2f, width / 2f, paint)
    }
}

/**
 * A tile's menu, as the full app's AppMenu: a glass card 250 wide, under the tile when the whole
 * menu fits below it, else above, the app's name over glyph rows.
 */
class TileMenu(context: Context, close: () -> Unit, title: String, anchor: RectF, rows: List<MenuRow>) : Sheet(context, close) {
    init {
        val w = Ui.px(250f)
        val panel = GlassBox(context, 20f)
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.pxi(8f), Ui.pxi(8f), Ui.pxi(8f), Ui.pxi(8f)) }
        col.addView(context.label(title, 15f, 600), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = Ui.pxi(14f); rightMargin = Ui.pxi(14f); topMargin = Ui.pxi(8f); bottomMargin = Ui.pxi(6f)
        })
        for (r in rows) { col.addView(r); row(r) }
        panel.addView(col)
        val below = anchor.bottom + Ui.px(296f) <= Ui.screenH
        val x = anchor.left.coerceIn(Ui.px(24f), (Ui.screenW - w - Ui.px(24f)).coerceAtLeast(Ui.px(24f)))
        addView(panel, LayoutParams(w.toInt(), LayoutParams.WRAP_CONTENT).apply { leftMargin = x.toInt() })
        panel.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                panel.viewTreeObserver.removeOnPreDrawListener(this)
                val gap = Ui.px(18f)
                val y = if (below) anchor.bottom + gap else anchor.top - gap - panel.height
                // Placed by layout, so its glass is baked from where it stays.
                (panel.layoutParams as LayoutParams).topMargin = y.coerceAtLeast(Ui.px(24f)).toInt()
                panel.requestLayout()
                // The menu grows out of the tile, as in the full app.
                panel.pivotX = (anchor.centerX() - x).coerceIn(0f, w)
                panel.pivotY = if (below) 0f else panel.height.toFloat()
                if (panel.isHardwareAccelerated) {
                    panel.alpha = 0f; panel.scaleX = 0.92f; panel.scaleY = 0.92f
                    panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).start()
                }
                // Not this frame: it would show the menu at the top for one frame before its place.
                return false
            }
        })
        focusFirst()
    }
}

/**
 * Personalize, as the full app's WallpaperSheet (without its phone card: Lite has no phone page):
 * a glass sheet from the bottom, the wallpaper cards, then tvOS-style groups of setting rows with
 * a note under each that follows the focused row.
 */
class Personalize(
    context: Context,
    close: () -> Unit,
    private val settings: LiteSettings,
    private val onWallpaper: (String) -> Unit,
    private val onClock: (Boolean) -> Unit,
    private val onHidden: () -> Unit,
    private val onSaverNow: () -> Unit,
    hiddenCount: Int,
) : Sheet(context, close) {
    private val thumbs = ArrayList<Pair<String, Thumb>>()
    private val strip = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val chips = ArrayList<Chip>()
    private val hints = HashMap<Control, Pair<Int, TextView>>()

    init {
        val sheetH = Ui.px(360f)
        val panel = GlassBox(context, 30f, bottomCorners = false)
        addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, sheetH.toInt(), Gravity.BOTTOM))
        val viewportView = FrameLayout(context)
        panel.addView(viewportView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            leftMargin = Ui.pxi(40f); rightMargin = Ui.pxi(32f); topMargin = Ui.pxi(24f)
        })
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        viewportView.addView(col, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        scroller = col
        viewport = (sheetH - Ui.px(24f)).toInt()

        col.add(context.label(context.getString(R.string.wp_title), 26f, 600, display = true, spacingEm = -0.015f))

        // Wallpaper: the built-in pictures, the chosen one ticked.
        col.add(section(R.string.wp_wallpaper), top = 18f)
        val stripClip = FrameLayout(context).apply { clipChildren = false }
        stripClip.addView(strip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        col.add(stripClip, top = 10f)
        for (key in Wallpapers.keys) {
            val t = Thumb(context, Wallpapers.name(context, key)) { Gradients.currentKey(settings.wallpaper) == key }
            t.action = {
                settings.wallpaper = key
                for ((_, other) in thumbs) other.invalidate()
                onWallpaper(key)
            }
            strip.addView(t, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = Ui.pxi(16f) })
            thumbs += key to t
        }
        row(*thumbs.map { it.second }.toTypedArray())
        renderThumbs()

        // Home: the clock's size, hidden apps.
        col.add(section(R.string.st_home_layout), top = 14f)
        val home = group()
        col.add(home, top = 10f)
        val homeNote = note()
        col.add(homeNote)
        val clockChips = listOf(false to R.string.st_clock_small, true to R.string.st_clock_large).map { (large, label) ->
            Chip(context, context.getString(label), { settings.largeClock == large }).apply {
                action = { settings.largeClock = large; onClock(large); refreshChips() }
            }
        }
        home.addView(settingRow(R.string.st_clock_size, clockChips, first = true))
        val hidden = Chip(context, if (hiddenCount > 0) context.getString(R.string.st_hidden_count, hiddenCount) else context.getString(R.string.st_hidden_none), { false }, Glyph.ChevronRight)
            .apply { action = onHidden }
        home.addView(settingRow(R.string.st_hidden_apps, listOf(hidden)))
        row(*clockChips.toTypedArray())
        row(hidden)
        hints[clockChips[0]] = R.string.st_clock_size_hint to homeNote
        hints[clockChips[1]] = R.string.st_clock_size_hint to homeNote
        hints[hidden] = R.string.st_hidden_apps_hint to homeNote

        // Screen saver: its kind, when it starts, and now.
        col.add(section(R.string.wp_screensaver), top = 4f)
        val saver = group()
        col.add(saver, top = 10f)
        col.add(note())
        val kinds = listOf(LiteSettings.Saver.Aerial to R.string.ss_aerial, LiteSettings.Saver.Clock to R.string.ss_clock).map { (kind, label) ->
            Chip(context, context.getString(label), { settings.saver == kind }).apply { action = { settings.saver = kind; refreshChips() } }
        }
        val delays = (LiteSettings.SAVER_MINUTES.map { it to context.getString(R.string.ss_minutes, it) } + (0 to context.getString(R.string.ss_never))).map { (m, label) ->
            Chip(context, label, { settings.saverMinutes == m }).apply { action = { settings.saverMinutes = m; refreshChips() } }
        }
        val now = Chip(context, context.getString(R.string.ss_start), { false }, Glyph.Play).apply { action = onSaverNow }
        saver.addView(settingRow(R.string.ss_kind, kinds + now, first = true))
        saver.addView(settingRow(R.string.ss_delay, delays))
        row(*(kinds + now).toTypedArray())
        row(*delays.toTypedArray())
        chips += clockChips + kinds + delays

        onFocusMoved = { c ->
            hints[c]?.let { (hint, view) -> view.text = context.getString(hint) }
            if (c is Thumb) keepThumbInView(c)
        }
        // Focus starts on the chosen wallpaper.
        focusFirst(0, thumbs.indexOfFirst { it.first == Gradients.currentKey(settings.wallpaper) }.coerceAtLeast(0))

        // It slides up, as the full app's sheet (the iOS sheet curve, near enough).
        panel.translationY = sheetH
        panel.post { if (panel.isHardwareAccelerated) panel.animate().translationY(0f).setDuration(320).setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start() else panel.translationY = 0f }
    }

    private fun refreshChips() { for (c in chips) c.refresh() }

    private fun section(title: Int) = context.label(context.getString(title), 15f, 600).apply { setPadding(Ui.pxi(4f), 0, 0, 0) }

    /** A card of setting rows: white at 5%, corners 16. */
    private fun group() = object : LinearLayout(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x0DFFFFFF }
        private val box = RectF()
        init { orientation = VERTICAL; setWillNotDraw(false) }
        override fun onDraw(canvas: Canvas) {
            box.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(box, Ui.px(16f), Ui.px(16f), paint)
        }
    }

    /** The note under a group: two lines of room, tertiary. */
    private fun note() = TextView(context).apply {
        setTextColor(Ui.TERTIARY); textPx(11f); typeface = Ui.font(500)
        maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        setPadding(Ui.pxi(16f), Ui.pxi(7f), Ui.pxi(16f), 0)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.pxi(38f))
    }

    /** One row of a group: the setting's name on the left, its chips on the right, a hairline above all but the first. */
    private fun settingRow(label: Int, controls: List<Control>, first: Boolean = false): View {
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        if (!first) box.addView(View(context).apply { setBackgroundColor(Ui.HAIRLINE) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { leftMargin = Ui.pxi(18f) })
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.pxi(18f), 0, Ui.pxi(10f), 0); clipChildren = false
        }
        line.addView(context.label(context.getString(label), 14f, 500), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        for (c in controls) line.addView(c, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = Ui.pxi(10f) })
        box.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.pxi(58f)))
        box.clipChildren = false
        return box
    }

    private fun keepThumbInView(t: Thumb) {
        val room = (strip.parent as View).width
        val left = t.left + strip.translationX
        val to = when {
            left < 0 -> -t.left.toFloat()
            left + t.width > room -> (room - t.right).toFloat()
            else -> strip.translationX
        }.coerceAtMost(0f)
        if (isHardwareAccelerated) strip.animate().translationX(to).setDuration(200).start() else strip.translationX = to
    }

    /** The cards' pictures, drawn small in the background, corners cut. */
    private fun renderThumbs() {
        val w = Ui.pxi(168f); val h = Ui.pxi(94f)
        Thread {
            for ((key, t) in thumbs) {
                val pic = if (key == Wallpapers.DAILY) Wallpapers.dailyFile(context)?.let { runCatching { Images.decodeCover(it, w, h) }.getOrNull() }
                    else Gradients.byKey(key)?.let { runCatching { Gradients.render(it, w, h) }.getOrNull() }
                if (pic == null) continue
                val face = Shapes.face(pic, w, h, Ui.px(12f), 0f)
                pic.recycle()
                t.post { t.picture = face }
            }
        }.start()
    }
}

/** Hidden apps, as a glass card like Control Center: pick one to put it back on Home. */
class HiddenApps(context: Context, close: () -> Unit, apps: List<AppEntry>, unhide: (AppEntry) -> Unit) : Sheet(context, close) {
    init {
        addView(View(context).apply { setBackgroundColor(0x73000000) }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val panel = GlassBox(context, 24f)
        addView(panel, LayoutParams(Ui.pxi(320f), LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            topMargin = Ui.pxi(24f); rightMargin = Ui.pxi(32f)
        })
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.pxi(8f), Ui.pxi(16f), Ui.pxi(8f), Ui.pxi(12f)) }
        panel.addView(col)
        col.addView(context.label(context.getString(R.string.hidden_title), 19f, 600, display = true), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = Ui.pxi(14f)
        })
        col.addView(context.label(context.getString(if (apps.isEmpty()) R.string.hidden_empty else R.string.hidden_hint), 13f, 500, Ui.SECONDARY),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = Ui.pxi(14f); topMargin = Ui.pxi(6f); bottomMargin = Ui.pxi(8f)
            })
        for (app in apps.take(9)) {
            val r = MenuRow(context, Glyph.EyeSlash, app.label).apply { action = { unhide(app) } }
            col.addView(r)
            row(r)
        }
        focusFirst()
    }
}
