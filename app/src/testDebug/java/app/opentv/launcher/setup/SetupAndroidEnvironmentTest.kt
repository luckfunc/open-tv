package app.opentv.launcher.setup

import android.Manifest
import android.app.NotificationManager
import app.opentv.launcher.guard.RevivalListener
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.os.Build
import app.opentv.launcher.settings.OpenTvSettings
import org.junit.Before
import app.opentv.launcher.guard.HomeGuardService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 34])
class SetupAndroidEnvironmentTest {
    @Before fun initializeSettingsOwnerForCurrentApplication() {
        OpenTvSettings::class.java.getDeclaredField("started").apply { isAccessible = true }
            .setBoolean(OpenTvSettings, false)
        OpenTvSettings.init(RuntimeEnvironment.getApplication())
    }

    private class Session : AdbSession {
        val commands = mutableListOf<String>()
        var supported = false
        var supportedNames = setOf("AUTO_START", "APP_AUTO_START")
        val allowed = mutableSetOf<String>()
        override suspend fun execute(command: String, timeoutMillis: Long): AdbCommandResult {
            commands += command
            return if (command.startsWith("appops get")) {
                val name = command.substringAfterLast(' ')
                if (supported && name in supportedNames) AdbCommandResult("$name: ${if (name in allowed) "allow" else "ignore"}", 0)
                else AdbCommandResult("Error: Unknown operation", 0)
            } else {
                if (command.startsWith("appops set")) allowed += command.split(' ')[3]
                AdbCommandResult("", 0)
            }
        }
        override fun close() = Unit
    }

