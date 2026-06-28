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

import android.graphics.Color
import com.aegis.ime.ime.theme.ImePalette
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LockedReadingHighlightParityTest {

    private fun relativeLuminance(color: Int): Double {
        fun channel(value: Int): Double {
            val c = value / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(Color.red(color)) +
            0.7152 * channel(Color.green(color)) +
            0.0722 * channel(Color.blue(color))
    }

    private fun contrast(foreground: Int, background: Int): Double {
        val a = relativeLuminance(foreground)
        val b = relativeLuminance(background)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    @Test fun the_marked_reading_stays_readable_on_the_rail_in_both_static_themes() {
        for ((name, theme) in listOf("light" to ImePalette.STATIC_LIGHT, "dark" to ImePalette.STATIC_DARK)) {
            val marked = contrast(theme.lockedReading, theme.functionSurface)
            val plain = contrast(theme.candidateText, theme.functionSurface)
            assertTrue(
                "$name marks the locked reading at ${"%.2f".format(marked)}:1, under the 4.5:1 a reader needs",
                marked >= 4.5,
            )
            assertTrue(
                "$name draws the plain readings at ${"%.2f".format(plain)}:1, under the 4.5:1 a reader needs",
                plain >= 4.5,
            )
            assertNotEquals("$name must still tell the marked reading apart", theme.candidateText, theme.lockedReading)
        }
    }
}
