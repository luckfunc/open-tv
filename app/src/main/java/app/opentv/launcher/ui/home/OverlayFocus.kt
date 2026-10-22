package app.opentv.launcher.ui.home

internal enum class AppMenuAction { Move, Unpin, Hide, ResetIcon, Info, Uninstall }
internal data class AppMenuTarget(val appKey: String, val action: AppMenuAction)

internal sealed interface HiddenAppsTarget {
    data object Back : HiddenAppsTarget
    data class App(val key: String) : HiddenAppsTarget
}

internal sealed interface HiddenAppsFocus {
    val target: HiddenAppsTarget?
    data object Initial : HiddenAppsFocus { override val target get() = null }
    data object Back : HiddenAppsFocus { override val target get() = HiddenAppsTarget.Back }
    data class App(val key: String, val index: Int) : HiddenAppsFocus { override val target get() = HiddenAppsTarget.App(key) }

    fun reconcile(keys: List<String>): HiddenAppsFocus = when (this) {
        Initial -> keys.firstOrNull()?.let { App(it, 0) } ?: Back
        Back -> Back
        is App -> {
            val current = keys.indexOf(key)
            val next = if (current >= 0) current else index.coerceIn(0, (keys.size - 1).coerceAtLeast(0))
            keys.getOrNull(next)?.let { App(it, next) } ?: Back
        }
    }

    fun observed(target: HiddenAppsTarget, keys: List<String>): HiddenAppsFocus = when (target) {
        HiddenAppsTarget.Back -> Back
        is HiddenAppsTarget.App -> keys.indexOf(target.key).takeIf { it >= 0 }?.let { App(target.key, it) } ?: this
    }
}
