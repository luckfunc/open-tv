package app.opentv.launcher.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import app.opentv.launcher.system.TvSource
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class AppEntry(
    val packageName: String,
    val label: String,
    val component: ComponentName,
    /** Launched with CATEGORY_LEANBACK_LAUNCHER when the app has a TV entry, else CATEGORY_LAUNCHER. */
    val leanback: Boolean,
    /** False for apps on the system image, which can be disabled but not uninstalled. */
    val removable: Boolean,
    val art: TileArt,
    /** Set for a TV input pinned to Home (HDMI, tuner...) rather than an app. */
    val source: TvSource? = null,
) {
    val key: String get() = packageName

    fun launchIntent(): Intent = source?.let { Intent(it.intent) } ?: Intent(Intent.ACTION_MAIN)
        .addCategory(if (leanback) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
        .setComponent(component)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
}

/** A Home tile for a TV input. Its key never collides with a package name. */
fun sourceEntry(source: TvSource): AppEntry = AppEntry(
    packageName = SOURCE_KEY_PREFIX + source.id,
    label = source.label,
    component = ComponentName("input", source.id),
    leanback = false,
    removable = false,
    art = TileArtFactory.forSource(source.kind, source.label, source.deviceName),
    source = source,
)

const val SOURCE_KEY_PREFIX = "input:"

enum class IconMutationResult { Applied, InvalidKey, UnknownApp, StorageFailure }

object AppCatalog {
    /** Xiaomi's app store declares only this category, not CATEGORY_LAUNCHER (MiTV-MFTR0). */
    private const val CATEGORY_APPSTORE_LAUNCHER = "android.intent.category.APPSTORE_LAUNCHER"

    /**
     * Firmware apps that the maker's own launcher shows but that declare no launcher category at
     * all, so no query finds them. Verified on Xiaomi MiTV-MFTR0.
     */
    private val UNLISTED_ENTRIES = listOf(
        ComponentName("com.xiaomi.mitv.mediaexplorer", "com.xiaomi.mitv.mediaexplorer.NewScraperMainEntryActivity"), // 高清播放器
        ComponentName("com.xiaomi.smarthome.tv", "com.xiaomi.smarthome.tv.ui.MainActivity"), // 米家
    )

    /** Standard firmware utility identities; classification never replaces their artwork. */
    private val SYSTEM_CATEGORIES = listOf(
        Intent.CATEGORY_APP_GALLERY,
        Intent.CATEGORY_APP_CALENDAR,
        Intent.CATEGORY_APP_BROWSER,
        Intent.CATEGORY_APP_MARKET,
        CATEGORY_APPSTORE_LAUNCHER,
    )

    private class Cached(val packageStamp: Long, val iconStamp: Long, val width: Int, val label: String, val art: TileArt)
    private val cache = HashMap<String, Cached>()
    private val mutations = Mutex()
    private var discovered: Map<String, Pair<ActivityInfo, Boolean>> = emptyMap()
    private var categories: Set<String> = emptySet()
    private val snapshot = MutableStateFlow<List<AppEntry>>(emptyList())

    /** Complete snapshots keep unrelated package updates when observers skip emissions. */
    val entries = snapshot.asStateFlow()
    val loaded: List<AppEntry> get() = entries.value

    /**
     * Every app with a TV entry, plus apps that only have a phone-style entry. China-market firmwares
     * (Xiaomi's settings, gallery, weather...) expose many of their own apps with CATEGORY_LAUNCHER only,
     * and a few with no launcher category at all (see [UNLISTED_ENTRIES]).
     * One tile per package; the TV entry wins.
     */
    suspend fun load(context: Context): List<AppEntry> = withContext(Dispatchers.Default) {
        mutations.withLock { discover(context) }
    }

    private fun discover(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val found = LinkedHashMap<String, Pair<ActivityInfo, Boolean>>()
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
            .forEach { found.putIfAbsent(it.activityInfo.packageName, it.activityInfo to true) }
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .forEach { found.putIfAbsent(it.activityInfo.packageName, it.activityInfo to false) }
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(CATEGORY_APPSTORE_LAUNCHER), 0)
            .forEach { found.putIfAbsent(it.activityInfo.packageName, it.activityInfo to false) }
        for (component in UNLISTED_ENTRIES) {
            val info = runCatching { pm.getActivityInfo(component, 0) }.getOrNull() ?: continue
            if (info.exported && info.isEnabled && info.applicationInfo.enabled) found.putIfAbsent(info.packageName, info to false)
        }
        found.remove(context.packageName)

        val byCategory = HashSet<String>()
        for (category in SYSTEM_CATEGORIES) {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)
                .forEach { byCategory.add(it.activityInfo.packageName) }
        }
        pm.resolveActivity(Intent(android.provider.Settings.ACTION_SETTINGS), 0)
            ?.let { byCategory.add(it.activityInfo.packageName) }

        discovered = found
        categories = byCategory
        cache.keys.retainAll(found.keys)
        return found.values.map { (info, leanback) -> entry(pm, info, leanback, byCategory) }
            .also { snapshot.value = it }
    }

    /** Preview reads wait for in-flight mutations before choosing cacheable artwork. */
    suspend fun iconEntry(key: String): AppEntry? = mutations.withLock {
        if (CustomIcons.isValidKey(key)) loaded.firstOrNull { it.key == key } else null
    }

    suspend fun saveIcon(context: Context, key: String, input: InputStream): IconMutationResult =
        mutateIcon(context, key) { CustomIcons.save(key, input, context.cacheDir) }

    suspend fun resetIcon(context: Context, key: String): IconMutationResult =
        mutateIcon(context, key) { CustomIcons.clear(key) }

    private suspend fun mutateIcon(context: Context, key: String, persist: () -> Boolean): IconMutationResult =
        withContext(Dispatchers.IO) {
            mutations.withLock {
                if (!CustomIcons.isValidKey(key)) return@withLock IconMutationResult.InvalidKey
                val (info, leanback) = discovered[key] ?: return@withLock IconMutationResult.UnknownApp
                val saved = try {
                    persist()
                } catch (e: SecurityException) {
                    Log.w("OpenTVIcons", "Icon storage denied for $key", e)
                    false
                }
                if (!saved) {
                    Log.w("OpenTVIcons", "Icon mutation failed for $key")
                    return@withLock IconMutationResult.StorageFailure
                }
                cache.remove(key)
                val updated = entry(context.packageManager, info, leanback, categories)
                snapshot.value = loaded.map { if (it.key == key) updated else it }
                IconMutationResult.Applied
            }
        }

    /**
     * loadIcon and loadBanner pick the density bucket of the screen: a 96 px icon at 320 dpi, 72 px
     * at TCL's 240 dpi, stretched to a 198 px tile it looks soft (Xiaomi MiTV4-ANSM0, issue #17).
     * Ask for the densest bucket the app ships instead; the bitmap layers of adaptive icons too.
     */
    private fun densest(pm: PackageManager, info: ActivityInfo, res: Int): android.graphics.drawable.Drawable? =
        if (res == 0) null else runCatching {
            pm.getResourcesForApplication(info.applicationInfo)
                .getDrawableForDensity(res, android.util.DisplayMetrics.DENSITY_XXXHIGH, null)
        }.getOrNull()

    private fun entry(pm: PackageManager, info: ActivityInfo, leanback: Boolean, byCategory: Set<String>): AppEntry {
        val pkg = info.packageName
        // A picture chosen on the phone page changes the tile as much as an update does.
        val packageStamp = runCatching { pm.getPackageInfo(pkg, 0).lastUpdateTime }.getOrDefault(0L)
        val iconStamp = CustomIcons.stamp(pkg)
        val cached = cache[pkg]?.takeIf {
            it.packageStamp == packageStamp && it.iconStamp == iconStamp && it.width == TileArtFactory.W
        }
        val system = (info.applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
        val label: String
        val art: TileArt
        if (cached != null) {
            label = cached.label
            art = cached.art
        } else {
            label = try {
                info.loadLabel(pm).toString().trim().ifEmpty { pkg }
            } catch (e: Exception) {
                Log.w("OpenTVApps", "Label unavailable for $pkg", e)
                pkg
            }
            art = try {
                artwork(pm, info, leanback, system, byCategory)
            } catch (e: Exception) {
                Log.w("OpenTVApps", "Artwork unavailable for $pkg; using fallback", e)
                TileArtFactory.fromBanner(android.graphics.drawable.ColorDrawable(android.graphics.Color.DKGRAY))
            }
            cache[pkg] = Cached(packageStamp, iconStamp, TileArtFactory.W, label, art)
        }
        return AppEntry(pkg, label, ComponentName(pkg, info.name), leanback, !system, art)
    }

    private fun artwork(pm: PackageManager, info: ActivityInfo, leanback: Boolean, system: Boolean, byCategory: Set<String>): TileArt {
        val pkg = info.packageName
        val custom = CustomIcons.load(pkg)
        if (custom != null) {
            return try {
                TileArtFactory.fromBanner(android.graphics.drawable.BitmapDrawable(android.content.res.Resources.getSystem(), custom))
            } finally {
                custom.recycle()
            }
        }
        if (CustomIcons.has(pkg)) Log.w("OpenTVIcons", "Custom artwork unreadable for $pkg")
        val recipe = TileRecipes.of(pkg)
        val native = recipe == null && (system || UtilityIcons.isVerifiedUtility(pkg) || pkg in byCategory)
        val suppliedBanner = if (leanback || native) densest(pm, info, info.bannerResource)
            ?: runCatching { info.loadBanner(pm) }.getOrNull() else null
        if (native) {
            if (suppliedBanner != null && suppliedBanner.intrinsicWidth >= suppliedBanner.intrinsicHeight * 4 / 3) {
                return TileArtFactory.fromBanner(suppliedBanner)
            }
            return TileArtFactory.fromNativeIcon(suppliedBanner ?: densest(pm, info, info.iconResource) ?: info.loadIcon(pm))
        }
        // Keep existing recipe and non-system fallback rendering unchanged.
        val banner = suppliedBanner?.takeIf { it.intrinsicWidth >= it.intrinsicHeight * 4 / 3 }
        return when {
            recipe?.glyph != null -> TileArtFactory.glyphTile(recipe.top ?: 0, recipe.bottom ?: 0, recipe.glyph)
            banner != null && recipe?.useIcon != true -> TileArtFactory.fromBanner(banner)
            else -> TileArtFactory.fromIcon(densest(pm, info, info.iconResource) ?: info.loadIcon(pm), recipe)
        }
    }
}
