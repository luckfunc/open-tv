package app.opentv.launcher.ui.input

enum class PressSource { Key, Pointer }
data class PressToken(val source: PressSource, val code: Int = 0, val sequence: Long = 0L) {
    companion object {
        val Pointer = PressToken(PressSource.Pointer)
        fun key(code: Int, downTime: Long = 0L) = PressToken(PressSource.Key, code, downTime)
    }
}

enum class PressAction { None, Activate, LongActivate }

/**
 * One control owns a press from its beginning to its end. A different key/pointer cannot release
 * it, a held press can activate long at most once, and cancellation never activates anything.
 * Timing and Android event translation belong to the caller; these rules have no UI dependency.
 */
class PressInteraction {
    private var owner: PressToken? = null
    private var longActivated = false
    val pressed: Boolean get() = owner != null && !longActivated

    fun begin(token: PressToken, repeat: Boolean = false, longEnabled: Boolean = false): PressAction {
        if (repeat) return if (longEnabled) longPress(token) else PressAction.None
        if (owner == token) return PressAction.None
        val previous = owner
        // A new Android downTime is a new physical press, even if a previous key-up was lost.
        if (previous != null && !longActivated && !(previous.source == PressSource.Key && token.source == PressSource.Key &&
                previous.code == token.code && previous.sequence != token.sequence)) return PressAction.None
        owner = token
        longActivated = false
        return PressAction.None
    }

    fun longPress(token: PressToken): PressAction {
        if (owner != token || longActivated) return PressAction.None
        longActivated = true
        return PressAction.LongActivate
    }

    fun end(token: PressToken, canceled: Boolean = false): PressAction {
        if (owner != token) return PressAction.None
        val activate = !longActivated && !canceled
        cancel()
        return if (activate) PressAction.Activate else PressAction.None
    }

    fun owns(token: PressToken): Boolean = owner == token
    fun cancel() { owner = null; longActivated = false }
}
