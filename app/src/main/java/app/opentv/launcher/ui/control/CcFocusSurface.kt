package app.opentv.launcher.ui.control

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import app.opentv.launcher.CcPage
import app.opentv.launcher.ui.focus.FocusCoordinator
import app.opentv.launcher.ui.focus.focusTarget

internal class CcFocusSurfaceState(
    val owner: FocusCoordinator<CcFocusAddress, CcPage>,
    val memory: CcFocusMemory,
    val page: CcPage,
    val active: Boolean,
) {
    fun isActive(destination: CcPage) = active && destination == page

    fun request(destination: CcPage, control: CcControl) {
        if (isActive(destination)) owner.request(CcFocusAddress(destination, control))
    }
}

internal val LocalCcFocusSurface = staticCompositionLocalOf<CcFocusSurfaceState> { error("No Control Center focus owner") }
internal val LocalCcPage = staticCompositionLocalOf { CcPage.Main }

@Composable
internal fun CcFocusSurface(
    visible: Boolean,
    page: CcPage,
    inventory: CcFocusInventory,
    content: @Composable () -> Unit,
) {
    val owner = remember { FocusCoordinator<CcFocusAddress, CcPage> { it.page } }
    val memory = remember { CcFocusMemory() }
    val active = visible && LocalWindowInfo.current.isWindowFocused
    val target = remember(active, page, inventory) {
        // Relinquish traversal before composition can remove the focused binding.
        owner.request(null)
        memory.target(page, inventory)
    }
    DisposableEffect(active, page, inventory) {
        owner.request(if (active) target else null)
        onDispose { owner.request(null) }
    }
    CompositionLocalProvider(LocalCcFocusSurface provides CcFocusSurfaceState(owner, memory, page, active), content = content)
}

@Composable
internal fun Modifier.controlCenterFocus(control: CcControl): Modifier {
    val focus = LocalCcFocusSurface.current
    return focusTarget(focus.owner, CcFocusAddress(LocalCcPage.current, control), focus.memory::observed)
}

internal val ccPageActive: Boolean
    @Composable get() = LocalCcFocusSurface.current.isActive(LocalCcPage.current)
