package app.opentv.launcher.ui.wallpaper

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.*
import org.junit.Test

class SettingsNavigationTest {
    private val first = SettingsFocus.Wallpaper(WallpaperGroup.Daily, "daily:first")
    private val photo = SettingsFocus.Wallpaper(WallpaperGroup.Photos, "photo:mine")
    private val inventory = SettingsInventory(SettingsCategory.entries, listOf(first, photo), mapOf(
        SettingsSetting.Filter to listOf(false, true).map(SettingsSelection::Filter),
    ))

    @Test fun sidebarBrowsingDoesNotActivateAnotherCategory() {
        val state = SettingsNavigation(SettingsRoute.Sidebar(SettingsCategory.Launcher))
            .observed(SettingsFocus.Category(SettingsCategory.Phone))
        assertEquals(SettingsCategory.Launcher, state.route.category)
        assertEquals(SettingsFocus.Category(SettingsCategory.Phone), state.focus)
    }

    @Test fun activatingACategoryEntersItsDeclaredFirstControl() {
        val state = SettingsNavigation().enter(SettingsCategory.Launcher, inventory)
        assertEquals(SettingsFocus.Setting(SettingsSetting.Filter), state.focus)
        assertEquals(SettingsFocusScope.Details(SettingsCategory.Launcher), state.focus.scope)
    }

    @Test fun choicesSelectTheCurrentValueByIdentity() {
        val state = SettingsNavigation().enter(SettingsCategory.Launcher, inventory)
            .openChoices(SettingsSelection.Filter(true), inventory)
        assertEquals(SettingsFocus.Option(SettingsSelection.Filter(true)), state.focus)
    }

    @Test fun selectAndCancelBothReturnToTheExactSettingThatOpenedChoices() {
        val state = SettingsNavigation().enter(SettingsCategory.Launcher, inventory)
            .openChoices(SettingsSelection.Filter(false), inventory)
        val expected = SettingsFocus.Setting(SettingsSetting.Filter)
        assertEquals(expected, state.back()?.focus)
        assertEquals(expected, state.select(SettingsSelection.Filter(true), inventory)?.focus)
    }

    @Test fun backReturnsFromDetailsToItsOwnCategoryThenDismisses() {
        val sidebar = SettingsNavigation().enter(SettingsCategory.Launcher, inventory).back()!!
        assertEquals(SettingsFocus.Category(SettingsCategory.Launcher), sidebar.focus)
        assertNull(sidebar.back())
    }

    @Test fun anOldFocusObservationCannotReplaceANewerChoiceDestination() {
        val state = SettingsNavigation().enter(SettingsCategory.Launcher, inventory)
            .openChoices(SettingsSelection.Filter(false), inventory)
        assertEquals(state, state.observed(SettingsFocus.Category(SettingsCategory.Wallpaper)))
        assertEquals(state, state.observed(SettingsFocus.Setting(SettingsSetting.Filter)))
    }

    @Test fun illegalSelectionsCannotApplyOrOpenAnotherSettingsChoices() {
        val state = SettingsNavigation().enter(SettingsCategory.Launcher, inventory)
            .openChoices(SettingsSelection.Filter(false), inventory)
        val reduced = inventory.copy(options = mapOf(SettingsSetting.Filter to listOf(SettingsSelection.Filter(false))))
        assertNull(state.select(SettingsSelection.Filter(true), reduced))
        assertNull(SettingsNavigation().select(SettingsSelection.Filter(true), inventory))
        assertEquals(SettingsNavigation(), SettingsNavigation().openChoices(SettingsSelection.Filter(true), inventory))
    }

