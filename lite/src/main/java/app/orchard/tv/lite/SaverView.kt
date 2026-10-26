package app.orchard.tv.lite

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.util.Log
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.VideoView
import app.orchard.tv.lite.Ui.textPx
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * The screensaver, over everything with Home hidden beneath it. Aerial: Apple's films in the
 * system player (VideoView), through [FilmProxy]; three failures in a row and it is the clock.
 * Clock: the time on black, moved once a minute so it does not burn in.
 */
class SaverView(context: Context, aerial: Boolean, private val proxy: FilmProxy) : FrameLayout(context) {
    private val handler = Handler(Looper.getMainLooper())
    private val time = TextView(context)
    private val caption = TextView(context)
    private var video: VideoView? = null
    private var films: List<Film> = emptyList()
    private var next = 0
    private var failures = 0
    private var clockOnly = !aerial

    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            time.text = SimpleDateFormat(if (DateFormat.is24HourFormat(context)) "H:mm" else "h:mm", Locale.getDefault()).format(Date(now))
            if (clockOnly) moveClock()
            handler.postDelayed(this, 60_000 - now % 60_000 + 50)
        }
    }

    init {
        setBackgroundColor(0xFF000000.toInt())
        keepScreenOn = true
        time.apply {
            setTextColor(Ui.WHITE)
            typeface = Ui.bold
            includeFontPadding = false
            setShadowLayer(Ui.px(6f), 0f, Ui.px(1f), 0x88000000.toInt())
        }
        caption.apply {
            setTextColor(Ui.DIM)
            textPx(16f)
            setShadowLayer(Ui.px(6f), 0f, Ui.px(1f), 0x88000000.toInt())
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
        caption.visibility = GONE
        if (time.parent == null) addView(time, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        time.textPx(110f)
        moveClock()
    }

    private fun moveClock() {
        val lp = time.layoutParams as LayoutParams
        lp.gravity = Gravity.TOP or Gravity.START
        lp.leftMargin = Ui.pxi(60f + Random.nextInt(480))
        lp.topMargin = Ui.pxi(60f + Random.nextInt(300))
        time.layoutParams = lp
    }

    private fun showFilms() {
        val v = VideoView(context)
        video = v
        addView(v, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        addView(caption, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = Ui.pxi(48f)
            bottomMargin = Ui.pxi(40f)
        })
        addView(time, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            topMargin = Ui.pxi(36f)
            rightMargin = Ui.pxi(48f)
        })
        time.textPx(34f)
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
        caption.text = film.caption ?: ""
        handler.removeCallbacks(stalled)
        handler.postDelayed(stalled, STALL_MS)
        v.setVideoURI(Uri.parse(proxy.local(film.url)))
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

    private companion object {
        const val STALL_MS = 20_000L
    }
}
