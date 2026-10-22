package app.opentv.launcher.guard

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.getSystemService
import app.opentv.launcher.MainActivity
import app.opentv.launcher.settings.OpenTvSettings
import app.opentv.launcher.system.SystemProbe

/**
 * Keeps OpenTV as the home screen on firmwares that will not let a third-party app own HOME
 * (Xiaomi PatchWall, some TCL and Hisense builds), without disabling any system app:
 *
 * 1. When the stock launcher's home activity comes to the front (the Home key, an app exiting to
 *    it, the TV booting into it), OpenTV is brought back on top.
 * 2. The viewer can still open the stock launcher on purpose (its tile in OpenTV's grid, for the
 *    maker's own content). While it is in front the Home key is taken before the system sees it
 *    and answered with OpenTV, since pressing Home there would otherwise just stay put.
 *
 * On Android 12+, key filtering is normally on only in that second case, unless the viewer opts
 * into persistent filtering. Android 11 and older always filter to avoid delayed starts.
 * With it on, every key press on the TV detours
 * through this service; some apps then see a press as held and move twice (云视听小电视 on Xiaomi
 * MiTV-MFTR0, reported by a user and gone once OpenTV was uninstalled).
 *
 * Both only act while OpenTV is not the resolved HOME app; once it is, the system does the work.
 * The service reads window-change events only; it never reads screen content.
 */
class HomeGuardService : AccessibilityService() {

    /** HOME activities that are not ours: package to class names. Refreshed on every connect. */
    private var stockHomes: Map<String, Set<String>> = emptyMap()

    /** Set when the user opens the stock launcher on purpose from OpenTV's grid. */
    private var allowStockUntil = 0L

    /** The last window that came to the front, as package to class; null until one does. */
    private var lastWindow: Pair<String, String>? = null

