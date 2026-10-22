package app.opentv.launcher.ui.control

import app.opentv.launcher.CcPage

internal sealed interface CcControl {
    data object Personalize : CcControl
    data object Network : CcControl
    data object Settings : CcControl
    data object HiddenApps : CcControl
    data object SystemHome : CcControl
    data object About : CcControl
    data object Back : CcControl
}

internal data class CcFocusAddress(val page: CcPage, val control: CcControl)

internal object CcFocusInventory {
    fun controls(page: CcPage): List<CcControl> = when (page) {
        CcPage.Main -> listOf(
            CcControl.Network, CcControl.Personalize,
            CcControl.Settings, CcControl.HiddenApps, CcControl.SystemHome, CcControl.About,
        )
        CcPage.About -> listOf(CcControl.Back)
    }

    fun default(page: CcPage): CcControl = when (page) {
        CcPage.Main -> CcControl.Network
        else -> CcControl.Back
    }
}

internal fun controlCenterFocusTarget(
    remembered: CcControl?,
    available: List<CcControl>,
    default: CcControl,
): CcControl? = remembered?.takeIf { it in available } ?: default.takeIf { it in available } ?: available.firstOrNull()

internal class CcFocusMemory {
    private val last = mutableMapOf<CcPage, CcControl>()

    fun target(page: CcPage, inventory: CcFocusInventory): CcFocusAddress = CcFocusAddress(
        page,
        checkNotNull(controlCenterFocusTarget(last[page], inventory.controls(page), inventory.default(page))),
    )

    fun observed(address: CcFocusAddress) {
        last[address.page] = address.control
    }
}

internal fun CcPage.parentPage(): CcPage? = when (this) {
    CcPage.Main -> null
    CcPage.About -> CcPage.Main
}

internal val CcPage.depth: Int
    get() = when (this) {
        CcPage.Main -> 0
        CcPage.About -> 1
    }
