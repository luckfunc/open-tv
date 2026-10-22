package app.opentv.launcher.ui.control

import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import app.opentv.launcher.CcPage
import app.opentv.launcher.settings.OpenTvSettings
import app.opentv.launcher.ui.theme.LocalReducedMotion
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-w960dp-h540dp-land-mdpi")
class CcFocusSurfaceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var page by mutableStateOf(CcPage.Main)
    private var visible by mutableStateOf(true)
    private val inventory = CcFocusInventory
    private var mountDefault by mutableStateOf(true)
    private var mountAbout by mutableStateOf(true)
    private val focusEvents = mutableListOf<CcFocusAddress>()

    @Before fun prepareSettings() {
        OpenTvSettings.init(compose.activity.applicationContext)
    }

    @Test fun animatedChildHandoffAndParentReturnKeepTheirOwnFocusScopes() {
        show()
        row(CcPage.Main, CcControl.Network).assertIsFocused()
        compose.runOnIdle { page = CcPage.About }
        row(CcPage.About, CcControl.Back).assertIsFocused()
        key(CcPage.About, CcControl.Back, Key.DirectionCenter)
        row(CcPage.Main, CcControl.Network).assertIsFocused()
        repeat(inventory.controls(CcPage.Main).size - 1) { key(CcPage.Main, inventory.controls(CcPage.Main)[it], Key.DirectionDown) }
        row(CcPage.Main, CcControl.About).assertIsFocused()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { focusEvents.clear() }
        key(CcPage.Main, CcControl.About, Key.DirectionCenter)
        compose.runOnIdle { assertEquals(CcPage.About, page) }
        compose.mainClock.advanceTimeBy(64)
        row(CcPage.About, CcControl.Back).assertIsFocused()
        compose.onNodeWithText(label(CcPage.Main, CcControl.About)).assertExists()
        compose.onAllNodes(isFocused()).assertCountEquals(1)
        compose.runOnIdle {
            assertEquals(listOf(CcFocusAddress(CcPage.About, CcControl.Back)), focusEvents)
            focusEvents.clear()
        }
        key(CcPage.About, CcControl.Back, Key.DirectionCenter)
        compose.mainClock.advanceTimeBy(64)
        row(CcPage.Main, CcControl.About).assertIsFocused()
        compose.onAllNodes(isFocused()).assertCountEquals(1)
        compose.runOnIdle {
            assertEquals(listOf(CcFocusAddress(CcPage.Main, CcControl.About)), focusEvents)
        }
        compose.mainClock.autoAdvance = true
    }

    @Test fun aMissingBindingWaitsForTheRealDefaultInsteadOfFocusingAnotherControl() {
        mountDefault = false
        show()
        compose.onAllNodes(isFocused()).assertCountEquals(0)
        compose.runOnIdle { mountDefault = true }
        row(CcPage.Main, CcControl.Network).assertIsFocused()
    }

    @Test fun returningToASurfaceWaitsForItsRememberedBindingToRemount() {
        show()
        repeat(inventory.controls(CcPage.Main).size - 1) { key(CcPage.Main, inventory.controls(CcPage.Main)[it], Key.DirectionDown) }
        row(CcPage.Main, CcControl.About).assertIsFocused()
        compose.runOnIdle { visible = false }
        compose.onAllNodes(isFocused()).assertCountEquals(0)
        compose.runOnIdle { mountAbout = false; focusEvents.clear() }
        compose.runOnIdle { visible = true }
        compose.onAllNodes(isFocused()).assertCountEquals(0)
        compose.runOnIdle { mountAbout = true }
        row(CcPage.Main, CcControl.About).assertIsFocused()
        compose.runOnIdle { assertEquals(listOf(CcFocusAddress(CcPage.Main, CcControl.About)), focusEvents) }
    }

    @Test fun hiddenSurfaceDoesNotAcquireALateBindingUntilShown() {
        visible = false
        mountDefault = false
        show()
        compose.runOnIdle { mountDefault = true }
        compose.onAllNodes(isFocused()).assertCountEquals(0)
        compose.runOnIdle { visible = true }
        row(CcPage.Main, CcControl.Network).assertIsFocused()
    }

    @Test fun coveringAndReturningRestoresTheLastControlWithoutADefaultExcursion() {
        show()
        key(CcPage.Main, CcControl.Network, Key.DirectionDown)
        row(CcPage.Main, CcControl.Personalize).assertIsFocused()
        compose.runOnIdle { visible = false }
        compose.onAllNodes(isFocused()).assertCountEquals(0)
        compose.runOnIdle { focusEvents.clear(); visible = true }
        row(CcPage.Main, CcControl.Personalize).assertIsFocused()
        compose.runOnIdle { assertEquals(listOf(CcFocusAddress(CcPage.Main, CcControl.Personalize)), focusEvents) }
    }

    @Test fun anInactiveWindowDoesNotAcquireALateBindingUntilItReturns() {
        mountDefault = false
        show()
        focusWindow(false)
        compose.runOnIdle { mountDefault = true }
        compose.onAllNodes(isFocused()).assertCountEquals(0)
        focusWindow(true)
        row(CcPage.Main, CcControl.Network).assertIsFocused()
    }

    @Test fun focusedSingleRowOccupiesTheWholeGlassPillWithoutAnInsetBand() {
        compose.setContent {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                CcFocusSurface(true, CcPage.About, inventory) {
                    CompositionLocalProvider(LocalCcPage provides CcPage.About) {
                        Box(Modifier.width(236.dp).testTag("glass-pill")) {
                            ControlGroup { ControlRow(null, "About", CcControl.Back) {} }
                        }
                    }
                }
            }
        }
        focusWindow(true)
        val focusedRow = compose.onNode(isFocused())
        focusedRow.assertIsFocused()
        assertEquals(compose.onNodeWithTag("glass-pill").fetchSemanticsNode().boundsInRoot,
            focusedRow.fetchSemanticsNode().boundsInRoot)
    }

    private fun show() {
        compose.setContent {
            CcFocusSurface(visible, page, inventory) {
                AnimatedContent(page, transitionSpec = { fadeIn(tween(240)).togetherWith(fadeOut(tween(180))) }) { destination ->
                    CompositionLocalProvider(LocalCcPage provides destination) {
                        Column(Modifier.width(236.dp)) {
                            inventory.controls(destination).forEach { control ->
                                if ((mountDefault || control != inventory.default(destination)) && (mountAbout || control != CcControl.About)) {
                                    Box(Modifier.onFocusChanged { if (it.isFocused) focusEvents += CcFocusAddress(destination, control) }) {
                                        ControlRow(null, label(destination, control), control) {
                                            page = when (control) {
                                                CcControl.About -> CcPage.About
                                                CcControl.Back -> destination.parentPage() ?: CcPage.Main
                                                else -> destination
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        focusWindow(true)
    }

    private fun focusWindow(focused: Boolean) {
        compose.runOnIdle { compose.activity.window.decorView.dispatchWindowFocusChanged(focused) }
        compose.waitForIdle()
    }

    private fun label(page: CcPage, control: CcControl) = "$page $control"
    private fun row(page: CcPage, control: CcControl) = compose.onNode(
        hasAnyDescendant(hasText(label(page, control))) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Focused),
    )
    private fun key(page: CcPage, control: CcControl, key: Key) = row(page, control).performKeyInput { pressKey(key) }
}
