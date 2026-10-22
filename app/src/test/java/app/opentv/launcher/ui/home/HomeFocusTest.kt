package app.opentv.launcher.ui.home

import org.junit.Assert.*
import org.junit.Test

class HomeFocusTest {
    @Test fun firstFocusBelongsToTheFirstAppRatherThanTheTopmostStatusControl() {
        assertEquals(HomeFocusTarget.App("first"), HomeFocusMemory.AwaitingApps.reconcile(listOf("first", "second")).target)
    }
    @Test fun anEmptyCatalogCanStillFocusStatus() {
        assertEquals(HomeFocusTarget.Status, HomeFocusMemory.first(emptyList()).target)
    }
    @Test fun aRemovedAppRestoresItsSurvivingNeighbour() {
        assertEquals(HomeFocusMemory.Tile("last", 1), HomeFocusMemory.Tile("removed", 1).reconcile(listOf("first", "last")))
    }
    @Test fun aMovedAppKeepsIdentityAndUpdatesItsIndex() {
        assertEquals(HomeFocusMemory.Tile("moving", 2), HomeFocusMemory.Tile("moving", 0).reconcile(listOf("a", "b", "moving")))
    }
    @Test fun statusKeepsItsReturnAppAcrossCatalogChanges() {
        val status = HomeFocusMemory.Status(HomeFocusMemory.Tile("removed", 1)).reconcile(listOf("first", "next"))
        assertEquals(HomeFocusTarget.Status, status.target)
        assertEquals(HomeFocusMemory.Tile("next", 1), status.app)
    }
    @Test fun enteringStatusAndReturningDoesNotForgetTheAppColumn() {
        val start = HomeFocusMemory.Tile("third", 2)
        val status = start.observed(HomeFocusTarget.Status, listOf("first", "second", "third"))
        assertEquals(start, status.app)
    }
    @Test fun staleAppFocusCannotOverwriteANewerCatalog() {
        val start = HomeFocusMemory.Tile("current", 0)
        assertEquals(start, start.observed(HomeFocusTarget.App("removed"), listOf("current")))
    }
    @Test fun homeResetIsExplicitlyTheFirstAppEvenWhenStatusWasFocused() {
        assertEquals(HomeFocusTarget.App("first"), HomeFocusMemory.first(listOf("first", "second")).target)
    }
}
