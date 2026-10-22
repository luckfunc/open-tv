package app.opentv.launcher.ui.home

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import app.opentv.launcher.R
import app.opentv.launcher.apps.AppEntry
import app.opentv.launcher.apps.CustomIcons
import app.opentv.launcher.apps.TileArt
import app.opentv.launcher.settings.OpenTvSettings
import app.opentv.launcher.system.SourceKind
import app.opentv.launcher.system.TvSource
import app.opentv.launcher.ui.theme.LocalReducedMotion
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-w960dp-h540dp-land-mdpi")
class AppMenuTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val opened = mutableListOf<Intent>()
    private val actions = mutableListOf<String>()
    private val packageName = "test.menu.app"
    private var displayedApp by mutableStateOf<AppEntry?>(null)

    @Before fun prepare() {
        OpenTvSettings.init(compose.activity.application)
        CustomIcons.init(compose.activity.application)
    }

    @Test fun unsupportedInfoIsAbsentAndDpadOnlyVisitsRemainingActions() {
        show()
        row(R.string.menu_info).assertDoesNotExist()
        compose.onAllNodes(isFocusable()).assertCountEquals(2)
        row(R.string.menu_move).assertIsFocused()
        press(Key.DirectionDown)
        row(R.string.menu_hide).assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(listOf("hide:$packageName"), actions)
        assertTrue(opened.isEmpty())
    }

    @Test fun supportedInfoDeliversTheResolvedIntentOnce() {
        register(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        show()
        row(R.string.menu_info).assertExists()
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        row(R.string.menu_info).assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(1, opened.size)
        val intent = opened.single()
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals(Uri.parse("package:$packageName"), intent.data)
        assertNull(intent.component)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, intent.flags)
        assertTrue(actions.isEmpty())
    }

    @Test fun infoLaunchHonorsPreferredHandlerInsteadOfFirstCandidate() {
        val action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        val first = ComponentName("test.handler", "test.handler.Entry")
        val preferred = ComponentName("test.preferred", "test.preferred.Entry")
        register(action)
        register(action, component = preferred)
        val pm = compose.activity.packageManager
        val request = Intent(action, Uri.parse("package:$packageName"))
        val candidates = pm.queryIntentActivities(request, PackageManager.MATCH_DEFAULT_ONLY)
        assertEquals(first.packageName, candidates.first().activityInfo.packageName)
        pm.addPreferredActivity(IntentFilter(action).apply {
            addDataScheme("package")
            addCategory(Intent.CATEGORY_DEFAULT)
        }, IntentFilter.MATCH_CATEGORY_SCHEME, arrayOf(first, preferred), preferred)
        assertEquals(preferred.packageName,
            pm.resolveActivity(request, PackageManager.MATCH_DEFAULT_ONLY)!!.activityInfo.packageName)
        show()
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        val captured = opened.single()
        assertEquals(preferred.packageName,
            pm.resolveActivity(captured, PackageManager.MATCH_DEFAULT_ONLY)!!.activityInfo.packageName)
        assertEquals(preferred.className,
            pm.resolveActivity(captured, PackageManager.MATCH_DEFAULT_ONLY)!!.activityInfo.name)
    }

    @Test fun reopeningSameAppReevaluatesInfoHandler() {
        show()
        row(R.string.menu_info).assertDoesNotExist()
        val sameApp = displayedApp!!
        compose.runOnIdle { displayedApp = null }
        compose.waitForIdle()
        register(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        compose.runOnIdle { displayedApp = sameApp }
        row(R.string.menu_info).assertExists()
        row(R.string.menu_move).assertIsFocused()
    }

    @Test fun heldSelectRepeatAndOpeningReleaseCannotActivateMove() {
        show()
        compose.runOnIdle {
            compose.activity.dispatchKeyEvent(KeyEvent(100, 700, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 1))
            compose.activity.dispatchKeyEvent(KeyEvent(100, 750, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        }
        assertTrue(actions.isEmpty())
        assertTrue(opened.isEmpty())
        press(Key.DirectionCenter)
        assertEquals(listOf("move:$packageName"), actions)
    }

    @Test fun removedFocusedActionReconcilesToMoveForSource() {
        register(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        show()
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        row(R.string.menu_info).assertIsFocused()
        compose.runOnIdle {
            displayedApp = displayedApp!!.copy(source = TvSource("hdmi1", "HDMI 1", SourceKind.Hdmi, true, null, Intent("test.INPUT")))
        }
        row(R.string.menu_info).assertDoesNotExist()
        row(R.string.menu_move).assertIsFocused()
        press(Key.DirectionDown)
        row(R.string.menu_unpin_source).assertIsFocused()
    }

    @Test fun privateInfoHandlerIsExcluded() {
        register(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, exported = false)
        assertUnavailable()
    }

    @Test fun permissionDeniedInfoHandlerIsExcluded() {
        register(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, permission = "test.permission.PRIVATE")
        assertUnavailable()
    }

    @Test fun nonDefaultInfoHandlerIsExcluded() {
        register(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, default = false)
        assertUnavailable()
    }

    @Test fun disabledInfoHandlerIsExcluded() {
        register(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, enabled = false)
        assertUnavailable()
    }

    @Test fun disabledHandlerApplicationIsExcluded() {
        register(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, applicationEnabled = false)
        assertUnavailable()
    }

    @Test fun removableAppOnlyOffersUninstallWhenHandlerExists() {
        show(removable = true)
        row(R.string.menu_uninstall).assertDoesNotExist()
        compose.onAllNodes(isFocusable()).assertCountEquals(2)
    }

    @Test fun supportedUninstallIsDpadReachableWithoutActuallyUninstalling() {
        register(Intent.ACTION_DELETE)
        show(removable = true)
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        row(R.string.menu_uninstall).assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(1, opened.size)
        assertEquals(Intent.ACTION_DELETE, opened.single().action)
        assertNull(opened.single().component)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, opened.single().flags)
        assertEquals(Uri.parse("package:$packageName"), opened.single().data)
    }

    @Test fun systemAppNeverOffersUninstallEvenWithHandler() {
        register(Intent.ACTION_DELETE)
        show()
        row(R.string.menu_uninstall).assertDoesNotExist()
        press(Key.DirectionCenter)
        assertEquals(listOf("move:$packageName"), actions)
    }

    @Test fun sourceOnlyOffersMoveAndUnpinEvenWithAppHandlers() {
        register(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        register(Intent.ACTION_DELETE)
        show(source = TvSource("hdmi1", "HDMI 1", SourceKind.Hdmi, true, null, Intent("test.INPUT")))
        row(R.string.menu_info).assertDoesNotExist()
        row(R.string.menu_hide).assertDoesNotExist()
        row(R.string.menu_reset_icon).assertDoesNotExist()
        row(R.string.menu_uninstall).assertDoesNotExist()
        compose.onAllNodes(isFocusable()).assertCountEquals(2)
        press(Key.DirectionDown)
        row(R.string.menu_unpin_source).assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(listOf("unpin:$packageName"), actions)
    }

    @Test fun customIconResetRemainsDpadReachable() {
        File(compose.activity.filesDir, "icons/$packageName.png").writeBytes(byteArrayOf(1))
        show()
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        row(R.string.menu_reset_icon).assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(listOf("reset:$packageName"), actions)
    }

    private fun assertUnavailable() {
        show()
        row(R.string.menu_info).assertDoesNotExist()
        compose.onAllNodes(isFocusable()).assertCountEquals(2)
        press(Key.DirectionDown)
        row(R.string.menu_hide).assertIsFocused()
    }

    private fun register(action: String, exported: Boolean = true, enabled: Boolean = true,
        applicationEnabled: Boolean = true, permission: String? = null, default: Boolean = true,
        component: ComponentName = ComponentName("test.handler", "test.handler.Entry")) {
        val pm = shadowOf(compose.activity.packageManager)
        pm.addOrUpdateActivity(ActivityInfo().apply {
            packageName = component.packageName
            name = component.className
            this.exported = exported
            this.enabled = enabled
            this.permission = permission
            applicationInfo = ApplicationInfo().apply {
                packageName = component.packageName
                this.enabled = applicationEnabled
            }
        })
        pm.addIntentFilterForActivity(component, IntentFilter(action).apply {
            addDataScheme("package")
            if (default) addCategory(Intent.CATEGORY_DEFAULT)
        })
    }

    private fun show(removable: Boolean = false, source: TvSource? = null) {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).asImageBitmap()
        val app = AppEntry(packageName, "Test app", ComponentName(packageName, "Main"), true, removable,
            TileArt(bitmap, null, Rect.Zero, Color.Black, bitmap), source)
        displayedApp = app
        compose.setContent {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                AppMenu(displayedApp, Rect.Zero, { actions += "move:$it" }, { actions += "hide:$it" },
                    { opened += it }, { actions += "unpin:$it" }, { actions += "reset:$it" }, {})
            }
        }
        compose.runOnIdle { compose.activity.window.decorView.dispatchWindowFocusChanged(true) }
        compose.waitForIdle()
    }

    private fun row(label: Int) = compose.onNode(isFocusable() and hasAnyDescendant(hasText(compose.activity.getString(label))))
    private fun press(key: Key) = compose.onRoot().performKeyInput { pressKey(key) }
}
