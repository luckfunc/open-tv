package app.opentv.launcher.ui.wallpaper

import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import app.opentv.launcher.R
import app.opentv.launcher.settings.OpenTvSettings
import app.opentv.launcher.wallpaper.Wallpaper
import app.opentv.launcher.wallpaper.WallpaperKind
import app.opentv.launcher.wallpaper.Wallpapers
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-w960dp-h540dp-land-mdpi")
class SettingsContentTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun prepareSettings() {
        OpenTvSettings.init(compose.activity.applicationContext)
        OpenTvSettings.choosePersistentHomeFilter(false)
    }

    @Test fun selectingAndLeavingAChoiceReturnsToItsOriginRow() {
        show()
        enterLauncher()
        detail(R.string.st_home_filter).assertIsFocused()
        key(Key.DirectionCenter)
        detail(R.string.st_home_filter_off).assertIsFocused()
        key(Key.DirectionDown)
        detail(R.string.st_home_filter_on).assertIsFocused()
        key(Key.DirectionCenter)
        detail(R.string.st_home_filter).assertIsFocused()
        assertTrue(OpenTvSettings.persistentHomeFilter)
        back()
        sidebar(R.string.redesign_launcher).assertIsFocused()
    }

    @Test fun cancellingAChoiceRestoresItsOriginWithoutApplying() {
        show()
        enterLauncher()
        key(Key.DirectionCenter)
        detail(R.string.st_home_filter_off).assertIsFocused()
        key(Key.DirectionDown)
        back()
        detail(R.string.st_home_filter).assertIsFocused()
        assertFalse(OpenTvSettings.persistentHomeFilter)
    }

    @Test fun theSelectedSecondOptionReceivesFocusInsteadOfTheFirstOption() {
        OpenTvSettings.choosePersistentHomeFilter(true)
        show()
        enterLauncher()
        detail(R.string.st_home_filter).assertIsFocused()
        key(Key.DirectionCenter)
        detail(R.string.st_home_filter_on).assertIsFocused()
        key(Key.DirectionLeft)
        detail(R.string.st_home_filter).assertIsFocused()
        assertTrue(OpenTvSettings.persistentHomeFilter)
    }

    @Test fun coveringAndReturningResumesTheLastSettingsRow() {
        var visible by mutableStateOf(true)
        compose.setContent { SettingsContent(visible, {}) }
        focusWindow(true)
        enterLauncher()
        key(Key.DirectionDown)
        detail(R.string.setup_title).assertIsFocused()
        compose.runOnIdle { visible = false }
        compose.onNodeWithTag("settings-detail").assertDoesNotExist()
        compose.runOnIdle { visible = true }
        detail(R.string.setup_title).assertIsFocused()
    }

    @Test fun routeAndChoiceFocusSurviveSavedStateRestoration() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { SettingsContent(true, {}) }
        focusWindow(true)
        enterLauncher()
        key(Key.DirectionCenter)
        key(Key.DirectionDown)
        detail(R.string.st_home_filter_on).assertIsFocused()
        restoration.emulateSavedInstanceStateRestore()
        detail(R.string.st_home_filter_on).assertIsFocused()
        assertFalse(OpenTvSettings.persistentHomeFilter)
        key(Key.DirectionLeft)
        detail(R.string.st_home_filter).assertIsFocused()
    }

    @Test fun launcherChoiceFocusSurvivesRestoration() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { SettingsContent(true, {}) }
        focusWindow(true)
        enterLauncher()
        key(Key.DirectionCenter)
        key(Key.DirectionDown)
        detail(R.string.st_home_filter_on).assertIsFocused()
        restoration.emulateSavedInstanceStateRestore()
        detail(R.string.st_home_filter_on).assertIsFocused()
        key(Key.DirectionCenter)
        detail(R.string.st_home_filter).assertIsFocused()
        assertTrue(OpenTvSettings.persistentHomeFilter)
    }

    @Test fun restoringWhileHiddenKeepsTheChoiceForReopening() {
        var visible by mutableStateOf(true)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { SettingsContent(visible, {}) }
        focusWindow(true)
        enterLauncher()
        key(Key.DirectionCenter)
        key(Key.DirectionDown)
        detail(R.string.st_home_filter_on).assertIsFocused()
        compose.runOnIdle { visible = false }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { visible = true }
        detail(R.string.st_home_filter_on).assertIsFocused()
        back()
        detail(R.string.st_home_filter).assertIsFocused()
    }

    @Test fun virtualizedWallpaperFocusAndScrollSurviveHidingAndRestoration() {
        val items = ReflectionHelpers.getStaticField<MutableState<List<Wallpaper>>>(Wallpapers::class.java, "items\$delegate")
        items.value = (0..11).map { Wallpaper("grad:test$it", WallpaperKind.Builtin, "Test wallpaper $it") }
        val selection = Wallpapers.selectedId
        var visible by mutableStateOf(true)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { SettingsContent(visible, {}) }
        focusWindow(true)
        key(Key.DirectionRight)
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).performScrollToIndex(8)
        val target = compose.onNode(hasAnyDescendant(hasText("Test wallpaper 8")) and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Focused))
        target.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        target.assertIsFocused().assertIsDisplayed()
        compose.runOnIdle { visible = false }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { visible = true }
        target.assertIsFocused().assertIsDisplayed()
        assertEquals(selection, Wallpapers.selectedId)
    }

    @Test fun aSelectHeldAcrossWindowLossCannotActivateOnReturn() {
        show()
        enterLauncher()
        key(Key.DirectionCenter)
        key(Key.DirectionDown)
        detail(R.string.st_home_filter_on).assertIsFocused()
        compose.onNodeWithTag("settings-detail").performKeyInput { keyDown(Key.DirectionCenter) }
        focusWindow(false)
        focusWindow(true)
        compose.onNodeWithTag("settings-detail").performKeyInput { keyUp(Key.DirectionCenter) }
        detail(R.string.st_home_filter_on).assertIsFocused()
        assertFalse(OpenTvSettings.persistentHomeFilter)
    }

    @Test fun aSelectHeldAcrossHidingCannotActivateOnReturn() {
        var visible by mutableStateOf(true)
        compose.setContent { SettingsContent(visible, {}) }
        focusWindow(true)
        enterLauncher()
        key(Key.DirectionCenter)
        key(Key.DirectionDown)
        compose.onNodeWithTag("settings-detail").performKeyInput { keyDown(Key.DirectionCenter) }
        compose.runOnIdle { visible = false }
        compose.runOnIdle { visible = true }
        compose.onNodeWithTag("settings-detail").performKeyInput { keyUp(Key.DirectionCenter) }
        detail(R.string.st_home_filter_on).assertIsFocused()
        assertFalse(OpenTvSettings.persistentHomeFilter)
    }

    @Test fun noScreensaverEntryIsRendered() {
        show()
        listOf("Screensaver", "Start Now", "Start after").forEach { compose.onNodeWithText(it).assertDoesNotExist() }
        sidebar(R.string.wp_wallpaper).assertExists()
        sidebar(R.string.redesign_launcher).assertExists()
        sidebar(R.string.redesign_phone).assertExists()
    }

    @Test @Config(sdk = [30]) fun launcherOffersSetupButNotKeyFilteringBeforeAndroid12() {
        show()
        enterLauncher()
        detail(R.string.setup_title).assertIsFocused()
        detail(R.string.st_home_filter).assertDoesNotExist()
    }

    private fun show() {
        compose.setContent { SettingsContent(true, {}) }
        focusWindow(true)
        sidebar(R.string.wp_wallpaper).assertIsFocused()
    }

    private fun enterLauncher() { key(Key.DirectionDown); key(Key.DirectionRight) }
    private fun back() = compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    private fun key(key: Key) = compose.onNodeWithTag("settings-sidebar").performKeyInput { pressKey(key) }
    private fun focusWindow(focused: Boolean) {
        compose.runOnIdle { compose.activity.window.decorView.dispatchWindowFocusChanged(focused) }
        compose.waitForIdle()
    }
    private fun detail(label: Int) = row(compose.activity.getString(label), "settings-detail")
    private fun sidebar(label: Int) = row(compose.activity.getString(label), "settings-sidebar")
    private fun row(label: String, pane: String) = compose.onNode(
        hasText(label) and hasAnyAncestor(hasTestTag(pane)) and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Focused),
    )
}
