// SPDX-License-Identifier: GPL-3.0-only
//
// Copyright (C) 2026 lurixo
//
// This program is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, version 3.
//
// This program is distributed in the hope that it will be useful, but WITHOUT ANY
// WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
// PARTICULAR PURPOSE. See the GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License along with
// this program. If not, see <https://www.gnu.org/licenses/>.

package com.aegis.ime.ime

import android.annotation.SuppressLint
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.aegis.ime.R
import kotlin.math.roundToInt

internal const val KEY_HAPTIC_STRENGTH_DEFAULT = 80f
internal fun keyHapticStrength(value: Float): Float =
    if (value.isFinite()) value.coerceIn(0f, 100f) else KEY_HAPTIC_STRENGTH_DEFAULT

internal enum class KeyHaptic(
    val value: String,
    val labelRes: Int,
    val descriptionRes: Int,
    private val timings: LongArray,
    private val levels: FloatArray,
) {
    SYSTEM("system", R.string.key_haptic_system, R.string.key_haptic_system_description,
        longArrayOf(), floatArrayOf()),
    SOFT("soft", R.string.key_haptic_soft, R.string.key_haptic_soft_description,
        longArrayOf(1, 10, 12, 16, 16, 12, 10, 1), floatArrayOf(0f, .2f, .5f, .8f, 1f, .6f, .25f, 0f)),
    CRISP("crisp", R.string.key_haptic_crisp, R.string.key_haptic_crisp_description,
        longArrayOf(1, 3, 28, 5, 1), floatArrayOf(0f, .6f, 1f, .35f, 0f)),
    FIRM("firm", R.string.key_haptic_firm, R.string.key_haptic_firm_description,
        longArrayOf(1, 5, 65, 8, 1), floatArrayOf(0f, .8f, 1f, .45f, 0f)),
    TICK("tick", R.string.key_haptic_tick, R.string.key_haptic_tick_description,
        longArrayOf(1, 10, 9, 10, 9, 10, 1), floatArrayOf(0f, 1f, 0f, .85f, 0f, 1f, 0f)),
    THUD("thud", R.string.key_haptic_thud, R.string.key_haptic_thud_description,
        longArrayOf(1, 24, 18, 16, 10, 9, 1), floatArrayOf(0f, 1f, 0f, .6f, 0f, .3f, 0f)),
    DOUBLE("double", R.string.key_haptic_double, R.string.key_haptic_double_description,
        longArrayOf(1, 24, 52, 24, 1), floatArrayOf(0f, 1f, 0f, 1f, 0f));

    fun effect(vibrator: Vibrator, strength: Float, feedback: Int = HapticFeedbackConstants.KEYBOARD_TAP): VibrationEffect? {
        if (this == SYSTEM) return systemEffect
        val amount = keyHapticStrength(strength) / 100f
        if (amount == 0f) return null
        val amplitudeControl = vibrator.hasAmplitudeControl()
        val frequent = feedback == HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
        val durations = if (frequent) longArrayOf(1, 6, 1) else timings
        val amplitudes = if (frequent) floatArrayOf(0f, .45f, 0f) else levels
        val durationScale = (0.25f + 0.75f * amount) * if (feedback == HapticFeedbackConstants.LONG_PRESS) 1.2f else 1f
        return VibrationEffect.createWaveform(
            LongArray(durations.size) { index ->
                if (amplitudes[index] == 0f) durations[index] else {
                    val scale = if (amplitudeControl) durationScale else amount * amplitudes[index] * durationScale
                    (durations[index] * scale).roundToInt().coerceAtLeast(1).toLong()
                }
            },
            IntArray(amplitudes.size) { index ->
                if (amplitudes[index] == 0f) 0 else if (amplitudeControl) {
                    (255f * amount * amplitudes[index]).roundToInt().coerceIn(1, 255)
                } else VibrationEffect.DEFAULT_AMPLITUDE
            },
            -1,
        )
    }

    companion object {
        private val systemEffect by lazy { VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK) }
        fun of(value: String): KeyHaptic = entries.firstOrNull { it.value == value } ?: CRISP
    }
}

