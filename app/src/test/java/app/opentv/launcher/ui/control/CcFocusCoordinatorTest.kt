package app.opentv.launcher.ui.control

import app.opentv.launcher.CcPage
import app.opentv.launcher.ui.focus.FocusCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CcFocusCoordinatorTest {
    @Test fun outgoingPagesCannotAcquireTheIncomingPagesBackControl() {
        val owner = FocusCoordinator<CcFocusAddress, CcPage> { it.page }
        val outgoing = CcFocusAddress(CcPage.Main, CcControl.About)
        val incoming = CcFocusAddress(CcPage.About, CcControl.Back)
        val requests = mutableListOf<CcFocusAddress>()
        val old = owner.bind(outgoing) { requests += outgoing; true }
        old.attach()
        old.placed()
        owner.request(outgoing)
        assertTrue(old.observed(true))

        owner.request(incoming)
        assertFalse(old.canFocus)
        assertFalse(old.observed(true))
        val next = owner.bind(incoming) { requests += incoming; true }
        next.placed()
        assertEquals(listOf(outgoing), requests)
        next.attach()
        assertEquals(listOf(outgoing, incoming), requests)
        assertTrue(next.observed(true))
        assertTrue(owner.isEligible(incoming))
        assertFalse(owner.isEligible(outgoing))
    }

    @Test fun controlRequestsBelongOnlyToTheActivePageAndWindow() {
        val owner = FocusCoordinator<CcFocusAddress, CcPage> { it.page }
        val memory = CcFocusMemory()
        val requests = mutableListOf<CcFocusAddress>()
        val about = CcFocusAddress(CcPage.Main, CcControl.About)
        val back = CcFocusAddress(CcPage.About, CcControl.Back)
        listOf(about, back).forEach { address ->
            owner.bind(address) { requests += address; true }.apply { attach(); placed() }
        }
        CcFocusSurfaceState(owner, memory, CcPage.Main, active = false).request(CcPage.Main, CcControl.About)
        assertTrue(requests.isEmpty())
        val active = CcFocusSurfaceState(owner, memory, CcPage.Main, active = true)
        active.request(CcPage.About, CcControl.Back)
        assertTrue(requests.isEmpty())
        active.request(CcPage.Main, CcControl.About)
        assertEquals(listOf(about), requests)
    }

    @Test fun inactiveSurfaceKeepsLatePlacementsPendingUntilReactivated() {
        val owner = FocusCoordinator<CcFocusAddress, CcPage> { it.page }
        val target = CcFocusAddress(CcPage.Main, CcControl.About)
        var requests = 0
        owner.request(target)
        val binding = owner.bind(target) { requests++; true }
        binding.attach()
        owner.request(null)
        binding.placed()
        assertEquals(0, requests)
        assertFalse(binding.canFocus)
        owner.request(target)
        assertEquals(1, requests)
        assertTrue(binding.observed(true))
    }
}
