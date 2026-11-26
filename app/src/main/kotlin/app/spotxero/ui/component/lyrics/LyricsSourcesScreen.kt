package app.spotxero.ui.component.lyrics

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.spotxero.R
import app.spotxero.ui.component.ExpressiveSwitch
import app.spotxero.ui.component.settings.SettingsGroup
import app.spotxero.ui.component.settings.SettingsItem
import app.spotxero.ui.component.settings.SettingsSection
import app.spotxero.ui.component.settings.SettingsSwitchItem
import app.spotxero.ui.haptics.HapticEvent
import app.spotxero.ui.haptics.LocalAppHaptics
import app.spotxero.ui.theme.AppCardDefaults
import app.spotxero.ui.theme.AppSpacing
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun LyricsSourcesScreen(
    isExternalLyricsEnabled: Boolean,
    isForceExternalLyricsEnabled: Boolean,
    lyricsProvidersOrder: List<String>,
    isSpicylyricsEnabled: Boolean,
    isBetterlyricsEnabled: Boolean,
    isLrcredEnabled: Boolean,
    isLrclibEnabled: Boolean,
    isKugouEnabled: Boolean,
    isNeteaseEnabled: Boolean,
    isGeniusEnabled: Boolean,
    onExternalLyricsToggle: (Boolean) -> Unit,
    onForceExternalLyricsToggle: (Boolean) -> Unit,
    onOpenAiLyricsTranslation: () -> Unit = {},
    onProviderToggle: (id: String, enabled: Boolean) -> Unit,
    onReorderProviders: (newOrder: List<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        contentPadding = PaddingValues(
            start = AppSpacing.md,
            end = AppSpacing.md,
            top = AppSpacing.xs,
            bottom = 88.dp
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier.fillMaxSize()
    ) {
        item(key = "master_external_lyrics_section") {
            SettingsSection(
                title = stringResource(R.string.category_lyrics)
            ) {
                SettingsGroup {
                    SettingsSwitchItem(
                        title = stringResource(R.string.pref_external_lyrics_title),
                        subtitle = stringResource(R.string.pref_external_lyrics_desc),
                        painter = painterResource(id = R.drawable.ic_lyrics),
                        checked = isExternalLyricsEnabled,
                        onCheckedChange = onExternalLyricsToggle
                    )
                    SettingsSwitchItem(
                        title = stringResource(R.string.pref_force_external_lyrics_title),
                        subtitle = stringResource(R.string.pref_force_external_lyrics_desc),
                        painter = painterResource(id = R.drawable.ic_rule),
                        checked = isForceExternalLyricsEnabled,
                        enabled = isExternalLyricsEnabled,
                        onCheckedChange = { if (isExternalLyricsEnabled) onForceExternalLyricsToggle(it) }
                    )
                    SettingsItem(
                        title = stringResource(R.string.title_ai_lyrics_translation),
                        subtitle = stringResource(R.string.ai_lyrics_translation_desc),
                        painter = painterResource(id = R.drawable.ic_translate),
                        onClick = onOpenAiLyricsTranslation
                    )
                }
            }
        }

        item(key = "providers_section") {
            SettingsSection(
                title = stringResource(R.string.lyrics_providers_header)
            ) {
                LyricsProviderOrderCard(
                    providersOrder = lyricsProvidersOrder,
                    isSpicylyricsEnabled = isSpicylyricsEnabled,
                    isBetterlyricsEnabled = isBetterlyricsEnabled,
                    isLrcredEnabled = isLrcredEnabled,
                    isLrclibEnabled = isLrclibEnabled,
                    isKugouEnabled = isKugouEnabled,
                    isNeteaseEnabled = isNeteaseEnabled,
                    isGeniusEnabled = isGeniusEnabled,
                    enabled = isExternalLyricsEnabled,
                    onProviderToggle = onProviderToggle,
                    onReorderProviders = onReorderProviders
                )
            }
        }
    }
}

@Composable
fun ReorderMenu(
    index: Int,
    totalCount: Int,
    onMove: (fromIndex: Int, toIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onDragStart: () -> Unit = {},
    onDrag: (Float) -> Unit = {},
    onDragEnd: () -> Unit = {}
) {
    var expanded by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .then(
                if (enabled) {
                    Modifier.pointerInput(totalCount) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var isDragging = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null || !change.pressed) {
                                    if (isDragging) {
                                        onDragEnd()
                                    } else {
                                        expanded = true
                                    }
                                    break
                                }

                                val yDiff = change.position.y - down.position.y
                                if (!isDragging && abs(yDiff) > viewConfiguration.touchSlop) {
                                    isDragging = true
                                    onDragStart()
                                }

                                if (isDragging) {
                                    val dragAmount = change.positionChange().y
                                    change.consume()
                                    onDrag(dragAmount)
                                }
                            }
                        }
                    }
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_drag_indicator),
            contentDescription = stringResource(R.string.cd_reorder_menu),
            tint = if (enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            },
            modifier = Modifier.size(24.dp)
        )

        val haptics = LocalAppHaptics.current
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_move_to_top)) },
                enabled = enabled && index > 0,
                onClick = {
                    haptics.perform(HapticEvent.CONFIRM)
                    expanded = false
                    onMove(index, 0)
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_move_up)) },
                enabled = enabled && index > 0,
                onClick = {
                    haptics.perform(HapticEvent.CONFIRM)
                    expanded = false
                    onMove(index, index - 1)
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_move_down)) },
                enabled = enabled && index < totalCount - 1,
                onClick = {
                    haptics.perform(HapticEvent.CONFIRM)
                    expanded = false
                    onMove(index, index + 1)
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_move_to_bottom)) },
                enabled = enabled && index < totalCount - 1,
                onClick = {
                    haptics.perform(HapticEvent.CONFIRM)
                    expanded = false
                    onMove(index, totalCount - 1)
                }
            )
        }
    }
}

