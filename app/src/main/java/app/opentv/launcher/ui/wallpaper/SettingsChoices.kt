package app.opentv.launcher.ui.wallpaper

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.opentv.launcher.R
import app.opentv.launcher.settings.OpenTvSettings

internal val SettingsCategory.label: Int get() = when (this) {
    SettingsCategory.Wallpaper -> R.string.wp_wallpaper
    SettingsCategory.Launcher -> R.string.redesign_launcher
    SettingsCategory.Phone -> R.string.redesign_phone
}

internal object SettingsChoices {
    fun options(setting: SettingsSetting): List<SettingsSelection> = when (setting) {
        SettingsSetting.Filter -> listOf(false, true).map(SettingsSelection::Filter)
    }

    fun current(setting: SettingsSetting): SettingsSelection = when (setting) {
        SettingsSetting.Filter -> SettingsSelection.Filter(OpenTvSettings.persistentHomeFilter)
    }

    fun apply(selection: SettingsSelection) {
        when (selection) {
            is SettingsSelection.Filter -> OpenTvSettings.choosePersistentHomeFilter(selection.enabled)
        }
    }

    fun title(setting: SettingsSetting): Int = when (setting) {
        SettingsSetting.Filter -> R.string.st_home_filter
    }

    fun hint(setting: SettingsSetting): Int? = when (setting) {
        SettingsSetting.Filter -> R.string.st_home_filter_warning
    }

    @Composable
    fun label(selection: SettingsSelection): String = when (selection) {
        is SettingsSelection.Filter -> stringResource(if (selection.enabled) R.string.st_home_filter_on else R.string.st_home_filter_off)
    }
}
