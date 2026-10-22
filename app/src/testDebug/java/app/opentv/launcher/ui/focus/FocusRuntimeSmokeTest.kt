package app.opentv.launcher.ui.focus

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FocusRuntimeSmokeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun hostExecutesRealComposeFocusRequests() {
        compose.setContent {
            val requester = remember { FocusRequester() }
            Box(Modifier.size(40.dp).testTag("target").focusRequester(requester).focusable())
            LaunchedEffect(Unit) { requester.requestFocus() }
        }
        compose.onNodeWithTag("target").assertIsFocused()
    }
}
