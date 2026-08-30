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

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class PaletteContrastTest {

    private fun channel(v: Int): Double {
        val c = v / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(color: Int): Double =
        0.2126 * channel((color shr 16) and 255) +
            0.7152 * channel((color shr 8) and 255) +
            0.0722 * channel(color and 255)

    private fun ratio(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    @Test fun the_surfaces_layer_from_deep_board_to_bright_faces_on_both_palettes() {
        for (p in listOf(ImePalette.STATIC_LIGHT, ImePalette.STATIC_DARK)) {
            assertTrue("faces sit above the rail", luminance(p.keySurface) > luminance(p.railBg))
            assertTrue("the rail sits above the board", luminance(p.railBg) > luminance(p.keyboardBg))
            assertTrue(
                "faces still separate from the board: " + ratio(p.keySurface, p.keyboardBg),
                ratio(p.keySurface, p.keyboardBg) >= 1.15,
            )
            assertTrue(
                "faces stay off pure white: " + Integer.toHexString(p.keySurface),
                p.keySurface != 0xFFFFFFFF.toInt(),
            )
        }
    }

    @Test fun no_role_carries_the_retired_hardcoded_greens() {
        val greens = setOf(
            0xFF57A35B.toInt(),
            0xFF29702D.toInt(),
            0xFF4E9152.toInt(),
            0xFF8BD08F.toInt(),
            0xFF06250A.toInt(),
        )
        for (p in listOf(ImePalette.STATIC_LIGHT, ImePalette.STATIC_DARK)) {
            val roles = listOf(
                p.keyboardBg, p.keySurface, p.keyLabel, p.keyLabelSecondary, p.keyHint, p.keySub,
                p.accentBottom, p.accentLabel, p.candidateFirst, p.candidateText, p.preeditText, p.separator, p.gridLine,
                p.railBg, p.chipBg, p.chipText, p.icon, p.deletable, p.errorContainer, p.onErrorContainer,
                p.disabled, p.scrim, p.shadow, p.floatSurface,
            )
            assertTrue("no palette role may reuse a retired green", roles.none { it in greens })
        }
    }

    @Test fun hint_text_clears_the_accessibility_floor_on_both_palettes() {
        val light = ImePalette.STATIC_LIGHT
        val dark = ImePalette.STATIC_DARK
        assertTrue(
            "light hint on light keyboard: " + ratio(light.keyHint, light.keyboardBg),
            ratio(light.keyHint, light.keyboardBg) >= 4.5,
        )
        assertTrue(
            "light hint on light keys: " + ratio(light.keyHint, light.keySurface),
            ratio(light.keyHint, light.keySurface) >= 4.5,
        )
        assertTrue(
            "dark hint on dark keyboard: " + ratio(dark.keyHint, dark.keyboardBg),
            ratio(dark.keyHint, dark.keyboardBg) >= 4.5,
        )
    }
}
