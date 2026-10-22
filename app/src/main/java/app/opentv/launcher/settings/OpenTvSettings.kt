package app.opentv.launcher.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

/** OpenTV's own preferences. Compose state, written on the main thread only. */
object OpenTvSettings {
    internal const val PREFS = "settings"
    internal const val PERSISTENT_HOME_FILTER = "persistent_home_filter"
    private var started = false
    private lateinit var app: Context

    /** Low-cost motion remains opt-in; an explicit choice survives upgrades. */
    var instantNavigation by mutableStateOf(false); private set

    /** TV inputs pinned to Home as tiles, by TvInputInfo id. */
    var pinnedSources by mutableStateOf<Set<String>>(emptySet()); private set

    /**
     * The input to open when the TV is switched on (a set-top box, an Apple TV), by TvSource id;
     * null for none. Home still comes back to OpenTV. Issue #25.
     */
    var bootSource by mutableStateOf<String?>(null); private set

    /** Every app's name under its tile, not only the focused one's (issue #28). */
    var showAllNames by mutableStateOf(false); private set

    /** Opt-in key filtering on Android 12+, where some firmwares otherwise flash stock Home. */
    var persistentHomeFilter by mutableStateOf(false); private set

    /**
     * Only the top row on Home, low on the screen, so the wallpaper (a family photo) shows above it,
     * as tvOS does under a full-screen top shelf. The rest of the grid is one press down.
     */
    var oneRowHome by mutableStateOf(false); private set

    fun init(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        pinnedSources = prefs.getStringSet("pinned_sources", null)?.toSet() ?: emptySet()
        bootSource = prefs.getString("boot_source", null)
        showAllNames = prefs.getBoolean("show_all_names", false)
        instantNavigation = prefs.getBoolean("instant_navigation", false)
        persistentHomeFilter = prefs.getBoolean(PERSISTENT_HOME_FILTER, false)
        oneRowHome = prefs.getBoolean("one_row_home", false)
    }

    fun choosePersistentHomeFilter(on: Boolean) {
        persistentHomeFilter = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(PERSISTENT_HOME_FILTER, on) }
    }

    fun chooseInstantNavigation(on: Boolean) {
        instantNavigation = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("instant_navigation", on) }
    }

    fun chooseShowAllNames(on: Boolean) {
        showAllNames = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("show_all_names", on) }
    }

    fun chooseOneRowHome(on: Boolean) {
        oneRowHome = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("one_row_home", on) }
    }

    fun setPinned(inputId: String, pinned: Boolean) {
        pinnedSources = if (pinned) pinnedSources + inputId else pinnedSources - inputId
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putStringSet("pinned_sources", pinnedSources) }
    }
}
