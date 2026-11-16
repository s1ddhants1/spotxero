package app.spotxero.ui.component.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import app.spotxero.ui.theme.AppIcons
import app.spotxero.ui.theme.AppSpacing
import app.spotxero.ui.theme.appSwitchColors
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.spotxero.R
import app.spotxero.ui.viewmodel.DashboardUiState

@Composable
fun HomeDashboard(
    uiState: DashboardUiState,
    onTogglePremium: (Boolean) -> Unit,
    onCleanSharingToggle: (Boolean) -> Unit,
    onSpotifyAmoledToggle: (Boolean) -> Unit,
    onSpotifyMonetToggle: (Boolean) -> Unit = {},
    onSpotifyRoundyUiToggle: (Boolean) -> Unit = {},
    onHidePodcastsToggle: (Boolean) -> Unit = {},
    onHideAudiobooksToggle: (Boolean) -> Unit = {},
    onRequestScope: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
    ) {
        FrameworkStatusBanner(uiState = uiState, onRequestScope = onRequestScope)
        FeatureModuleCard(
            title = stringResource(R.string.pref_unrestricted_playback_title),
            subtitle = stringResource(R.string.pref_unrestricted_playback_desc),
            checked = uiState.isPremiumEnabled,
            onCheckedChange = onTogglePremium
        )

        FeatureModuleCard(
            title = stringResource(R.string.feature_clean_sharing_title),
            subtitle = stringResource(R.string.feature_clean_sharing_desc),
            checked = uiState.isCleanSharingEnabled,
            onCheckedChange = onCleanSharingToggle
        )

        FeatureModuleCard(
            title = stringResource(R.string.feature_hide_podcasts_title),
            subtitle = stringResource(R.string.feature_hide_podcasts_desc),
            checked = uiState.isHidePodcastsEnabled,
            onCheckedChange = onHidePodcastsToggle
        )

        FeatureModuleCard(
            title = stringResource(R.string.feature_hide_audiobooks_title),
            subtitle = stringResource(R.string.feature_hide_audiobooks_desc),
            checked = uiState.isHideAudiobooksEnabled,
            onCheckedChange = onHideAudiobooksToggle
        )

        FeatureModuleCard(
            title = stringResource(R.string.feature_spotify_amoled_title),
            subtitle = stringResource(R.string.feature_spotify_amoled_desc),
            checked = uiState.isSpotifyAmoledEnabled,
            onCheckedChange = onSpotifyAmoledToggle
        )

        FeatureModuleCard(
            title = stringResource(R.string.feature_spotify_monet_title),
            subtitle = stringResource(R.string.feature_spotify_monet_desc),
            checked = uiState.isSpotifyMonetEnabled,
            onCheckedChange = onSpotifyMonetToggle
        )

        FeatureModuleCard(
            title = stringResource(R.string.feature_spotify_roundy_ui_title),
            subtitle = stringResource(R.string.feature_spotify_roundy_ui_desc),
            checked = uiState.isSpotifyRoundyUiEnabled,
            onCheckedChange = onSpotifyRoundyUiToggle
        )
    }
}

@Composable
private fun FeatureModuleCard(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = null,
                thumbContent = if (checked) {
                    {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(SwitchDefaults.IconSize)
                        )
                    }
                } else null,
                colors = appSwitchColors()
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
    val canRequestScope = uiState.titleRes == R.string.framework_lsposed_not_in_scope_title
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
            .clickable(onClick = onRequestScope)
    } else {
        Modifier.fillMaxWidth()
    }

    OutlinedCard(
        modifier = cardModifier,
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.outlinedCardColors(containerColor = statusBg)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(shape = CircleShape, color = badgeColor, modifier = Modifier.size(38.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isBannerActive) AppIcons.CheckCircle else Icons.Filled.Warning,
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
