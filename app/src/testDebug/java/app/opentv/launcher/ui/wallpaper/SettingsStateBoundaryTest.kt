package app.opentv.launcher.ui.wallpaper

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Parcel
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.pressKey
import androidx.lifecycle.ViewModelProvider
import app.opentv.launcher.HomeViewModel
import app.opentv.launcher.MainActivity
import app.opentv.launcher.Overlay
import app.opentv.launcher.R
import app.opentv.launcher.settings.OpenTvSettings
import app.opentv.launcher.wallpaper.PhoneSession
import app.opentv.launcher.wallpaper.Wallpapers
import java.io.InvalidObjectException
import java.io.ObjectInputStream
import java.io.Serializable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [30, 34], qualifiers = "en-w960dp-h540dp-land-mdpi",
    instrumentedPackages = ["app.opentv.launcher"],
)
class SettingsStateBoundaryTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Before fun prepareOfflineOwners() {
        // AndroidX caches its first Application while Robolectric replaces it between tests.
        ReflectionHelpers.setStaticField(ViewModelProvider.AndroidViewModelFactory::class.java, "_instance", null)
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("self_setup", Context.MODE_PRIVATE).edit().putBoolean("acknowledged", true).commit()
        shadowOf(app).grantPermissions("app.opentv.launcher.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        app.getSharedPreferences("wallpaper", Context.MODE_PRIVATE).edit()
            .putString("selected", "grad:aurora").putString("screensaver", "photos").commit()
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putString("pin_hash", "legacy-owner-value").putBoolean("show_all_names", true)
            .putInt("screensaver_minutes", 5).commit()
        // Keep real wallpaper initialization, but do not run its background network jobs.
        ReflectionHelpers.getField<CoroutineScope>(Wallpapers, "scope").cancel()
        val session = PhoneSession(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)) {
            object : PhoneSession.Server {
                override fun open() = true
                override fun address(): String? = null
                override fun close() = Unit
            }
        }
        ReflectionHelpers.setStaticField(PhoneSession::class.java, "applicationSession", session)
    }

    @Test fun freshActivityShowsSkippableIntroAndSettingsReopensWithItsOwnBackDestination() {
        RuntimeEnvironment.getApplication().getSharedPreferences("self_setup", Context.MODE_PRIVATE)
            .edit().putBoolean("acknowledged", false).commit()
        val current = launch(Bundle())
        try {
            compose.runOnIdle {
                val model = ViewModelProvider(current.get())[HomeViewModel::class.java]
                assertSame(current.get().application, model.getApplication<android.app.Application>())
                val setupState = model.setup.state.value
                assertTrue(setupState.toString(), setupState is app.opentv.launcher.setup.SetupState.Intro)
                current.get().window.decorView.dispatchWindowFocusChanged(true)
            }
            compose.waitForIdle()
            compose.onNodeWithTag("setup-intro").assertExists()
            compose.onNode(hasText(current.get().getString(R.string.setup_skip)) and
                SemanticsMatcher.keyIsDefined(SemanticsProperties.Focused)).performTouchInput { click() }
            compose.onNodeWithTag("setup-intro").assertDoesNotExist()
            assertTrue(current.get().getSharedPreferences("self_setup", Context.MODE_PRIVATE).getBoolean("acknowledged", false))
            openSettings(current)
            key(Key.DirectionDown)
            key(Key.DirectionRight)
            if (Build.VERSION.SDK_INT >= 31) key(Key.DirectionDown)
            key(Key.DirectionCenter)
            compose.onNodeWithTag("setup-intro").assertExists()
            compose.runOnIdle { current.get().onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithTag("setup-intro").assertDoesNotExist()
            assertEquals(Overlay.Wallpaper, ViewModelProvider(current.get())[HomeViewModel::class.java].overlay)
        } finally {
            current.pause().stop().destroy()
        }
    }

    @Test fun backgroundRestorationPreservesSetupButExplicitHomeStillClosesIt() {
        val preferences = RuntimeEnvironment.getApplication().getSharedPreferences("self_setup", Context.MODE_PRIVATE)
        preferences.edit().putBoolean("acknowledged", false).commit()
        val current = launch(Bundle())
        try {
            compose.onNodeWithTag("setup-intro").assertExists()
            compose.runOnIdle {
                val model = ViewModelProvider(current.get())[HomeViewModel::class.java]
                val before = model.setup.state.value
                val restore = MainActivity.bringBackIntent(current.get())
                    .putExtra(MainActivity.EXTRA_RESTORE, true)
                    .putExtra(MainActivity.EXTRA_WOKE, true)
                assertFalse(restore.hasCategory(Intent.CATEGORY_HOME))
                current.newIntent(restore)
                assertSame(before, model.setup.state.value)
                assertFalse(preferences.getBoolean("acknowledged", false))
            }
            compose.onNodeWithTag("setup-intro").assertExists()
            compose.runOnIdle {
                current.newIntent(MainActivity.bringBackIntent(current.get()))
                assertEquals(app.opentv.launcher.setup.SetupState.Closed,
                    ViewModelProvider(current.get())[HomeViewModel::class.java].setup.state.value)
            }
            compose.onNodeWithTag("setup-intro").assertDoesNotExist()
        } finally {
            current.pause().stop().destroy()
        }
    }

    @Test fun queuedRestorationCannotResumeAnExplicitlyPausedGuard() {
        val current = launch(Bundle())
        try {
            assertTrue(app.opentv.launcher.guard.GuardSetup.setPaused(current.get(), true))
            current.pause()
            current.newIntent(MainActivity.bringBackIntent(current.get())
                .putExtra(MainActivity.EXTRA_RESTORE, true))
            current.resume()
            assertTrue(app.opentv.launcher.guard.GuardSetup.isPaused(current.get()))
            assertTrue(current.get().isFinishing)
        } finally {
            current.pause().stop().destroy()
        }
    }

    @Test fun backgroundRestorationWithClosedSetupStillReturnsHome() {
        val current = launch(Bundle())
        try {
            openSettings(current)
            compose.runOnIdle {
                current.newIntent(MainActivity.bringBackIntent(current.get())
                    .putExtra(MainActivity.EXTRA_RESTORE, true))
                val model = ViewModelProvider(current.get())[HomeViewModel::class.java]
                assertEquals(app.opentv.launcher.setup.SetupState.Closed, model.setup.state.value)
                assertEquals(Overlay.None, model.overlay)
            }
        } finally {
            current.pause().stop().destroy()
        }
    }

    @Test fun mainActivitySkipsUnreadableLegacyRootAndRestoresCurrentNavigationWithoutClearingOtherOwners() {
        // A synthetic corrupt owner exercises the Activity boundary independently of versioned string routes.
        OpenTvSettings.init(RuntimeEnvironment.getApplication())
        OpenTvSettings.choosePersistentHomeFilter(false)
        val original = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        original.get().savedStateRegistry.registerSavedStateProvider("SaveableStateRegistry:-1") {
            Bundle().apply { putSerializable("old-settings", arrayListOf(UnreadableLegacyState())) }
        }
        original.get().savedStateRegistry.registerSavedStateProvider("unrelated-owner") {
            Bundle().apply { putString("value", "retained") }
        }
        val saved = Bundle()
        original.saveInstanceState(saved).pause().stop().destroy()
        val control = Robolectric.buildActivity(ComponentActivity::class.java).create(parcelCopy(saved))
        assertThrows(RuntimeException::class.java) {
            val legacy = requireNotNull(control.get().savedStateRegistry.consumeRestoredStateForKey("SaveableStateRegistry:-1"))
            @Suppress("DEPRECATION")
            legacy.getSerializable("old-settings")
        }
        control.destroy()

        val current = launch(saved)
        try {
            openSettings(current)
            key(Key.DirectionDown)
            key(Key.DirectionRight)
            if (Build.VERSION.SDK_INT >= 31) {
                key(Key.DirectionCenter)
                key(Key.DirectionDown)
                detail(current, R.string.st_home_filter_on).assertIsFocused()
                assertFalse(OpenTvSettings.persistentHomeFilter)
            } else {
                detail(current, R.string.setup_title).assertIsFocused()
            }
            assertEquals("retained", current.get().savedStateRegistry.consumeRestoredStateForKey("unrelated-owner")?.getString("value"))
            assertLegacyPreferences(current)
            current.get().savedStateRegistry.registerSavedStateProvider("unrelated-owner") {
                Bundle().apply { putString("value", "retained again") }
            }
            val savedAgain = Bundle()
            current.saveInstanceState(savedAgain).pause().stop().destroy()
            val next = launch(savedAgain)
            try {
                openSettings(next)
                if (Build.VERSION.SDK_INT >= 31) {
                    detail(next, R.string.st_home_filter_on).assertIsFocused()
                    assertFalse(OpenTvSettings.persistentHomeFilter)
                    key(Key.DirectionCenter)
                    detail(next, R.string.st_home_filter).assertIsFocused()
                    assertTrue(OpenTvSettings.persistentHomeFilter)
                } else {
                    detail(next, R.string.setup_title).assertIsFocused()
                }
                assertEquals("retained again", next.get().savedStateRegistry.consumeRestoredStateForKey("unrelated-owner")?.getString("value"))
                assertLegacyPreferences(next)
            } finally {
                next.pause().stop().destroy()
            }
        } finally {
            if (!current.get().isDestroyed) current.pause().stop().destroy()
        }
    }

    @Test fun oldScreensaverStringRoutesFallBackWithoutDeletingStoredValues() {
        // These are the old string Saver payloads, not serialized current enum instances.
        val app = RuntimeEnvironment.getApplication()
        listOf(
            listOf("sidebar", "Screensaver", "Screensaver"),
            listOf("setting", "Screensaver"), listOf("setting", "Delay"),
            listOf("action", "StartScreensaver"),
            listOf("choices", "Screensaver", "Photos"), listOf("choices", "Delay", "5"),
        ).forEach { payload ->
            assertEquals(SettingsNavigation(), SettingsNavigationSaver.restore(payload))
            assertEquals("photos", app.getSharedPreferences("wallpaper", Context.MODE_PRIVATE).getString("screensaver", null))
            assertEquals(5, app.getSharedPreferences("settings", Context.MODE_PRIVATE).getInt("screensaver_minutes", 0))
        }
        val current = launch(Bundle())
        try {
            openSettings(current)
            detailSidebar(current, R.string.wp_wallpaper).assertIsFocused()
            assertLegacyPreferences(current)
        } finally {
            current.pause().stop().destroy()
        }
    }

    private fun launch(saved: Bundle): ActivityController<MainActivity> {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        // Robolectric's manually built Activity has no display-associated Context on Android 12+.
        if (Build.VERSION.SDK_INT >= 31) {
            ReflectionHelpers.callInstanceMethod<Void>(
                controller.get().baseContext, "updateDisplay", ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 0),
            )
        }
        return controller.create(parcelCopy(saved)).start().resume().visible()
    }

    private fun openSettings(controller: ActivityController<MainActivity>) {
        compose.runOnIdle {
            ViewModelProvider(controller.get())[HomeViewModel::class.java].overlay = Overlay.Wallpaper
            controller.get().window.decorView.dispatchWindowFocusChanged(true)
        }
        compose.waitForIdle()
    }

    private fun assertLegacyPreferences(controller: ActivityController<MainActivity>) {
        val prefs = controller.get().getSharedPreferences("settings", Context.MODE_PRIVATE)
        assertEquals("legacy-owner-value", prefs.getString("pin_hash", null))
        assertTrue(prefs.getBoolean("show_all_names", false))
        assertTrue(OpenTvSettings.showAllNames)
        assertEquals(5, prefs.getInt("screensaver_minutes", 0))
        assertEquals("photos", controller.get().getSharedPreferences("wallpaper", Context.MODE_PRIVATE).getString("screensaver", null))
    }

    private fun key(key: Key) = compose.onNodeWithTag("settings-sidebar").performKeyInput { pressKey(key) }

    private fun detail(controller: ActivityController<MainActivity>, label: Int) = compose.onNode(
        hasText(controller.get().getString(label)) and
            hasAnyAncestor(hasTestTag("settings-detail")) and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Focused),
    )

    private fun detailSidebar(controller: ActivityController<MainActivity>, label: Int) = compose.onNode(
        hasText(controller.get().getString(label)) and
            hasAnyAncestor(hasTestTag("settings-sidebar")) and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Focused),
    )

    private fun parcelCopy(bundle: Bundle): Bundle {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(bundle)
            parcel.setDataPosition(0)
            requireNotNull(parcel.readBundle(javaClass.classLoader))
        } finally {
            parcel.recycle()
        }
    }

    private class UnreadableLegacyState : Serializable {
        private fun readObject(input: ObjectInputStream) {
            throw InvalidObjectException("Removed settings classes must not be deserialized")
        }
    }
}
