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

/**
 * F1: the semantic colour tokens for the self-drawn IME surface (keyboard + candidate strip + preedit +
 * every panel), as plain ARGB [Int]s ready to feed Paint / setBackgroundColor / GradientDrawable.
 *
 * Source of truth = Material 3 dynamic colour (Monet), the SAME scheme the Compose Setup/Phrase screens
 * already use — but read via [imePalette] from a non-@Composable context so the self-drawn views and the
 * IME service can use it. minSdk 34 ⇒ Monet is always present; [STATIC_LIGHT]/[STATIC_DARK] are kept only
 * as a safety fallback (no-Monet AOSP/OEM, or a resolution error). The static values are the project's
 * current hand-tuned colours, so wiring a view to its matching token is pixel-identical until the source
 * is switched to dynamic.
 */
data class ImePalette(
    val keyboardBg: Int,            // keyboard / panel floor
    val keySurface: Int,            // flat key fill (F2: MD3 tonal, no gradient)
    val keySurfacePressed: Int,     // pressed key fill
    val keyLabel: Int,              // primary glyph
    val keyLabelSecondary: Int,     // special / small glyph
    val keyHint: Int,               // sub / super-script
    val accentTop: Int,             // enter / primary key gradient top
    val accentBottom: Int,          // enter gradient bottom
    val accentLabel: Int,           // on-accent text
    val accentGlow: Int,            // enter halo (ARGB incl. alpha)
    val candidateFirst: Int,        // top-candidate highlight
    val candidateText: Int,
    val preeditText: Int,           // pinyin tab
    val separator: Int,
    val railBg: Int,                // scroll track / side rail
    val panelBg: Int,               // panel floor
    val panelSubBg: Int,            // panel sub-area
    val chipBg: Int,
    val chipText: Int,
    val icon: Int,                  // toolbar / panel line icons
    val deletable: Int,             // removable-item accent (custom-symbol ✕)
    val disabled: Int,
    val scrim: Int,
    val shadow: Int,                // drop-shadow colour (incl. alpha) for the 2 elevated surfaces
) {
    companion object {
        /** Current hand-tuned LIGHT values (the baseline palette) — the zero-visual-change baseline. */
        val STATIC_LIGHT = ImePalette(
            keyboardBg = 0xFFE6E9EF.toInt(),
            keySurface = 0xFFFFFFFF.toInt(),
            keySurfacePressed = 0xFFDCE0E6.toInt(),
            keyLabel = 0xFF202124.toInt(),
            keyLabelSecondary = 0xFF37474F.toInt(),
            keyHint = 0xFF90A4AE.toInt(),
            accentTop = 0xFF7CC47F.toInt(),
            accentBottom = 0xFF57A35B.toInt(),
            accentLabel = 0xFFFFFFFF.toInt(),
            accentGlow = 0x9943A047.toInt(),
            candidateFirst = 0xFF2E7D32.toInt(),
            candidateText = 0xFF202124.toInt(),
            preeditText = 0xFF1565C0.toInt(),
            separator = 0xFFD2D7DE.toInt(),
            railBg = 0xFFEFF1F5.toInt(),
            panelBg = 0xFFF7F8FA.toInt(),
            panelSubBg = 0xFFEFF1F4.toInt(),
            chipBg = 0xFFE2E5E9.toInt(),
            chipText = 0xFF202124.toInt(),
            icon = 0xFF455A64.toInt(),
            deletable = 0xFFD32F2F.toInt(),
            disabled = 0xFFB0BEC5.toInt(),
            scrim = 0x66000000,
            shadow = 0x22000000, // U-polish: the value the capsule/preedit shadow was hardcoded to (light identical)
        )

        /** A safety DARK fallback for no-Monet devices (Monet's real dark scheme is used when present). */
        val STATIC_DARK = ImePalette(
            keyboardBg = 0xFF1A1C1E.toInt(),
            keySurface = 0xFF2C3034.toInt(),
            keySurfacePressed = 0xFF3A3F44.toInt(),
            keyLabel = 0xFFE3E2E6.toInt(),
            keyLabelSecondary = 0xFFC2C7CE.toInt(),
            keyHint = 0xFF8D9199.toInt(),
            accentTop = 0xFF6FB374.toInt(),
            accentBottom = 0xFF4E9152.toInt(),
            accentLabel = 0xFF06250A.toInt(),
            accentGlow = 0x9943A047.toInt(),
            candidateFirst = 0xFF8BD08F.toInt(),
            candidateText = 0xFFE3E2E6.toInt(),
            preeditText = 0xFF9FC9FF.toInt(),
            separator = 0xFF3A3E42.toInt(),
            railBg = 0xFF222629.toInt(),
            panelBg = 0xFF1A1C1E.toInt(),
            panelSubBg = 0xFF222629.toInt(),
            chipBg = 0xFF2C3034.toInt(),
            chipText = 0xFFE3E2E6.toInt(),
            icon = 0xFFB0B6BE.toInt(),
            deletable = 0xFFFFB4AB.toInt(),
            disabled = 0xFF5A5E62.toInt(),
            scrim = 0x99000000.toInt(),
            shadow = 0x40000000, // U-polish: a touch stronger on dark so the elevated surface still reads as lifted
        )

        /**
         * Build the palette from Monet dynamic colour for the given [dark] mode (path A — same scheme as
         * the Setup screen). Falls back to the matching static palette if dynamic colour is unavailable.
         * Not a hot path: the IME service resolves this once per show / config change and caches it.
         */
        fun from(ctx: Context, dark: Boolean): ImePalette = runCatching {
            val cs: ColorScheme = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            ImePalette(
                keyboardBg = cs.surfaceContainer.toArgb(),
                keySurface = cs.surfaceBright.toArgb(),
                keySurfacePressed = cs.surfaceContainerHigh.toArgb(),
                keyLabel = cs.onSurface.toArgb(),
                keyLabelSecondary = cs.onSurfaceVariant.toArgb(),
                keyHint = cs.outline.toArgb(),
                accentTop = cs.primary.toArgb(),
                accentBottom = cs.primary.toArgb(),
                accentLabel = cs.onPrimary.toArgb(),
                accentGlow = withAlpha(cs.primary.toArgb(), 0x99),
                candidateFirst = cs.primary.toArgb(),
                candidateText = cs.onSurface.toArgb(),
                preeditText = cs.primary.toArgb(),
                separator = cs.outlineVariant.toArgb(),
                railBg = cs.surfaceContainerLow.toArgb(),
                panelBg = cs.surfaceContainerLow.toArgb(),
                panelSubBg = cs.surfaceContainer.toArgb(),
                chipBg = cs.secondaryContainer.toArgb(),
                chipText = cs.onSecondaryContainer.toArgb(),
                icon = cs.onSurfaceVariant.toArgb(),
                deletable = cs.error.toArgb(),
                disabled = cs.outline.toArgb(),
                scrim = withAlpha(cs.scrim.toArgb(), 0x66),
                shadow = withAlpha(cs.scrim.toArgb(), if (dark) 0x40 else 0x22),
            )
        }.getOrElse { if (dark) STATIC_DARK else STATIC_LIGHT }

        private fun withAlpha(argb: Int, alpha: Int): Int = (argb and 0x00FFFFFF) or (alpha shl 24)
    }
}
