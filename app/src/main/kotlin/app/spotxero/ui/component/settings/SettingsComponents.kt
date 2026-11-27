package app.spotxero.ui.component.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.spotxero.R
import app.spotxero.ui.component.ExpressiveSwitch
import app.spotxero.ui.haptics.HapticEvent
import app.spotxero.ui.haptics.LocalAppHaptics
import app.spotxero.ui.haptics.SliderDetents
import app.spotxero.ui.theme.AppSpacing

@Stable
internal class SegmentRegistry {
    private val rows = mutableMapOf<Any, Float>()
    var order by mutableStateOf(emptyList<Any>())
        private set

    fun shape(row: Any): CornerBasedShape {
        val count = order.size
        val index = order.indexOf(row)
        val outer = 20.dp
        val inner = 4.dp
        return when {
            count <= 1 -> RoundedCornerShape(outer)
            index == 0 -> RoundedCornerShape(topStart = outer, topEnd = outer, bottomStart = inner, bottomEnd = inner)
            index == count - 1 -> RoundedCornerShape(topStart = inner, topEnd = inner, bottomStart = outer, bottomEnd = outer)
            index > 0 -> RoundedCornerShape(inner)
            else -> RoundedCornerShape(outer)
        }
    }

    fun place(row: Any, y: Float) {
        if (rows[row] != y) {
            rows[row] = y
            val sorted = rows.entries.sortedBy { it.value }.map { it.key }
            if (sorted != order) {
                order = sorted
            }
        }
    }

    fun remove(row: Any) {
        if (rows.remove(row) != null) {
            order = rows.entries.sortedBy { it.value }.map { it.key }
        }
    }
}

internal val LocalSegmentRegistry = staticCompositionLocalOf<SegmentRegistry?> { null }

@Composable
fun SettingsGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val registry = remember { SegmentRegistry() }
    CompositionLocalProvider(LocalSegmentRegistry provides registry) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            content = content
        )
    }
}

@Composable
fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    painter: Painter? = null,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(role = Role.Button, onClick = onClick)
                } else Modifier
            )
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)
            .semantics { heading() },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (painter != null || icon != null) {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center
            ) {
                if (painter != null) {
                    Icon(
                        painter = painter,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                } else if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        if (onClick != null) {
            Icon(
                painter = painterResource(id = R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    painter: Painter? = null,
    onTitleClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        SectionTitle(
            text = title,
            icon = icon,
            painter = painter,
            onClick = onTitleClick
        )
        content()
    }
}

@Composable
fun SurfaceCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    elevation: Dp = 1.dp,
    shape: CornerBasedShape? = null,
    color: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    content: @Composable () -> Unit
) {
    val group = LocalSegmentRegistry.current
    val row = remember { Any() }
    DisposableEffect(group, row) {
        onDispose { group?.remove(row) }
    }
    val cardShape = shape ?: group?.shape(row) ?: RoundedCornerShape(20.dp)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (group != null) {
                    Modifier.onPlaced { coordinates ->
                        group.place(row, coordinates.positionInParent().y)
                    }
                } else Modifier
            )
            .clip(cardShape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(enabled = enabled, onClick = onClick)
                } else Modifier
            ),
        shape = cardShape,
        color = color,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = elevation,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, borderColor)
    ) {
        CompositionLocalProvider(
            LocalSegmentRegistry provides null,
            content = content
        )
    }
}

@Composable
fun IconTextRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    painter: Painter? = null,
    leadingContent: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    tintIcon: Boolean = true
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingContent != null) {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center
            ) {
                leadingContent()
            }
        } else if (painter != null || icon != null) {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center
            ) {
                val iconTint = if (enabled) {
                    if (tintIcon) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                }
                if (painter != null) {
                    Icon(
                        painter = painter,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = iconTint
                    )
                } else if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = iconTint
                    )
                }
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                )
            }
        }

        trailingContent?.invoke()
    }
}

