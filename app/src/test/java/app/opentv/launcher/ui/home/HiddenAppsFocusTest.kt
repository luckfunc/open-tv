package app.opentv.launcher.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class HiddenAppsFocusTest {
    @Test fun firstEntryStartsOnAnAppWhenAvailable() {
        assertEquals(HiddenAppsTarget.App("first"), HiddenAppsFocus.Initial.reconcile(listOf("first")).target)
    }
    @Test fun anEmptyListUsesItsBackControl() {
        assertEquals(HiddenAppsTarget.Back, HiddenAppsFocus.Initial.reconcile(emptyList()).target)
    }
    @Test fun unhidingAnAppSelectsItsNearestSurvivingNeighbour() {
        assertEquals(HiddenAppsTarget.App("next"), HiddenAppsFocus.App("removed", 1).reconcile(listOf("first", "next")).target)
    }
    @Test fun listUpdatesDoNotStealFocusFromBack() {
        assertEquals(HiddenAppsFocus.Back, HiddenAppsFocus.Back.reconcile(listOf("first")))
    }
    @Test fun staleAppEventsCannotRestoreAnUnhiddenItem() {
        val focus = HiddenAppsFocus.App("current", 0)
        assertEquals(focus, focus.observed(HiddenAppsTarget.App("removed"), listOf("current")))
    }
}
