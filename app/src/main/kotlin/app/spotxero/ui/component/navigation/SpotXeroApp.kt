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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.spotxero.ui.component.dashboard.AppQuickControls
import app.spotxero.ui.component.dashboard.HomeDashboard
import app.spotxero.ui.component.settings.SettingsScreen
import app.spotxero.ui.theme.AppSpacing
import app.spotxero.ui.viewmodel.DashboardUiEffect
import app.spotxero.ui.viewmodel.DashboardUiState
import app.spotxero.ui.viewmodel.DashboardViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpotXeroApp(
    viewModel: DashboardViewModel,
    uiState: DashboardUiState,
    darkTheme: Boolean,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isImeVisible = WindowInsets.isImeVisible

    BackHandler(enabled = uiState.showSettings) {
        viewModel.handleBack()
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is DashboardUiEffect.ShowSnackbar -> {
                    snackbarHostState.showSnackbar(effect.message)
                }
                is DashboardUiEffect.ShowToast -> {
                    android.widget.Toast.makeText(context, effect.message, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            AppTopBar(
                showSettings = uiState.showSettings,
                settingsSubpage = uiState.settingsSubpage,
                onBack = { viewModel.handleBack() },
                onNavigateToSettings = { viewModel.showSettingsScreen() }
            )
        },
        bottomBar = {
            AnimatedVisibility(
                visible = !uiState.showSettings && !isImeVisible,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                AppQuickControls(
                    onForceStop = { viewModel.forceStopSpotify() },
                    onOpenApp = { viewModel.openSpotify() }
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) { padding ->
        AnimatedContent(
            targetState = if (uiState.showSettings) "settings" else "main",
            label = "ScreenTransition",
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) { screen ->
            when (screen) {
                "settings" -> {
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
                        onLauncherIconHiddenToggle = { viewModel.setLauncherIconHidden(it) }
                    )
                }
                else -> {
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
                                onTogglePremium = { viewModel.setPremiumEnabled(it) },
                                onCleanSharingToggle = { viewModel.setCleanSharing(it) },
                                onSpotifyAmoledToggle = { viewModel.setSpotifyAmoled(it) },
                                onHidePodcastsToggle = { viewModel.setHidePodcasts(it) },
                                onForceLocalPlaybackToggle = { viewModel.setForceLocalPlayback(it) },
                                onSpoofClientToggle = { viewModel.setSpoofClient(it) },
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
