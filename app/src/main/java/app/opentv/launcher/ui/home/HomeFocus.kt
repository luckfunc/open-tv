package app.opentv.launcher.ui.home

import java.io.Serializable

internal sealed interface HomeFocusTarget {
    data object Status : HomeFocusTarget
    data class App(val key: String) : HomeFocusTarget
}

internal sealed interface HomeFocusMemory : Serializable {
    val target: HomeFocusTarget?
    val app: Tile?
    data object AwaitingApps : HomeFocusMemory {
        override val target get() = null
        override val app get() = null
    }
    data class Tile(val key: String, val index: Int) : HomeFocusMemory {
        override val target get() = HomeFocusTarget.App(key)
        override val app get() = this
    }
    data class Status(val returnTo: Tile?) : HomeFocusMemory {
        override val target get() = HomeFocusTarget.Status
        override val app get() = returnTo
    }

    fun reconcile(keys: List<String>): HomeFocusMemory = when (this) {
        AwaitingApps -> first(keys)
        is Tile -> nearest(keys) ?: Status(null)
        is Status -> copy(returnTo = returnTo?.nearest(keys))
    }

    fun observed(target: HomeFocusTarget, keys: List<String>): HomeFocusMemory = when (target) {
        HomeFocusTarget.Status -> Status(app?.nearest(keys))
        is HomeFocusTarget.App -> keys.indexOf(target.key).takeIf { it >= 0 }?.let { Tile(target.key, it) } ?: this
    }

    companion object {
        fun first(keys: List<String>): HomeFocusMemory = keys.firstOrNull()?.let { Tile(it, 0) } ?: Status(null)
    }
}

private fun HomeFocusMemory.Tile.nearest(keys: List<String>): HomeFocusMemory.Tile? {
    val current = keys.indexOf(key)
    val resolved = if (current >= 0) current else index.coerceIn(0, (keys.size - 1).coerceAtLeast(0))
    return keys.getOrNull(resolved)?.let { HomeFocusMemory.Tile(it, resolved) }
}
