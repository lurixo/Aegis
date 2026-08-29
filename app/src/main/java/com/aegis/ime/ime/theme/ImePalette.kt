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

data class ImePalette(
    val keyboardBg: Int,
    val keySurface: Int,
    val keyLabel: Int,
    val keyLabelSecondary: Int,
    val keyHint: Int,
    val keySub: Int,
    val accentBottom: Int,
    val accentLabel: Int,
    val candidateFirst: Int,
    val candidateText: Int,
    val preeditText: Int,
    val separator: Int,
    val railBg: Int,
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
            keyboardBg = 0xFFC9D0DA.toInt(),
            keySurface = 0xFFDCE2EA.toInt(),
            keyLabel = 0xFF16181B.toInt(),
            keyLabelSecondary = 0xFF3C4A54.toInt(),
            keyHint = 0xFF46525C.toInt(),
            keySub = 0xFF8A959C.toInt(),
            accentBottom = 0xFF6750A4.toInt(),
            accentLabel = 0xFFFFFFFF.toInt(),
            candidateFirst = 0xFF6750A4.toInt(),
            candidateText = 0xFF16181B.toInt(),
            preeditText = 0xFF33639C.toInt(),
            separator = 0xFFBCC5D1.toInt(),
            railBg = 0xFFD7DDE6.toInt(),
            chipBg = 0xFFDCE2EA.toInt(),
            chipText = 0xFF16181B.toInt(),
            icon = 0xFF3C4A54.toInt(),
            deletable = 0xFFD32F2F.toInt(),
            errorContainer = 0xFFF9DEDC.toInt(),
            onErrorContainer = 0xFF410E0B.toInt(),
            disabled = 0xFF9AA5AC.toInt(),
            scrim = 0x66000000,
            shadow = 0x22000000,
            floatSurface = 0xFFE6EBF1.toInt(),
        )

        val STATIC_DARK = ImePalette(
            keyboardBg = 0xFF111417.toInt(),
            keySurface = 0xFF363C43.toInt(),
            keyLabel = 0xFFE4E6EA.toInt(),
            keyLabelSecondary = 0xFFC3C9D0.toInt(),
            keyHint = 0xFF8F979F.toInt(),
            keySub = 0xFF79828B.toInt(),
            accentBottom = 0xFFD0BCFF.toInt(),
            accentLabel = 0xFF381E72.toInt(),
            candidateFirst = 0xFFD0BCFF.toInt(),
            candidateText = 0xFFE4E6EA.toInt(),
            preeditText = 0xFF9FC9FF.toInt(),
            separator = 0xFF3E444B.toInt(),
            railBg = 0xFF262B30.toInt(),
            chipBg = 0xFF262B30.toInt(),
            chipText = 0xFFE4E6EA.toInt(),
            icon = 0xFFB3BAC2.toInt(),
            deletable = 0xFFFFB4AB.toInt(),
            errorContainer = 0xFF8C1D18.toInt(),
            onErrorContainer = 0xFFF9DEDC.toInt(),
            disabled = 0xFF5D646B.toInt(),
            scrim = 0x99000000.toInt(),
            shadow = 0x40000000,
            floatSurface = 0xFF24282D.toInt(),
        )

        fun from(ctx: Context, dark: Boolean): ImePalette = runCatching {
            val cs: ColorScheme = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            ImePalette(
                keyboardBg = cs.surfaceDim.toArgb(),
                keySurface = (if (dark) cs.surfaceBright else cs.surfaceContainer).toArgb(),
                keyLabel = cs.onSurface.toArgb(),
                keyLabelSecondary = cs.onSurfaceVariant.toArgb(),
                keyHint = cs.outline.toArgb(),
                keySub = cs.outline.toArgb(),
                accentBottom = cs.primary.toArgb(),
                accentLabel = cs.onPrimary.toArgb(),
                candidateFirst = cs.primary.toArgb(),
                candidateText = cs.onSurface.toArgb(),
                preeditText = cs.primary.toArgb(),
                separator = cs.outlineVariant.toArgb(),
                railBg = cs.surfaceContainerHigh.toArgb(),
                chipBg = cs.secondaryContainer.toArgb(),
                chipText = cs.onSecondaryContainer.toArgb(),
                icon = cs.onSurfaceVariant.toArgb(),
                deletable = cs.error.toArgb(),
                errorContainer = cs.errorContainer.toArgb(),
                onErrorContainer = cs.onErrorContainer.toArgb(),
                disabled = cs.outline.toArgb(),
                scrim = withAlpha(cs.scrim.toArgb(), 0x66),
                shadow = withAlpha(cs.scrim.toArgb(), if (dark) 0x40 else 0x22),
                floatSurface = cs.surfaceContainer.toArgb(),
            )
        }.getOrElse { if (dark) STATIC_DARK else STATIC_LIGHT }

        private fun withAlpha(argb: Int, alpha: Int): Int = (argb and 0x00FFFFFF) or (alpha shl 24)
    }
}