@Composable
fun SettingsItem(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    valueLabel: String? = null,
    icon: ImageVector? = null,
    painter: Painter? = null,
    leadingContent: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    showChevron: Boolean = true,
    trailingContent: @Composable (() -> Unit)? = null
) {
    SurfaceCard(
        modifier = modifier.alpha(if (enabled) 1f else 0.45f),
        onClick = onClick,
        enabled = enabled
    ) {
        IconTextRow(
            title = title,
            subtitle = subtitle,
            icon = icon,
            painter = painter,
            leadingContent = leadingContent,
            enabled = enabled,
            tintIcon = false,
            trailingContent = {
                if (trailingContent != null) {
                    trailingContent()
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (valueLabel != null) {
                            Text(
                                text = valueLabel,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (showChevron) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_chevron_right),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        )
    }
}

@Composable
fun SettingsSwitchItem(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    painter: Painter? = null,
    leadingContent: @Composable (() -> Unit)? = null,
    enabled: Boolean = true
) {
    val haptics = LocalAppHaptics.current
    val interactionSource = remember { MutableInteractionSource() }
    val group = LocalSegmentRegistry.current
    val row = remember { Any() }
    DisposableEffect(group, row) {
        onDispose { group?.remove(row) }
    }
    val cardShape = group?.shape(row) ?: RoundedCornerShape(20.dp)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f)
            .then(
                if (group != null) {
                    Modifier.onPlaced { coordinates ->
                        group.place(row, coordinates.positionInParent().y)
                    }
                } else Modifier
            )
            .clip(cardShape)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                interactionSource = interactionSource,
                indication = null,
                onValueChange = {
                    haptics.toggle(it)
                    onCheckedChange(it)
                }
            ),
        shape = cardShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 1.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        CompositionLocalProvider(LocalSegmentRegistry provides null) {
            IconTextRow(
                title = title,
                subtitle = subtitle,
                icon = icon,
                painter = painter,
                leadingContent = leadingContent,
                enabled = enabled,
                tintIcon = checked,
                trailingContent = {
                    ExpressiveSwitch(
                        checked = checked,
                        enabled = enabled,
                        onCheckedChange = null
                    )
                }
            )
        }
    }
}

@Composable
fun SliderSettingCard(
    title: String,
    value: Float,
    defaultValue: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueLabel: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
    onValueChange: (Float) -> Unit,
) {
    val haptics = LocalAppHaptics.current
    val detents = remember(range, steps) { SliderDetents(range, steps, value) }
    detents.reset(value)

    SurfaceCard(
        modifier = modifier.alpha(if (enabled) 1f else 0.45f),
        enabled = enabled
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 16.dp,
                    end = 12.dp,
                    top = 16.dp,
                    bottom = 12.dp
                )
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(AppSpacing.xxs))
                    Text(
                        text = valueLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }

                val resetEnabled = enabled && value != defaultValue
                IconButton(
                    onClick = {
                        if (resetEnabled) {
                            haptics.perform(HapticEvent.CONFIRM)
                            onValueChange(defaultValue)
                        }
                    },
                    enabled = resetEnabled
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_replay),
                        contentDescription = stringResource(R.string.settings_reset_to_default),
                        tint = if (resetEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    )
                }
            }

            if (description != null) {
                Spacer(Modifier.height(AppSpacing.xs))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = AppSpacing.xs)
                )
            }

            Spacer(Modifier.height(AppSpacing.xs))

            Slider(
                value = value,
                onValueChange = { newValue ->
                    if (detents.moved(newValue)) {
                        haptics.perform(HapticEvent.SEGMENT_TICK)
                    }
                    onValueChange(newValue)
                },
                valueRange = range,
                steps = steps,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = title }
            )
        }
    }
}

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: CornerBasedShape = RoundedCornerShape(20.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.clip(shape).clickable(onClick = onClick)
                else Modifier.clip(shape)
            ),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 1.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            content = content
        )
    }
}
