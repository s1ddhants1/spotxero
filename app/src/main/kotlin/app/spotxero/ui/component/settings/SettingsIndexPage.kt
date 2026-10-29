package app.spotxero.ui.component.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import app.spotxero.ui.theme.AppIcons
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.spotxero.R
import app.spotxero.ui.theme.AppSpacing
import app.spotxero.ui.viewmodel.SettingsSubpage

@Composable
fun SettingsIndexPage(
    onNavigateToSubpage: (SettingsSubpage) -> Unit
) {
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = AppSpacing.md)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
    ) {
        Spacer(Modifier.height(AppSpacing.xxs))

        SettingsCategoryCard(
            icon = AppIcons.Palette,
            title = stringResource(R.string.title_appearance),
            description = stringResource(R.string.appearance_category_desc),
            onClick = { onNavigateToSubpage(SettingsSubpage.APPEARANCE) }
        )

        SettingsCategoryCard(
            icon = Icons.Filled.Info,
            title = stringResource(R.string.title_about),
            description = stringResource(R.string.about_category_desc),
            onClick = { onNavigateToSubpage(SettingsSubpage.ABOUT) }
        )

        Spacer(Modifier.height(AppSpacing.xs))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.md, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledTonalIconButton(
                onClick = { uriHandler.openUri("https://github.com/s1ddhants1/SpotXero") },
                shape = CircleShape,
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_github_logo),
                    contentDescription = stringResource(R.string.cd_github),
                    modifier = Modifier.size(24.dp)
                )
            }

            FilledTonalIconButton(
                onClick = { uriHandler.openUri("https://t.me/SpotXero") },
                shape = CircleShape,
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_telegram_logo),
                    contentDescription = stringResource(R.string.cd_telegram),
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Spacer(Modifier.height(AppSpacing.md))
    }
}