    @Test fun legacyDisclosureIgnoresScreensaverOwnershipAndRetainsKeepAdbCompatibility() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("system", Context.MODE_PRIVATE)
        preferences.edit().putBoolean("own_screensaver", true).putBoolean("keep_adb", false).commit()
        val saved = preferences.all.toMap()
        val environment = AndroidSetupEnvironment(context)
        assertFalse(environment.legacyBehaviors())
        assertEquals(saved, preferences.all)
        preferences.edit().putBoolean("keep_adb", true).commit()
        assertTrue(environment.legacyBehaviors())
        assertTrue(preferences.getBoolean("own_screensaver", false))
    }

    @Test fun notificationPublicGrantWinsWhenLegacySettingIsEmpty() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val component = ComponentName(context, RevivalListener::class.java)
        Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners", "")
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowOf(manager).setNotificationListenerAccessGranted(component, true)
        val snapshot = AndroidSetupEnvironment(context).snapshot(Session())
        assertTrue(SetupOperation.Notification in snapshot.configured)
    }

    @Test fun notificationPublicDenialWinsOverStaleLegacySetting() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val component = ComponentName(context, RevivalListener::class.java)
        Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners", component.flattenToString())
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowOf(manager).setNotificationListenerAccessGranted(component, false)
        val snapshot = AndroidSetupEnvironment(context).snapshot(Session())
        assertFalse(SetupOperation.Notification in snapshot.configured)
    }

    @Test @Config(sdk = [26]) fun notificationLegacySettingRemainsFallbackBeforeApi27() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val component = ComponentName(context, RevivalListener::class.java)
        val environment = AndroidSetupEnvironment(context)
        Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners", component.flattenToString())
        assertTrue(SetupOperation.Notification in environment.snapshot(Session()).configured)
        Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners", "")
        assertFalse(SetupOperation.Notification in environment.snapshot(Session()).configured)
    }

    @Test fun vendorOperationMustBeRecognizedBeforeAnyWrite() = runTest {
        val environment = AndroidSetupEnvironment(RuntimeEnvironment.getApplication())
        val session = Session()
        assertFalse(environment.apply(SetupOperation.AutoStart, session))
        assertEquals(listOf("appops get app.opentv.launcher AUTO_START", "appops get app.opentv.launcher APP_AUTO_START"), session.commands)
        session.commands.clear()
        session.supported = true
        assertTrue(environment.apply(SetupOperation.AutoStart, session))
        assertEquals(listOf("appops get app.opentv.launcher AUTO_START", "appops set app.opentv.launcher AUTO_START allow"), session.commands)
    }

    @Test fun restrictedServiceAccessRequiresKnownModeAndAuthoritativeReadback() = runTest {
        val environment = AndroidSetupEnvironment(RuntimeEnvironment.getApplication())
        val session = Session()
        assertFalse(SetupOperation.ServiceAccess in environment.snapshot(session).optionalOperations)
        assertFalse(environment.apply(SetupOperation.ServiceAccess, session))
        assertFalse(session.commands.any { it.startsWith("appops set") })
        session.supported = true
        session.supportedNames = setOf("ACCESS_RESTRICTED_SETTINGS")
        val before = environment.snapshot(session)
        assertTrue(SetupOperation.ServiceAccess in before.optionalOperations)
        assertFalse(SetupOperation.ServiceAccess in before.configured)
        session.commands.clear()
        assertTrue(environment.apply(SetupOperation.ServiceAccess, session))
        assertEquals(listOf(
            "appops get app.opentv.launcher ACCESS_RESTRICTED_SETTINGS",
            "appops set app.opentv.launcher ACCESS_RESTRICTED_SETTINGS allow",
            "appops get app.opentv.launcher ACCESS_RESTRICTED_SETTINGS",
        ), session.commands)
        assertTrue(SetupOperation.ServiceAccess in environment.snapshot(session).configured)
    }

    @Test fun fallbackNameIsPrivateAndPinnedForWritesAndReadback() = runTest {
        val environment = AndroidSetupEnvironment(RuntimeEnvironment.getApplication())
        val session = Session().apply {
            supported = true
            supportedNames = setOf("APP_AUTO_START")
        }
        val before = environment.snapshot(session)
        assertEquals(setOf(SetupOperation.AutoStart), before.optionalOperations)
        assertEquals("APP_AUTO_START", before.before["autostart_operation"])
        assertEquals("APP_AUTO_START: ignore", before.before["APP_AUTO_START"])
        session.commands.clear()
        session.supportedNames = setOf("AUTO_START", "APP_AUTO_START")
        assertTrue(environment.apply(SetupOperation.AutoStart, session))
        assertTrue(SetupOperation.AutoStart in environment.snapshot(session).configured)
        assertFalse(session.commands.any { it.endsWith(" AUTO_START") || it.endsWith(" AUTO_START allow") })
        assertTrue(session.commands.contains("appops set app.opentv.launcher APP_AUTO_START allow"))
    }

    @Test fun allShellWritesAreFixedToThisPackageWithoutOtherPrivileges() = runTest {
        val session = Session().apply { supported = true }
        val environment = AndroidSetupEnvironment(RuntimeEnvironment.getApplication())
        listOf(SetupOperation.SecureSettings, SetupOperation.Overlay, SetupOperation.Notification, SetupOperation.AutoStart)
            .forEach { environment.apply(it, session) }
        val writes = session.commands.filterNot { it.startsWith("appops get") }
        assertEquals(listOf(
            "pm grant app.opentv.launcher android.permission.WRITE_SECURE_SETTINGS",
            "appops set app.opentv.launcher SYSTEM_ALERT_WINDOW allow",
            "cmd notification allow_listener app.opentv.launcher/app.opentv.launcher.guard.RevivalListener",
            "appops set app.opentv.launcher AUTO_START allow",
        ), writes)
    }

    @Test fun guardSnapshotRequiresPersistentFilteringOnlyOnAndroid12AndLaterWithoutWriting() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val resolver = context.contentResolver
        val mine = ComponentName(context, HomeGuardService::class.java).flattenToString()
        val settings = context.getSharedPreferences(OpenTvSettings.PREFS, Context.MODE_PRIVATE)
        settings.edit().putBoolean(OpenTvSettings.PERSISTENT_HOME_FILTER, false).commit()
        Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, mine)
        Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        val session = Session()
        val snapshot = AndroidSetupEnvironment(context).snapshot(session)
        assertEquals(Build.VERSION.SDK_INT < 31, SetupOperation.HomeGuard in snapshot.configured)
        assertEquals("false", snapshot.before["persistent_home_filter"])
        assertFalse(settings.getBoolean(OpenTvSettings.PERSISTENT_HOME_FILTER, true))
        assertEquals(mine, Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
        assertEquals(1, Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED))
        assertTrue(session.commands.all { it.startsWith("appops get") })
    }

    @Test fun guardApplyPreparesFilteringThroughSettingsOwnerOnlyOnAndroid12AndLater() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val context = RuntimeEnvironment.getApplication()
            shadowOf(context).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
            OpenTvSettings.choosePersistentHomeFilter(false)
            val environment = AndroidSetupEnvironment(context)
            assertTrue(environment.apply(SetupOperation.HomeGuard, Session()))
            val expected = Build.VERSION.SDK_INT >= 31
            assertEquals(expected, OpenTvSettings.persistentHomeFilter)
            assertEquals(expected, context.getSharedPreferences(OpenTvSettings.PREFS, Context.MODE_PRIVATE)
                .getBoolean(OpenTvSettings.PERSISTENT_HOME_FILTER, false))
            assertTrue(SetupOperation.HomeGuard in environment.snapshot(Session()).configured)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun guardEnablePreservesTheLatestOtherAccessibilityServices() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val context = RuntimeEnvironment.getApplication()
            shadowOf(context).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
            val resolver = context.contentResolver
            val original = "vendor.one/.Assistant:vendor.two/vendor.two.Reader"
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, original)
            val environment = AndroidSetupEnvironment(context)
            environment.apply(SetupOperation.HomeGuard, Session())
            val actual = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            assertEquals(original + ":" + ComponentName(context, HomeGuardService::class.java).flattenToString(), actual)
            assertEquals(1, Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED))
            assertTrue(environment.snapshot(Session()).configured.contains(SetupOperation.HomeGuard))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun stalledGuardRebindPreservesServicesAddedDuringRestoration() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val context = RuntimeEnvironment.getApplication()
            shadowOf(context).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
            val resolver = context.contentResolver
            val mine = ComponentName(context, HomeGuardService::class.java).flattenToString()
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "vendor.one/.Assistant:$mine")
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            AndroidSetupEnvironment(context).apply(SetupOperation.HomeGuard, Session())
            assertEquals("vendor.one/.Assistant", Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "vendor.one/.Assistant:vendor.two/.Reader")
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1500))
            assertEquals("vendor.one/.Assistant:vendor.two/.Reader:$mine", Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun successfulDefaultModeIsRecognizedButFailedQueriesStayUnknown() = runTest {
        val environment = AndroidSetupEnvironment(RuntimeEnvironment.getApplication())
        for (exit in listOf(0, 1)) {
            val commands = mutableListOf<String>()
            val session = object : AdbSession {
                override suspend fun execute(command: String, timeoutMillis: Long): AdbCommandResult {
                    commands += command
                    return AdbCommandResult("No operations.\nDefault mode: ignore", exit)
                }
                override fun close() = Unit
            }
            val snapshot = environment.snapshot(session)
            assertEquals(exit == 0, SetupOperation.AutoStart in snapshot.optionalOperations)
            assertEquals(exit == 0, environment.apply(SetupOperation.AutoStart, session))
            assertEquals(exit == 0, commands.any { it.startsWith("appops set") })
        }
        assertEquals("allow", AndroidSetupEnvironment.recognizedMode("AUTO_START", "No operations.\nDefault mode: allow"))
    }

    @Test fun journalRetainsOriginalBeforeAcrossLaterSnapshots() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("self_setup", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val environment = AndroidSetupEnvironment(context)
        fun snapshot(value: String) = SetupSnapshot(emptySet(), emptySet(), mapOf("AUTO_START" to value), false, false)
        environment.journal(snapshot("ignore"), emptyMap())
        environment.journal(snapshot("allow"), mapOf(SetupOperation.AutoStart to SetupOutcome.AlreadySet))
        assertEquals("ignore", org.json.JSONObject(preferences.getString("first_before", "{}")).getString("AUTO_START"))
        assertEquals("allow", org.json.JSONObject(preferences.getString("last_journal", "{}")).getJSONObject("before").getString("AUTO_START"))
    }

    @Test fun parserDoesNotTreatUnknownOrDifferentOperationsAsRecognized() {
        assertNull(AndroidSetupEnvironment.recognizedMode("AUTO_START", "Unknown operation"))
        assertNull(AndroidSetupEnvironment.recognizedMode("AUTO_START", "APP_AUTO_START: allow"))
        assertEquals("ignore", AndroidSetupEnvironment.recognizedMode("AUTO_START", "AUTO_START: ignore; time=1"))
    }
}
