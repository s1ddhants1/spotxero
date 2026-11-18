package app.spotxero.ui.component.lyrics

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.spotxero.ui.theme.AppIcons
import app.spotxero.ui.theme.appSwitchColors

fun connectedShape(
    first: Boolean,
    last: Boolean,
    outer: Dp = 20.dp,
    inner: Dp = 5.dp,
): RoundedCornerShape = RoundedCornerShape(
    topStart = if (first) outer else inner,
    topEnd = if (first) outer else inner,
    bottomStart = if (last) outer else inner,
    bottomEnd = if (last) outer else inner,
)

data class MetrolistSettingsItem(
    val title: String,
    val description: String? = null,
    val valueLabel: String? = null,
    val isSwitch: Boolean = false,
    val checked: Boolean = false,
    val onCheckedChange: ((Boolean) -> Unit)? = null,
    val showChevron: Boolean = true,
    val enabled: Boolean = true,
    val customContent: (@Composable () -> Unit)? = null,
    val onClick: (() -> Unit)? = null
)

@Composable
fun MetrolistSettingsGroup(
    title: String? = null,
    description: String? = null,
    items: List<MetrolistSettingsItem>,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 16.dp,
    topPadding: Dp = 24.dp,
    enabled: Boolean = true,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.6f)
            .padding(top = topPadding)
    ) {
        if (!title.isNullOrBlank()) {
            Column(
                modifier = Modifier.padding(start = horizontalPadding, end = horizontalPadding * 2, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.semantics { heading() },
                )
                description?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        val haptics = app.spotxero.ui.haptics.rememberAppHaptics()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items.forEachIndexed { index, item ->
                val first = index == 0
                val last = index == items.lastIndex
                val shape = connectedShape(first, last, outer = 20.dp, inner = 5.dp)

                if (item.customContent != null) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateContentSize(),
                        shape = shape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shadowElevation = 0.dp,
                        tonalElevation = 0.dp
                    ) {
                        Box(Modifier.alpha(if (item.enabled) 1f else 0.38f)) {
                            item.customContent.invoke()
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 72.dp)
                            .clip(shape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .then(
                                if (item.isSwitch) {
                                    Modifier.clickable(enabled = item.enabled && item.onCheckedChange != null) {
                                        val next = !item.checked
                                        haptics.toggle(next)
                                        item.onCheckedChange?.invoke(next)
                                    }
                                } else {
                                    Modifier.clickable(enabled = item.enabled && item.onClick != null) {
                                        haptics.perform(app.spotxero.ui.haptics.HapticEvent.PRESS)
                                        item.onClick?.invoke()
                                    }
                                }
                            )
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (item.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                            )
                            item.description?.let { desc ->
                                Text(
                                    text = desc,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (item.enabled) 1f else 0.38f),
                                )
                            }
                        }

                        Spacer(Modifier.width(16.dp))

                        if (item.isSwitch) {
                            app.spotxero.ui.component.ExpressiveSwitch(
                                checked = item.checked,
                                onCheckedChange = null,
                                enabled = item.enabled,
                            )
                        } else {
                            item.valueLabel?.let { label ->
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (item.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                    textAlign = TextAlign.End,
                                    modifier = Modifier.widthIn(max = 130.dp),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            if (item.showChevron) {
                                Icon(
                                    imageVector = AppIcons.ChevronRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (item.enabled) 1f else 0.38f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