private fun View.feedbackHost(): InputView? {
    var node: android.view.ViewParent? = this as? InputView ?: parent
    while (node != null) {
        if (node is InputView) return node
        node = node.parent
    }
    return null
}

internal fun View.playImeKeyFeedback(hapticsEnabled: Boolean) {
    feedbackHost()?.playKeySound()
    performImeKeyHaptic(hapticsEnabled)
}

internal fun View.playImeTapFeedback() {
    if (!isEnabled) return
    val host = feedbackHost()
    var node: View? = this
    var standaloneHaptics = false
    while (node != null) {
        if (node is KeyHapticsAware) { standaloneHaptics = node.hapticEnabled; break }
        node = node.parent as? View
    }
    playImeKeyFeedback(host?.keyHapticsEnabled ?: standaloneHaptics)
}

internal fun View.bindImeTapFeedback(source: View = this) {
    isSoundEffectsEnabled = false
    setOnTouchListener { _, event ->
        if (isEnabled && event.actionMasked == MotionEvent.ACTION_DOWN) source.playImeTapFeedback()
        false
    }
}

internal fun View.performImeKeyHaptic(
    enabled: Boolean,
    feedback: Int = HapticFeedbackConstants.KEYBOARD_TAP,
    style: KeyHaptic = imeKeyHapticStyle(),
    strength: Float = imeKeyHapticStrength(),
) {
    if (!enabled || !isHapticFeedbackEnabled || (style != KeyHaptic.SYSTEM && keyHapticStrength(strength) == 0f)) return
    val customAllowed = Build.VERSION.SDK_INT >= 36 ||
        (Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0 &&
            Settings.System.getInt(context.contentResolver, "keyboard_vibration_enabled", 1) != 0)
    val vibrator = context.getSystemService(Vibrator::class.java)
    val frequent = feedback == HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
    if (style != KeyHaptic.SYSTEM && customAllowed && vibrator?.hasVibrator() == true) {
        style.effect(vibrator, strength, feedback)?.let { vibrator.vibrate(it, ImeKeyHaptics.attributes) }
        return
    }
    val request = if (feedback == HapticFeedbackConstants.LONG_PRESS) HapticFeedbackConstants.KEYBOARD_TAP else feedback
    if (performHapticFeedback(request)) return
    if (!customAllowed || frequent || vibrator?.hasVibrator() != true) return
    style.effect(vibrator, strength)?.let { vibrator.vibrate(it, ImeKeyHaptics.attributes) }
}

internal fun View.previewImeKeyHaptic(style: KeyHaptic, strength: Float = KEY_HAPTIC_STRENGTH_DEFAULT) {
    val vibrator = context.getSystemService(Vibrator::class.java) ?: return
    if (!vibrator.hasVibrator()) return
    val effect = style.effect(vibrator, strength)
    if (effect == null) vibrator.cancel() else vibrator.vibrate(effect, ImeKeyHaptics.attributes)
}

private fun View.imeKeyHapticStrength(): Float {
    var ancestor: android.view.ViewParent? = this as? InputView ?: parent
    while (ancestor != null) {
        if (ancestor is InputView) return ancestor.keyHapticStrength
        ancestor = ancestor.parent
    }
    return KEY_HAPTIC_STRENGTH_DEFAULT
}

private fun View.imeKeyHapticStyle(): KeyHaptic {
    var ancestor: android.view.ViewParent? = this as? InputView ?: parent
    while (ancestor != null) {
        if (ancestor is InputView) return ancestor.keyHapticStyle
        ancestor = ancestor.parent
    }
    return KeyHaptic.CRISP
}

private object ImeKeyHaptics {
    private const val IME_FEEDBACK_USAGE = 0x52
    private val touchAttributes = VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH)
    @SuppressLint("WrongConstant")
    private val inputMethodAttributes = VibrationAttributes.createForUsage(IME_FEEDBACK_USAGE)
    val attributes: VibrationAttributes
        get() = if (Build.VERSION.SDK_INT >= 36) inputMethodAttributes else touchAttributes
}
