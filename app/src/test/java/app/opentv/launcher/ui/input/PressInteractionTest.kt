package app.opentv.launcher.ui.input

import org.junit.Assert.*
import org.junit.Test

class PressInteractionTest {
    private val select = PressToken.key(23, 100L)

    @Test fun aShortPressActivatesExactlyOnce() {
        val press = PressInteraction()
        press.begin(select)
        assertTrue(press.pressed)
        assertEquals(PressAction.Activate, press.end(select))
        assertEquals(PressAction.None, press.end(select))
        assertFalse(press.pressed)
    }
    @Test fun aReleaseCannotActivateAControlThatDidNotOwnTheBeginning() {
        assertEquals(PressAction.None, PressInteraction().end(select))
    }
    @Test fun aMenuOrMoveOpenedByLongPressCannotActivateOnTheOpeningRelease() {
        val oldControl = PressInteraction()
        oldControl.begin(select)
        assertEquals(PressAction.LongActivate, oldControl.longPress(select))
        assertEquals(PressAction.None, oldControl.end(select))
        val destination = PressInteraction()
        assertEquals(PressAction.None, destination.end(select))
    }
    @Test fun timerAndNativeRepeatCannotFireTwoLongActions() {
        val press = PressInteraction()
        press.begin(select)
        assertEquals(PressAction.LongActivate, press.longPress(select))
        repeat(5) { assertEquals(PressAction.None, press.begin(select, repeat = true, longEnabled = true)) }
    }
    @Test fun orphanRepeatsNeverStartAPress() {
        val press = PressInteraction()
        assertEquals(PressAction.None, press.begin(select, repeat = true, longEnabled = true))
        assertEquals(PressAction.None, press.end(select))
    }
    @Test fun pointerCannotEndOrReplaceAnActiveRemotePress() {
        val press = PressInteraction()
        press.begin(select)
        press.begin(PressToken.Pointer)
        assertEquals(PressAction.None, press.end(PressToken.Pointer))
        assertTrue(press.pressed)
        assertEquals(PressAction.Activate, press.end(select))
    }
    @Test fun aDifferentKeyCannotReleaseTheOwnedKey() {
        val press = PressInteraction()
        press.begin(select)
        assertEquals(PressAction.None, press.end(PressToken.key(66, 100L)))
        assertEquals(PressAction.Activate, press.end(select))
    }
    @Test fun cancellationCoversDragWindowLossAndDisabledControls() {
        val press = PressInteraction()
        press.begin(select); press.cancel()
        assertEquals(PressAction.None, press.longPress(select))
        assertEquals(PressAction.None, press.end(select))
        press.begin(PressToken.Pointer)
        assertEquals(PressAction.None, press.end(PressToken.Pointer, canceled = true))
    }
    @Test fun duplicateDownsDoNotRearmALongPress() {
        val press = PressInteraction()
        press.begin(select); press.longPress(select)
        press.begin(select)
        assertEquals(PressAction.None, press.longPress(select))
        assertEquals(PressAction.None, press.end(select))
    }
    @Test fun aNewPhysicalPressRecoversFromALostKeyUp() {
        val press = PressInteraction()
        val next = PressToken.key(23, 200L)
        press.begin(select); press.longPress(select)
        press.begin(next)
        assertEquals(PressAction.None, press.end(select))
        assertEquals(PressAction.Activate, press.end(next))
    }
    @Test fun aConsumedLongPressCanHandOffToANewPointerGesture() {
        val press = PressInteraction()
        press.begin(select); press.longPress(select)
        press.begin(PressToken.Pointer)
        assertEquals(PressAction.None, press.end(select))
        assertEquals(PressAction.Activate, press.end(PressToken.Pointer))
    }
    @Test fun aNewMenuPressCanFollowALongPressWhoseReleaseWentToAModal() {
        val press = PressInteraction()
        val menu = PressToken.key(82, 200L)
        press.begin(select); press.longPress(select)
        press.begin(menu)
        assertEquals(PressAction.Activate, press.end(menu))
    }
}
