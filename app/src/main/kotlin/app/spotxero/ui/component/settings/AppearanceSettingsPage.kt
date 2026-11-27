package app.spotxero.ui.component.settings

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.spotxero.R
import app.spotxero.core.PreferencesManager.ThemeMode
import app.spotxero.ui.haptics.HapticEvent
import app.spotxero.ui.haptics.LocalAppHaptics
import app.spotxero.ui.theme.AppSpacing

private data class ThemeOption(
    val mode: ThemeMode,
    val labelRes: Int,
    val iconRes: Int
)

private val THEME_OPTIONS = listOf(
    ThemeOption(ThemeMode.SYSTEM, R.string.theme_mode_system, R.drawable.ic_smartphone),
    ThemeOption(ThemeMode.LIGHT, R.string.theme_mode_light, R.drawable.ic_light_mode),
    ThemeOption(ThemeMode.DARK, R.string.theme_mode_dark, R.drawable.ic_dark_mode)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettingsPage(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    dynamicColorEnabled: Boolean,
    onDynamicColorToggle: (Boolean) -> Unit,
    amoledEnabled: Boolean,
    onAmoledToggle: (Boolean) -> Unit,
    launcherIconHidden: Boolean,
    onLauncherIconHiddenToggle: (Boolean) -> Unit,
    hapticFeedbackEnabled: Boolean,
    onHapticFeedbackToggle: (Boolean) -> Unit
) {
    val haptics = LocalAppHaptics.current
    val isDynamicColorSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = AppSpacing.md)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(AppSpacing.xxs))

        SettingsSection(
            title = stringResource(R.string.appearance_app_theme)
        ) {
            SettingsGroup {
                SurfaceCard(onClick = null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(AppSpacing.md),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                    ) {
                        Text(
                            text = stringResource(R.string.appearance_app_theme),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        SingleChoiceSegmentedButtonRow(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            THEME_OPTIONS.forEachIndexed { index, option ->
                                SegmentedButton(
                                    selected = themeMode == option.mode,
                                    onClick = {
                                        haptics.perform(HapticEvent.SEGMENT_TICK)
                                        onThemeModeChange(option.mode)
                                    },
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index = index,
                                        count = THEME_OPTIONS.size
                                    ),
                                    icon = {
                                        Icon(
                                            painter = painterResource(id = option.iconRes),
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = stringResource(option.labelRes),
                                            maxLines = 1
                                        )
                                    }
                                )
                            }
                        }
                    }
                }

                SettingsSwitchItem(
                    title = stringResource(R.string.appearance_dynamic_colors),
                    subtitle = if (isDynamicColorSupported) {
                        stringResource(R.string.appearance_dynamic_colors_desc)
                    } else {
                        stringResource(R.string.appearance_dynamic_colors_unsupported)
                    },
                    painter = painterResource(id = R.drawable.ic_palette),
                    checked = dynamicColorEnabled && isDynamicColorSupported,
                    enabled = isDynamicColorSupported,
                    onCheckedChange = onDynamicColorToggle
                )

                SettingsSwitchItem(
                    title = stringResource(R.string.appearance_amoled_title),
                    subtitle = stringResource(R.string.appearance_amoled_desc),
                    painter = painterResource(id = R.drawable.ic_dark_mode),
                    checked = amoledEnabled,
                    onCheckedChange = onAmoledToggle
                )
            }
        }

        SettingsSection(
            title = stringResource(R.string.category_preferences)
        ) {
            SettingsGroup {
                SettingsSwitchItem(
                    title = stringResource(R.string.appearance_hide_icon_title),
                    subtitle = stringResource(R.string.appearance_hide_icon_desc),
                    painter = painterResource(id = if (launcherIconHidden) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                    checked = launcherIconHidden,
                    onCheckedChange = onLauncherIconHiddenToggle
                )

                SettingsSwitchItem(
                    title = stringResource(R.string.appearance_enable_haptic_feedback),
                    subtitle = stringResource(R.string.appearance_enable_haptic_feedback_desc),
                    painter = painterResource(id = R.drawable.ic_vibration),
                    checked = hapticFeedbackEnabled,
                    onCheckedChange = onHapticFeedbackToggle
                )
            }
        }

        Spacer(Modifier.height(AppSpacing.md))
    }
}