    private val handler = Handler(Looper.getMainLooper())
    private var temporaryKeyFiltering = false
    private var filterPreferences: SharedPreferences? = null
    private val filterPreferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == OpenTvSettings.PERSISTENT_HOME_FILTER) filterKeys(temporaryKeyFiltering)
    }

    /** Credential-protected preferences must not be opened during Direct Boot. */
    private fun observeFilterPreference() {
        if (filterPreferences != null || !GuardSetup.isUserUnlocked(this)) return
        filterPreferences = getSharedPreferences(OpenTvSettings.PREFS, Context.MODE_PRIVATE).also {
            it.registerOnSharedPreferenceChangeListener(filterPreferenceListener)
        }
    }

    private fun stopObservingFilterPreference() {
        filterPreferences?.unregisterOnSharedPreferenceChangeListener(filterPreferenceListener)
        filterPreferences = null
    }

    private var takeOverReason = ""
    private var takeOverOnWake = false
    private val takeOver = Runnable { takeOverIfStockHomeInFront(takeOverReason, takeOverOnWake) }

    /**
     * Bound before unlock (Direct Boot), the guard takes over as soon as credential storage is
     * available, from a process that is already running: on TCL 75S365C (Amlogic t982) starting a
     * process right after unlock waits several seconds behind TCL's boot-time scheduling (PR #45:
     * unlock to OpenTV 7.6 s -> 0.9 s). It looks at what is in front first, like every take-over, so
     * an input that HDMI-CEC switched to at power-on is left alone.
     */
    private val userUnlocked = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Before unlock only Direct Boot aware launchers resolve.
            refreshStockHomes()
            observeFilterPreference()
            filterKeys(false)
            // Credential-protected guard and ADB preferences were skipped while locked.
            GuardSetup.ensureEnabled(this@HomeGuardService)
            val skipReason = when {
                SystemClock.elapsedRealtime() >= BOOT_WINDOW_MS -> "outside boot window"
                getSystemService<PowerManager>()?.isInteractive != true -> "screen off"
                else -> null
            }
            if (skipReason == null) takeOverOnceSettled("user unlocked", woke = true)
            else Log.i(TAG, "user unlocked; not taking over Home ($skipReason)")
        }
    }

    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Firmwares put their own launcher back in front while the TV is in standby, and no
            // window change follows on wake. Look once things have settled after the screen lights.
            takeOverOnceSettled("screen on", woke = true)
        }
    }

    private val adbObserver by lazy {
        object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = GuardSetup.ensureAdb(this@HomeGuardService)
        }
    }

    override fun onServiceConnected() {
        instance = this
        // Register before querying homes or checking the state so an unlock cannot be missed.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            registerReceiver(userUnlocked, IntentFilter(Intent.ACTION_USER_UNLOCKED))
        }
        refreshStockHomes()
        observeFilterPreference()
        filterKeys(false)
        registerReceiver(screenOn, IntentFilter(Intent.ACTION_SCREEN_ON))
        contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ADB_ENABLED), false, adbObserver)
        GuardSetup.ensureAdb(this)
        if (!GuardSetup.isUserUnlocked(this)) {
            Log.i(TAG, "bound while user locked; deferred takeover until unlock")
            return
        }
        // Bound a little after boot, or brought back after the firmware killed OpenTV: either way
        // the stock launcher is what's in front, and the window change that showed it is long gone.
        // Not after an update, though: installing over OpenTV also kills it, and whoever is watching
        // an input or another app at that moment must not be pulled back to Home.
        val revived = SystemClock.elapsedRealtime() - GuardSetup.lastRevivedAt < 60_000 && !justUpdated()
        // OpenTV died with its home screen in front (it crashed, was updated, or was killed) and the
        // stock launcher was uncovered; pressing Home there changes nothing the guard could see.
        // Updates count too: OpenTV was in front.
        val diedInFront = GuardSetup.wasHomeInFront(this) &&
            (Build.VERSION.SDK_INT < 24 || SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime() < 60_000)
        // Revived with the screen off (standby): the screen-on check takes over at wake.
        // Not gated on being the default Home: Skyworth/Coolita (8R145, Android 11) starts its own
        // launcher at boot whatever Home resolves to (issue #43). The take-over looks first, and
        // bringing OpenTV back over itself changes nothing.
        if ((SystemClock.elapsedRealtime() < BOOT_WINDOW_MS || revived || diedInFront) &&
            getSystemService<PowerManager>()?.isInteractive == true
        ) {
            takeOverOnceSettled(
                when {
                    revived -> "re-enabled after being switched off"
                    diedInFront -> "back after OpenTV died in front"
                    else -> "bound shortly after boot"
                },
                // Back from standby or a boot, not from a crash: the moment to open the input
                // the owner wants the TV to start on.
                woke = !diedInFront || revived,
            )
        }
    }

    /**
     * Takes Home back after a short wait, and only if nothing but the stock launcher came to the
     * front meanwhile. At power-on an HDMI-CEC source (an Apple TV) switches the TV to its input
     * just as the screen lights and the guard is bound again; taking Home at once bounced the
     * viewer straight back out of the input (reported by a user, reproduced on Xiaomi MiTV-MFTR0:
     * the input's ExternalSourceActivity started 130 ms before the guard was bound).
     */
    private fun takeOverOnceSettled(reason: String, woke: Boolean) {
        takeOverReason = reason
        takeOverOnWake = woke
        handler.removeCallbacks(takeOver)
        handler.postDelayed(takeOver, SETTLE_MS)
    }

    private fun justUpdated(): Boolean = runCatching {
        System.currentTimeMillis() - packageManager.getPackageInfo(packageName, 0).lastUpdateTime < 2 * 60_000
    }.getOrDefault(false)

    /**
     * Also when OpenTV is the default Home: that only steers the HOME intent. Xiaomi MiTV-ANSP0
     * (Android 9) accepts set-home-activity, yet when standby force-stops OpenTV, PatchWall's old
     * task is simply uncovered and stays in front after wake (issue #6).
     */
    private fun takeOverIfStockHomeInFront(reason: String, woke: Boolean) {
        if (GuardSetup.isPaused(this)) return
        val last = lastWindow
        // Unknown means we were only just bound, which happens after a standby kill. Any screen of
        // the stock launcher counts: when OpenTV is killed, whatever was on top of the launcher's
        // task is uncovered, not always its home activity (PatchWall's StrategicLayerActivity).
        // Unless the firmware keeps stopping OpenTV: then an unknown front is most likely the app
        // the viewer is in, and Home stays with the Home key (LeTV eUI, issue #63).
        if (last == null && GuardSetup.revivedOften(this)) {
            Log.i(TAG, "$reason, but OpenTV keeps being stopped and the front is unknown; leaving it")
            return
        }
        val stockInFront = last == null || last.first in stockHomes
        if (!stockInFront && last != null) Log.i(TAG, "$reason with ${last.first} in front; leaving it there")
        if (!stockInFront || SystemClock.uptimeMillis() < allowStockUntil) return
        Log.i(TAG, "$reason with the stock launcher in front; bringing OpenTV back")
        goHome(woke, restoring = true)
    }

    override fun onDestroy() {
        stopObservingFilterPreference()
        runCatching { unregisterReceiver(screenOn) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { unregisterReceiver(userUnlocked) }
        }
        runCatching { contentResolver.unregisterContentObserver(adbObserver) }
        handler.removeCallbacksAndMessages(null)
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopObservingFilterPreference()
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onInterrupt() = Unit

    /**
     * Filters temporarily in the stock launcher, or persistently when the viewer opts in.
     * Preference changes reapply the last temporary request without waiting for a window event.
     * Up to Android 11 always: there, once Home reaches the system, it holds every background
     * activity start for five seconds, so OpenTV came back only after five seconds of the TCL
     * launcher (TCL MT5879, since 1.11 stopped filtering by default).
     */
    private fun filterKeys(on: Boolean) {
        temporaryKeyFiltering = on
        val info = serviceInfo ?: return
        val flag = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        val persistent = filterPreferences?.getBoolean(OpenTvSettings.PERSISTENT_HOME_FILTER, false) ?: false
        val filtering = !GuardSetup.isPaused(this) &&
            HomeKeyFilterPolicy.shouldFilter(Build.VERSION.SDK_INT, on, persistent)
        val want = if (filtering) info.flags or flag else info.flags and flag.inv()
        if (want == info.flags) return
        info.flags = want
        serviceInfo = info
        Log.i(TAG, if (want and flag != 0) "filtering keys" else "stopped filtering keys")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (GuardSetup.isPaused(this)) return false
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return false
        if (isDefaultHome()) return false
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
            Log.i(TAG, "Home key taken before the system saw it")
            goHome()
        }
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: return
        // Only activities count as what is in front. Overlays and dialogs also raise this event:
        // TCL's voice assistant (com.tcl.walleve) does at boot on TCL 75S365C, and taking it for
        // an app the viewer chose kept the guard from taking over (PR #45).
        if (isActivity(pkg, cls)) lastWindow = pkg to cls
        if (GuardSetup.isPaused(this)) return
        val allowed = SystemClock.uptimeMillis() < allowStockUntil
        if (pkg in stockHomes) {
            filterKeys(allowed)
            // Only the stock launcher's home activity itself, not its dialogs or other screens.
            // Not gated on isDefaultHome(): see takeOverIfStockHomeInFront.
            if (cls !in stockHomes.getValue(pkg) || allowed) return
            Log.i(TAG, "stock home $pkg came to the front; bringing OpenTV back")
            goHome()
            return
        }
        filterKeys(false)
        // Leaving the stock launcher for a real app ends the visit: Home goes to OpenTV again.
        if (allowed && pkg != packageName && isApp(pkg)) allowStockUntil = 0L
    }

    private val activityCache = HashMap<Pair<String, String>, Boolean>()

    private fun isActivity(pkg: String, cls: String): Boolean = activityCache.getOrPut(pkg to cls) {
        runCatching { packageManager.getActivityInfo(android.content.ComponentName(pkg, cls), 0) }.isSuccess
    }

    /** True for packages with a launcher entry, i.e. apps rather than system overlays and dialogs. */
    private fun isApp(pkg: String): Boolean =
        packageManager.getLeanbackLaunchIntentForPackage(pkg) != null || packageManager.getLaunchIntentForPackage(pkg) != null

    private fun isDefaultHome(): Boolean =
        packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName == packageName

    private fun refreshStockHomes() {
        val standard = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        // Coocaa's launcher (Skyworth 9H89_G91 and 9S60_G60, Android 8) declares only private
        // categories such as android.intent.category.HOME.CC; the firmware starts it by component
        // (issues #69, #73). Every MAIN activity is listed so their filters can be read.
        val vendor = runCatching {
            packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN), PackageManager.GET_RESOLVED_FILTER)
                .filter { ri -> ri.filter?.categoriesIterator()?.asSequence()?.any { it.startsWith(VENDOR_HOME_PREFIX) } == true }
        }.getOrDefault(emptyList())
        stockHomes = (standard + vendor)
            // Negative priority entries are fallbacks shown only while nothing else can be Home.
            .filter { it.activityInfo.packageName != packageName && it.priority >= 0 }
            .groupBy({ it.activityInfo.packageName }, { it.activityInfo.name })
            .mapValues { it.value.toSet() }
    }

    /**
     * Brings OpenTV's one task to the front. No CATEGORY_HOME: on firmwares where OpenTV is not the
     * system Home, that category made the system open a second, home-type task with a fresh
     * MainActivity every time, which lost the focused tile (Xiaomi MiTV-MFTR0, 1.11).
     */
    private fun goHome(woke: Boolean = false, restoring: Boolean = false) {
        if (GuardSetup.isPaused(this)) return
        // Starting an activity can light the screen: on OPPO K9 the TV came back on by itself a few
        // seconds into standby (issue #22). The screen-on check takes over at wake instead.
        if (getSystemService<PowerManager>()?.isInteractive == false) {
            Log.i(TAG, "screen off; leaving Home to the screen-on check")
            return
        }
        runCatching {
            startActivity(MainActivity.bringBackIntent(this)
                .putExtra(MainActivity.EXTRA_WOKE, woke)
                .putExtra(MainActivity.EXTRA_RESTORE, restoring))
        }.onFailure { Log.w(TAG, "could not bring OpenTV Home to the front", it) }
    }

    companion object {
        private const val TAG = "OpenTVGuard"
        private const val BOOT_WINDOW_MS = 3 * 60 * 1000L
        private const val SETTLE_MS = 1500L
        private const val VENDOR_HOME_PREFIX = "android.intent.category.HOME."

        @Volatile
        private var instance: HomeGuardService? = null

        val isRunning: Boolean get() = instance != null

        /** True if [packageName] is a stock launcher the guard keeps away from the front. */
        fun isStockHome(packageName: String): Boolean = instance?.stockHomes?.containsKey(packageName) == true

        /**
         * Opens the stock launcher on purpose (its tile in OpenTV's grid). Xiaomi's launcher
         * activities require a signature permission (com.mitv.tvhome.permission.HOME_STATE), so an
         * app cannot start them; the system Home action can. Returns false if the guard is not running.
         */
        fun openStockLauncher(): Boolean {
            val s = instance ?: return false
            allowStockLauncher()
            return s.performGlobalAction(GLOBAL_ACTION_HOME)
        }

        fun onPauseChanged() {
            instance?.let { service ->
                service.handler.removeCallbacks(service.takeOver)
                service.filterKeys(false)
            }
        }

        fun exitToSystemHome(context: Context): Boolean {
            val service = instance
            val useGlobalHome = service != null && !service.isDefaultHome()
            val home = if (useGlobalHome) null else SystemProbe.systemHomeIntent(context)
            if (!useGlobalHome && home == null) return false
            val wasPaused = GuardSetup.isPaused(context)
            if (!GuardSetup.setPaused(context, true)) return false
            val opened = runCatching {
                if (useGlobalHome) service!!.performGlobalAction(GLOBAL_ACTION_HOME)
                else {
                    context.startActivity(home!!)
                    true
                }
            }.getOrDefault(false)
            if (!opened) GuardSetup.setPaused(context, wasPaused)
            return opened
        }

        /** Lets the stock launcher stay in front until OpenTV is back: the viewer opened it on purpose. */
        fun allowStockLauncher() {
            instance?.allowStockUntil = SystemClock.uptimeMillis() + 60 * 60 * 1000L
        }

        /** Called when OpenTV comes back to the front: the stock launcher is fair game again. */
        fun onLauncherResumed() {
            val s = instance ?: return
            s.allowStockUntil = 0L
            s.filterKeys(false)
        }
    }
}
