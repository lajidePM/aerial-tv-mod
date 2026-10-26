package app.orchard.tv.lite

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Resources
import android.graphics.Bitmap
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
    private var panel: Panel? = null
    private var saver: SaverView? = null
    private var backdrop: Drawable? = null
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
        settings = LiteSettings(this)
        root = FrameLayout(this)
        home = HomeView(this, this)
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

    private fun loadBackdrop(key: String) {
        work.execute {
            val b: Bitmap = runCatching { Wallpapers.load(this, key) }
                .onFailure { Log.w(TAG, "wallpaper $key", it) }.getOrNull() ?: return@execute
            handler.post {
                if (isFinishing) return@post
                val d = BitmapDrawable(resources, b)
                backdrop = d
                if (saver == null) window.setBackgroundDrawable(d)
            }
        }
    }

    private fun loadApps() {
        val w = Ui.pxi(HomeView.TILE_W * HomeView.FOCUS_SCALE)
        val h = Ui.pxi(HomeView.TILE_H * HomeView.FOCUS_SCALE)
        val r = Ui.px(HomeView.RADIUS * HomeView.FOCUS_SCALE)
        work.execute {
            val started = System.currentTimeMillis()
            val list = AppCatalog.load(this, w, h, r)
            AppCatalog.prune(this, list.mapTo(HashSet()) { it.key })
            Log.i(TAG, "${list.size} apps in ${System.currentTimeMillis() - started} ms")
            handler.post {
                if (isFinishing) return@post
                all = list
                arrange()
            }
        }
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

    override fun openMenu(app: AppEntry) {
        val rows = ArrayList<Row>()
        rows += Row.Action(getString(R.string.menu_move)) {
            closePanel()
            home.startMove(app)
        }
        rows += Row.Action(getString(R.string.menu_hide)) {
            closePanel()
            settings.hidden = settings.hidden + app.key
            arrange()
        }
        rows += Row.Action(getString(R.string.menu_info)) {
            closePanel()
            open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app.component.packageName)))
        }
        if (app.removable) rows += Row.Action(getString(R.string.menu_uninstall)) {
            closePanel()
            open(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + app.component.packageName)))
        }
        showPanel(Panel(this, app.label, rows, ::closePanel))
    }

    override fun openSettings() {
        val keys = Wallpapers.keys
        val savers = LiteSettings.Saver.entries
        val minutes = LiteSettings.SAVER_MINUTES
        val rows = ArrayList<Row>()
        rows += Row.Choice(getString(R.string.wallpaper), keys.map { Wallpapers.name(it) }, keys.indexOf(settings.wallpaper).coerceAtLeast(0)) {
            settings.wallpaper = keys[it]
            // Baking takes a moment on these CPUs: only the one the owner stops on.
            handler.removeCallbacks(bake)
            handler.postDelayed(bake, 500)
        }
        rows += Row.Choice(getString(R.string.screensaver), savers.map { getString(saverName(it)) }, savers.indexOf(settings.saver)) {
            settings.saver = savers[it]
            resetIdle()
        }
        rows += Row.Choice(getString(R.string.saver_after), minutes.map { getString(R.string.minutes, it) },
            minutes.indexOf(settings.saverMinutes).coerceAtLeast(0)) {
            settings.saverMinutes = minutes[it]
            resetIdle()
        }
        rows += Row.Action(getString(R.string.saver_now)) {
            closePanel()
            showSaver()
        }
        rows += Row.Action(getString(R.string.hidden_apps), settings.hidden.size.takeIf { it > 0 }?.toString()) { openHidden() }
        rows += Row.Action(getString(R.string.system_settings)) {
            closePanel()
            open(Intent(Settings.ACTION_SETTINGS))
        }
        rows += Row.Note(getString(R.string.version, versionName(), Build.VERSION.RELEASE))
        showPanel(Panel(this, getString(R.string.settings), rows, ::closePanel))
    }

    private val bake = Runnable { loadBackdrop(settings.wallpaper) }

    private fun openHidden() {
        val hidden = settings.hidden
        val rows = ArrayList<Row>()
        for (app in all.filter { it.key in hidden }.sortedBy { it.label }) {
            rows += Row.Action(app.label, getString(R.string.unhide)) {
                settings.hidden = settings.hidden - app.key
                arrange()
                openHidden()
            }
        }
        if (rows.isEmpty()) rows += Row.Note(getString(R.string.no_hidden))
        showPanel(Panel(this, getString(R.string.hidden_apps), rows, ::closePanel))
    }

    override fun startSaver() = showSaver()

    override fun saveOrder(keys: List<String>) {
        // Hidden apps keep their place for when they come back.
        val hidden = settings.order.filter { it in settings.hidden }
        settings.order = keys + hidden
    }

    // ---- panels and the screensaver ----

    private fun showPanel(p: Panel) {
        panel?.let { root.removeView(it) }
        panel = p
        root.addView(p)
        redrawAllInSoftware()
    }

    private fun closePanel() {
        panel?.let { root.removeView(it) }
        panel = null
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
        val s = SaverView(this, settings.saver == LiteSettings.Saver.Aerial, proxy)
        saver = s
        root.addView(s)
        // Home underneath would be drawn for nothing: hide it and the wallpaper.
        home.visibility = android.view.View.INVISIBLE
        window.setBackgroundDrawable(ColorDrawable(0xFF000000.toInt()))
    }

    private fun hideSaver() {
        val s = saver ?: return
        saver = null
        s.release()
        root.removeView(s)
        home.visibility = android.view.View.VISIBLE
        backdrop?.let { window.setBackgroundDrawable(it) }
        home.refreshClock()
        resetIdle()
    }

    private val idle = Runnable { if (resumed) showSaver() }

    private fun resetIdle() {
        handler.removeCallbacks(idle)
        if (resumed && saver == null && settings.saver != LiteSettings.Saver.Off) {
            handler.postDelayed(idle, settings.saverMinutes * 60_000L)
        }
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) {
            Log.w(TAG, "could not open $intent", t)
        }
    }

    private fun versionName() = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""

    private fun saverName(s: LiteSettings.Saver) = when (s) {
        LiteSettings.Saver.Aerial -> R.string.saver_aerial
        LiteSettings.Saver.Clock -> R.string.saver_clock
        LiteSettings.Saver.Off -> R.string.saver_off
    }

    private companion object {
        const val TAG = "AerialLite"
    }
}
