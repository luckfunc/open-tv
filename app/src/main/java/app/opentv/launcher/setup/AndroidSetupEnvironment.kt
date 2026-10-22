package app.opentv.launcher.setup

import android.Manifest
import android.app.NotificationManager
import android.util.Log
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import app.opentv.launcher.guard.GuardSetup
import app.opentv.launcher.guard.HomeGuardService
import app.opentv.launcher.guard.RevivalListener
import app.opentv.launcher.settings.OpenTvSettings
import app.opentv.launcher.system.HomeStatus
import app.opentv.launcher.system.SystemProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal class AndroidSetupEnvironment(private val context: Context) : SetupEnvironment {
    private val preferences = context.getSharedPreferences("self_setup", Context.MODE_PRIVATE)
    private var autoStartName: String? = null
    override fun trace(event: String, error: Throwable?) {
        if (error == null) Log.i("OpenTVSetup", event)
        else Log.e("OpenTVSetup", "$event exception=${error.javaClass.name}", error)
    }

    private suspend fun execute(session: AdbSession, command: String): AdbCommandResult {
        trace("command start=$command")
        return try {
            session.execute(command).also {
                trace("command=$command exitCode=${it.exitCode} output=${it.output.take(1024)}")
            }
        } catch (error: Exception) {
            trace("command failed=$command", error)
            throw error
        }
    }

    override fun acknowledged() = preferences.getBoolean("acknowledged", false)
    override fun acknowledge() { preferences.edit().putBoolean("acknowledged", true).apply() }
    override fun nativeHome() = SystemProbe.homeStatus(context) == HomeStatus.Default
    override fun legacyBehaviors() = GuardSetup.keepAdb(context)

    override suspend fun snapshot(session: AdbSession): SetupSnapshot = withContext(Dispatchers.IO) {
        val configured = mutableSetOf<SetupOperation>()
        val before = linkedMapOf<String, String>()
        val optional = mutableSetOf<SetupOperation>()
        val serviceAccessMode = readServiceAccess(session, before)
        if (serviceAccessMode != null) optional += SetupOperation.ServiceAccess
        if (serviceAccessMode == "allow") configured += SetupOperation.ServiceAccess
        val secure = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
        before["secure_settings"] = secure.toString()
        if (secure) configured += SetupOperation.SecureSettings
        val resolver = context.contentResolver
        val accessibility = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val accessibilityEnabled = Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        val listeners = Settings.Secure.getString(resolver, "enabled_notification_listeners").orEmpty()
        before["accessibility"] = accessibility
        before["accessibility_enabled"] = accessibilityEnabled.toString()
        before["notification_listeners"] = listeners
        val persistentHomeFilter = context.getSharedPreferences(OpenTvSettings.PREFS, Context.MODE_PRIVATE)
            .getBoolean(OpenTvSettings.PERSISTENT_HOME_FILTER, false)
        before["persistent_home_filter"] = persistentHomeFilter.toString()
        if (containsComponent(accessibility, ComponentName(context, HomeGuardService::class.java)) &&
            accessibilityEnabled == 1 && (Build.VERSION.SDK_INT < 31 || persistentHomeFilter)
        ) configured += SetupOperation.HomeGuard
        val listenerComponent = ComponentName(context, RevivalListener::class.java)
        val notificationGranted = if (Build.VERSION.SDK_INT >= 27) {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .isNotificationListenerAccessGranted(listenerComponent)
        } else containsComponent(listeners, listenerComponent)
        before["notification_access_granted"] = notificationGranted.toString()
        if (notificationGranted) configured += SetupOperation.Notification
        val overlay = execute(session, "appops get $PACKAGE SYSTEM_ALERT_WINDOW")
        val overlayMode = if (overlay.exitCode == 0) recognizedMode("SYSTEM_ALERT_WINDOW", overlay.output) else null
        before["SYSTEM_ALERT_WINDOW"] = overlayMode ?: "unsupported_or_unreadable"
        val overlayAllowed = if (Build.VERSION.SDK_INT >= 23) Settings.canDrawOverlays(context) else overlayMode == "allow"
        if (overlayAllowed) configured += SetupOperation.Overlay
        val autoStartMode = readAutoStart(session, before)
        if (autoStartMode != null) optional += SetupOperation.AutoStart
        if (autoStartMode == "allow") configured += SetupOperation.AutoStart
        SetupSnapshot(configured, optional, before, HomeGuardService.isRunning, RevivalListener.isConnected).also {
            trace("snapshot configured=${it.configured} persistentHomeFilter=$persistentHomeFilter guardBound=${it.guardBound} listenerBound=${it.listenerBound}")
        }
    }

    override suspend fun apply(operation: SetupOperation, session: AdbSession): Boolean = when (operation) {
        SetupOperation.ServiceAccess -> {
            if (readServiceAccess(session) == null) false
            else execute(session, "appops set $PACKAGE ACCESS_RESTRICTED_SETTINGS allow").exitCode == 0 &&
                readServiceAccess(session) == "allow"
        }
        SetupOperation.SecureSettings -> execute(session, "pm grant $PACKAGE android.permission.WRITE_SECURE_SETTINGS").exitCode == 0
        SetupOperation.Overlay -> execute(session, "appops set $PACKAGE SYSTEM_ALERT_WINDOW allow").exitCode == 0
        SetupOperation.Notification -> execute(session, "cmd notification allow_listener $PACKAGE/app.opentv.launcher.guard.RevivalListener").exitCode == 0
        SetupOperation.HomeGuard -> withContext(Dispatchers.Main.immediate) {
            trace("operation=HomeGuard start")
            if (Build.VERSION.SDK_INT >= 31) {
                OpenTvSettings.init(context)
                OpenTvSettings.choosePersistentHomeFilter(true)
            }
            val state = snapshot(session)
            if (SetupOperation.HomeGuard in state.configured) {
                if (!state.guardBound) GuardSetup.rebindIfStalled(context)
            } else GuardSetup.ensureEnabled(context)
            trace("operation=HomeGuard requested guardBound=${HomeGuardService.isRunning}")
            true
        }
        SetupOperation.AutoStart -> {
            if (readAutoStart(session) == null) false
            else execute(session, "appops set $PACKAGE ${checkNotNull(autoStartName)} allow").exitCode == 0
        }
    }

    private suspend fun readServiceAccess(session: AdbSession, before: MutableMap<String, String> = linkedMapOf()): String? {
        val name = "ACCESS_RESTRICTED_SETTINGS"
        val result = execute(session, "appops get $PACKAGE $name")
        before[name] = result.output
        return if (result.exitCode == 0) recognizedMode(name, result.output) else null
    }

    private suspend fun readAutoStart(session: AdbSession, before: MutableMap<String, String> = linkedMapOf()): String? {
        val names = autoStartName?.let(::listOf) ?: listOf("AUTO_START", "APP_AUTO_START")
        for (name in names) {
            val result = execute(session, "appops get $PACKAGE $name")
            before[name] = result.output
            val mode = if (result.exitCode == 0) recognizedMode(name, result.output) else null
            if (mode != null) {
                autoStartName = name
                before["autostart_operation"] = name
                return mode
            }
        }
        return null
    }

    override fun journal(before: SetupSnapshot, outcomes: Map<SetupOperation, SetupOutcome>) {
        val record = JSONObject().put("before", JSONObject(before.before))
            .put("outcomes", JSONObject(outcomes.mapKeys { it.key.name }.mapValues { it.value.name }))
        val edit = preferences.edit().putString("last_journal", record.toString())
        if (!preferences.contains("first_before")) edit.putString("first_before", JSONObject(before.before).toString())
        check(edit.commit())
    }

    companion object {
        const val PACKAGE = "app.opentv.launcher"
        fun containsComponent(entries: String, component: ComponentName) = entries.split(':').any {
            ComponentName.unflattenFromString(it) == component
        }
        fun recognizedMode(operation: String, output: String): String? =
            Regex("(?:^|\\n|\\s)${Regex.escape(operation)}: (allow|ignore|deny|default|foreground)(?:[;\\s]|$)")
                .find(output)?.groupValues?.get(1)
                ?: Regex("(?m)^No operations\\.\\s*\\nDefault mode: (allow|ignore|deny|default|foreground)\\s*$")
                    .find(output.trim())?.groupValues?.get(1)
    }
}

internal fun setupDebuggingIntent(context: Context): Intent? {
    fun usable(intent: Intent): Boolean {
        val info = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo ?: return false
        return info.enabled && info.exported && info.applicationInfo.enabled &&
            (info.permission.isNullOrEmpty() || context.packageManager.checkPermission(info.permission, context.packageName) == PackageManager.PERMISSION_GRANTED)
    }
    return listOf(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), SystemProbe.settingsIntent(context))
        .firstOrNull(::usable)
}
