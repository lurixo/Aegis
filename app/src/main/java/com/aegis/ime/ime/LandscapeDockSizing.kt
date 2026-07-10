package com.aegis.ime.ime

import kotlin.math.floor
import kotlin.math.roundToInt

internal object LandscapeDockSizing {

    internal data class WidthSpec(
        val surfaceWidth: Int,
        val floating: Boolean,
        val effectiveLeftGutter: Int,
        val requiredSurfaceWidth: Int,
    )

    internal data class HeightSpec(
        val preeditHeight: Int,
        val barHeight: Int,
        val keyboardHeight: Int,
        val bottomExtra: Int,
        val navBottom: Int,
        val rootHeight: Int,
        val constrained: Boolean,
        val emergency: Boolean,
    )

    fun resolveWidth(
        landscape: Boolean,
        slotWidth: Int,
        preferredSurfaceWidth: Int,
        density: Float,
        leftSystemInset: Int,
        rightSystemInset: Int,
    ): WidthSpec {
        val width = slotWidth.coerceAtLeast(0)
        if (!landscape || width == 0) {
            return WidthSpec(width, floating = false, effectiveLeftGutter = 0, requiredSurfaceWidth = width)
        }

        val normalSide = dp(SIDE_PADDING_DP, density)
        val requiredKeyboard = dp(
            ALPHA_BOTTOM_GAP_COUNT * KEY_GAP_DP + ALPHA_BOTTOM_WEIGHT * MIN_ALPHA_KEY_DP,
            density,
        )
        val requiredSurface = requiredKeyboard + normalSide + maxOf(normalSide, rightSystemInset.coerceAtLeast(0))
        val candidate = maxOf(preferredSurfaceWidth.coerceAtLeast(1), requiredSurface).coerceAtMost(width)
        val effectiveGutter = width - candidate - leftSystemInset.coerceAtLeast(0)
        val floating = candidate < width && effectiveGutter >= dp(MIN_HOST_GUTTER_DP, density)
        return if (floating) {
            WidthSpec(candidate, floating = true, effectiveLeftGutter = effectiveGutter, requiredSurfaceWidth = requiredSurface)
        } else {
            WidthSpec(width, floating = false, effectiveLeftGutter = 0, requiredSurfaceWidth = requiredSurface)
        }
    }

    fun resolveHeight(
        availableHeight: Int,
        density: Float,
        rowCount: Int,
        preferredKeyboardHeight: Int,
        fractionalRows: Boolean = false,
        editBarVisible: Boolean,
        navBottom: Int,
    ): HeightSpec {
        val cap = availableHeight.coerceAtLeast(0)
        val rows = rowCount.coerceAtLeast(1)
        val nav = navBottom.coerceIn(0, cap)
        val preferredPreedit = dp(PREEDIT_DP, density)
        val preferredBar = dp(BAR_DP, density)
        val barCount = if (editBarVisible) 2 else 1
        val preferredExtra = dp(BOTTOM_EXTRA_DP, density)
        val minimumFace = dp(if (rows <= 4) MIN_NINE_FACE_DP else MIN_ALPHA_FACE_DP, density)
        val minimumGap = dp(MIN_VERTICAL_GAP_DP, density)
        val minimumKeyboard = if (fractionalRows) {

            rows * minimumFace + rows * 2 * minimumGap
        } else {
            rows * minimumFace + (rows + 1) * minimumGap
        }
        val preferredKeyboard = preferredKeyboardHeight.coerceAtLeast(minimumKeyboard)
        val preferredRoot = nav + preferredPreedit + barCount * preferredBar + preferredKeyboard + preferredExtra

        if (cap >= preferredRoot) {
            return HeightSpec(
                preferredPreedit,
                preferredBar,
                preferredKeyboard,
                preferredExtra,
                nav,
                preferredRoot,
                constrained = false,
                emergency = false,
            )
        }

        val chrome = preferredPreedit + barCount * preferredBar
        val afterChrome = cap - nav - chrome
        if (afterChrome >= 0) {
            val keyboard = minOf(preferredKeyboard, afterChrome).coerceAtLeast(0)
            val extra = minOf(preferredExtra, (afterChrome - keyboard).coerceAtLeast(0))
            return HeightSpec(
                preferredPreedit,
                preferredBar,
                keyboard,
                extra,
                nav,
                nav + chrome + keyboard + extra,
                constrained = true,
                emergency = keyboard < minimumKeyboard,
            )
        }

        val flexible = (cap - nav).coerceAtLeast(0)
        val weightTotal = chrome + minimumKeyboard
        val scale = if (weightTotal > 0) flexible.toFloat() / weightTotal else 0f
        val preedit = floor(preferredPreedit * scale).toInt().coerceAtLeast(0)
        val bar = floor(preferredBar * scale).toInt().coerceAtLeast(0)
        val keyboard = (flexible - preedit - barCount * bar).coerceAtLeast(0)
        return HeightSpec(
            preedit,
            bar,
            keyboard,
            bottomExtra = 0,
            navBottom = nav,
            rootHeight = nav + preedit + barCount * bar + keyboard,
            constrained = true,
            emergency = true,
        )
    }

