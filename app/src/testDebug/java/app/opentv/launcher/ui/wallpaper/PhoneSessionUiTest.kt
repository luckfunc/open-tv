package app.opentv.launcher.ui.wallpaper

import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.opentv.launcher.wallpaper.PhoneSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhoneSessionUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val clock = TestCoroutineScheduler()
    private val applicationScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(clock))
    private var starts = 0
    private var stops = 0
    private val session = PhoneSession(applicationScope) {
        object : PhoneSession.Server {
            override fun open(): Boolean { starts++; return true }
            override fun address() = "http://phone/"
            override fun close() { stops++ }
        }
    }

    @After fun cancelApplicationScope() { applicationScope.cancel() }

    @Test fun anInitiallyHiddenScreenDoesNotStartOrStopAService() {
        var visible by mutableStateOf(false)
        var observed: String? = null
        compose.setContent {
            val url = rememberPhoneUrl(session, visible)
            SideEffect { observed = url }
        }
        compose.runOnIdle {
            clock.advanceTimeBy(600_000)
            clock.runCurrent()
            assertEquals(0, starts)
            assertEquals(0, stops)
            assertNull(observed)
            visible = true
        }
        compose.runOnIdle { clock.runCurrent() }
        compose.runOnIdle {
            assertEquals("http://phone/", observed)
            visible = false
        }
        compose.runOnIdle {
            assertNull(observed)
            clock.runCurrent()
            clock.advanceTimeBy(300_000)
            clock.runCurrent()
            assertEquals(1, stops)
            assertNull(session.url.value)
        }
    }

    @Test fun disposalCancelsTheUiScopeButNotTheGraceShutdown() {
        var mounted by mutableStateOf(true)
        lateinit var uiScope: CoroutineScope
        compose.setContent {
            if (mounted) {
                val scope = rememberCoroutineScope()
                rememberPhoneUrl(session, true)
                SideEffect { uiScope = scope }
            }
        }
        compose.runOnIdle {
            clock.runCurrent()
            assertEquals(1, starts)
            mounted = false
        }
        compose.runOnIdle {
            assertFalse(uiScope.isActive)
            clock.runCurrent()
            clock.advanceTimeBy(299_999)
            clock.runCurrent()
            assertEquals(0, stops)
            clock.advanceTimeBy(1)
            clock.runCurrent()
            assertEquals(1, stops)
            assertNull(session.url.value)
        }
    }

    @Test fun disposalBeforeStartupDoesNotLoseTheFinalRelease() {
        var mounted by mutableStateOf(true)
        compose.setContent { if (mounted) rememberPhoneUrl(session, true) }
        compose.runOnIdle {
            assertEquals(0, starts)
            mounted = false
        }
        compose.runOnIdle {
            clock.runCurrent()
            assertEquals(1, starts)
            clock.advanceTimeBy(300_000)
            clock.runCurrent()
            assertEquals(1, stops)
            assertNull(session.url.value)
        }
    }
}
