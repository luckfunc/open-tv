package app.opentv.launcher.setup

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import app.opentv.launcher.R
import app.opentv.launcher.settings.OpenTvSettings
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 34], qualifiers = "en-w960dp-h540dp-land-mdpi")
class SetupContentTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun prepare() { OpenTvSettings.init(compose.activity) }

    @Test fun freshInstallationShowsIntroductionWithoutAnyConnectionOrWrite() {
        compose.activity.getSharedPreferences("self_setup", Context.MODE_PRIVATE).edit().clear().commit()
        val environment = AndroidSetupEnvironment(compose.activity)
        assertFalse(environment.acknowledged())
        var checks = 0
        var writes = 0
        var closes = 0
        compose.setContent { SetupContent(SetupState.Intro(false), { checks++ }, { writes++ }, {}, { closes++ }, {}) }
        focusWindow(true)
        compose.onNodeWithTag("setup-intro").assertExists()
        val card = compose.onNodeWithTag("setup-card").fetchSemanticsNode().boundsInRoot
        assertEquals(with(compose.density) { 700.dp.toPx() }, card.width, 1f)
        assertEquals(with(compose.density) { 400.dp.toPx() }, card.height, 1f)
        assertTrue(card.top >= 0f)
        assertTrue(card.bottom <= with(compose.density) { 540.dp.toPx() })
        row(R.string.setup_check).assertIsFocused()
        listOf(R.string.setup_step_connect, R.string.setup_step_enable, R.string.setup_step_finish).forEachIndexed { index, label ->
            compose.onNodeWithTag("setup-step-$index").assertExists().assert(hasAnyDescendant(hasText(compose.activity.getString(label))))
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Focused))
        }
        row(R.string.setup_skip).performTouchInput { click() }
        assertEquals(1, closes)
        assertEquals(0, checks)
        assertEquals(0, writes)
    }

    @Test fun legacyDisclosureOnlyExplainsRetainedDebuggingBehavior() {
        var writes = 0
        val review = SetupState.Review(SetupSnapshot(emptySet(), emptySet(), emptyMap(), false, false), true)
        compose.setContent { SetupContent(review, {}, { writes++ }, {}, {}, {}) }
        compose.onNodeWithText(compose.activity.getString(R.string.setup_legacy_disclosure)).assertExists()
        for (locale in listOf(java.util.Locale.ENGLISH, java.util.Locale.SIMPLIFIED_CHINESE)) {
            val configuration = android.content.res.Configuration(compose.activity.resources.configuration).apply { setLocale(locale) }
            val disclosure = compose.activity.createConfigurationContext(configuration).getString(R.string.setup_legacy_disclosure)
            assertFalse(disclosure.contains("screensaver", ignoreCase = true))
            assertFalse(disclosure.contains("屏保"))
        }
        assertEquals(0, writes)
    }

    @Test fun checkingBackCancelsButReviewBackDismissesWithoutApply() {
        var state by mutableStateOf<SetupState>(SetupState.Checking())
        var cancels = 0
        var closes = 0
        var writes = 0
        compose.setContent { SetupContent(state, {}, { writes++ }, { cancels++ }, { closes++ }, {}) }
        focusWindow(true)
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(1, cancels)
        compose.runOnIdle { state = SetupState.Review(SetupSnapshot(emptySet(), emptySet(), emptyMap(), false, false), false) }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(1, closes)
        assertEquals(0, writes)
    }

    @Test fun reviewShowsOneFriendlyAutoStartFeatureWithoutApplying() {
        var writes = 0
        val review = SetupState.Review(SetupSnapshot(emptySet(), emptySet(), emptyMap(), false, false), false)
        compose.setContent { SetupContent(review, {}, { writes++ }, {}, {}, {}) }
        val label = compose.activity.getString(R.string.setup_purpose_boot)
        val status = compose.activity.getString(R.string.setup_unsupported)
        compose.onAllNodesWithText(compose.activity.getString(R.string.setup_status_row, label, status)).assertCountEquals(1)
        compose.onAllNodesWithText("AUTO_START", substring = true).assertCountEquals(0)
        assertEquals(0, writes)
    }

    @Test fun heldSelectAcrossWindowLossCannotApplyChanges() {
        var writes = 0
        val review = SetupState.Review(SetupSnapshot(emptySet(), emptySet(), emptyMap(), false, false), false)
        compose.setContent { SetupContent(review, {}, { writes++ }, {}, {}, {}) }
        focusWindow(true)
        row(R.string.setup_apply).assertIsFocused()
        compose.onNodeWithTag("setup-review").performKeyInput { keyDown(Key.DirectionCenter) }
        focusWindow(false)
        focusWindow(true)
        compose.onNodeWithTag("setup-review").performKeyInput { keyUp(Key.DirectionCenter) }
        assertEquals(0, writes)
    }

    @Test fun aHeldCancelCannotBecomeApplyWhenAuthorizationCompletes() {
        var state by mutableStateOf<SetupState>(SetupState.Checking())
        var writes = 0
        compose.setContent { SetupContent(state, {}, { writes++ }, {}, {}, {}) }
        focusWindow(true)
        row(R.string.setup_cancel).assertIsFocused()
        compose.onNodeWithTag("setup-checking").performKeyInput { keyDown(Key.DirectionCenter) }
        compose.runOnIdle { state = SetupState.Review(SetupSnapshot(emptySet(), emptySet(), emptyMap(), false, false), false) }
        compose.onNodeWithTag("setup-review").performKeyInput { keyUp(Key.DirectionCenter) }
        assertEquals(0, writes)
    }

    @Test fun verifiedResultFocusesOnlyFinishAndSelectDismissesWithoutCheckingOrApplying() {
        var checks = 0
        var writes = 0
        var closes = 0
        var opens = 0
        val snapshot = SetupSnapshot(SetupOperation.entries.toSet(), SetupOperation.entries.filter { it.optional }.toSet(), emptyMap(), true, true)
        val result = SetupState.Result(SetupReport(SetupOperation.entries.associateWith { SetupOutcome.Verified }, snapshot))
        compose.setContent { SetupContent(result, { checks++ }, { writes++ }, {}, { closes++ }, { opens++ }) }
        focusWindow(true)
        row(R.string.setup_finish).assertIsFocused()
        compose.onAllNodes(isFocusable()).assertCountEquals(1)
        compose.onNodeWithText(compose.activity.getString(R.string.setup_complete_title)).assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.setup_verified)).assertExists()
        SetupOperation.entries.forEach { operation ->
            compose.onAllNodesWithText(compose.activity.getString(operation.label), substring = true).assertCountEquals(0)
        }
        compose.onAllNodesWithText(compose.activity.getString(R.string.setup_listener_binding), substring = true).assertCountEquals(0)
        compose.onAllNodesWithText(compose.activity.getString(R.string.setup_retry)).assertCountEquals(0)
        compose.onAllNodesWithText(compose.activity.getString(R.string.setup_open_settings)).assertCountEquals(0)
        compose.onAllNodesWithText(compose.activity.getString(R.string.setup_done)).assertCountEquals(0)
        compose.onAllNodesWithText(compose.activity.getString(R.string.setup_finish)).assertCountEquals(1)
        compose.onNodeWithTag("setup-result").performKeyInput { pressKey(Key.Enter) }
        assertEquals(1, closes)
        assertEquals(0, checks)
        assertEquals(0, writes)
        assertEquals(0, opens)
    }

    @Test fun partialResultKeepsRetryWithoutApplying() {
        var checks = 0
        var writes = 0
        val snapshot = SetupSnapshot(emptySet(), emptySet(), emptyMap(), false, false)
        val result = SetupState.Result(SetupReport(mapOf(
            SetupOperation.Overlay to SetupOutcome.Failed,
            SetupOperation.SecureSettings to SetupOutcome.Verified,
            SetupOperation.Notification to SetupOutcome.Uncertain,
        ), snapshot))
        compose.setContent { SetupContent(result, { checks++ }, { writes++ }, {}, {}, {}) }
        focusWindow(true)
        compose.onAllNodesWithText(compose.activity.getString(R.string.setup_finish)).assertCountEquals(0)
        compose.onAllNodesWithText(compose.activity.getString(R.string.setup_secure_settings), substring = true).assertCountEquals(0)
        compose.onNodeWithText(compose.activity.getString(R.string.setup_status_row,
            compose.activity.getString(R.string.setup_overlay), compose.activity.getString(R.string.setup_failed))).assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.setup_status_row,
            compose.activity.getString(R.string.setup_notification), compose.activity.getString(R.string.setup_uncertain))).assertExists()
        row(R.string.setup_retry).performTouchInput { click() }
        assertEquals(1, checks)
        assertEquals(0, writes)
    }

    @Test fun failedConnectionOffersDiagnosisWithoutRetryingOrWriting() {
        var checks = 0
        var writes = 0
        var diagnostics = 0
        compose.setContent { SetupContent(SetupState.Blocked(AdbFailureReason.ConnectionRefused),
            { checks++ }, { writes++ }, {}, {}, {}, { diagnostics++ }) }
        focusWindow(true)
        compose.onNodeWithText(compose.activity.getString(R.string.setup_error_code, "C01")).assertExists()
        row(R.string.setup_diagnostics).performTouchInput { click() }
        assertEquals(1, diagnostics)
        assertEquals(0, checks)
        assertEquals(0, writes)
    }

    @Test fun diagnosticSummaryFitsCanvasAndBackDoesNotRunSetup() {
        val store = AndroidSetupDiagnostics.get(compose.activity)
        store.start().record(DiagnosticStage.Connection, DiagnosticEvent.Failed,
            failure = AdbFailure(AdbFailureReason.ConnectionRefused))
        var backs = 0
        compose.setContent { SetupDiagnosticScreen(SetupDiagnosticScreenProps { backs++ }) }
        focusWindow(true)
        compose.waitUntil(5000) { compose.onAllNodesWithText("C01", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("C01", substring = true).assertIsDisplayed()
        row(R.string.setup_diagnostic_back).assertIsDisplayed().assertIsFocused()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(1, backs)
        store.awaitWrites()
    }

    private fun row(resource: Int) = compose.onNode(hasText(compose.activity.getString(resource)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Focused))
    private fun focusWindow(focused: Boolean) {
        compose.runOnIdle { compose.activity.window.decorView.dispatchWindowFocusChanged(focused) }
        compose.waitForIdle()
    }
}
