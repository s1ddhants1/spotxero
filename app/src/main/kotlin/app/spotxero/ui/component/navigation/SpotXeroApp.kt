package app.spotxero.ui.component.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.widget.Toast
import app.spotxero.ui.component.dashboard.AppQuickControls
import app.spotxero.ui.component.dashboard.HomeDashboard
import app.spotxero.ui.component.lyrics.AiLyricsTranslationScreen
import app.spotxero.ui.component.lyrics.LyricsSourcesScreen
import app.spotxero.ui.component.settings.SettingsScreen
import app.spotxero.ui.theme.AppSpacing
import app.spotxero.ui.viewmodel.DashboardUiEffect
import app.spotxero.ui.viewmodel.DashboardUiState
import app.spotxero.ui.viewmodel.DashboardViewModel
import kotlin.math.roundToInt

private enum class Screen {
    DASHBOARD,
    SETTINGS,
    LYRICS_SOURCES,
    AI_LYRICS_TRANSLATION
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpotXeroApp(
    viewModel: DashboardViewModel,
    uiState: DashboardUiState,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isImeVisible = WindowInsets.isImeVisible

    val screen = when {
        uiState.showAiLyricsTranslation -> Screen.AI_LYRICS_TRANSLATION
        uiState.showLyricsSources -> Screen.LYRICS_SOURCES
        uiState.showSettings -> Screen.SETTINGS
        else -> Screen.DASHBOARD
    }

    BackHandler(enabled = screen != Screen.DASHBOARD) {
        viewModel.handleBack()
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is DashboardUiEffect.ShowToast -> {
                    Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            AppTopBar(
                showSettings = uiState.showSettings,
                settingsSubpage = uiState.settingsSubpage,
                showLyricsSources = uiState.showLyricsSources,
                showAiLyricsTranslation = uiState.showAiLyricsTranslation,
                onBack = { viewModel.handleBack() },
                onNavigateToSettings = { viewModel.showSettingsScreen() }
            )
        },
        bottomBar = {
            AnimatedVisibility(
                visible = screen == Screen.DASHBOARD && !isImeVisible,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                AppQuickControls(
                    onForceStop = { viewModel.forceStopSpotify() },
                    onOpenApp = { viewModel.launchSpotify() }
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) { padding ->
        AnimatedContent(
            targetState = screen,
            label = "ScreenTransition",
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) { currentScreen ->
            when (currentScreen) {
                Screen.AI_LYRICS_TRANSLATION -> {
                    AiLyricsTranslationScreen(
                        isAiTranslationEnabled = uiState.isAiLyricsTranslationEnabled,
                        isAiTranslationDualLine = uiState.isAiTranslationDualLine,
                        aiProvider = uiState.aiTranslationProvider,
                        openRouterApiKey = uiState.aiTranslationApiKey,
                        openRouterBaseUrl = uiState.aiTranslationBaseUrl,
                        openRouterModel = uiState.aiTranslationModel,
                        translateLanguage = uiState.aiTranslationTargetLang,
                        translateMode = uiState.aiTranslationTranslateMode,
                        deeplApiKey = uiState.aiTranslationDeeplApiKey,
                        deeplFormality = uiState.aiTranslationDeeplFormality,
                        aiSystemPrompt = uiState.aiTranslationCustomPrompt,
                        onAiTranslationEnabledChange = { viewModel.setAiLyricsTranslationEnabled(it) },
                        onAiTranslationDualLineChange = { viewModel.setAiTranslationDualLine(it) },
                        onAiProviderChange = { viewModel.setAiTranslationProvider(it) },
                        onOpenRouterApiKeyChange = { viewModel.setAiTranslationApiKey(it) },
                        onOpenRouterBaseUrlChange = { viewModel.setAiTranslationBaseUrl(it) },
                        onOpenRouterModelChange = { viewModel.setAiTranslationModel(it) },
                        onTranslateLanguageChange = { viewModel.setAiTranslationTargetLang(it) },
                        onTranslateModeChange = { viewModel.setAiTranslationTranslateMode(it) },
                        onDeeplApiKeyChange = { viewModel.setAiTranslationDeeplApiKey(it) },
                        onDeeplFormalityChange = { viewModel.setAiTranslationDeeplFormality(it) },
                        onAiSystemPromptChange = { viewModel.setAiTranslationCustomPrompt(it) }
                    )
                }
                Screen.LYRICS_SOURCES -> {
                    LyricsSourcesScreen(
                        isExternalLyricsEnabled = uiState.isExternalLyricsEnabled,
                        isForceExternalLyricsEnabled = uiState.isForceExternalLyricsEnabled,
                        lyricsProvidersOrder = uiState.lyricsProvidersOrder,
                        isSpicylyricsEnabled = uiState.isSpicylyricsEnabled,
                        isBetterlyricsEnabled = uiState.isBetterlyricsEnabled,
                        isLrcredEnabled = uiState.isLrcredEnabled,
                        isLrclibEnabled = uiState.isLrclibEnabled,
                        isKugouEnabled = uiState.isKugouEnabled,
                        isNeteaseEnabled = uiState.isNeteaseEnabled,
                        isGeniusEnabled = uiState.isGeniusEnabled,
                        onExternalLyricsToggle = { viewModel.setExternalLyrics(it) },
                        onForceExternalLyricsToggle = { viewModel.setForceExternalLyrics(it) },
                        onOpenAiLyricsTranslation = { viewModel.showAiLyricsTranslationScreen() },
                        onProviderToggle = { id, enabled -> viewModel.setLyricsProviderEnabled(id, enabled) },
                        onReorderProviders = { viewModel.reorderLyricsProviders(it) }
                    )
                }
                Screen.SETTINGS -> {
                    SettingsScreen(
                        currentSubpage = uiState.settingsSubpage,
                        onNavigateToSubpage = { viewModel.selectSettingsSubpage(it) },
                        themeMode = uiState.themeMode,
                        onThemeModeChange = { viewModel.setThemeMode(it) },
                        dynamicColorEnabled = uiState.isDynamicColorEnabled,
                        onDynamicColorToggle = { viewModel.setDynamicColor(it) },
                        amoledEnabled = uiState.isAppAmoledEnabled,
                        onAmoledToggle = { viewModel.setAppAmoled(it) },
                        launcherIconHidden = uiState.isLauncherIconHidden,
                        onLauncherIconHiddenToggle = { viewModel.setLauncherIconHidden(it) },
                        hapticFeedbackEnabled = uiState.isHapticFeedbackEnabled,
                        onHapticFeedbackToggle = { viewModel.setHapticFeedback(it) }
                    )
                }
                Screen.DASHBOARD -> {
                    LazyColumn(
                        contentPadding = PaddingValues(
                            start = AppSpacing.md,
                            end = AppSpacing.md,
                            top = AppSpacing.xs,
                            bottom = 88.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        item(key = "home_dashboard") {
                            HomeDashboard(
                                uiState = uiState,
                                onMasterToggle = { viewModel.setMasterEnabled(it) },
                                onTogglePremium = { viewModel.setPremiumEnabled(it) },
                                onCleanSharingToggle = { viewModel.setCleanSharing(it) },
                                onSpotifyAmoledToggle = { viewModel.setSpotifyAmoled(it) },
                                onSpotifyMonetToggle = { viewModel.setSpotifyMonet(it) },
                                onSpotifyRoundyUiToggle = { viewModel.setSpotifyRoundyUi(it) },
                                onHidePodcastsToggle = { viewModel.setHidePodcasts(it) },
                                onHideAudiobooksToggle = { viewModel.setHideAudiobooks(it) },
                                onHidePremiumTabToggle = { viewModel.setHidePremiumTab(it) },
                                onLyricsToggle = { viewModel.setLyrics(it) },
                                onForceLocalPlaybackToggle = { viewModel.setForceLocalPlayback(it) },
                                onDoubleTapToSeekToggle = { viewModel.setDoubleTapToSeekEnabled(it) },
                                onSeekDurationChange = { viewModel.setSeekDurationSeconds(it.roundToInt()) },
                                onSeekProgressiveToggle = { viewModel.setSeekProgressive(it) },
                                onOpenLyricsSources = { viewModel.showLyricsSourcesScreen() },
                                onRequestScope = { viewModel.requestScope() }
                            )
                        }
                        item(key = "bottom_spacer") { Spacer(Modifier.height(AppSpacing.xs)) }
                    }
                }
            }
        }
    }
}