    @Test fun savedRoutesContainOnlyStringsAndRestoreEveryFocusKind() {
        listOf(
            SettingsNavigation(SettingsRoute.Sidebar(SettingsCategory.Launcher, SettingsCategory.Phone)),
            SettingsNavigation().enter(SettingsCategory.Launcher, inventory),
            SettingsNavigation(SettingsRoute.Detail(SettingsFocus.Action(SettingsAction.Setup))),
            SettingsNavigation(SettingsRoute.Detail(photo)),
            SettingsNavigation().enter(SettingsCategory.Launcher, inventory).openChoices(SettingsSelection.Filter(true), inventory),
        ).forEach { state ->
            val saved = with(SettingsNavigationSaver) { SaverScope { it is String }.save(state) }!!
            assertTrue((saved as List<*>).all { it is String })
            assertEquals(state, SettingsNavigationSaver.restore(saved))
        }
    }

    @Test fun unknownOrIncompleteSavedNamesFallBackWithoutLegacyEnumValues() {
        listOf(
            emptyList(), listOf("sidebar", "Home"), listOf("setting", "Pin"),
            listOf("action", "DeviceLocation"), listOf("choices", "Focus", "Standard"),
            listOf("sidebar", "Screensaver", "Screensaver"), listOf("setting", "Screensaver"),
            listOf("setting", "Delay"), listOf("action", "StartScreensaver"),
            listOf("choices", "Screensaver", "Photos"), listOf("choices", "Delay", "5"),
            listOf("choices", "Filter", "yes"), listOf("wallpaper", "unknown", "photo:mine"),
            listOf("wallpaper", "Photos"), listOf("unknown"),
        ).forEach { saved -> assertEquals(SettingsNavigation(), SettingsNavigationSaver.restore(saved)) }
        assertEquals(SettingsNavigation(SettingsRoute.Sidebar(SettingsCategory.Phone)),
            SettingsNavigationSaver.restore(listOf("sidebar", "Phone", "unknown")))
    }

    @Test fun noScreensaverEntryRemainsInTheSettingsInventory() {
        assertEquals(listOf(SettingsCategory.Wallpaper, SettingsCategory.Launcher, SettingsCategory.Phone), inventory.categories)
        assertEquals(listOf(SettingsSetting.Filter), SettingsSetting.entries)
        assertEquals(listOf(SettingsAction.Categories, SettingsAction.Setup), SettingsAction.entries)
    }

    @Test fun aVirtualizedWallpaperIsNotMistakenForADeletedOne() {
        val state = SettingsNavigation(SettingsRoute.Detail(photo))
        assertEquals(state, state.reconcile(inventory))
    }

    @Test fun deletedWallpapersFallBackByGroupThenInventoryWithoutChangingSelection() {
        val sameGroup = SettingsFocus.Wallpaper(WallpaperGroup.Photos, "photo:other")
        val state = SettingsNavigation(SettingsRoute.Detail(photo))
        assertEquals(sameGroup, state.reconcile(inventory.copy(wallpapers = listOf(first, sameGroup))).focus)
        assertEquals(first, state.reconcile(inventory.copy(wallpapers = listOf(first))).focus)
        assertEquals(SettingsFocus.Category(SettingsCategory.Wallpaper), state.reconcile(inventory.copy(wallpapers = emptyList())).focus)
    }

    @Test fun unsupportedRestoredCategoriesNormalizeToAnAvailableOne() {
        val state = SettingsNavigation(SettingsRoute.Detail(SettingsFocus.Setting(SettingsSetting.Filter)))
        val available = inventory.copy(categories = SettingsCategory.entries.filter { it != SettingsCategory.Launcher })
        assertEquals(SettingsFocus.Category(SettingsCategory.Wallpaper), state.reconcile(available).focus)
        assertEquals(state, state.enter(SettingsCategory.Launcher, available))
    }

    @Test fun removedOptionsNormalizeToTheFirstAvailableValue() {
        val state = SettingsNavigation().enter(SettingsCategory.Launcher, inventory)
            .openChoices(SettingsSelection.Filter(true), inventory)
        val reduced = inventory.copy(options = mapOf(SettingsSetting.Filter to listOf(SettingsSelection.Filter(false))))
        assertEquals(SettingsFocus.Option(SettingsSelection.Filter(false)), state.reconcile(reduced).focus)
    }
}
