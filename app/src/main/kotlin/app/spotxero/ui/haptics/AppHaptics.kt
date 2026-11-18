package app.spotxero.ui.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
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
    val context = LocalContext.current.applicationContext
    return remember(view, context) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator ?: (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

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

                view.isHapticFeedbackEnabled = true
                val flags = HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING or HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                runCatching {
                    ViewCompat.performHapticFeedback(view, feedback, flags)
                }

                if (vibrator != null && vibrator.hasVibrator()) {
                    performVibratorFeedback(vibrator, event)
                }
            }
        )
    }
}

private fun performVibratorFeedback(vibrator: Vibrator, event: HapticEvent) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val effectId = when (event) {
            HapticEvent.PRESS, HapticEvent.TOGGLE_ON, HapticEvent.CONFIRM, HapticEvent.DICE_ROLL ->
                VibrationEffect.EFFECT_CLICK
            HapticEvent.TOGGLE_OFF, HapticEvent.DRAG_SNAP, HapticEvent.SEGMENT_TICK ->
                VibrationEffect.EFFECT_TICK
            HapticEvent.REJECT ->
                VibrationEffect.EFFECT_DOUBLE_CLICK
            HapticEvent.DRAG_START ->
                VibrationEffect.EFFECT_CLICK
            HapticEvent.LONG_PRESS ->
                VibrationEffect.EFFECT_HEAVY_CLICK
        }
        val effect = runCatching {
            VibrationEffect.createPredefined(effectId)
        }.getOrNull() ?: createOneShotFallback(event)

        vibrateWithUsage(vibrator, effect)
    } else {
        val effect = createOneShotFallback(event)
        vibrateWithUsage(vibrator, effect)
    }
}

private fun createOneShotFallback(event: HapticEvent): VibrationEffect {
    return when (event) {
        HapticEvent.PRESS, HapticEvent.TOGGLE_ON, HapticEvent.CONFIRM, HapticEvent.DRAG_START, HapticEvent.DICE_ROLL ->
            VibrationEffect.createOneShot(15, 160)
        HapticEvent.TOGGLE_OFF, HapticEvent.DRAG_SNAP, HapticEvent.SEGMENT_TICK ->
            VibrationEffect.createOneShot(10, 100)
        HapticEvent.REJECT ->
            VibrationEffect.createWaveform(longArrayOf(0, 25, 40, 25), intArrayOf(0, 180, 0, 180), -1)
        HapticEvent.LONG_PRESS ->
            VibrationEffect.createOneShot(40, 220)
    }
}

private fun vibrateWithUsage(vibrator: Vibrator, effect: VibrationEffect) {
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val attrs = VibrationAttributes.Builder()
                .setUsage(VibrationAttributes.USAGE_TOUCH)
                .build()
            vibrator.vibrate(effect, attrs)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(effect)
        }
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