@Composable
private fun LyricsProviderOrderCard(
    providersOrder: List<String>,
    isSpicylyricsEnabled: Boolean,
    isBetterlyricsEnabled: Boolean,
    isLrcredEnabled: Boolean,
    isLrclibEnabled: Boolean,
    isKugouEnabled: Boolean,
    isNeteaseEnabled: Boolean,
    isGeniusEnabled: Boolean,
    enabled: Boolean,
    onProviderToggle: (id: String, enabled: Boolean) -> Unit,
    onReorderProviders: (newOrder: List<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    var currentList by remember(providersOrder) { mutableStateOf(providersOrder) }
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var itemHeightPx by remember { mutableFloatStateOf(0f) }
    val haptics = LocalAppHaptics.current
    val cardShape = RoundedCornerShape(20.dp)

    val onMove: (fromIndex: Int, toIndex: Int) -> Unit = { from, to ->
        if (from != to && from in currentList.indices && to in currentList.indices) {
            val mutable = currentList.toMutableList()
            val item = mutable.removeAt(from)
            mutable.add(to, item)
            currentList = mutable
            onReorderProviders(mutable)
        }
    }

    Card(
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = AppCardDefaults.cardElevation,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = modifier
            .fillMaxWidth()
            .clip(cardShape)
            .alpha(if (enabled) 1f else 0.45f)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            currentList.forEachIndexed { index, providerId ->
                if (index > 0) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(horizontal = AppSpacing.md)
                    )
                }

                val isItemDragging = draggingIndex == index
                val isChecked = when (providerId) {
                    "spicylyrics" -> isSpicylyricsEnabled
                    "betterlyrics" -> isBetterlyricsEnabled
                    "lrcred" -> isLrcredEnabled
                    "lrclib" -> isLrclibEnabled
                    "kugou" -> isKugouEnabled
                    "netease" -> isNeteaseEnabled
                    "genius" -> isGeniusEnabled
                    else -> true
                }

                val title = when (providerId) {
                    "spicylyrics" -> stringResource(R.string.provider_spicylyrics_title)
                    "betterlyrics" -> stringResource(R.string.provider_betterlyrics_title)
                    "lrcred" -> stringResource(R.string.provider_lrcred_title)
                    "lrclib" -> stringResource(R.string.provider_lrclib_title)
                    "kugou" -> stringResource(R.string.provider_kugou_title)
                    "netease" -> stringResource(R.string.provider_netease_title)
                    "genius" -> stringResource(R.string.provider_genius_title)
                    else -> providerId.replaceFirstChar { it.uppercase() }
                }

                val itemElevation by animateFloatAsState(
                    targetValue = if (isItemDragging) 8f else 0f,
                    animationSpec = tween(150),
                    label = "RowElevation"
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(if (isItemDragging) 2f else 1f)
                        .graphicsLayer {
                            if (isItemDragging) {
                                translationY = dragOffsetY
                                shadowElevation = itemElevation
                            }
                        }
                        .onGloballyPositioned { coordinates ->
                            if (itemHeightPx == 0f) {
                                itemHeightPx = coordinates.size.height.toFloat()
                            }
                        }
                        .clickable(enabled = enabled) {
                            haptics.toggle(!isChecked)
                            onProviderToggle(providerId, !isChecked)
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                ) {
                    ReorderMenu(
                        index = index,
                        totalCount = currentList.size,
                        onMove = onMove,
                        enabled = enabled,
                        onDragStart = {
                            haptics.perform(HapticEvent.DRAG_START)
                            draggingIndex = index
                            dragOffsetY = 0f
                        },
                        onDrag = { delta ->
                            dragOffsetY += delta
                            val stepHeight = if (itemHeightPx > 0f) itemHeightPx else 100f
                            val targetIndex = (draggingIndex + (dragOffsetY / stepHeight).roundToInt())
                                .coerceIn(0, currentList.lastIndex)
                            if (targetIndex != draggingIndex) {
                                haptics.perform(HapticEvent.DRAG_SNAP)
                                val mutable = currentList.toMutableList()
                                val item = mutable.removeAt(draggingIndex)
                                mutable.add(targetIndex, item)
                                currentList = mutable
                                dragOffsetY -= (targetIndex - draggingIndex) * stepHeight
                                draggingIndex = targetIndex
                            }
                        },
                        onDragEnd = {
                            if (draggingIndex != -1) {
                                haptics.perform(HapticEvent.CONFIRM)
                                draggingIndex = -1
                                dragOffsetY = 0f
                                onReorderProviders(currentList)
                            }
                        }
                    )

                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )

                    ExpressiveSwitch(
                        checked = isChecked,
                        onCheckedChange = { onProviderToggle(providerId, it) },
                        enabled = enabled,
                    )
                }
            }
        }
    }
}
