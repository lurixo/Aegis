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

import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PanelSurfaceTest {

    private val ctx = RuntimeEnvironment.getApplication()

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

    private val paths
        get() = listOf(
            "static light" to ImePalette.STATIC_LIGHT,
            "static dark" to ImePalette.STATIC_DARK,
            "dynamic light" to ImePalette.from(ctx, dark = false),
            "dynamic dark" to ImePalette.from(ctx, dark = true),
        )

    @Test fun the_panel_content_stands_well_clear_of_the_columns_around_it() {
        for ((name, p) in paths) {
            assertTrue(
                "$name lifts the panel content above the columns: " + ratio(p.panelBg, p.keyboardBg),
                luminance(p.panelBg) > luminance(p.keyboardBg),
            )
            assertTrue(
                "$name keeps the panel content readable apart from the columns: " + ratio(p.panelBg, p.keyboardBg),
                ratio(p.panelBg, p.keyboardBg) >= 1.12,
            )
        }
    }

    @Test fun the_panel_surface_follows_the_wallpaper_instead_of_a_fixed_value() {
        val light = ImePalette.from(ctx, dark = false)
        val dark = ImePalette.from(ctx, dark = true)
        assertNotEquals("the light palette must reach the dynamic path", ImePalette.STATIC_LIGHT, light)
        assertNotEquals("the dark palette must reach the dynamic path", ImePalette.STATIC_DARK, dark)

        assertEquals(dynamicLightColorScheme(ctx).surfaceContainerLowest.toArgb(), light.panelBg)
        assertTrue(
            "the dark panel face is derived above surfaceBright, not pinned to it",
            luminance(dark.panelBg) > luminance(dynamicDarkColorScheme(ctx).surfaceBright.toArgb()),
        )
    }
}
