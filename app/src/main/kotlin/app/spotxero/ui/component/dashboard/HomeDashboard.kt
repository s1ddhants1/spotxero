package app.spotxero.ui.component.dashboard

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.spotxero.R
import app.spotxero.ui.component.settings.CategoryHeader
import app.spotxero.ui.component.settings.NavigationSettingCard
import app.spotxero.ui.component.settings.SwitchSettingCard
import app.spotxero.ui.haptics.HapticEvent
import app.spotxero.ui.haptics.LocalAppHaptics
import app.spotxero.ui.theme.AppCardDefaults
import app.spotxero.ui.theme.AppIcons
import app.spotxero.ui.theme.AppSpacing
import app.spotxero.ui.theme.appSwitchColors
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
    onLyricsToggle: (Boolean) -> Unit = {},
    onForceLocalPlaybackToggle: (Boolean) -> Unit = {},
    onOpenLyricsSources: () -> Unit = {},
    onRequestScope: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
    ) {
        FrameworkStatusBanner(uiState = uiState, onRequestScope = onRequestScope)

        MasterSwitchCard(
            checked = uiState.isMasterEnabled,
            onCheckedChange = onMasterToggle
        )

        CategoryHeader(title = stringResource(R.string.category_playback))
        SwitchSettingCard(
            title = stringResource(R.string.pref_unrestricted_playback_title),
            subtitle = stringResource(R.string.pref_unrestricted_playback_desc),
            icon = AppIcons.PlayCircle,
            checked = uiState.isPremiumEnabled,
            onCheckedChange = onTogglePremium
        )
        SwitchSettingCard(
            title = stringResource(R.string.feature_force_local_playback_title),
            subtitle = stringResource(R.string.feature_force_local_playback_desc),
            icon = AppIcons.VolumeUp,
            checked = uiState.isForceLocalPlaybackEnabled,
            onCheckedChange = onForceLocalPlaybackToggle
        )

        CategoryHeader(title = stringResource(R.string.category_lyrics))
        SwitchSettingCard(
            title = stringResource(R.string.feature_uncapped_lyrics_title),
            subtitle = stringResource(R.string.feature_uncapped_lyrics_desc),
            icon = AppIcons.Lyrics,
            checked = uiState.isLyricsEnabled,
            onCheckedChange = onLyricsToggle
        )
        NavigationSettingCard(
            title = stringResource(R.string.feature_external_lyrics_title),
            subtitle = stringResource(R.string.feature_external_lyrics_desc),
            icon = AppIcons.Lyrics,
            onClick = onOpenLyricsSources
        )

        CategoryHeader(title = stringResource(R.string.category_privacy))
        SwitchSettingCard(
            title = stringResource(R.string.feature_clean_sharing_title),
            subtitle = stringResource(R.string.feature_clean_sharing_desc),
            icon = AppIcons.LinkOff,
            checked = uiState.isCleanSharingEnabled,
            onCheckedChange = onCleanSharingToggle
        )

        CategoryHeader(title = stringResource(R.string.category_content))
        SwitchSettingCard(
            title = stringResource(R.string.feature_hide_podcasts_title),
            subtitle = stringResource(R.string.feature_hide_podcasts_desc),
            icon = AppIcons.AdaptiveAudioMicOff,
            checked = uiState.isHidePodcastsEnabled,
            onCheckedChange = onHidePodcastsToggle
        )

        SwitchSettingCard(
            title = stringResource(R.string.feature_hide_audiobooks_title),
            subtitle = stringResource(R.string.feature_hide_audiobooks_desc),
            icon = AppIcons.AutoStoriesOff,
            checked = uiState.isHideAudiobooksEnabled,
            onCheckedChange = onHideAudiobooksToggle
        )

        CategoryHeader(title = stringResource(R.string.category_theme))
        SwitchSettingCard(
            title = stringResource(R.string.feature_spotify_amoled_title),
            subtitle = stringResource(R.string.feature_spotify_amoled_desc),
            icon = AppIcons.DarkMode,
            checked = uiState.isSpotifyAmoledEnabled,
            onCheckedChange = onSpotifyAmoledToggle
        )

        SwitchSettingCard(
            title = stringResource(R.string.feature_spotify_monet_title),
            subtitle = stringResource(R.string.feature_spotify_monet_desc),
            icon = AppIcons.Palette,
            checked = uiState.isSpotifyMonetEnabled,
            onCheckedChange = onSpotifyMonetToggle
        )

        SwitchSettingCard(
            title = stringResource(R.string.feature_spotify_roundy_ui_title),
            subtitle = stringResource(R.string.feature_spotify_roundy_ui_desc),
            icon = AppIcons.RoundedCorner,
            checked = uiState.isSpotifyRoundyUiEnabled,
            onCheckedChange = onSpotifyRoundyUiToggle
        )
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
    Card(
        shape = MaterialTheme.shapes.extraLarge,
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
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
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
                        imageVector = AppIcons.PowerSettingsNew,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(Modifier.width(AppSpacing.sm))

            Text(
                text = stringResource(R.string.feature_master_switch_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (checked) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.weight(1f)
            )

            Spacer(Modifier.width(AppSpacing.xs))
            app.spotxero.ui.component.ExpressiveSwitch(
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
    val isBannerActive = uiState.isFrameworkConnected && uiState.isInjectable
    val canRequestScope = uiState.canRequestScope
    val haptics = LocalAppHaptics.current
    val statusBg = if (isBannerActive) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
    }
    val badgeColor = if (isBannerActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    val iconTint = if (isBannerActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onError

    val cardModifier = if (canRequestScope) {
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .clickable(onClick = {
                haptics.perform(HapticEvent.PRESS)
                onRequestScope()
            })
    } else {
        Modifier.fillMaxWidth()
    }

    OutlinedCard(
        modifier = cardModifier,
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.outlinedCardColors(containerColor = statusBg)
    ) {
        Row(
            modifier = Modifier.padding(AppSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
        ) {
            Surface(shape = CircleShape, color = badgeColor, modifier = Modifier.size(38.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isBannerActive) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (uiState.titleArgs.isEmpty()) {
                        stringResource(uiState.titleRes)
                    } else {
                        stringResource(uiState.titleRes, *uiState.titleArgs.toTypedArray())
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(AppSpacing.xxs))
                Text(
                    text = if (uiState.descArgs.isEmpty()) {
                        stringResource(uiState.descRes)
                    } else {
                        stringResource(uiState.descRes, *uiState.descArgs.toTypedArray())
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (canRequestScope) {
                Icon(
                    imageVector = AppIcons.Launch,
                    contentDescription = null,
                    tint = badgeColor,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
