package app.opentv.launcher.apps

import android.content.Context
import androidx.core.content.edit

/** The user's arrangement: tile order and hidden apps, keyed by package name. */
class LayoutStore(context: Context) {
    private val prefs = context.getSharedPreferences("layout", Context.MODE_PRIVATE)

    private var order: List<String> =
        prefs.getString("order", null)?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
    private var hidden: Set<String> = prefs.getStringSet("hidden", null)?.toSet() ?: emptySet()
    private var initialized = prefs.getBoolean("initialized", false)

    /** Adds discoveries without discarding hidden or temporarily absent keys. */
    fun reconcile(all: List<AppEntry>) {
        if (all.isEmpty()) return
        val next = reconciledOrder(all)
        val nextHidden = if (initialized) hidden else hidden + Defaults.hidden(all.map { it.key }.toSet())
        if (initialized && next == order && nextHidden == hidden) return
        order = next
        hidden = nextHidden
        initialized = true
        save()
    }

    /** A read-only snapshot, including discoveries not yet reconciled. */
    fun arrange(all: List<AppEntry>): Pair<List<AppEntry>, List<AppEntry>> {
        val byKey = all.associateBy { it.key }
        val ordered = reconciledOrder(all).mapNotNull(byKey::get)
        val hiddenKeys = if (initialized) hidden else hidden + Defaults.hidden(byKey.keys)
        return ordered.filter { it.key !in hiddenKeys } to ordered.filter { it.key in hiddenKeys }
    }

    private fun reconciledOrder(all: List<AppEntry>): List<String> {
        val known = order.toHashSet()
        return order + all.filter { it.key !in known }.sortedWith(Defaults.comparator).map { it.key }.distinct()
    }

    fun hide(key: String) {
        if (key in hidden) return
        hidden = hidden + key
        save()
    }

    fun unhide(key: String) {
        if (key !in hidden) return
        hidden = hidden - key
        save()
    }

    /**
     * Moves [key] onto the slot of [target] among the visible tiles, shifting the ones in between,
     * the way tvOS reflows its grid while an app is being moved.
     */
    fun move(key: String, target: String) {
        if (key == target) return
        val list = order.toMutableList()
        val from = list.indexOf(key)
        val to = list.indexOf(target)
        if (from < 0 || to < 0) return
        list.removeAt(from)
        val insertAt = list.indexOf(target).let { if (from < to) it + 1 else it }
        list.add(insertAt, key)
        order = list
        save()
    }

    /** Commits reconciled, visible keys without discarding omitted slots. */
    fun commitVisibleOrder(keys: List<String>) {
        require(keys.distinct().size == keys.size && keys.all { it in order && it !in hidden })
        order = mergeVisibleOrder(order, keys)
        save()
    }

    private fun save() = prefs.edit {
        putString("order", order.joinToString(","))
        putStringSet("hidden", hidden)
        putBoolean("initialized", initialized)
    }
}

/** First-run arrangement: streaming apps first, the TV's own utilities last. */
private object Defaults {
    private val preferred = listOf(
        "com.ktcp.video",                  // 云视听极光
        "com.gitvdemo.video",              // 银河奇异果
        "com.cibn.tv",                     // CIBN 酷喵
        "com.hunantv.license",             // 芒果 TV
        "com.xiaodianshi.tv.yst",          // 云视听小电视
        "com.netflix.ninja",
        "com.google.android.youtube.tv",
        "com.apple.atve.androidtv.appletv",
        "com.disney.disneyplus",
        "com.amazon.amazonvideo.livingroom",
        "com.hbo.hbonow",
        "com.spotify.tv.android",
        "com.mitv.mivideoplayer",
        "com.quark.yun.tv",
    )

    private val utilities = listOf(
        "com.xiaomi.mitv.settings",
        "com.android.tv.settings",
        "com.xiaomi.mitv.tvmanager",
        "com.mitv.tvhome",
        "com.google.android.tvlauncher",
        "com.google.android.apps.tv.launcherx",
    )

    val comparator = compareBy<AppEntry>(
        { preferred.indexOf(it.key).let { i -> if (i < 0) Int.MAX_VALUE else i } },
        { if (it.key in utilities || it.key.startsWith("com.android.")) 1 else 0 },
        { utilities.indexOf(it.key) },
        { it.label.lowercase() },
    )

    fun hidden(installed: Set<String>): Set<String> = buildSet {
        // Xiaomi's own settings app covers everything AOSP settings does, and more.
        if ("com.xiaomi.mitv.settings" in installed) add("com.android.tv.settings")
    }
}
