package app.orchard.tv.lite

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Resources
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.widget.FrameLayout
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * Aerial Lite's only Activity: Home. Holds Home, at most one panel and the screensaver, and routes
 * every key itself; loading runs on one background thread so the main thread only places views.
 */
class HomeActivity : Activity(), HomeView.Host {
    private lateinit var settings: LiteSettings
    private lateinit var root: FrameLayout
    private lateinit var home: HomeView
    private var panel: Sheet? = null
    private var saver: SaverView? = null
    private var backdrop: Drawable? = null
    /** The wallpaper as Home shows it, for the clock screensaver's light. */
    private var wallpaperPicture: android.graphics.Bitmap? = null
    /** The grid's background: the frosted wallpaper under 55% black, stretched (the full app's GRID_SHADE). */
    private var gridBackdrop: Drawable? = null
    private var inGrid = false
    private var all: List<AppEntry> = emptyList()
    private val work = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val proxy by lazy { FilmProxy(applicationContext) }
    private var resumed = false
    /** The key whose press woke the screensaver: its repeats and release are not for Home. */
    private var swallow = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        TileFactory.useScreen()
        settings = LiteSettings(this)
        Weather.init(this)
        root = FrameLayout(this)
        home = HomeView(this, this)
        home.setLargeClock(settings.largeClock)
        root.addView(home)
        setContentView(root)
        hideBars()
        loadBackdrop(settings.wallpaper)
        loadApps()
        registerReceiver(packages, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
    }

