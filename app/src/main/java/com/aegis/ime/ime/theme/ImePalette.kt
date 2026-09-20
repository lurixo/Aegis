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

package com.aegis.ime.ime.theme

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils

data class ImePalette(
    val keyboardBg: Int,
    val keySurface: Int,
    val functionSurface: Int,
    val keyLabel: Int,
    val keyLabelSecondary: Int,
    val keyHint: Int,
    val keySub: Int,
    val accentBottom: Int,
    val accentLabel: Int,
    val candidateFirst: Int,
    val candidateText: Int,
    val lockedReading: Int,
    val preeditText: Int,
    val separator: Int,
    val gridLine: Int,
    val panelBg: Int,
    val chipBg: Int,
    val chipText: Int,
    val icon: Int,
    val deletable: Int,
    val errorContainer: Int,
    val onErrorContainer: Int,
    val disabled: Int,
    val scrim: Int,
    val shadow: Int,
    val floatSurface: Int,
) {
    companion object {
        val STATIC_LIGHT = ImePalette(
            keyboardBg = 0xFFDCE2EA.toInt(),
            keySurface = 0xFFF4F7FB.toInt(),
            functionSurface = 0xFFB7BEC7.toInt(),
            keyLabel = 0xFF16181B.toInt(),
            keyLabelSecondary = 0xFF3C4A54.toInt(),
            keyHint = 0xFF46525C.toInt(),
            keySub = 0xFF8A959C.toInt(),
            accentBottom = 0xFF6750A4.toInt(),
            accentLabel = 0xFFFFFFFF.toInt(),
            candidateFirst = 0xFF6750A4.toInt(),
            candidateText = 0xFF16181B.toInt(),
            lockedReading = 0xFF21005D.toInt(),
            preeditText = 0xFF33639C.toInt(),
            separator = 0xFFBCC5D1.toInt(),
            gridLine = 0xFF000000.toInt(),
            panelBg = 0xFFF4F7FB.toInt(),
            chipBg = 0xFFDCE2EA.toInt(),
            chipText = 0xFF16181B.toInt(),
            icon = 0xFF3C4A54.toInt(),
            deletable = 0xFFD32F2F.toInt(),
            errorContainer = 0xFFF9DEDC.toInt(),
            onErrorContainer = 0xFF410E0B.toInt(),
            disabled = 0xFF9AA5AC.toInt(),
            scrim = 0x66000000,
            shadow = 0x22000000,
            floatSurface = 0xFFFAFCFE.toInt(),
        )

        val STATIC_DARK = ImePalette(
            keyboardBg = 0xFF24282D.toInt(),
            keySurface = 0xFF525A64.toInt(),
            functionSurface = 0xFF383E45.toInt(),
            keyLabel = 0xFFE4E6EA.toInt(),
            keyLabelSecondary = 0xFFC3C9D0.toInt(),
            keyHint = 0xFF8F979F.toInt(),
            keySub = 0xFF79828B.toInt(),
            accentBottom = 0xFFD0BCFF.toInt(),
            accentLabel = 0xFF381E72.toInt(),
            candidateFirst = 0xFFD0BCFF.toInt(),
            candidateText = 0xFFE4E6EA.toInt(),
            lockedReading = 0xFFEADDFF.toInt(),
            preeditText = 0xFF9FC9FF.toInt(),
            separator = 0xFF3E444B.toInt(),
            gridLine = 0xFFA5AEB9.toInt(),
            panelBg = 0xFF525A64.toInt(),
            chipBg = 0xFF262B30.toInt(),
            chipText = 0xFFE4E6EA.toInt(),
            icon = 0xFFB3BAC2.toInt(),
            deletable = 0xFFFFB4AB.toInt(),
            errorContainer = 0xFF8C1D18.toInt(),
            onErrorContainer = 0xFFF9DEDC.toInt(),
            disabled = 0xFF5D646B.toInt(),
            scrim = 0x99000000.toInt(),
            shadow = 0x40000000,
            floatSurface = 0xFF5D6672.toInt(),
        )

        fun from(ctx: Context, dark: Boolean): ImePalette = runCatching {
            val cs: ColorScheme = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            ImePalette(
                keyboardBg = cs.surfaceContainer.toArgb(),
                keySurface = if (dark) brightFace(cs) else cs.surfaceContainerLowest.toArgb(),
                functionSurface = if (dark) cs.surfaceContainerHighest.toArgb() else sunkenFace(cs),
                keyLabel = cs.onSurface.toArgb(),
                keyLabelSecondary = cs.onSurfaceVariant.toArgb(),
                keyHint = cs.outline.toArgb(),
                keySub = cs.outline.toArgb(),
                accentBottom = cs.primary.toArgb(),
                accentLabel = cs.onPrimary.toArgb(),
                candidateFirst = cs.primary.toArgb(),
                candidateText = cs.onSurface.toArgb(),
                lockedReading = cs.onPrimaryContainer.toArgb(),
                preeditText = cs.primary.toArgb(),
                separator = cs.outlineVariant.toArgb(),
                gridLine = if (dark) darkOutline(cs) else 0xFF000000.toInt(),
                panelBg = if (dark) brightFace(cs) else cs.surfaceContainerLowest.toArgb(),
                chipBg = cs.secondaryContainer.toArgb(),
                chipText = cs.onSecondaryContainer.toArgb(),
                icon = cs.onSurfaceVariant.toArgb(),
                deletable = cs.error.toArgb(),
                errorContainer = cs.errorContainer.toArgb(),
                onErrorContainer = cs.onErrorContainer.toArgb(),
                disabled = cs.outline.toArgb(),
                scrim = withAlpha(cs.scrim.toArgb(), 0x66),
                shadow = withAlpha(cs.scrim.toArgb(), if (dark) 0x40 else 0x22),
                floatSurface = (if (dark) floatFace(cs) else cs.surfaceBright.toArgb()),
            )
        }.getOrElse { if (dark) STATIC_DARK else STATIC_LIGHT }

        private fun darkOutline(cs: ColorScheme): Int {
            val surfaces = listOf(
                cs.surfaceContainer.toArgb(),
                cs.surfaceContainerHigh.toArgb(),
                cs.surfaceContainerHighest.toArgb(),
                brightFace(cs),
                floatFace(cs),
            )
            for (step in 0..10) {
                val color = opaque(ColorUtils.blendARGB(cs.outline.toArgb(), cs.onSurface.toArgb(), step / 10f))
                if (surfaces.all { ColorUtils.calculateContrast(color, it) >= 3.0 }) return color
            }
            return cs.onSurface.toArgb()
        }

        private const val SUNKEN_FACE_BLEND = 0.09f

        private fun sunkenFace(cs: ColorScheme): Int =
            opaque(ColorUtils.blendARGB(cs.surfaceDim.toArgb(), cs.onSurface.toArgb(), SUNKEN_FACE_BLEND))

        private const val BRIGHT_FACE_BLEND = 0.15f

        private fun brightFace(cs: ColorScheme): Int =
            opaque(ColorUtils.blendARGB(cs.surfaceBright.toArgb(), cs.onSurface.toArgb(), BRIGHT_FACE_BLEND))

        private const val FLOAT_FACE_BLEND = 0.25f

        private fun floatFace(cs: ColorScheme): Int =
            opaque(ColorUtils.blendARGB(cs.surfaceBright.toArgb(), cs.onSurface.toArgb(), FLOAT_FACE_BLEND))

        private fun withAlpha(argb: Int, alpha: Int): Int = (argb and 0x00FFFFFF) or (alpha shl 24)

        private fun opaque(argb: Int): Int = argb or 0xFF000000.toInt()
    }
}
