package app.opentv.launcher.ui.control

import app.opentv.launcher.CcPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlCenterPolicyTest {
    private val inventory = CcFocusInventory

    @Test fun backFollowsThePageHierarchy() {
        assertNull(CcPage.Main.parentPage())
        assertEquals(CcPage.Main, CcPage.About.parentPage())
    }

    @Test fun everyBackTransitionMovesTowardTheRoot() {
        CcPage.entries.forEach { page ->
            page.parentPage()?.let { parent -> assertTrue(parent.depth < page.depth) }
        }
        assertEquals(1, CcPage.About.depth)
    }

    @Test fun mainInventoryIncludesEveryActionInVisualOrder() {
        assertEquals(listOf(
            CcControl.Network, CcControl.Personalize,
            CcControl.Settings, CcControl.HiddenApps, CcControl.SystemHome, CcControl.About,
        ), inventory.controls(CcPage.Main))
    }

    @Test fun noScreensaverControlIsExposed() {
        assertTrue(CcPage.entries.flatMap(inventory::controls).none { it.toString().contains("Screensaver") })
    }

    @Test fun aboutHasOnlyItsBackControl() {
        assertEquals(listOf(CcControl.Back), inventory.controls(CcPage.About))
    }

    @Test fun everyPageRetainsItsExistingDefault() {
        val memory = CcFocusMemory()
        CcPage.entries.forEach { page ->
            val expected = if (page == CcPage.Main) CcControl.Network else CcControl.Back
            assertEquals(CcFocusAddress(page, expected), memory.target(page, inventory))
            assertTrue(expected in inventory.controls(page))
        }
    }

    @Test fun returningToMainRestoresTheDetailsEntry() {
        val memory = CcFocusMemory()
        memory.observed(CcFocusAddress(CcPage.Main, CcControl.About))
        memory.observed(memory.target(CcPage.About, inventory))
        assertEquals(CcControl.About, memory.target(checkNotNull(CcPage.About.parentPage()), inventory).control)
    }

    @Test fun unavailableControlsFallBackToBack() {
        val memory = CcFocusMemory()
        memory.observed(CcFocusAddress(CcPage.About, CcControl.Personalize))
        assertEquals(CcControl.Back, memory.target(CcPage.About, inventory).control)
    }

    @Test fun changingAnotherPageDoesNotLoseRememberedIdentity() {
        val memory = CcFocusMemory()
        memory.observed(CcFocusAddress(CcPage.Main, CcControl.Personalize))
        memory.observed(memory.target(CcPage.About, inventory))
        assertEquals(CcControl.Personalize, memory.target(CcPage.Main, inventory).control)
    }

    @Test fun missingDefaultsCannotRequestAnUnattachedControl() {
        assertEquals(CcControl.Back, controlCenterFocusTarget(CcControl.Personalize, listOf(CcControl.Back), CcControl.About))
        assertNull(controlCenterFocusTarget(null, emptyList(), CcControl.About))
    }
}
