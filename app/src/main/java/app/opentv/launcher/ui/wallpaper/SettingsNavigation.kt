package app.opentv.launcher.ui.wallpaper

import androidx.compose.runtime.saveable.listSaver

internal enum class SettingsCategory { Wallpaper, Launcher, Phone }
internal enum class WallpaperGroup { Daily, Photos, Builtin }

internal enum class SettingsSetting(val category: SettingsCategory) {
    Filter(SettingsCategory.Launcher),
}

internal sealed interface SettingsSelection {
    val setting: SettingsSetting
    data class Filter(val enabled: Boolean) : SettingsSelection { override val setting get() = SettingsSetting.Filter }
}

internal enum class SettingsAction(val category: SettingsCategory) {
    Categories(SettingsCategory.Phone), Setup(SettingsCategory.Launcher),
}

internal sealed interface SettingsFocusScope {
    data object Sidebar : SettingsFocusScope
    data class Details(val category: SettingsCategory) : SettingsFocusScope
    data class Choices(val setting: SettingsSetting) : SettingsFocusScope
}

internal sealed interface SettingsFocus {
    val scope: SettingsFocusScope
    data class Category(val category: SettingsCategory) : SettingsFocus { override val scope get() = SettingsFocusScope.Sidebar }
    sealed interface Detail : SettingsFocus {
        val category: SettingsCategory
        override val scope get() = SettingsFocusScope.Details(category)
    }
    data class Setting(val setting: SettingsSetting) : Detail { override val category get() = setting.category }
    data class Action(val action: SettingsAction) : Detail { override val category get() = action.category }
    data class Wallpaper(val group: WallpaperGroup, val id: String) : Detail { override val category get() = SettingsCategory.Wallpaper }
    data class Option(val selection: SettingsSelection) : SettingsFocus { override val scope get() = SettingsFocusScope.Choices(selection.setting) }
}

internal sealed interface SettingsRoute {
    val category: SettingsCategory
    val focus: SettingsFocus
    data class Sidebar(
        override val category: SettingsCategory = SettingsCategory.Wallpaper,
        val focused: SettingsCategory = category,
    ) : SettingsRoute { override val focus get() = SettingsFocus.Category(focused) }
    data class Detail(override val focus: SettingsFocus.Detail) : SettingsRoute { override val category get() = focus.category }
    data class Choices(val option: SettingsSelection) : SettingsRoute {
        override val category get() = option.setting.category
        override val focus get() = SettingsFocus.Option(option)
    }
}

internal data class SettingsInventory(
    val categories: List<SettingsCategory>,
    val wallpapers: List<SettingsFocus.Wallpaper>,
    val options: Map<SettingsSetting, List<SettingsSelection>>,
) {
    init { require(categories.isNotEmpty()) }

    fun firstDetail(category: SettingsCategory): SettingsFocus.Detail? = when (category) {
        SettingsCategory.Wallpaper -> wallpapers.firstOrNull()
        SettingsCategory.Launcher -> if (options[SettingsSetting.Filter].orEmpty().isNotEmpty()) SettingsFocus.Setting(SettingsSetting.Filter)
            else SettingsFocus.Action(SettingsAction.Setup)
        SettingsCategory.Phone -> SettingsFocus.Action(SettingsAction.Categories)
    }
}

/** Navigation contains identities and values, never native focus nodes or deferred UI callbacks. */
internal data class SettingsNavigation(val route: SettingsRoute = SettingsRoute.Sidebar()) {
    val focus get() = route.focus

    fun enter(category: SettingsCategory, inventory: SettingsInventory): SettingsNavigation {
        if (category !in inventory.categories) return this
        return inventory.firstDetail(category)?.let { copy(route = SettingsRoute.Detail(it)) }
            ?: copy(route = SettingsRoute.Sidebar(category))
    }

    fun openChoices(selection: SettingsSelection, inventory: SettingsInventory): SettingsNavigation {
        if (selection.setting.category != route.category) return this
        val options = inventory.options[selection.setting].orEmpty()
        val option = options.firstOrNull { it == selection } ?: options.firstOrNull() ?: return this
        return copy(route = SettingsRoute.Choices(option))
    }

    fun select(selection: SettingsSelection, inventory: SettingsInventory): SettingsNavigation? {
        val page = route as? SettingsRoute.Choices ?: return null
        if (selection.setting != page.option.setting || selection !in inventory.options[selection.setting].orEmpty()) return null
        return copy(route = SettingsRoute.Detail(SettingsFocus.Setting(selection.setting)))
    }

