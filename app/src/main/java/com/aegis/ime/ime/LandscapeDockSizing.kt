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

    fun preferredKeyboardHeight(rowCount: Int, density: Float): Int {
        val rows = rowCount.coerceAtLeast(1)
        val face = if (rows <= 4) PREFERRED_FACE_DP + NINE_FACE_EXTRA_DP else PREFERRED_FACE_DP
        return dp(rows * face + (rows + 1) * KEY_GAP_DP, density)
    }

    fun effectiveVerticalGap(
        keyboardHeight: Int,
        rowCount: Int,
        density: Float,
        fractionalRows: Boolean,
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

    private const val PREFERRED_FACE_DP = 52
    private const val NINE_FACE_EXTRA_DP = 2
    private const val MIN_ALPHA_FACE_DP = 28
    private const val MIN_NINE_FACE_DP = 32
    private const val MIN_VERTICAL_GAP_DP = 2
}
