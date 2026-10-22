package app.opentv.launcher.ui.wallpaper

import org.junit.Assert.*
import org.junit.Test

class SetupNavigationTest {
    private val oldSdkInventory = SettingsInventory(SettingsCategory.entries, emptyList(), emptyMap())

    @Test fun launcherSetupIsFocusableWithoutKeyFilterOnOldSdk() {
        val navigation = SettingsNavigation().enter(SettingsCategory.Launcher, oldSdkInventory)
        assertEquals(SettingsFocus.Action(SettingsAction.Setup), navigation.focus)
        assertEquals(SettingsCategory.Launcher, navigation.route.category)
    }

    @Test fun restoredUnsupportedFilterDetailsAndChoicesReturnToSetup() {
        val details = SettingsNavigation(SettingsRoute.Detail(SettingsFocus.Setting(SettingsSetting.Filter)))
        val choices = SettingsNavigation(SettingsRoute.Choices(SettingsSelection.Filter(true)))
        assertEquals(SettingsFocus.Action(SettingsAction.Setup), details.reconcile(oldSdkInventory).focus)
        assertEquals(SettingsFocus.Action(SettingsAction.Setup), choices.reconcile(oldSdkInventory).focus)
    }
}