    fun back(): SettingsNavigation? = when (val page = route) {
        is SettingsRoute.Sidebar -> null
        is SettingsRoute.Detail -> copy(route = SettingsRoute.Sidebar(page.category))
        is SettingsRoute.Choices -> copy(route = SettingsRoute.Detail(SettingsFocus.Setting(page.option.setting)))
    }

    fun observed(target: SettingsFocus): SettingsNavigation = when (val page = route) {
        is SettingsRoute.Sidebar -> if (target is SettingsFocus.Category) copy(route = page.copy(focused = target.category)) else this
        is SettingsRoute.Detail -> if (target is SettingsFocus.Detail && target.category == page.category) copy(route = SettingsRoute.Detail(target)) else this
        is SettingsRoute.Choices -> if (target is SettingsFocus.Option && target.selection.setting == page.option.setting) copy(route = SettingsRoute.Choices(target.selection)) else this
    }

    fun reconcile(inventory: SettingsInventory): SettingsNavigation {
        if (route.category !in inventory.categories) return copy(route = SettingsRoute.Sidebar(inventory.categories.first()))
        return when (val page = route) {
            is SettingsRoute.Sidebar -> if (page.focused in inventory.categories) this else copy(route = page.copy(focused = page.category))
            is SettingsRoute.Choices -> {
                val options = inventory.options[page.option.setting].orEmpty()
                if (page.option in options) this else options.firstOrNull()?.let { copy(route = SettingsRoute.Choices(it)) }
                    ?: copy(route = inventory.firstDetail(page.category)?.let(SettingsRoute::Detail) ?: SettingsRoute.Sidebar(page.category))
            }
            is SettingsRoute.Detail -> {
                val target = page.focus
                if (target is SettingsFocus.Setting && inventory.options[target.setting].orEmpty().isEmpty()) {
                    copy(route = inventory.firstDetail(page.category)?.let(SettingsRoute::Detail) ?: SettingsRoute.Sidebar(page.category))
                } else if (target !is SettingsFocus.Wallpaper || target in inventory.wallpapers) this else {
                    val fallback = inventory.wallpapers.firstOrNull { it.group == target.group } ?: inventory.wallpapers.firstOrNull()
                    copy(route = fallback?.let(SettingsRoute::Detail) ?: SettingsRoute.Sidebar(SettingsCategory.Wallpaper))
                }
            }
        }
    }
}

internal val SettingsNavigationSaver = listSaver<SettingsNavigation, String>(
    save = { navigation ->
        when (val route = navigation.route) {
            is SettingsRoute.Sidebar -> listOf("sidebar", route.category.name, route.focused.name)
            is SettingsRoute.Detail -> when (val focus = route.focus) {
                is SettingsFocus.Setting -> listOf("setting", focus.setting.name)
                is SettingsFocus.Action -> listOf("action", focus.action.name)
                is SettingsFocus.Wallpaper -> listOf("wallpaper", focus.group.name, focus.id)
            }
            is SettingsRoute.Choices -> listOf("choices", route.option.setting.name, when (val option = route.option) {
                is SettingsSelection.Filter -> option.enabled.toString()
            })
        }
    },
    restore = { values ->
        val name = values.getOrNull(1)
        val value = values.getOrNull(2)
        val route = when (values.firstOrNull()) {
            "sidebar" -> SettingsCategory.entries.firstOrNull { it.name == name }?.let { category ->
                SettingsRoute.Sidebar(category, SettingsCategory.entries.firstOrNull { it.name == value } ?: category)
            }
            "setting" -> SettingsSetting.entries.firstOrNull { it.name == name }?.let { SettingsRoute.Detail(SettingsFocus.Setting(it)) }
            "action" -> SettingsAction.entries.firstOrNull { it.name == name }?.let { SettingsRoute.Detail(SettingsFocus.Action(it)) }
            "wallpaper" -> WallpaperGroup.entries.firstOrNull { it.name == name }?.let { group ->
                value?.let { SettingsRoute.Detail(SettingsFocus.Wallpaper(group, it)) }
            }
            "choices" -> when (SettingsSetting.entries.firstOrNull { it.name == name }) {
                SettingsSetting.Filter -> value?.toBooleanStrictOrNull()?.let(SettingsSelection::Filter)
                null -> null
            }?.let(SettingsRoute::Choices)
            else -> null
        }
        SettingsNavigation(route ?: SettingsRoute.Sidebar())
    },
)
