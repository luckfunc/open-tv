package app.opentv.launcher.ui.home

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.lifecycle.ViewModelStore
import app.opentv.launcher.CcPage
import app.opentv.launcher.HomeViewModel
import app.opentv.launcher.Overlay
import app.opentv.launcher.R
import app.opentv.launcher.apps.CustomIcons
import app.opentv.launcher.settings.OpenTvSettings
import app.opentv.launcher.system.SystemProbe
import app.opentv.launcher.ui.theme.LocalReducedMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-w960dp-h540dp-land-mdpi")
class HomeBackTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    private lateinit var vm: HomeViewModel
    private val openedIntents = mutableListOf<Intent>()

    @Before fun prepare() {
        Dispatchers.setMain(dispatcher)
        val app = compose.activity.application
        app.getSharedPreferences("self_setup", Context.MODE_PRIVATE).edit().putBoolean("acknowledged", true).commit()
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putString("pin_hash", "legacy-owner-hash").putString("owner_marker", "retained").commit()
        OpenTvSettings.init(app)
        CustomIcons.init(app)
        OpenTvSettings.chooseShowAllNames(true)
        OpenTvSettings.chooseOneRowHome(false)
        val pm = shadowOf(app.packageManager)
        val entries = (0..11).map { index ->
            ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    packageName = "app.opentv.backtest$index"
                    name = "$packageName.Main"
                    nonLocalizedLabel = "Back test $index"
                    applicationInfo = ApplicationInfo().apply {
                        packageName = "app.opentv.backtest$index"
                        enabled = true
                    }
                    exported = true
                    enabled = true
                }
            }
        }
        pm.addResolveInfoForIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), entries)
        vm = HomeViewModel(app)
        models.put("home", vm)
        compose.waitUntil(10_000) {
            dispatcher.scheduler.runCurrent()
            vm.loaded
        }
        assertEquals(12, vm.apps.size)
        compose.setContent {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                HomeScreen(vm, { _, _ -> }, { intent ->
                    assertEquals(Overlay.None, vm.overlay)
                    openedIntents += intent
                })
            }
        }
        compose.runOnIdle { compose.activity.window.decorView.dispatchWindowFocusChanged(true) }
        compose.waitForIdle()
    }

    @After fun cleanUp() {
        models.clear()
        Dispatchers.resetMain()
    }

    @Test fun repeatedDispatcherBackAtRootStaysHome() {
        tile(0).assertIsFocused()
        repeat(3) {
            back()
            tile(0).assertIsFocused()
            assertHome()
        }
    }

    @Test fun repeatedNativeBackAtRootStaysHome() {
        repeat(3) {
            nativeBack()
            tile(0).assertIsFocused()
            assertHome()
        }
    }

    @Test fun repeatedBackFromStatusPillStaysHome() {
        press(Key.DirectionUp)
        repeat(3) {
            back()
            assertHome()
        }
        press(Key.DirectionDown)
        tile(0).assertIsFocused()
    }

    @Test fun backFromLowerRowReturnsToTopBeforeBecomingABoundary() {
        press(Key.DirectionDown)
        tile(6).assertIsFocused()
        back()
        tile(0).assertIsFocused()
        back()
        tile(0).assertIsFocused()
        assertHome()
    }

    @Test fun backClosesControlCenterChildThenParentAndStaysHome() {
        compose.runOnIdle { vm.overlay = Overlay.ControlCenter(CcPage.About) }
        back()
        compose.runOnIdle { assertEquals(Overlay.ControlCenter(), vm.overlay) }
        back()
        assertHome()
        back()
        assertHome()
    }

    @Test fun nativeBackCancelsReorderAndRestoresOriginalOrder() {
        val order = vm.apps.map { it.key }
        compose.runOnIdle {
            vm.startMove(order.first())
            vm.moveBy(1)
        }
        nativeBack()
        compose.runOnIdle {
            assertNull(vm.movingKey)
            assertEquals(order, vm.apps.map { it.key })
        }
        assertHome()
    }

    @Test fun expandedControlCenterKeepsTitleTimeAndLabeledControls() {
        compose.runOnIdle { vm.overlay = Overlay.ControlCenter() }
        compose.onNodeWithText(compose.activity.getString(R.string.cc_title)).assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.cc_personalize)).assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.cc_network)).assertExists()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.cc_system_home)).assertExists()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.cc_about)).assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.cc_system_home)).assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.cc_about)).assertDoesNotExist()
        compose.onNode(hasText(compose.activity.getString(R.string.cc_title)) and hasAnySibling(
            SemanticsMatcher("clock text") { node ->
                node.config.getOrElse(SemanticsProperties.Text) { emptyList() }
                    .any { it.text.matches(Regex("\\d{1,2}:\\d{2}")) }
            },
        )).assertExists()
    }

    @Test fun aboutShowsOnlyTheUserFacingVersion() {
        compose.runOnIdle { vm.overlay = Overlay.ControlCenter(CcPage.About) }
        compose.onNodeWithText("OpenTV ${app.opentv.launcher.BuildConfig.VERSION_NAME.substringBefore('-')}").assertExists()
        listOf("GitHub", "https://", "APK", "Home Guard", "signing key", "-guard", "install.sh", "BUILD=1").forEach { text ->
            compose.onNodeWithText(text, substring = true).assertDoesNotExist()
        }
    }

    @Test fun navigationKeysStillWorkAndBackReturnsHome() {
        press(Key.DirectionDown)
        tile(6).assertIsFocused()
        back()
        tile(0).assertIsFocused()
        assertHome()
    }

    @Test fun activityHasNoAutomaticScreensaverOrWakeInputOverrides() {
        val activity = app.opentv.launcher.MainActivity::class.java
        assertFalse(activity.declaredMethods.any {
            it.name in setOf("dispatchKeyEvent", "dispatchTouchEvent", "restartIdleTimer", "idleBeforeScreensaver", "hideScreensaver")
        })
        assertFalse(activity.declaredFields.any { it.name.contains("screensaver", ignoreCase = true) })
        assertFalse(HomeViewModel::class.java.declaredFields.any { it.name.contains("screensaver", ignoreCase = true) })
    }

    @Test fun legacyParentalHashDoesNotGateSettingsOrHiddenAppsOrErasePreferences() {
        val context = compose.activity
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val saved = prefs.all.toMap()
        compose.runOnIdle { vm.overlay = Overlay.ControlCenter() }
        compose.onNodeWithContentDescription(context.getString(R.string.cc_settings)).performClick()
        compose.runOnIdle {
            assertEquals(1, openedIntents.size)
            assertTrue(SystemProbe.settingsIntent(context).filterEquals(openedIntents.last()))
            assertEquals(Overlay.None, vm.overlay)
            vm.overlay = Overlay.ControlCenter()
        }
        compose.onNodeWithContentDescription(context.getString(R.string.cc_hidden_apps)).performClick()
        compose.runOnIdle { assertEquals(Overlay.HiddenApps, vm.overlay) }
        back()
        compose.runOnIdle {
            assertEquals(Overlay.ControlCenter(), vm.overlay)
            assertEquals(saved, prefs.all)
            assertEquals("legacy-owner-hash", prefs.getString("pin_hash", null))
        }
    }

    private fun assertHome() = compose.runOnIdle {
        assertEquals(Overlay.None, vm.overlay)
        assertFalse(compose.activity.isFinishing)
    }

    private fun tile(index: Int) = compose.onNode(
        hasAnySibling(hasText(vm.apps[index].label)) and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Focused),
    )

    private fun back() {
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun nativeBack() {
        compose.runOnIdle {
            compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        }
        compose.waitForIdle()
    }

    private fun press(key: Key) = compose.onRoot().performKeyInput { pressKey(key) }
}
