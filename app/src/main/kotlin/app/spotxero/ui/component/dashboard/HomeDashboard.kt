package app.spotxero.ui.component.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.spotxero.R
import app.spotxero.ui.component.ExpressiveSwitch
import app.spotxero.ui.component.settings.SettingsGroup
import app.spotxero.ui.component.settings.SettingsItem
import app.spotxero.ui.component.settings.SettingsSection
import app.spotxero.ui.component.settings.SettingsSwitchItem
import app.spotxero.ui.component.settings.SliderSettingCard
import app.spotxero.ui.haptics.HapticEvent
import app.spotxero.ui.haptics.LocalAppHaptics
import app.spotxero.ui.theme.AppCardDefaults
import app.spotxero.ui.theme.AppSpacing
import app.spotxero.ui.viewmodel.DashboardUiState

@Composable
fun HomeDashboard(
    uiState: DashboardUiState,
    onMasterToggle: (Boolean) -> Unit,
    onTogglePremium: (Boolean) -> Unit,
    onCleanSharingToggle: (Boolean) -> Unit,
    onSpotifyAmoledToggle: (Boolean) -> Unit,
    onSpotifyMonetToggle: (Boolean) -> Unit = {},
    onSpotifyRoundyUiToggle: (Boolean) -> Unit = {},
    onHidePodcastsToggle: (Boolean) -> Unit = {},
    onHideAudiobooksToggle: (Boolean) -> Unit = {},
    onHidePremiumTabToggle: (Boolean) -> Unit = {},
    onLyricsToggle: (Boolean) -> Unit = {},
    onForceLocalPlaybackToggle: (Boolean) -> Unit = {},
    onDoubleTapToSeekToggle: (Boolean) -> Unit = {},
    onSeekDurationChange: (Float) -> Unit = {},
    onSeekProgressiveToggle: (Boolean) -> Unit = {},
    onOpenLyricsSources: () -> Unit = {},
    onRequestScope: () -> Unit = {},
    onOpenInAppSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        FrameworkStatusBanner(
            uiState = uiState,
            onRequestScope = onRequestScope
        )

        if (uiState.isIntegrated) {
            SettingsGroup {
                SettingsItem(
                    title = stringResource(R.string.btn_open_in_app_settings),
                    subtitle = stringResource(R.string.open_in_app_settings_desc),
                    painter = painterResource(id = R.drawable.ic_tune),
                    onClick = onOpenInAppSettings,
                    trailingContent = {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_launch),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                )
            }
        } else {

        MasterSwitchCard(
            checked = uiState.isMasterEnabled,
            onCheckedChange = onMasterToggle
        )


        SettingsSection(
            title = stringResource(R.string.category_playback)
        ) {
            SettingsGroup {
                SettingsSwitchItem(
                    title = stringResource(R.string.pref_unrestricted_playback_title),
                    subtitle = stringResource(R.string.pref_unrestricted_playback_desc),
                    painter = painterResource(id = R.drawable.ic_play_circle),
                    checked = uiState.isPremiumEnabled,
                    onCheckedChange = onTogglePremium
                )
                SettingsSwitchItem(
                    title = stringResource(R.string.feature_force_local_playback_title),
                    subtitle = stringResource(R.string.feature_force_local_playback_desc),
                    painter = painterResource(id = R.drawable.ic_volume_up),
                    checked = uiState.isForceLocalPlaybackEnabled,
                    onCheckedChange = onForceLocalPlaybackToggle
                )
                SettingsSwitchItem(
                    title = stringResource(R.string.feature_double_tap_to_seek_title),
                    subtitle = stringResource(R.string.feature_double_tap_to_seek_desc),
                    painter = painterResource(id = R.drawable.ic_fast_forward),
                    checked = uiState.isDoubleTapToSeekEnabled,
                    onCheckedChange = onDoubleTapToSeekToggle
                )
                if (uiState.isDoubleTapToSeekEnabled) {
                    SliderSettingCard(
                        title = stringResource(R.string.seek_duration),
                        description = stringResource(R.string.seek_duration_description),
                        value = uiState.seekDurationSeconds.toFloat(),
                        defaultValue = 10f,
                        range = 5f..30f,
                        steps = 4,
                        valueLabel = stringResource(R.string.settings_seconds_value, uiState.seekDurationSeconds),
                        onValueChange = onSeekDurationChange
                    )
                    SettingsSwitchItem(
                        title = stringResource(R.string.seek_seconds_addup),
                        subtitle = stringResource(R.string.seek_seconds_addup_desc),
                        painter = painterResource(id = R.drawable.ic_arrow_forward),
                        checked = uiState.isSeekProgressive,
                        onCheckedChange = onSeekProgressiveToggle
                    )
                }
            }
        }

        SettingsSection(
            title = stringResource(R.string.category_lyrics)
        ) {
            SettingsGroup {
                SettingsSwitchItem(
                    title = stringResource(R.string.feature_uncapped_lyrics_title),
                    subtitle = stringResource(R.string.feature_uncapped_lyrics_desc),
                    painter = painterResource(id = R.drawable.ic_lyrics),
                    checked = uiState.isLyricsEnabled,
                    onCheckedChange = onLyricsToggle
                )
                SettingsItem(
                    title = stringResource(R.string.feature_external_lyrics_title),
                    subtitle = stringResource(R.string.feature_external_lyrics_desc),
                    painter = painterResource(id = R.drawable.ic_lyrics),
                    onClick = onOpenLyricsSources
                )
            }
        }

        SettingsSection(
            title = stringResource(R.string.category_privacy)
        ) {
            SettingsGroup {
                SettingsSwitchItem(
                    title = stringResource(R.string.feature_clean_sharing_title),
                    subtitle = stringResource(R.string.feature_clean_sharing_desc),
                    painter = painterResource(id = R.drawable.ic_link_off),
                    checked = uiState.isCleanSharingEnabled,
                    onCheckedChange = onCleanSharingToggle
                )
            }
        }

        SettingsSection(
            title = stringResource(R.string.category_content)
        ) {
            SettingsGroup {
                SettingsSwitchItem(
                    title = stringResource(R.string.feature_hide_premium_tab_title),
                    subtitle = stringResource(R.string.feature_hide_premium_tab_desc),
                    painter = painterResource(id = R.drawable.ic_visibility_off),
                    checked = uiState.isHidePremiumTabEnabled,
                    onCheckedChange = onHidePremiumTabToggle
                )
                SettingsSwitchItem(
                    title = stringResource(R.string.feature_hide_podcasts_title),
                    subtitle = stringResource(R.string.feature_hide_podcasts_desc),
                    painter = painterResource(id = R.drawable.ic_adaptive_audio_mic_off),
                    checked = uiState.isHidePodcastsEnabled,
                    onCheckedChange = onHidePodcastsToggle
                )
                SettingsSwitchItem(
                    title = stringResource(R.string.feature_hide_audiobooks_title),
                    subtitle = stringResource(R.string.feature_hide_audiobooks_desc),
                    painter = painterResource(id = R.drawable.ic_auto_stories_off),
                    checked = uiState.isHideAudiobooksEnabled,
                    onCheckedChange = onHideAudiobooksToggle
                )
            }
        }

        SettingsSection(
            title = stringResource(R.string.category_theme)
        ) {
            SettingsGroup {
                SettingsSwitchItem(
                    title = stringResource(R.string.feature_spotify_amoled_title),
                    subtitle = stringResource(R.string.feature_spotify_amoled_desc),
                    painter = painterResource(id = R.drawable.ic_dark_mode),
                    checked = uiState.isSpotifyAmoledEnabled,
                    onCheckedChange = onSpotifyAmoledToggle
                )
                SettingsSwitchItem(
                    title = stringResource(R.string.feature_spotify_monet_title),
                    subtitle = stringResource(R.string.feature_spotify_monet_desc),
                    painter = painterResource(id = R.drawable.ic_palette),
                    checked = uiState.isSpotifyMonetEnabled,
                    onCheckedChange = onSpotifyMonetToggle
                )
                SettingsSwitchItem(
                    title = stringResource(R.string.feature_spotify_roundy_ui_title),
                    subtitle = stringResource(R.string.feature_spotify_roundy_ui_desc),
                    painter = painterResource(id = R.drawable.ic_rounded_corner),
                    checked = uiState.isSpotifyRoundyUiEnabled,
                    onCheckedChange = onSpotifyRoundyUiToggle
                )
            }
        }

        }
    }
}

