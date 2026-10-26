package app.orchard.tv.wallpaper

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import app.orchard.tv.BuildConfig
import app.orchard.tv.system.DiagLog
import app.orchard.tv.settings.AerialSettings
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Display
import android.view.WindowManager
import androidx.core.content.getSystemService
import org.json.JSONObject
import java.util.Locale

/**
 * Apple TV's aerial screensaver films. Aerial ships only the catalogue (assets/aerials.json, built
 * from Apple's public resources-16.tar): addresses and place names. The films themselves stream from
 * Apple's servers while the screensaver runs, the way the Apple TV plays them.
 */
class Aerial(
    val id: String,
    val label: String,
    val category: String,
    /** 1080p H.264, about 10 Mbps: plays everywhere. */
    val url: String,
    /** 4K SDR HEVC, about 12 Mbps. */
    val uhd: String,
    /** 4K Dolby Vision (profile 5, HEVC 10-bit), about 24 Mbps. */
    val dolbyVision: String,
    /** (second, caption) pairs: tvOS changes the place name as the camera moves on. */
    val captions: List<Pair<Int, String>>,
) {
    fun captionAt(seconds: Int): String = captions.lastOrNull { it.first <= seconds }?.second ?: label

    fun url(quality: AerialQuality): String = when (quality) {
        AerialQuality.DolbyVision -> dolbyVision
        AerialQuality.Uhd -> uhd
        else -> url
    }
}

/** Which of Apple's files the screensaver streams. Auto takes the best this TV can play. */
enum class AerialQuality(val key: String) {
    Auto("auto"), Hd("1080p"), Uhd("4k"), DolbyVision("dolby_vision");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Auto
    }
}

/**
 * What this TV's decoders and display can play, asked once. A TV's UI is usually 1080p whatever
 * its panel (Xiaomi MiTV-MFTR0 reports a single 1920×1080 mode), so the panel cannot be asked;
 * the decoders can. 4K SDR costs about what the 1080p H.264 files do (12 against 10 Mbps), so a
 * TV that decodes it gets it. Dolby Vision needs a decoder for profile 5 at 4K and a display that
 * lists Dolby Vision: Apple's HDR files are profile 5, which has no HDR10 base layer to fall back
 * on, and decoded by anything else they come out green and purple.
 */
object AerialFormats {
    @Volatile
    private var probed: Set<AerialQuality>? = null

    fun supported(context: Context): Set<AerialQuality> = probed ?: probe(context).also { probed = it }

    /** [chosen], or for Auto the best supported; a choice the TV cannot play falls back too. */
    fun resolve(context: Context, chosen: AerialQuality): AerialQuality {
        val ok = supported(context)
        return if (chosen != AerialQuality.Auto && chosen in ok) chosen
        else listOf(AerialQuality.DolbyVision, AerialQuality.Uhd).firstOrNull { it in ok && (chosen == AerialQuality.Auto || it.ordinal < chosen.ordinal) }
            ?: AerialQuality.Hd
    }

    /**
     * What the aerial wallpaper plays: as chosen, but never Dolby Vision. A TV in Dolby Vision mode
     * shows the tiles over the film with Dolby's picture settings. A screensaver that carries the
     * wallpaper's film on plays this too (Screensaver), so the two are one, with no switch.
     */
    fun forWallpaper(context: Context): AerialQuality {
        val q = resolve(context, AerialSettings.aerialQuality).let { if (it == AerialQuality.DolbyVision) AerialQuality.Uhd else it }
        return if (q == AerialQuality.Uhd && wallpaperStep(context) >= 1) AerialQuality.Hd else q
    }

    /**
     * How far the aerial wallpaper has stepped down on this TV, from the frames it dropped while
     * moving (LiveWallpaper): 0 not at all, 1 from 4K to 1080p H.264 (the lightest of Apple's
     * files), 2 not moving on Home at all. With quality on Auto, or on Dolby Vision, which the
     * wallpaper never plays anyway (the owner of TCL MT5879 chose it for the screensaver); 4K or
     * 1080p chosen is played as chosen. Kept until the app is updated or a quality is chosen,
     * then tried again from the top.
     */
    fun wallpaperStep(context: Context): Int {
        if (!wallpaperSteps) return 0
        if (!stepRead) {
            stepRead = true
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            step = if (prefs.getInt("wallpaper_step_version", 0) == STEP_VERSION) prefs.getInt("wallpaper_step", 0) else 0
        }
        return step
    }

