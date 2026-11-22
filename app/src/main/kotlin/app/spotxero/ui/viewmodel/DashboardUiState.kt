package app.spotxero.ui.viewmodel

import androidx.compose.runtime.Immutable
import app.spotxero.R
import app.spotxero.core.PrefKeys
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
    val isMasterEnabled: Boolean = true,
    val isPremiumEnabled: Boolean = true,
    val isFrameworkConnected: Boolean = false,
    val isInjectable: Boolean = false,
    val canRequestScope: Boolean = false,
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
    val isHapticFeedbackEnabled: Boolean = true,
    val isCleanSharingEnabled: Boolean = true,
    val isLyricsEnabled: Boolean = true,
    val showLyricsSources: Boolean = false,
    val showAiLyricsTranslation: Boolean = false,
    val isExternalLyricsEnabled: Boolean = false,
    val isForceExternalLyricsEnabled: Boolean = false,
    val isAiLyricsTranslationEnabled: Boolean = false,
    val isAiTranslationDualLine: Boolean = true,
    val aiTranslationProvider: String = "OpenRouter",
    val aiTranslationApiKey: String = "",
    val aiTranslationBaseUrl: String = PrefKeys.OpenRouterDefaultBaseUrl,
    val aiTranslationModel: String = PrefKeys.OpenRouterDefaultModel,
    val aiTranslationTargetLang: String = "en",
    val aiTranslationCustomPrompt: String = "",
    val aiTranslationTranslateMode: String = "Literal",
    val aiTranslationDeeplApiKey: String = "",
    val aiTranslationDeeplFormality: String = "default",
    val lyricsProvidersOrder: List<String> = listOf("spicylyrics", "betterlyrics", "lrcred", "lrclib", "kugou", "netease", "genius"),
    val isSpicylyricsEnabled: Boolean = true,
    val isBetterlyricsEnabled: Boolean = true,
    val isLrcredEnabled: Boolean = true,
    val isLrclibEnabled: Boolean = true,
    val isKugouEnabled: Boolean = true,
    val isNeteaseEnabled: Boolean = true,
    val isGeniusEnabled: Boolean = true,
    val isForceLocalPlaybackEnabled: Boolean = true,
    val isSpotifyAmoledEnabled: Boolean = false,
    val isSpotifyMonetEnabled: Boolean = false,
    val isSpotifyRoundyUiEnabled: Boolean = false,
    val isHidePodcastsEnabled: Boolean = false,
    val isHideAudiobooksEnabled: Boolean = false,
    val isHidePremiumTabEnabled: Boolean = true,
    val isDoubleTapToSeekEnabled: Boolean = true,
    val seekDurationSeconds: Int = 10,
    val isSeekProgressive: Boolean = false,

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
}
