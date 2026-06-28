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
    val keySurfacePressed: Int,
    val keyLabel: Int,
    val keyLabelSecondary: Int,
    val keyHint: Int,
    val accentBottom: Int,
    val accentLabel: Int,
    val candidateFirst: Int,
    val candidateText: Int,
    val preeditText: Int,
    val separator: Int,
    val railBg: Int,
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
) {
    companion object {
        val STATIC_LIGHT = ImePalette(
            keyboardBg = 0xFFE6E9EF.toInt(),
            keySurface = 0xFFFFFFFF.toInt(),
            keySurfacePressed = 0xFFDCE0E6.toInt(),
            keyLabel = 0xFF202124.toInt(),
            keyLabelSecondary = 0xFF37474F.toInt(),
            keyHint = 0xFF90A4AE.toInt(),
            accentBottom = 0xFF57A35B.toInt(),
            accentLabel = 0xFFFFFFFF.toInt(),
            candidateFirst = 0xFF2E7D32.toInt(),
            candidateText = 0xFF202124.toInt(),
            preeditText = 0xFF1565C0.toInt(),
            separator = 0xFFD2D7DE.toInt(),
            railBg = 0xFFEFF1F5.toInt(),
            panelBg = 0xFFF7F8FA.toInt(),
            chipBg = 0xFFE2E5E9.toInt(),
            chipText = 0xFF202124.toInt(),
            icon = 0xFF455A64.toInt(),
            deletable = 0xFFD32F2F.toInt(),
            errorContainer = 0xFFF9DEDC.toInt(),
            onErrorContainer = 0xFF410E0B.toInt(),
            disabled = 0xFFB0BEC5.toInt(),
            scrim = 0x66000000,
            shadow = 0x22000000,
        )

        val STATIC_DARK = ImePalette(
            keyboardBg = 0xFF1A1C1E.toInt(),
            keySurface = 0xFF2C3034.toInt(),
            keySurfacePressed = 0xFF3A3F44.toInt(),
            keyLabel = 0xFFE3E2E6.toInt(),
            keyLabelSecondary = 0xFFC2C7CE.toInt(),
            keyHint = 0xFF8D9199.toInt(),
            accentBottom = 0xFF4E9152.toInt(),
            accentLabel = 0xFF06250A.toInt(),
            candidateFirst = 0xFF8BD08F.toInt(),
            candidateText = 0xFFE3E2E6.toInt(),
            preeditText = 0xFF9FC9FF.toInt(),
            separator = 0xFF3A3E42.toInt(),
            railBg = 0xFF222629.toInt(),
            panelBg = 0xFF1A1C1E.toInt(),
            chipBg = 0xFF2C3034.toInt(),
            chipText = 0xFFE3E2E6.toInt(),
            icon = 0xFFB0B6BE.toInt(),
            deletable = 0xFFFFB4AB.toInt(),
            errorContainer = 0xFF8C1D18.toInt(),
            onErrorContainer = 0xFFF9DEDC.toInt(),
            disabled = 0xFF5A5E62.toInt(),
            scrim = 0x99000000.toInt(),
            shadow = 0x40000000,
        )

        fun from(ctx: Context, dark: Boolean): ImePalette = runCatching {
            val cs: ColorScheme = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            ImePalette(
                keyboardBg = cs.surfaceContainer.toArgb(),
                keySurface = cs.surfaceBright.toArgb(),
                keySurfacePressed = cs.surfaceContainerHigh.toArgb(),
                keyLabel = cs.onSurface.toArgb(),
                keyLabelSecondary = cs.onSurfaceVariant.toArgb(),
                keyHint = cs.outline.toArgb(),
                accentBottom = cs.primary.toArgb(),
                accentLabel = cs.onPrimary.toArgb(),
                candidateFirst = cs.primary.toArgb(),
                candidateText = cs.onSurface.toArgb(),
                preeditText = cs.primary.toArgb(),
                separator = cs.outlineVariant.toArgb(),
                railBg = cs.surfaceContainerLow.toArgb(),
                panelBg = cs.surfaceContainerLow.toArgb(),
                chipBg = cs.secondaryContainer.toArgb(),
                chipText = cs.onSecondaryContainer.toArgb(),
                icon = cs.onSurfaceVariant.toArgb(),
                deletable = cs.error.toArgb(),
                errorContainer = cs.errorContainer.toArgb(),
                onErrorContainer = cs.onErrorContainer.toArgb(),
                disabled = cs.outline.toArgb(),
                scrim = withAlpha(cs.scrim.toArgb(), 0x66),
                shadow = withAlpha(cs.scrim.toArgb(), if (dark) 0x40 else 0x22),
            )
        }.getOrElse { if (dark) STATIC_DARK else STATIC_LIGHT }

        private fun withAlpha(argb: Int, alpha: Int): Int = (argb and 0x00FFFFFF) or (alpha shl 24)
    }
}
