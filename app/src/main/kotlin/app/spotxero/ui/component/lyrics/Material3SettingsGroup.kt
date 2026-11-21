package app.spotxero.ui.component.lyrics

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.spotxero.ui.component.ExpressiveSwitch
import app.spotxero.ui.haptics.rememberAppHaptics
import app.spotxero.ui.theme.AppIcons

fun ColorScheme.settingsRowContainerColor(pressed: Boolean = false): Color {
    val container = if (pressed) surfaceContainerHighest else surfaceContainerHigh
    return if (container == background) surfaceColorAtElevation(if (pressed) 12.dp else 6.dp) else container
}

@Composable
fun segmentedGroupShape(
    isFirst: Boolean,
    isLast: Boolean,
    pressed: Boolean,
    outer: Dp = 20.dp,
    inner: Dp = 5.dp,
): Shape {
    val spec = tween<Dp>(200)
    val top by animateDpAsState(if (isFirst || pressed) outer else inner, spec, label = "segmentTop")
    val bottom by animateDpAsState(if (isLast || pressed) outer else inner, spec, label = "segmentBottom")
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

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
                    fontWeight = FontWeight.Bold,
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

        val haptics = rememberAppHaptics()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items.forEachIndexed { index, item ->
                MetrolistSettingsItemRow(
                    item = item,
                    first = index == 0,
                    last = index == items.lastIndex,
                    haptics = haptics
                )
            }
        }
    }
}

@Composable
private fun MetrolistSettingsItemRow(
    item: MetrolistSettingsItem,
    first: Boolean,
    last: Boolean,
    haptics: app.spotxero.ui.haptics.AppHaptics
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val shape = segmentedGroupShape(first, last, isPressed)
    val backgroundColor by animateColorAsState(
        targetValue = MaterialTheme.colorScheme.settingsRowContainerColor(isPressed),
        label = "backgroundColor"
    )

    if (item.customContent != null) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(),
            shape = shape,
            color = MaterialTheme.colorScheme.settingsRowContainerColor(),
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
                .background(backgroundColor)
                .then(
                    if (item.isSwitch) {
                        Modifier.toggleable(
                            value = item.checked,
                            enabled = item.enabled && item.onCheckedChange != null,
                            role = Role.Switch,
                            interactionSource = interactionSource,
                            indication = null,
                            onValueChange = {
                                haptics.toggle(it)
                                item.onCheckedChange?.invoke(it)
                            }
                        )
                    } else {
                        Modifier.clickable(
                            enabled = item.enabled && item.onClick != null,
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = {
                                item.onClick?.invoke()
                            }
                        )
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
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (item.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                )
                item.description?.let { desc ->
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (item.enabled) 1f else 0.38f),
                    )
                }
            }

            Spacer(Modifier.width(16.dp))

            if (item.isSwitch) {
                ExpressiveSwitch(
                    checked = item.checked,
                    onCheckedChange = null,
                    enabled = item.enabled,
                    modifier = Modifier.clearAndSetSemantics { }
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
