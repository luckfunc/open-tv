package app.opentv.launcher

import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.platform.ComposeView
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import app.opentv.launcher.apps.AppEntry
import app.opentv.launcher.apps.CustomIcons
import app.opentv.launcher.apps.TileArtFactory
import app.opentv.launcher.guard.GuardSetup
import app.opentv.launcher.guard.HomeGuardService
import app.opentv.launcher.ui.home.HomeScreen
import app.opentv.launcher.ui.theme.LocalReducedMotion
import app.opentv.launcher.ui.theme.canvasDensity
import app.opentv.launcher.settings.OpenTvSettings
import app.opentv.launcher.wallpaper.Wallpapers
import app.opentv.launcher.system.MissingClipboard
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {

    private val vm by viewModels<HomeViewModel>()

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = vm.reload()
    }

    // See MissingClipboard.
    override fun getSystemService(name: String): Any? =
        super.getSystemService(name) ?: if (name == CLIPBOARD_SERVICE) MissingClipboard.standIn else null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        TileArtFactory.useCanvasDensity(canvasDensity(resources.displayMetrics))
        Wallpapers.init(this)
        CustomIcons.init(this)
        OpenTvSettings.init(this)
        val reducedMotion = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

        // The home screen paints every pixel itself; a window background would be one more
        // full-screen fill per frame for the TV's GPU.
        window.setBackgroundDrawable(null)
        preferSmoothestRefreshRate()

        // Compose unpacks its restored Bundle before individual savers run.
        // Do not read the old root, which can contain removed Serializable settings classes.
        setContentView(ComposeView(this).apply {
            setTag(androidx.compose.ui.R.id.compose_view_saveable_id_tag, COMPOSE_STATE_ID)
        })
        setContent {
            // TVs ship at different densities for the same 1080p panel (Xiaomi 320dpi, TCL 240dpi),
            // which would make the same layout bigger on one and smaller on the other. OpenTV is
            // designed on a 960×540 canvas, so it scales that canvas to whatever the panel is.
            val canvas = Density(
                density = canvasDensity(resources.displayMetrics),
                fontScale = LocalDensity.current.fontScale,
            )
            CompositionLocalProvider(LocalReducedMotion provides reducedMotion, LocalDensity provides canvas) {
                HomeScreen(
                    vm = vm,
                    onLaunch = ::launch,
                    onOpen = ::open,
                )
            }
        }

        ContextCompat.registerReceiver(this, screenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON), ContextCompat.RECEIVER_NOT_EXPORTED)
        if (savedInstanceState == null && firstStartSinceBoot()) openBootSource("boot")
        if (intent.getBooleanExtra(EXTRA_WOKE, false)) openBootSource("brought back on wake")
    }

    override fun onDestroy() {
        lifecycleHandler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(screenOnReceiver) }
        super.onDestroy()
    }

    // ---- The input the TV starts on (issue #25) ----

    private var bootSourceOpenedAt = Long.MIN_VALUE / 2

    /**
     * Woken with OpenTV still in front (TCL keeps it through standby). Where the firmware killed
     * OpenTV instead (Xiaomi), Home Guard brings it back with [EXTRA_WOKE].
     */
    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            lifecycleHandler.postDelayed({
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) openBootSource("screen on")
            }, 1500)
        }
    }

    /** Once per boot (Settings.Global.BOOT_COUNT, Android 7+), within a few minutes of it. */
    private fun firstStartSinceBoot(): Boolean {
        if (SystemClock.elapsedRealtime() > 3 * 60 * 1000L) return false
        if (Build.VERSION.SDK_INT < 24) return true
        val boot = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)
        val prefs = getSharedPreferences("system", MODE_PRIVATE)
        if (boot < 0 || prefs.getInt("boot_source_boot", -2) == boot) return false
        prefs.edit().putInt("boot_source_boot", boot).apply()
        return true
    }

    /**
     * Opens the input the owner wants the TV to start on, if any. The list of inputs loads with
     * the apps, so wait for it a moment. Several routes may fire for one wake; the first wins.
     */
    private fun openBootSource(reason: String) {
        val id = OpenTvSettings.bootSource ?: return
        if (SystemClock.elapsedRealtime() - bootSourceOpenedAt < 15_000) return
        bootSourceOpenedAt = SystemClock.elapsedRealtime()
        lifecycleScope.launch {
            val source = withTimeoutOrNull(8_000) {
                snapshotFlow { vm.sources.firstOrNull { it.id == id } }.filterNotNull().first()
            } ?: return@launch
            android.util.Log.i("OpenTV", "$reason: opening ${source.label}, the input the TV starts on")
            openedAppSinceHome = true
            open(source.intent)
        }
    }

    /**
     * Runs Home at the fastest refresh rate the panel can switch to without going black, so focus
     * and scrolling move as smoothly as the TV allows (a 120 Hz TV that idles its UI at 60 Hz).
     * Only seamless switches, which Android 12 can name: on a TV a mode change is otherwise a second
     * of black screen, every time Home comes back. Xiaomi MiTV-MFTR0 and MFFU1 have a single 60 Hz
     * mode; TCL MT5879 (Android 11) lists 50 to 120 Hz but cannot say which switches are seamless,
     * so both stay as they are.
     */
    private fun preferSmoothestRefreshRate() {
        if (Build.VERSION.SDK_INT < 31) return
        val display = display ?: return
        val current = display.mode
        val seamless = current.alternativeRefreshRates
        val best = display.supportedModes
            .filter { m ->
                m.physicalWidth == current.physicalWidth && m.physicalHeight == current.physicalHeight &&
                    m.refreshRate <= 120.5f && seamless.any { kotlin.math.abs(it - m.refreshRate) < 0.5f }
            }
            .maxByOrNull { it.refreshRate }
            ?.takeIf { it.refreshRate > current.refreshRate + 0.5f } ?: return
        android.util.Log.i("OpenTV", "refresh rate ${current.refreshRate} -> ${best.refreshRate} Hz (mode ${best.modeId})")
        window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
    }

    /** Deferred wake-source opening and Home Guard binding checks. */
    private val lifecycleHandler = Handler(Looper.getMainLooper())

    /** An app (or the stock launcher) was opened from the grid and Home has not been pressed since. */
    private var openedAppSinceHome = false

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_RESTORE, false) &&
            vm.setup.state.value != app.opentv.launcher.setup.SetupState.Closed
        ) {
            android.util.Log.i("OpenTVSetup", "background restoration preserves active setup")
            return
        }
        if (intent.getBooleanExtra(EXTRA_RESTORE, false) && GuardSetup.isPaused(this)) return
        if (intent.getBooleanExtra(EXTRA_WOKE, false)) openBootSource("brought back on wake")
        if (intent.hasCategory(Intent.CATEGORY_HOME) || intent.action == Intent.ACTION_MAIN) {
            // Since 1.11 Home Guard answers Home from an app by bringing OpenTV back with this same
            // intent, so the intent alone cannot tell Home-on-Home from coming back from an app.
            vm.onHomePressed(resetFocus = !openedAppSinceHome)
            openedAppSinceHome = false
        }
    }

    override fun onStart() {
        super.onStart()
        GuardSetup.ensureEnabled(this)
        lifecycleHandler.postDelayed({ GuardSetup.rebindIfStalled(this) }, 5_000)
        vm.reloadIfPackagesChanged()
        ContextCompat.registerReceiver(
            this, packageReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_EXPORTED,
        )
        // Apps can change while OpenTV is in the background without it hearing about it.
        vm.reload()
        Wallpapers.refreshDailyIfStale()
        Wallpapers.rotateIfDue()
    }

    override fun onResume() {
        super.onResume()
        val restoring = intent.getBooleanExtra(EXTRA_RESTORE, false)
        intent.removeExtra(EXTRA_RESTORE)
        if (restoring && GuardSetup.isPaused(this)) {
            finish()
            return
        }
        GuardSetup.resume(this)
        GuardSetup.setHomeInFront(this, true)
        HomeGuardService.onLauncherResumed()
        vm.refreshSystem()
        // Back in front by any route (Home was handled in onNewIntent, which runs first; Back from
        // the app lands here directly), so the next Home press is a Home-on-Home again.
        openedAppSinceHome = false
    }

    override fun onPause() {
        super.onPause()
        GuardSetup.setHomeInFront(this, false)
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(packageReceiver)
    }

    /** Opens an app, growing it out of its tile. */
    private fun launch(app: AppEntry, bounds: Rect?) {
        openedAppSinceHome = true
        // The stock launcher (Xiaomi's PatchWall, with the maker's own iQIYI / Mango content) can
        // only be opened through the system Home action; see HomeGuardService.openStockLauncher.
        // Hisense VIDAA hands the Home action to OpenTV once persist.vt.use_home_pkg points at it,
        // so there it went nowhere; its launcher opens like any app (issue #87). Xiaomi's throws.
        if (HomeGuardService.isStockHome(app.packageName)) {
            HomeGuardService.allowStockLauncher()
            if (runCatching { startActivity(app.launchIntent()) }.isSuccess || HomeGuardService.openStockLauncher()) return
        }
        val options = bounds?.let {
            ActivityOptions.makeScaleUpAnimation(
                window.decorView, it.left.toInt(), it.top.toInt(), it.width.toInt(), it.height.toInt(),
            ).toBundle()
        }
        try {
            startActivity(app.launchIntent(), options)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.launch_failed, app.label), Toast.LENGTH_SHORT).show()
        }
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        internal const val COMPOSE_STATE_ID = "opentv-ui-state-v2"

        /** How Home Guard and the boot receiver bring OpenTV back: the same task the launcher entry uses. */
        fun bringBackIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

        /** Set by Home Guard when it brings OpenTV back after standby or a boot. */
        const val EXTRA_WOKE = "app.opentv.launcher.WOKE"

        /** Background task restoration, not an explicit Home press. */
        const val EXTRA_RESTORE = "app.opentv.launcher.RESTORE"
    }
}