    /** Whether the wallpaper may step down here: not where a quality other than Dolby Vision is chosen. */
    val wallpaperSteps: Boolean
        get() = AerialSettings.aerialQuality == AerialQuality.Auto || AerialSettings.aerialQuality == AerialQuality.DolbyVision

    /** Whether the aerial wallpaper stays still on Home here (step 2). */
    fun wallpaperStill(context: Context): Boolean = wallpaperStep(context) >= 2

    /** One step down from what the wallpaper plays now: to 1080p from 4K, else to still. The new step. */
    fun stepWallpaperDown(context: Context, why: String): Int {
        val next = if (forWallpaper(context) == AerialQuality.Uhd) 1 else 2
        DiagLog.i("wallpaper steps down to ${if (next == 1) "1080p" else "still"}: $why")
        step = next
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("wallpaper_step", next).putInt("wallpaper_step_version", STEP_VERSION).apply()
        return next
    }

    /** A quality chosen again: the wallpaper starts from the top. */
    fun resetWallpaperSteps(context: Context) {
        step = 0
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("wallpaper_step").remove("wallpaper_step_version").apply()
    }

    private var step by mutableIntStateOf(0)
    private var stepRead = false
    private const val PREFS = "aerial_formats"
    private const val STEP_VERSION = BuildConfig.VERSION_CODE

    /** One step down, after a decoder failed on [quality]. */
    fun lower(quality: AerialQuality): AerialQuality = when (quality) {
        AerialQuality.DolbyVision -> AerialQuality.Uhd
        else -> AerialQuality.Hd
    }

    private fun probe(context: Context): Set<AerialQuality> {
        val found = mutableSetOf(AerialQuality.Hd)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return found
        val codecs = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder } }.getOrDefault(emptyList())
        fun caps(mime: String) = codecs.mapNotNull { info ->
            if (info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) null
            else runCatching { info.getCapabilitiesForType(mime) }.getOrNull()
        }
        val uhd = caps(MediaFormat.MIMETYPE_VIDEO_HEVC).any { c ->
            runCatching { c.videoCapabilities.areSizeAndRateSupported(3840, 2160, 30.0) }.getOrDefault(false)
        }
        if (uhd) found += AerialQuality.Uhd
        val dvDecoder = caps(MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION).any { c ->
            c.profileLevels.any {
                it.profile == MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheStn &&
                    it.level >= MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelUhd30
            }
        }
        val dvDisplay = runCatching {
            @Suppress("DEPRECATION")
            context.getSystemService<WindowManager>()?.defaultDisplay?.hdrCapabilities?.supportedHdrTypes
                ?.contains(Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION) == true
        }.getOrDefault(false)
        if (uhd && dvDecoder && dvDisplay) found += AerialQuality.DolbyVision
        return found
    }
}

object Aerials {
    @Volatile
    private var cache: List<Aerial>? = null

    fun all(context: Context): List<Aerial> = cache ?: synchronized(this) {
        cache ?: load(context).also { cache = it }
    }

    private fun load(context: Context): List<Aerial> {
        val zh = Locale.getDefault().language == "zh"
        val root = JSONObject(context.assets.open("aerials.json").bufferedReader().use { it.readText() })
        val arr = root.getJSONArray("videos")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val caps = o.getJSONArray("captions")
            Aerial(
                id = o.getString("id"),
                label = o.getString("label"),
                category = o.getString("category"),
                url = o.getString("url"),
                uhd = o.optString("uhd").ifEmpty { o.getString("url") },
                dolbyVision = o.optString("dv").ifEmpty { o.getString("url") },
                captions = (0 until caps.length()).map { j ->
                    val c = caps.getJSONArray(j)
                    c.getInt(0) to (if (zh) c.getString(1) else c.getString(2)).replace(' ', ' ')
                },
            )
        }
    }
}