    fun preferredKeyboardHeight(rowCount: Int, density: Float): Int {
        val rows = rowCount.coerceAtLeast(1)
        val face = if (rows <= 4) PREFERRED_FACE_DP + NINE_FACE_EXTRA_DP else PREFERRED_FACE_DP
        return dp(rows * face + (rows + 1) * KEY_GAP_DP, density)
    }

    fun effectiveVerticalGap(
        keyboardHeight: Int,
        rowCount: Int,
        density: Float,
        fractionalRows: Boolean = false,
    ): Float {
        val height = keyboardHeight.coerceAtLeast(0).toFloat()
        val rows = rowCount.coerceAtLeast(1)
        val preferredGap = KEY_GAP_DP * density
        val minimumGap = MIN_VERTICAL_GAP_DP * density
        val minimumFace = (if (rows <= 4) MIN_NINE_FACE_DP else MIN_ALPHA_FACE_DP) * density
        val minimumKeyboard = if (fractionalRows) {
            rows * minimumFace + rows * 2 * minimumGap
        } else {
            rows * minimumFace + (rows + 1) * minimumGap
        }
        if (height >= minimumKeyboard) {
            val availableGap = if (fractionalRows) {
                (height - rows * minimumFace) / (rows * 2)
            } else {
                (height - rows * minimumFace) / (rows + 1)
            }
            return availableGap.coerceIn(minimumGap, preferredGap)
        }

        val emergencyDivisor = if (fractionalRows) rows * 4f else (rows + 1) * 2f
        return minOf(minimumGap, height / emergencyDivisor).coerceAtLeast(0f)
    }

    private fun dp(value: Int, density: Float): Int = (value * density).roundToInt()
    private fun dp(value: Float, density: Float): Int = (value * density).roundToInt()

    private const val SIDE_PADDING_DP = 4
    private const val KEY_GAP_DP = 6
    private const val MIN_ALPHA_KEY_DP = 20f
    private const val ALPHA_BOTTOM_GAP_COUNT = 8
    private const val ALPHA_BOTTOM_WEIGHT = 11.6f
    private const val MIN_HOST_GUTTER_DP = 48

    private const val PREEDIT_DP = 26
    private const val BAR_DP = 44
    private const val BOTTOM_EXTRA_DP = 28
    private const val PREFERRED_FACE_DP = 52
    private const val NINE_FACE_EXTRA_DP = 2
    private const val MIN_ALPHA_FACE_DP = 28
    private const val MIN_NINE_FACE_DP = 32
    private const val MIN_VERTICAL_GAP_DP = 2
}
