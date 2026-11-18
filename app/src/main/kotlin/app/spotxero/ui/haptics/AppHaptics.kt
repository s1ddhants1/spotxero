package app.spotxero.ui.haptics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.core.view.HapticFeedbackConstantsCompat
import androidx.core.view.ViewCompat
import app.spotxero.core.PreferencesManager

enum class HapticEvent {
    PRESS,
    TOGGLE_ON,
    TOGGLE_OFF,
    CONFIRM,
    REJECT,
    DRAG_START,
    DRAG_SNAP,
    LONG_PRESS,
    SEGMENT_TICK,
    DICE_ROLL,
}

class AppHaptics(
    private val enabled: () -> Boolean,
    private val platformFeedback: (HapticEvent) -> Unit,
) {
    fun perform(event: HapticEvent) {
        if (enabled()) platformFeedback(event)
    }

    fun toggle(checked: Boolean) = perform(if (checked) HapticEvent.TOGGLE_ON else HapticEvent.TOGGLE_OFF)
}

val LocalAppHaptics = staticCompositionLocalOf<AppHaptics> {
    error("No AppHaptics provided")
}

@Composable
fun rememberAppHaptics(): AppHaptics {
    val view = LocalView.current
    return remember(view) {
        AppHaptics(
            enabled = { PreferencesManager.enableHapticFeedback },
            platformFeedback = { event ->
                val feedback = when (event) {
                    HapticEvent.PRESS -> HapticFeedbackConstantsCompat.VIRTUAL_KEY
                    HapticEvent.TOGGLE_ON -> HapticFeedbackConstantsCompat.TOGGLE_ON
                    HapticEvent.TOGGLE_OFF -> HapticFeedbackConstantsCompat.TOGGLE_OFF
                    HapticEvent.CONFIRM -> HapticFeedbackConstantsCompat.CONFIRM
                    HapticEvent.REJECT -> HapticFeedbackConstantsCompat.REJECT
                    HapticEvent.DRAG_START -> HapticFeedbackConstantsCompat.DRAG_START
                    HapticEvent.DRAG_SNAP -> HapticFeedbackConstantsCompat.GESTURE_THRESHOLD_ACTIVATE
                    HapticEvent.LONG_PRESS -> HapticFeedbackConstantsCompat.LONG_PRESS
                    HapticEvent.SEGMENT_TICK -> HapticFeedbackConstantsCompat.SEGMENT_TICK
                    HapticEvent.DICE_ROLL -> HapticFeedbackConstantsCompat.CONTEXT_CLICK
                }
                ViewCompat.performHapticFeedback(view, feedback)
            }
        )
    }
}

@Composable
fun AppHapticsProvider(content: @Composable () -> Unit) {
    val haptics = rememberAppHaptics()
    val composeHaptics = remember(haptics) {
        object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                haptics.perform(hapticFeedbackType.toHapticEvent())
            }
        }
    }
    CompositionLocalProvider(
        LocalAppHaptics provides haptics,
        LocalHapticFeedback provides composeHaptics,
        content = content
    )
}

internal fun HapticFeedbackType.toHapticEvent(): HapticEvent =
    when (this) {
        HapticFeedbackType.LongPress -> HapticEvent.LONG_PRESS
        HapticFeedbackType.ToggleOn -> HapticEvent.TOGGLE_ON
        HapticFeedbackType.ToggleOff -> HapticEvent.TOGGLE_OFF
        HapticFeedbackType.Confirm -> HapticEvent.CONFIRM
        HapticFeedbackType.Reject -> HapticEvent.REJECT
        HapticFeedbackType.GestureThresholdActivate -> HapticEvent.DRAG_SNAP
        HapticFeedbackType.TextHandleMove,
        HapticFeedbackType.SegmentTick,
        HapticFeedbackType.SegmentFrequentTick -> HapticEvent.SEGMENT_TICK
        else -> HapticEvent.PRESS
    }