@Composable
private fun MasterSwitchCard(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalAppHaptics.current
    val handleToggle: (Boolean) -> Unit = { newVal ->
        haptics.toggle(newVal)
        onCheckedChange(newVal)
    }
    val interactionSource = remember { MutableInteractionSource() }
    val cardShape = RoundedCornerShape(20.dp)
    Card(
        shape = cardShape,
        colors = CardDefaults.cardColors(
            containerColor = if (checked) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
            contentColor = if (checked) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        ),
        elevation = AppCardDefaults.cardElevation,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = modifier
            .fillMaxWidth()
            .clip(cardShape)
            .toggleable(
                value = checked,
                role = Role.Switch,
                interactionSource = interactionSource,
                indication = null,
                onValueChange = handleToggle
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Surface(
                shape = CircleShape,
                color = if (checked) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
                contentColor = if (checked) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_power_settings_new),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(Modifier.width(AppSpacing.sm))

            Text(
                text = stringResource(R.string.feature_master_switch_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (checked) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.weight(1f)
            )

            Spacer(Modifier.width(AppSpacing.xs))
            ExpressiveSwitch(
                checked = checked,
                onCheckedChange = null,
            )
        }
    }
}

@Composable
private fun FrameworkStatusBanner(
    uiState: DashboardUiState,
    onRequestScope: () -> Unit
) {
    val isReady = uiState.isFrameworkConnected && uiState.isInjectable
    val isPaused = isReady && !uiState.isMasterEnabled
    val isBannerActive = isReady && !isPaused
    val canRequestScope = uiState.canRequestScope
    val haptics = LocalAppHaptics.current
    val statusBg = when {
        isBannerActive -> MaterialTheme.colorScheme.surfaceContainerHigh
        isPaused -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
    }
    val badgeColor = when {
        isBannerActive -> MaterialTheme.colorScheme.primary
        isPaused -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.error
    }
    val iconTint = when {
        isBannerActive -> MaterialTheme.colorScheme.onPrimary
        isPaused -> MaterialTheme.colorScheme.surface
        else -> MaterialTheme.colorScheme.onError
    }
    val borderColor = when {
        isBannerActive || isPaused -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        else -> MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
    }
    val cardShape = RoundedCornerShape(20.dp)

    val cardModifier = if (canRequestScope) {
        Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .clickable(onClick = {
                haptics.perform(HapticEvent.PRESS)
                onRequestScope()
            })
    } else {
        Modifier.fillMaxWidth()
    }

    val titleText = if (isPaused) {
        stringResource(R.string.framework_paused_title)
    } else if (uiState.titleArgs.isEmpty()) {
        stringResource(uiState.titleRes)
    } else {
        stringResource(uiState.titleRes, *uiState.titleArgs.toTypedArray())
    }

    val descText = if (isPaused) {
        stringResource(R.string.framework_paused_desc)
    } else if (uiState.descArgs.isEmpty()) {
        stringResource(uiState.descRes)
    } else {
        stringResource(uiState.descRes, *uiState.descArgs.toTypedArray())
    }

    val spotifyLabel = uiState.activeSpotify?.takeIf {
        uiState.titleRes != R.string.framework_spotify_not_installed_title
    }?.let { info ->
        "Spotify ${info.versionName}"
    }

    OutlinedCard(
        modifier = cardModifier,
        shape = cardShape,
        colors = CardDefaults.outlinedCardColors(containerColor = statusBg),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(AppSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
        ) {
            Surface(shape = CircleShape, color = badgeColor, modifier = Modifier.size(38.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(
                            id = when {
                                isBannerActive -> R.drawable.ic_check_circle
                                isPaused -> R.drawable.ic_power_settings_new
                                else -> R.drawable.ic_warning
                            }
                        ),
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = titleText,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(AppSpacing.xxs))
                Text(
                    text = descText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (spotifyLabel != null) {
                    Spacer(Modifier.height(AppSpacing.xs))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.8f)
                    ) {
                        Text(
                            text = spotifyLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
            if (canRequestScope) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_launch),
                    contentDescription = null,
                    tint = badgeColor,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
