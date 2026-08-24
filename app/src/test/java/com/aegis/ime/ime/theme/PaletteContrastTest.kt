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
