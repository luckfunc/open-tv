package app.opentv.launcher

import android.app.Application
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.opentv.launcher.apps.AppCatalog
import app.opentv.launcher.apps.AppEntry
import app.opentv.launcher.apps.LayoutStore
import app.opentv.launcher.apps.ReorderSession
import app.opentv.launcher.apps.sourceEntry
import app.opentv.launcher.settings.OpenTvSettings
import app.opentv.launcher.setup.SetupController
import app.opentv.launcher.setup.AndroidSetupDiagnostics
import app.opentv.launcher.setup.AndroidSetupEnvironment
import app.opentv.launcher.setup.SetupReturn
import app.opentv.launcher.setup.SetupState
import app.opentv.launcher.setup.adb.AndroidAdbConnector
import app.opentv.launcher.system.SystemProbe
import app.opentv.launcher.system.TvSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Overlay {
    data object None : Overlay
    data class ControlCenter(val page: CcPage = CcPage.Main) : Overlay
    data class AppMenu(val key: String, val anchor: Rect) : Overlay
    data object HiddenApps : Overlay
    data object Wallpaper : Overlay
}

enum class CcPage { Main, About }

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val context get() = getApplication<Application>()
    private val store = LayoutStore(app)
    private var all: List<AppEntry> = emptyList()

    var apps by mutableStateOf<List<AppEntry>>(emptyList()); private set
    var hiddenApps by mutableStateOf<List<AppEntry>>(emptyList()); private set
    var loaded by mutableStateOf(false); private set

    var overlay by mutableStateOf<Overlay>(Overlay.None)
    var movingKey by mutableStateOf<String?>(null); private set
    private var moveSession: ReorderSession? = null

    var sources by mutableStateOf<List<TvSource>>(emptyList()); private set


    /**
     * Set when Home is pressed: go back to the top row. Done once the window has focus again,
     * since a focus request made while another app is in front is lost.
     */
    var homeResetPending by mutableStateOf(false)

    private val setupDiagnostics = AndroidSetupDiagnostics.get(app)
    internal val setup = SetupController(
        viewModelScope,
        { AndroidAdbConnector(context, setupDiagnostics.current) },
        AndroidSetupEnvironment(app),
        setupDiagnostics,
    )

    internal fun openSetup() {
        overlay = Overlay.Wallpaper
        setup.open(SetupReturn.Personalization)
    }

    internal fun dismissSetup() {
        val destination = setup.destination
        setup.dismiss()
        if (setup.state.value == SetupState.Closed) {
            overlay = if (destination == SetupReturn.Personalization) Overlay.Wallpaper else Overlay.None
        }
    }

    override fun onCleared() {
        setup.close()
        super.onCleared()
    }

    private var loadJob: Job? = null

    init {
        reload()
        viewModelScope.launch {
            AppCatalog.entries.collect { arrange() }
        }
    }

    /** PackageManager's change counter when the list was last loaded (Android 8+). */
    private var packagesSeen = 0

    /**
     * Apps installed or removed while OpenTV was in the background (from an app store in front)
     * send no broadcast OpenTV hears: it listens only while started. Catch up when Home comes back
     * (issue #55). Before Android 8 there is no change counter; the reload is cheap, tiles are cached.
     */
    fun reloadIfPackagesChanged() {
        if (!loaded) return
        if (Build.VERSION.SDK_INT >= 26 && context.packageManager.getChangedPackages(packagesSeen) == null) return
        reload()
    }

    fun reload() {
        if (Build.VERSION.SDK_INT >= 26) {
            packagesSeen = context.packageManager.getChangedPackages(0)?.sequenceNumber ?: packagesSeen
        }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val ctx = context
            all = AppCatalog.load(ctx)
            sources = withContext(Dispatchers.Default) { SystemProbe.sources(ctx) }
            arrange()
            loaded = true
        }
    }

    private fun arrange() {
        all = AppCatalog.loaded
        // Pinned TV inputs share the grid, order and hiding with the apps.
        val pinned = sources.filter { it.id in OpenTvSettings.pinnedSources }.map(::sourceEntry)
        store.reconcile(all + pinned)
        val (visible, hidden) = store.arrange(all + pinned)
        val session = moveSession
        apps = if (session != null && visible.any { it.key == session.key }) {
            val byKey = visible.associateBy { it.key }
            session.reconcile(visible.map { it.key }).mapNotNull(byKey::get)
        } else {
            moveSession = null
            movingKey = null
            visible
        }
        hiddenApps = hidden
    }

    /** Drops the picture chosen on the phone page; the tile goes back to the app's own artwork. */
    fun resetIcon(key: String) {
        viewModelScope.launch {
            AppCatalog.resetIcon(context, key)
        }
    }

    /** Pins a TV input to Home (as the first tile, where it is easiest to reach) or takes it off. */
    fun setSourcePinned(source: TvSource, pinned: Boolean) {
        OpenTvSettings.setPinned(source.id, pinned)
        arrange()
        if (pinned) {
            val key = apps.firstOrNull { it.source?.id == source.id }?.key
            val first = apps.firstOrNull()?.key
            if (key != null && first != null && key != first) {
                store.move(key, first)
                arrange()
            }
        }
    }

    fun refreshSystem() {
        viewModelScope.launch {
            val ctx = context
            val s = withContext(Dispatchers.Default) { SystemProbe.sources(ctx) }
            // TvSource holds an Intent, which has no value equality; compare what a tile shows.
            fun shown(list: List<TvSource>) = list.map { Triple(it.id, it.label, it.deviceName) }
            if (shown(s) != shown(sources)) { sources = s; arrange() } else sources = s
        }
    }

    fun hide(key: String) {
        store.hide(key)
        arrange()
    }

    fun unhide(key: String) {
        store.unhide(key)
        arrange()
    }

    fun startMove(key: String) {
        if (apps.none { it.key == key } || moveSession != null) return
        moveSession = ReorderSession(key, apps.map { it.key })
        overlay = Overlay.None
        movingKey = key
    }

    /** Preview only: remote boundaries are resolved by the grid before reaching this method. */
    fun moveBy(delta: Int) {
        val session = moveSession ?: return
        val from = apps.indexOfFirst { it.key == session.key }
        if (from < 0) return
        session.moveTo((from + delta).coerceIn(apps.indices))
        val byKey = apps.associateBy { it.key }
        apps = session.keys.mapNotNull(byKey::get)
    }

    fun endMove(commit: Boolean = true) {
        val session = moveSession ?: return
        moveSession = null
        movingKey = null
        if (commit) store.commitVisibleOrder(session.keys)
        arrange()
    }

    /**
     * Home was pressed. On Home itself focus goes back to the top row, as on tvOS; coming back
     * from an app opened from the grid, focus stays on that app's tile.
     */
    fun onHomePressed(resetFocus: Boolean = true) {
        if (setup.state.value != SetupState.Closed) setup.returnHome()
        overlay = Overlay.None
        endMove(commit = false)
        if (resetFocus) homeResetPending = true
    }
}
