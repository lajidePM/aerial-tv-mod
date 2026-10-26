package app.orchard.tv.lite

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class AppEntry(
    val key: String,
    val label: String,
    val component: ComponentName,
    val leanback: Boolean,
    /** False for apps on the system image, which cannot be uninstalled. */
    val removable: Boolean,
    val tile: Bitmap,
) {
    fun launchIntent(): Intent = Intent(Intent.ACTION_MAIN)
        .addCategory(if (leanback) CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
        .setComponent(component)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    companion object {
        /** Intent.CATEGORY_LEANBACK_LAUNCHER, which the 4.x SDKs do not name. */
        const val CATEGORY_LEANBACK_LAUNCHER = "android.intent.category.LEANBACK_LAUNCHER"
    }
}

/**
 * Every launchable app, one tile per package, and the TV's own launcher. Tiles are drawn once per
 * app version and kept as PNGs in cache/tiles, so a cold start only decodes small files.
 */
object AppCatalog {
    private const val TAG = "AerialLite"

    fun load(context: Context, w: Int, h: Int, radius: Float): List<AppEntry> {
        val pm = context.packageManager
        val found = LinkedHashMap<String, Pair<ActivityInfo, Boolean>>()
        fun add(category: String, leanback: Boolean) {
            for (r in pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                val info = r.activityInfo
                if (!found.containsKey(info.packageName)) found[info.packageName] = info to leanback
            }
        }
        add(AppEntry.CATEGORY_LEANBACK_LAUNCHER, true)
        add(Intent.CATEGORY_LAUNCHER, false)
        // The TV's own launcher, which some firmwares declare only as a Home.
        val stockHomes = HashSet<String>()
        for (r in pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)) {
            val info = r.activityInfo
            if (r.priority < 0 || !info.exported || found.containsKey(info.packageName)) continue
            found[info.packageName] = info to false
            stockHomes += info.packageName
        }
        found.remove(context.packageName)

        val dir = File(context.cacheDir, "tiles").apply { mkdirs() }
        val index = context.getSharedPreferences("tiles", Context.MODE_PRIVATE)
        val homeLabel = context.getString(R.string.tile_stock_home)
        // Two threads: these TVs have two to four slow cores, and Home's own thread needs one.
        val pool = Executors.newFixedThreadPool(2)
        try {
            val jobs = found.values.map { (info, leanback) ->
                pool.submit(Callable {
                    runCatching { entry(pm, info, leanback, info.packageName in stockHomes, homeLabel, dir, index, w, h, radius) }
                        .onFailure { Log.w(TAG, "left ${info.packageName} out of Home", it) }
                        .getOrNull()
                })
            }
            return jobs.mapNotNull { it.get() }
        } finally {
            pool.shutdown()
        }
    }

    private fun entry(
        pm: PackageManager, info: ActivityInfo, leanback: Boolean, stockHome: Boolean, homeLabel: String,
        dir: File, index: android.content.SharedPreferences, w: Int, h: Int, radius: Float,
    ): AppEntry {
        val pkg = info.packageName
        val stamp = runCatching { pm.getPackageInfo(pkg, 0).lastUpdateTime }.getOrDefault(0L)
        val system = (info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val component = ComponentName(pkg, info.name)
        val key = "$stamp|$w|$h"
        val file = File(dir, "$pkg.png")
        val cached = index.getString(pkg, null)
        if (cached != null && cached.startsWith("$key|") && file.exists()) {
            BitmapFactory.decodeFile(file.path)?.let {
                val label = if (stockHome) homeLabel else cached.substring(key.length + 1)
                return AppEntry(pkg, label, component, leanback, !system, it)
            }
        }
        val label = runCatching { info.loadLabel(pm).toString().trim() }.getOrDefault("").ifEmpty { pkg }
        val tile = TileArt.fromIcon(icon(pm, info), w, h, radius)
        runCatching {
            FileOutputStream(file).use { tile.compress(Bitmap.CompressFormat.PNG, 100, it) }
            index.edit().putString(pkg, "$key|$label").apply()
        }
        return AppEntry(pkg, if (stockHome) homeLabel else label, component, leanback, !system, tile)
    }

    /**
     * The densest icon the app ships: loadIcon picks the screen's bucket, which on a 160 dpi TV is
     * 48 px, soft on a 120 px tile. getDrawableForDensity came in Android 4.0.3.
     */
    private fun icon(pm: PackageManager, info: ActivityInfo): Drawable {
        val res = info.iconResource
        if (res != 0 && Build.VERSION.SDK_INT >= 15) {
            runCatching {
                pm.getResourcesForApplication(info.applicationInfo).getDrawableForDensity(res, DisplayMetrics.DENSITY_XHIGH)
            }.getOrNull()?.let { return it }
        }
        return runCatching { info.loadIcon(pm) }.getOrNull() ?: pm.defaultActivityIcon
    }

    /** Drops the cached tiles of apps no longer installed. */
    fun prune(context: Context, keep: Set<String>) {
        val index = context.getSharedPreferences("tiles", Context.MODE_PRIVATE)
        val gone = index.all.keys.filter { it !in keep }
        if (gone.isEmpty()) return
        val e = index.edit()
        for (pkg in gone) {
            e.remove(pkg)
            File(File(context.cacheDir, "tiles"), "$pkg.png").delete()
        }
        e.apply()
    }
}