    override fun onDestroy() {
        unregisterReceiver(packages)
        hideSaver()
        proxy.stop()
        work.shutdown()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    /**
     * TVs have no navigation bar; Android-x86 and a few boxes do. HIDE_NAVIGATION alone, without
     * 4.4's immersive flags: those show a "swipe down to exit full screen" dialog the first time,
     * and a remote has no way to swipe.
     */
    @Suppress("DEPRECATION")
    private fun hideBars() {
        val id = Resources.getSystem().getIdentifier("config_showNavigationBar", "bool", "android")
        val navBar = id != 0 && Resources.getSystem().getBoolean(id)
        root.systemUiVisibility = if (navBar) android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION else android.view.View.SYSTEM_UI_FLAG_LOW_PROFILE
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        home.refreshClock()
        home.clock.weather = Weather.line(this)
        // Bing's photo of the day: looked at again when Home is back, at most every three hours.
        if (settings.wallpaper == Wallpapers.DAILY && System.currentTimeMillis() - backdropLoadedAt > 3 * 60 * 60 * 1000L) loadBackdrop(settings.wallpaper)
        Weather.refreshIfStale({ work.execute(it) }) { handler.post { home.clock.weather = Weather.line(this) } }
        resetIdle()
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacks(idle)
        hideSaver()
        super.onPause()
    }

    /** The Home key while Home is in front: back to the first tile, as the full app does. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            hideSaver()
            closePanel()
            home.reset()
        }
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        resetIdle()
        if (saver != null) {
            if (e.action == KeyEvent.ACTION_DOWN) {
                swallow = e.keyCode
                hideSaver()
            }
            return true
        }
        if (e.keyCode == swallow) {
            if (e.action == KeyEvent.ACTION_UP) swallow = -1
            return true
        }
        panel?.let { if (it.onKey(e)) return true }
        if (panel == null && home.onKey(e)) return true
        return super.dispatchKeyEvent(e)
    }

    // ---- loading ----

    private var backdropLoadedAt = 0L

    private fun loadBackdrop(key: String) {
        backdropLoadedAt = System.currentTimeMillis()
        work.execute {
            val b = runCatching { Wallpapers.load(this, key) }
                .onFailure { Log.w(TAG, "wallpaper $key", it) }.getOrNull() ?: return@execute
            val grid = Images.darkened(b.frost, 0.55f)
            handler.post {
                if (isFinishing) return@post
                backdrop = BitmapDrawable(resources, b.home)
                wallpaperPicture = b.home
                Glass.frost = b.frost
                gridBackdrop = BitmapDrawable(resources, grid).apply { setFilterBitmap(true) }
                home.setGlass(b.frost)
                if (saver == null) window.setBackgroundDrawable(if (inGrid) gridBackdrop else backdrop)
            }
        }
    }

    private fun loadApps() {
        work.execute {
            val started = System.currentTimeMillis()
            // Home first, with stand-ins for tiles not made yet; then the real ones as they come.
            val first = AppCatalog.load(this, quick = true)
            Log.i(TAG, "${first.size} apps in ${System.currentTimeMillis() - started} ms")
            handler.post { if (!isFinishing) { all = first; arrange() } }
            if (first.any { it.placeholder }) {
                val list = AppCatalog.load(this) { batch -> handler.post { if (!isFinishing) replace(batch) } }
                Log.i(TAG, "${list.size} tiles made in ${System.currentTimeMillis() - started} ms")
            }
            AppCatalog.prune(this, first.mapTo(HashSet()) { it.key })
            // The icon library's pictures, once Home is up; new ones redraw those tiles.
            if (runCatching { IconLibrary.refresh(AppCatalog.installed) }.getOrDefault(false)) handler.post(reload)
        }
    }

    /** Tiles that are ready take the place of their stand-ins. */
    private fun replace(batch: List<AppEntry>) {
        val byKey = HashMap<String, AppEntry>()
        for (e in batch) byKey[e.key] = e
        all = all.map { byKey[it.key] ?: it }
        arrange()
    }

    private val reload = Runnable { loadApps() }

    private val packages = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            handler.removeCallbacks(reload)
            handler.postDelayed(reload, 500)
        }
    }

    /** Home's tiles: the owner's order first, new apps after, hidden ones left out. */
    private fun arrange() {
        val order = settings.order
        val hidden = settings.hidden
        val rank = HashMap<String, Int>()
        order.forEachIndexed { i, k -> rank[k] = i }
        val shown = all.filter { it.key !in hidden }
            .sortedBy { rank[it.key] ?: (order.size + all.indexOf(it)) }
        home.setApps(shown)
    }

    // ---- HomeView.Host ----

    override fun launch(app: AppEntry) {
        try {
            startActivity(app.launchIntent())
        } catch (t: Throwable) {
            Log.w(TAG, "could not open ${app.key}", t)
            Toast.makeText(this, getString(R.string.launch_failed, app.label), Toast.LENGTH_SHORT).show()
        }
    }

    override fun openMenu(app: AppEntry, tile: android.view.View) {
        val rows = ArrayList<MenuRow>()
        rows += MenuRow(this, Glyph.Move, getString(R.string.menu_move)).apply { action = { closePanel(); home.startMove(app) } }
        rows += MenuRow(this, Glyph.EyeSlash, getString(R.string.menu_hide)).apply {
            action = {
                closePanel()
                settings.hidden = settings.hidden + app.key
                arrange()
            }
        }
        rows += MenuRow(this, Glyph.Info, getString(R.string.menu_info)).apply {
            action = { closePanel(); open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app.component.packageName))) }
        }
        if (app.removable) rows += MenuRow(this, Glyph.Trash, getString(R.string.menu_uninstall), destructive = true).apply {
            action = { closePanel(); open(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + app.component.packageName))) }
        }
        showPanel(TileMenu(this, ::closePanel, app.label, tileRect(tile), rows))
    }

    /** Where [tile]'s picture is on screen, at its focused size. */
    private fun tileRect(tile: android.view.View): android.graphics.RectF {
        val loc = IntArray(2)
        tile.getLocationInWindow(loc)
        val ext = Ui.px(HomeView.TILE_EXT) * tile.scaleX
        // getLocationInWindow gives the scaled view's top left.
        return android.graphics.RectF(loc[0] + ext, loc[1] + ext, loc[0] + tile.width * tile.scaleX - ext, loc[1] + tile.height * tile.scaleY - ext)
    }

    override fun openControlCenter() {
        showPanel(ControlCenter(this, ::closePanel, open = { closePanel(); open(it) }, personalize = ::openPersonalize, noInputs = {
            Toast.makeText(this, getString(R.string.cc_no_inputs), Toast.LENGTH_SHORT).show()
        }))
    }

    private fun openPersonalize() {
        showPanel(Personalize(
            this, ::closePanel, settings,
            onWallpaper = {
                // Baking takes a moment on these CPUs: only the one the owner stops on.
                handler.removeCallbacks(bake)
                handler.postDelayed(bake, 500)
            },
            onClock = { home.setLargeClock(it) },
            onHidden = ::openHidden,
            onSaverNow = { closePanel(); showSaver() },
            hiddenCount = all.count { it.key in settings.hidden },
        ))
    }

    private val bake = Runnable { loadBackdrop(settings.wallpaper) }

    private fun openHidden() {
        val hidden = settings.hidden
        showPanel(HiddenApps(this, ::closePanel, all.filter { it.key in hidden }.sortedBy { it.label }) { app ->
            settings.hidden = settings.hidden - app.key
            arrange()
            openHidden()
        })
    }

    override fun startSaver() = showSaver()

    /**
     * Down in the grid the background is the frosted wallpaper under black, as in the full app.
     * The swap fades over the window background (two full-screen draws only while it fades), then
     * the window background itself becomes the new picture. Without hardware acceleration it jumps.
     */
    override fun inGrid(grid: Boolean) {
        if (inGrid == grid) return
        inGrid = grid
        val to = (if (grid) gridBackdrop else backdrop) ?: return
        if (saver != null) return
        if (!root.isHardwareAccelerated) { window.setBackgroundDrawable(to); return }
        val from = (if (grid) backdrop else gridBackdrop) ?: run { window.setBackgroundDrawable(to); return }
        val fade = android.graphics.drawable.TransitionDrawable(arrayOf(from, to)).apply { isCrossFadeEnabled = true }
        window.setBackgroundDrawable(fade)
        fade.startTransition(220)
        handler.postDelayed({ if (inGrid == grid && saver == null) window.setBackgroundDrawable(to) }, 260)
    }

    override fun saveOrder(keys: List<String>) {
        // Hidden apps keep their place for when they come back.
        val hidden = settings.order.filter { it in settings.hidden }
        settings.order = keys + hidden
    }

    // ---- panels and the screensaver ----

    private fun showPanel(p: Sheet) {
        panel?.let { root.removeView(it) }
        panel = p
        root.addView(p)
        // Panels at the top right take the status pill's place, as in the full app.
        home.pill.alpha = if (p is ControlCenter || p is HiddenApps) 0f else 1f
        redrawAllInSoftware()
    }

    private fun closePanel() {
        panel?.let { root.removeView(it) }
        panel = null
        home.pill.alpha = 1f
        redrawAllInSoftware()
    }

    /**
     * Drawn by the CPU, a frame only repaints its dirty region and keeps the rest from the buffer
     * before. Right after the first start (Android-x86 4.4, every fresh install) that buffer can
     * still be black, and opening a panel left Home black around it but for a strip at the top.
     * A panel opens or closes rarely, so there the whole window is drawn again.
     */
    private fun redrawAllInSoftware() {
        if (!root.isHardwareAccelerated) root.invalidate()
    }

    private fun showSaver() {
        if (saver != null) return
        closePanel()
        val s = SaverView(this, settings.saver == LiteSettings.Saver.Aerial, proxy, wallpaperPicture)
        saver = s
        root.addView(s)
        // Home underneath would be drawn for nothing: hide it and the wallpaper.
        home.visibility = android.view.View.INVISIBLE
        window.setBackgroundDrawable(ColorDrawable(0xFF000000.toInt()))
        // Holding the screen on keeps the firmware's screensaver from starting over ours; after a
        // long stretch it lets go so the TV's own power policy can still turn the panel off.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        handler.postDelayed(releaseScreen, SCREENSAVER_HOLD_MS)
    }

    private val releaseScreen = Runnable { window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }

    private fun hideSaver() {
        val s = saver ?: return
        saver = null
        s.release()
        root.removeView(s)
        handler.removeCallbacks(releaseScreen)
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        home.visibility = android.view.View.VISIBLE
        (if (inGrid) gridBackdrop else backdrop)?.let { window.setBackgroundDrawable(it) }
        home.refreshClock()
        resetIdle()
    }

    private val idle = Runnable { if (resumed) showSaver() }

    private fun resetIdle() {
        handler.removeCallbacks(idle)
        if (resumed && saver == null && settings.saverMinutes > 0) {
            handler.postDelayed(idle, idleBeforeSaver())
        }
    }

    /**
     * The full app's rule: Aerial's screensaver has to start before the firmware's (ad-carrying)
     * one. Xiaomi keeps that delay in system/screen_saver_timeout; start 15 s ahead of it when it
     * is shorter than the chosen delay, and never sooner than 30 s.
     */
    private fun idleBeforeSaver(): Long {
        val chosen = settings.saverMinutes * 60_000L
        val firmware = runCatching { Settings.System.getLong(contentResolver, "screen_saver_timeout") }.getOrNull()?.takeIf { it > 0 }
        return if (firmware != null) minOf(chosen, maxOf(30_000L, firmware - 15_000L)) else chosen
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) {
            Log.w(TAG, "could not open $intent", t)
        }
    }

    private companion object {
        const val TAG = "AerialLite"
        const val SCREENSAVER_HOLD_MS = 4 * 60 * 60 * 1000L
    }
}
