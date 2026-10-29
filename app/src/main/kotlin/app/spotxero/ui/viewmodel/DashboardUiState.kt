package app.spotxero.ui.viewmodel

import androidx.compose.runtime.Immutable
import app.spotxero.R
import app.spotxero.core.PreferencesManager

enum class SettingsSubpage {
    APPEARANCE,
    ABOUT
}

@Immutable
data class InstalledSpotifyInfo(
    val packageName: String,
    val versionName: String,
    val variantLabel: String
)

@Immutable
data class DashboardUiState(
    val isModuleActive: Boolean = false,
    val isPremiumEnabled: Boolean = true,
    val detectedFramework: String? = null,
    val isFrameworkConnected: Boolean = false,
    val isInjectable: Boolean = false,
    val titleRes: Int = R.string.framework_inactive_title,
    val titleArgs: List<String> = emptyList(),
    val descRes: Int = R.string.framework_inactive_desc,
    val descArgs: List<String> = emptyList(),
    val showSettings: Boolean = false,
    val settingsSubpage: SettingsSubpage? = null,
    val themeMode: PreferencesManager.ThemeMode = PreferencesManager.ThemeMode.SYSTEM,
    val isDynamicColorEnabled: Boolean = false,
    val isAppAmoledEnabled: Boolean = false,
    val isLauncherIconHidden: Boolean = false,
    val isCleanSharingEnabled: Boolean = true,
    val isSpotifyAmoledEnabled: Boolean = false,
    val isHidePremiumTabEnabled: Boolean = false,
    val isBlockAdsEnabled: Boolean = true,
    val isHideBrandAdsEnabled: Boolean = true,
    val isHidePlayerAdsEnabled: Boolean = true,
    val isHidePodcastsEnabled: Boolean = false,

    val activeSpotify: InstalledSpotifyInfo? = null,
    val installedSpotifyVariants: List<InstalledSpotifyInfo> = emptyList()
) {
    fun isDarkTheme(systemDark: Boolean): Boolean = when (themeMode) {
        PreferencesManager.ThemeMode.LIGHT -> false
        PreferencesManager.ThemeMode.DARK -> true
        PreferencesManager.ThemeMode.SYSTEM -> systemDark
    }
}

sealed interface DashboardUiEffect {
    data class ShowToast(val message: String) : DashboardUiEffect
    data class ShowSnackbar(val message: String) : DashboardUiEffect
}
