package app.opentv.launcher.ui.focus

import org.junit.Assert.*
import org.junit.Test

class FocusCoordinatorTest {
    private enum class Group { Sidebar, Detail, Choices }
    private enum class Target(val group: Group) {
        Wallpaper(Group.Sidebar), Home(Group.Sidebar), Layout(Group.Detail), Names(Group.Detail),
        Off(Group.Choices), On(Group.Choices),
    }
    private val owner = FocusCoordinator<Target, Group> { it.group }
    private val requests = mutableListOf<Target>()
    private fun ready(target: Target, accepted: Boolean = true, onRequest: () -> Unit = {}) =
        owner.bind(target) { requests += target; onRequest(); accepted }.apply { attach(); placed() }

    @Test fun aRequestedTargetWaitsForBothAttachmentAndPlacement() {
        owner.request(Target.Layout)
        val row = owner.bind(Target.Layout) { requests += Target.Layout; true }
        row.attach()
        assertTrue(requests.isEmpty())
        row.placed()
        assertEquals(listOf(Target.Layout), requests)
    }

    @Test fun placementBeforeRegistrationDoesNotLoseTheRequest() {
        owner.request(Target.Layout)
        val row = owner.bind(Target.Layout) { requests += Target.Layout; true }
        row.placed()
        assertTrue(requests.isEmpty())
        row.attach()
        assertEquals(listOf(Target.Layout), requests)
    }

    @Test fun choiceHandoffNeverOffersTheSidebarAsFallback() {
        val sidebar = ready(Target.Wallpaper)
        val origin = ready(Target.Layout)
        owner.request(Target.Layout); origin.observed(true)
        owner.request(Target.On)
        assertFalse(sidebar.canFocus)
        assertFalse(origin.canFocus)
        assertFalse(owner.isEligible(Target.Off))
        val option = ready(Target.On)
        assertTrue(option.canFocus)
        assertFalse(owner.isEligible(Target.Off))
        assertTrue(option.observed(true))
        assertTrue(owner.isEligible(Target.Off))
        assertFalse(sidebar.canFocus)
    }

    @Test fun aTrueRequestReturnDoesNotPretendFocusWasObserved() {
        val row = ready(Target.Layout)
        owner.request(Target.Layout)
        assertFalse(owner.isEligible(Target.Names))
        row.observed(true)
        assertTrue(owner.isEligible(Target.Names))
    }

    @Test fun oldDisposalCannotEraseTheReplacementForTheSameAddress() {
        val old = ready(Target.Layout)
        owner.request(Target.Layout); old.observed(true)
        val replacement = ready(Target.Layout)
        old.detach()
        requests.clear()
        owner.request(Target.Layout)
        assertEquals(listOf(Target.Layout), requests)
        assertFalse(old.canFocus)
        assertTrue(replacement.canFocus)
    }

    @Test fun oldPlacementAndFocusCannotReviveAnObsoleteBinding() {
        owner.request(Target.Layout)
        val old = owner.bind(Target.Layout) { fail("stale binding requested focus"); false }
        old.attach()
        val current = ready(Target.Layout)
        old.placed()
        assertFalse(old.observed(true))
        assertTrue(current.observed(true))
        assertTrue(current.canFocus)
    }

    @Test fun aLaterDestinationWinsOverLateAttachmentOfTheEarlierOne() {
        owner.request(Target.Layout)
        owner.request(Target.On)
        ready(Target.Layout)
        ready(Target.On)
        assertEquals(listOf(Target.On), requests)
    }

    @Test fun synchronousCallbacksCannotRecursivelyDeliverOrClearANewerDestination() {
        var depth = 0
        var maximumDepth = 0
        lateinit var old: FocusCoordinator<Target, Group>.Binding
        lateinit var next: FocusCoordinator<Target, Group>.Binding
        old = ready(Target.Layout) {
            depth++; maximumDepth = maxOf(maximumDepth, depth)
            owner.request(Target.On)
            assertFalse(old.observed(true))
            depth--
        }
        next = ready(Target.On) {
            depth++; maximumDepth = maxOf(maximumDepth, depth)
            next.observed(true)
            depth--
        }
        owner.request(Target.Layout)
        assertEquals(listOf(Target.Layout, Target.On), requests)
        assertEquals(1, maximumDepth)
        assertTrue(owner.isEligible(Target.Off))
        assertFalse(owner.isEligible(Target.Layout))
    }

    @Test fun deactivationCancelsPendingDeliveryAndLateAcquisition() {
        owner.request(Target.On)
        owner.request(null)
        val option = ready(Target.On)
        assertTrue(requests.isEmpty())
        assertFalse(option.observed(true))
        assertFalse(option.canFocus)
    }

    @Test fun rejectedRequestsRemainPendingWithoutAnAutomaticRetryLoop() {
        val row = ready(Target.Layout, accepted = false)
        owner.request(Target.Layout)
        repeat(3) { row.placed() }
        assertEquals(listOf(Target.Layout), requests)
        assertFalse(owner.isEligible(Target.Names))
        owner.request(Target.Layout)
        assertEquals(listOf(Target.Layout, Target.Layout), requests)
    }

    @Test fun disposalOfTheFocusedNodeDoesNotOpenAnUnrelatedFallback() {
        val row = ready(Target.Layout)
        owner.request(Target.Layout); row.observed(true)
        row.detach()
        assertFalse(owner.isEligible(Target.Wallpaper))
        assertFalse(owner.isEligible(Target.Names))
        val replacement = ready(Target.Layout)
        assertTrue(replacement.canFocus)
        assertTrue(replacement.observed(true))
    }

    @Test fun anAcquiredTargetDoesNotReRequestOnUnrelatedCompositionUpdates() {
        val row = ready(Target.Layout)
        owner.request(Target.Layout); row.observed(true)
        repeat(4) { owner.request(Target.Layout) }
        assertEquals(listOf(Target.Layout), requests)
    }
}
