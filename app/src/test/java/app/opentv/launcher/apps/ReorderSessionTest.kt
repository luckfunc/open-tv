package app.opentv.launcher.apps

import org.junit.Assert.*
import org.junit.Test

class ReorderSessionTest {
    @Test fun previewsNeverMutateTheOriginalArrangement() {
        val original = listOf("a", "b", "c", "d")
        val session = ReorderSession("b", original)
        session.moveTo(3)
        assertEquals(listOf("a", "c", "d", "b"), session.keys)
        assertEquals(listOf("a", "b", "c", "d"), original)
        // Cancel simply discards the session: there is no persistence operation to undo.
    }
    @Test fun repeatedMovesRetainOneStableMovingIdentity() {
        val session = ReorderSession("b", listOf("a", "b", "c", "d"))
        session.moveTo(3); session.moveTo(0)
        assertEquals(listOf("b", "a", "c", "d"), session.keys)
        session.moveTo(-1); session.moveTo(20)
        assertEquals(listOf("b", "a", "c", "d"), session.keys)
    }
    @Test fun removalAndArrivalDoNotLoseUnrelatedApps() {
        val session = ReorderSession("b", listOf("a", "b", "c"))
        session.moveTo(0)
        assertEquals(listOf("b", "c", "d"), session.reconcile(listOf("b", "c", "d")))
    }
    @Test fun confirmingPreservesHiddenAndTemporarilyMissingKeys() {
        assertEquals(listOf("c", "hidden", "a", "missing", "b"),
            mergeVisibleOrder(listOf("a", "hidden", "b", "missing", "c"), listOf("c", "a", "b")))
    }
    @Test fun newVisibleKeysAreNotDroppedOnCommit() {
        val result = mergeVisibleOrder(listOf("a", "hidden", "b"), listOf("d", "b", "a"))
        assertEquals(listOf("d", "hidden", "b", "a"), result)
        assertEquals(result.size, result.toSet().size)
    }
}
