package app.opentv.launcher.guard

import android.Manifest
import android.content.Context
import android.content.Intent
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 34])
class GuardPauseTest {
    @Test fun pausePersistsAndManualResumeRestoresGuardWithoutRemovingOtherServices() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        val other = "other.package/other.package.Service"
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, other)
        assertTrue(GuardSetup.setPaused(context, true))
        assertTrue(context.getSharedPreferences("system", Context.MODE_PRIVATE).getBoolean("guard_paused", false))
        GuardSetup.ensureEnabled(context)
        GuardSetup.rebindIfStalled(context)
        assertEquals(other, Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
        GuardSetup.resume(context)
        assertFalse(GuardSetup.isPaused(context))
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        assertTrue(enabled.split(':').contains(other))
        assertTrue(enabled.contains(HomeGuardService::class.java.name))
    }

    @Test fun legacyScreensaverOwnershipNeverChangesSystemDreamOrPreferences() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        val preferences = context.getSharedPreferences("system", Context.MODE_PRIVATE)
        preferences.edit().putBoolean("own_screensaver", true).commit()
        val saved = preferences.all.toMap()
        val resolver = context.contentResolver
        for (component in listOf("vendor.package/.Dream", "app.opentv.launcher/.dream.OpenTvDream")) {
            Settings.Secure.putString(resolver, "screensaver_components", component)
            Settings.Secure.putInt(resolver, "screensaver_enabled", 0)
            var writes = 0
            val observer = object : android.database.ContentObserver(null) {
                override fun onChange(selfChange: Boolean) { writes++ }
            }
            resolver.registerContentObserver(Settings.Secure.getUriFor("screensaver_components"), false, observer)
            resolver.registerContentObserver(Settings.Secure.getUriFor("screensaver_enabled"), false, observer)
            try {
                GuardSetup.ensureEnabled(context)
                assertEquals(component, Settings.Secure.getString(resolver, "screensaver_components"))
                assertEquals(0, Settings.Secure.getInt(resolver, "screensaver_enabled"))
                assertEquals(0, writes)
                assertEquals(saved, preferences.all)
            } finally {
                resolver.unregisterContentObserver(observer)
            }
        }
    }

    @Test fun applicationDoesNotRegisterDreamService() {
        val context = RuntimeEnvironment.getApplication()
        val services = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SERVICES)
            .services.orEmpty()
        assertFalse(services.any { it.permission == "android.permission.BIND_DREAM_SERVICE" || it.name.contains("OpenTvDream") })
        assertTrue(context.packageManager.queryIntentServices(Intent("android.service.dreams.DreamService").setPackage(context.packageName), 0).isEmpty())
    }

    @Test fun bootDoesNotOpenLauncherWhilePaused() {
        val context = RuntimeEnvironment.getApplication()
        assertTrue(GuardSetup.setPaused(context, true))
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(shadowOf(context).nextStartedActivity)
        assertTrue(GuardSetup.isPaused(context))
        GuardSetup.resume(context)
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNotNull(shadowOf(context).nextStartedActivity)
    }

    @org.robolectric.annotation.Implements(android.accessibilityservice.AccessibilityService::class)
    class ConnectedAccessibilityShadow : org.robolectric.shadows.ShadowAccessibilityService() {
        private var info: android.accessibilityservice.AccessibilityServiceInfo? = null
        @org.robolectric.annotation.Implementation
        protected fun getServiceInfo(): android.accessibilityservice.AccessibilityServiceInfo? = info
        @org.robolectric.annotation.Implementation
        protected fun setServiceInfo(value: android.accessibilityservice.AccessibilityServiceInfo) { info = value }
    }

    @Test @Config(shadows = [ConnectedAccessibilityShadow::class]) fun pausedServiceDoesNotConsumeHome() {
        val context = RuntimeEnvironment.getApplication()
        assertTrue(GuardSetup.setPaused(context, true))
        val service = org.robolectric.Robolectric.buildService(HomeGuardService::class.java).create().get()
        val filterFlag = android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        service.serviceInfo = android.accessibilityservice.AccessibilityServiceInfo().apply { flags = filterFlag }
        HomeGuardService::class.java.getDeclaredMethod("filterKeys", Boolean::class.javaPrimitiveType).apply {
            isAccessible = true
        }.invoke(service, true)
        assertEquals(0, service.serviceInfo.flags and filterFlag)
        val consumed = HomeGuardService::class.java.getDeclaredMethod("onKeyEvent", android.view.KeyEvent::class.java).apply {
            isAccessible = true
        }.invoke(service, android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_HOME))
        assertEquals(false, consumed)
    }
}
