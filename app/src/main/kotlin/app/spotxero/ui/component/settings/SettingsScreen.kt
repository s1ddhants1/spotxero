package app.spotxero.ui.component.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import app.spotxero.core.PreferencesManager.ThemeMode
import app.spotxero.ui.viewmodel.SettingsSubpage

@Composable
fun SettingsScreen(
    currentSubpage: SettingsSubpage?,
    onNavigateToSubpage: (SettingsSubpage) -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    dynamicColorEnabled: Boolean,
    onDynamicColorToggle: (Boolean) -> Unit,
    amoledEnabled: Boolean,
    onAmoledToggle: (Boolean) -> Unit,
    launcherIconHidden: Boolean,
    onLauncherIconHiddenToggle: (Boolean) -> Unit
) {
    AnimatedContent(
        targetState = currentSubpage,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "SettingsSubpageTransition"
    ) { subpage ->
        when (subpage) {
            null -> SettingsIndexPage(onNavigateToSubpage = onNavigateToSubpage)
            SettingsSubpage.APPEARANCE -> AppearanceSettingsPage(
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                dynamicColorEnabled = dynamicColorEnabled,
                onDynamicColorToggle = onDynamicColorToggle,
                amoledEnabled = amoledEnabled,
                onAmoledToggle = onAmoledToggle,
                launcherIconHidden = launcherIconHidden,
                onLauncherIconHiddenToggle = onLauncherIconHiddenToggle
            )
            SettingsSubpage.ABOUT -> AboutSettingsPage()
        }
    }
}
