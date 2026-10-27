package app.orchard.tv.lite

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.VideoView
import app.orchard.tv.lite.Ui.textPx
import kotlin.random.Random

/**
 * The screensaver, in the full app's look (ui/home/Screensaver.kt), over everything with Home
 * hidden beneath it.
 *
 * Aerial: Apple's films in the system player (VideoView) through [FilmProxy], a little shade at
 * the top and bottom, the time top left and the place bottom left; both drift a few units every
 * two minutes so nothing sits on the same pixels all night. Three failures in a row and it is the
 * clock.
 *
 * Clock: the large clock (date, time, weather) over the wallpaper at 75%, somewhere new each minute.
 */
class SaverView(context: Context, aerial: Boolean, private val proxy: FilmProxy, private val wallpaper: Bitmap?) : FrameLayout(context) {
    private val handler = Handler(Looper.getMainLooper())
    private val time = context.label("", 33.8f, 600, 0xEBFFFFFF.toInt(), spacingEm = 0.05f)
    private val caption = TextView(context)
    private val shade = Shade(context)
    private var video: VideoView? = null
    private var films: List<Film> = emptyList()
    private var next = 0
    private var failures = 0
    private var clockOnly = !aerial
    private var clock: ClockView? = null

    private val tick = object : Runnable {
        override fun run() {
            time.text = timeText(context)
            clock?.let { it.refresh(); it.weather = Weather.line(context); moveClock() }
            val now = System.currentTimeMillis()
            // Every two minutes the words wander a little.
            if (!clockOnly && (now / 60_000) % 2 == 0L) drift()
            handler.postDelayed(this, 60_000 - now % 60_000 + 50)
        }
    }

    init {
        setBackgroundColor(0xFF000000.toInt())
        keepScreenOn = true
        time.setShadowLayer(Ui.px(4f), 0f, 0f, 0x66000000)
        caption.apply {
            setTextColor(0xEBFFFFFF.toInt())
            textPx(19f)
            typeface = Ui.font(600, 32f)
            if (Build.VERSION.SDK_INT >= 21) letterSpacing = -0.01f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = Ui.pxi(620f)
            includeFontPadding = false
            setShadowLayer(Ui.px(4f), 0f, 0f, 0x66000000)
        }
        if (aerial) {
            films = Aerials.load(context).shuffled()
            if (films.isEmpty()) clockOnly = true
        }
        if (clockOnly) showClock() else showFilms()
        tick.run()
    }

    private fun showClock() {
        clockOnly = true
        video?.let { it.stopPlayback(); removeView(it) }
        video = null
        removeView(shade); removeView(caption); removeView(time)
        // The wallpaper's light under the clock, at 75% over black.
        if (wallpaper != null) addView(ImageView(context).apply {
            setImageBitmap(wallpaper)
            scaleType = ImageView.ScaleType.FIT_XY
            alpha = 0.75f
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val c = ClockView(context).apply { large = true; refresh(); weather = Weather.line(context) }
        clock = c
        addView(c, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        moveClock()
    }

    /** The full app's ClockSaver: anywhere in the screen less a margin and the clock's own size; faded across on a GPU. */
    private fun moveClock() {
        val c = clock ?: return
        val x = Ui.px(64f + (960f - 128f - 420f) * Random.nextFloat())
        val y = Ui.px(56f + (Ui.canvasH - 112f - 170f) * Random.nextFloat())
        if (c.isHardwareAccelerated && c.alpha > 0f && c.translationX != 0f) {
            c.animate().alpha(0f).setDuration(1200).withLayerCompat().setListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    c.animate().setListener(null)
                    c.translationX = x; c.translationY = y
                    c.animate().alpha(1f).setDuration(1200).start()
                }
            }).start()
        } else {
            c.translationX = x; c.translationY = y
        }
    }

    private fun android.view.ViewPropertyAnimator.withLayerCompat() = if (Build.VERSION.SDK_INT >= 16) withLayer() else this

    private fun showFilms() {
        val v = VideoView(context)
        video = v
        addView(v, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        addView(shade, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(time, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            topMargin = Ui.pxi(40f); leftMargin = Ui.pxi(56f)
        })
        addView(caption, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = Ui.pxi(56f); bottomMargin = Ui.pxi(44f)
        })
        v.setOnPreparedListener { mp ->
            failures = 0
            handler.removeCallbacks(stalled)
            mp.setVolume(0f, 0f)
            mp.start()
        }
        v.setOnCompletionListener { play() }
        v.setOnErrorListener { _: MediaPlayer, what: Int, extra: Int ->
            Log.i("AerialLite", "film failed: $what/$extra")
            fail()
            true
        }
        play()
    }

    private fun play() {
        val v = video ?: return
        val film = films[next % films.size]
        next++
        setCaption(film.caption ?: "")
        handler.removeCallbacks(stalled)
        handler.postDelayed(stalled, STALL_MS)
        v.setVideoURI(Uri.parse(proxy.local(film.url)))
    }

    /** One name after the other, as the full app's: the old fades out, then the new fades in. */
    private fun setCaption(text: String) {
        if (!caption.isHardwareAccelerated || caption.text.isNullOrEmpty()) { caption.text = text; return }
        caption.animate().alpha(0f).setDuration(400).setListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                caption.animate().setListener(null)
                caption.text = text
                caption.animate().alpha(1f).setStartDelay(0).setDuration(600).start()
            }
        }).start()
    }

    /** The full app's saverDrift: up to 24 across and 14 down, eased over six seconds. */
    private fun drift() {
        val dx = Ui.px(24f * (Random.nextFloat() * 2 - 1))
        val dy = Ui.px(14f * (Random.nextFloat() * 2 - 1))
        for ((v, sign) in listOf(time to 1f, caption to -1f)) {
            if (v.isHardwareAccelerated) v.animate().translationX(dx).translationY(sign * dy).setDuration(6000).start()
            else { v.translationX = dx; v.translationY = sign * dy }
        }
    }

    private fun fail() {
        handler.removeCallbacks(stalled)
        failures++
        if (failures >= 3) showClock() else play()
    }

    /** No picture after [STALL_MS]: a slow network or a film the decoder never starts. */
    private val stalled = Runnable { fail() }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        video?.stopPlayback()
    }

    /** Just enough shade under the words to read them over bright sky or snow. */
    private class Shade(context: Context) : View(context) {
        private val top = Paint()
        private val bottom = Paint()
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            top.shader = LinearGradient(0f, 0f, 0f, h * 0.18f, 0x4D000000, 0, Shader.TileMode.CLAMP)
            bottom.shader = LinearGradient(0f, h * 0.7f, 0f, h.toFloat(), 0, 0x73000000, Shader.TileMode.CLAMP)
        }
        override fun onDraw(canvas: Canvas) {
            canvas.drawRect(0f, 0f, width.toFloat(), height * 0.18f, top)
            canvas.drawRect(0f, height * 0.7f, width.toFloat(), height.toFloat(), bottom)
        }
    }

    private companion object {
        const val STALL_MS = 20_000L
    }
}
