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
class FunctionSurfaceTest {

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

    private val lightPaths
        get() = listOf(
            "static light" to ImePalette.STATIC_LIGHT,
            "dynamic light" to ImePalette.from(ctx, dark = false),
        )

    private val darkPaths
        get() = listOf(
            "static dark" to ImePalette.STATIC_DARK,
            "dynamic dark" to ImePalette.from(ctx, dark = true),
        )

    private val paths get() = lightPaths + darkPaths

    @Test fun the_light_board_carries_bright_faces_over_a_sunken_function_block() {
        for ((name, p) in lightPaths) {
            assertTrue(
                "$name lifts the letter faces above the board: " + ratio(p.keySurface, p.keyboardBg),
                luminance(p.keySurface) > luminance(p.keyboardBg),
            )
            assertTrue(
                "$name sinks the function keys below the board: " + ratio(p.functionSurface, p.keyboardBg),
                luminance(p.functionSurface) < luminance(p.keyboardBg),
            )
            assertTrue(
                "$name parts the function keys from the letter faces: " + ratio(p.keySurface, p.functionSurface),
                ratio(p.keySurface, p.functionSurface) >= 1.55,
            )
            assertTrue(
                "$name keeps the function keys off the board: " + ratio(p.functionSurface, p.keyboardBg),
                ratio(p.functionSurface, p.keyboardBg) >= 1.35,
            )
        }
    }

    @Test fun the_dark_board_stacks_faces_over_function_keys_over_the_board() {
        for ((name, p) in darkPaths) {
            assertTrue(
                "$name lifts the letter faces above the function keys: " + ratio(p.keySurface, p.functionSurface),
                luminance(p.keySurface) > luminance(p.functionSurface),
            )
            assertTrue(
                "$name lifts the function keys above the board: " + ratio(p.functionSurface, p.keyboardBg),
                luminance(p.functionSurface) > luminance(p.keyboardBg),
            )
            assertTrue(
                "$name parts the function keys from the letter faces: " + ratio(p.keySurface, p.functionSurface),
                ratio(p.keySurface, p.functionSurface) >= 1.45,
            )
            assertTrue(
                "$name keeps the function keys off the board: " + ratio(p.functionSurface, p.keyboardBg),
                ratio(p.functionSurface, p.keyboardBg) >= 1.25,
            )
        }
    }

    @Test fun the_scroll_track_shares_the_function_face_and_still_carries_the_marked_reading() {
        for ((name, p) in paths) {
            assertTrue(
                "$name marks the locked reading on the track at " + ratio(p.lockedReading, p.functionSurface),
                ratio(p.lockedReading, p.functionSurface) >= 4.5,
            )
            assertTrue(
                "$name draws the plain readings on the track at " + ratio(p.keyLabel, p.functionSurface),
                ratio(p.keyLabel, p.functionSurface) >= 4.5,
            )
            assertNotEquals("$name must tell the marked reading apart", p.keyLabel, p.lockedReading)
        }
    }

    @Test fun every_face_of_a_palette_is_opaque() {
        for ((name, p) in paths) {
            val faces = mapOf(
                "board" to p.keyboardBg,
                "letter face" to p.keySurface,
                "function face" to p.functionSurface,
                "panel face" to p.panelBg,
                "floating face" to p.floatSurface,
                "grid line" to p.gridLine,
            )
            for ((role, color) in faces) {
                assertEquals("$name leaves the $role translucent", 255, (color ushr 24) and 255)
            }
        }
    }

    @Test fun the_floating_layer_never_matches_the_surface_it_covers() {
        for ((name, p) in paths) {
            assertTrue(
                "$name floats the popup above the board: " + ratio(p.floatSurface, p.keyboardBg),
                luminance(p.floatSurface) > luminance(p.keyboardBg),
            )
            assertTrue(
                "$name parts the popup from the board: " + ratio(p.floatSurface, p.keyboardBg),
                ratio(p.floatSurface, p.keyboardBg) >= 1.10,
            )
            assertTrue(
                "$name parts the popup from the function keys: " + ratio(p.floatSurface, p.functionSurface),
                ratio(p.floatSurface, p.functionSurface) >= 1.40,
            )
        }
        for ((name, p) in paths) {
            assertTrue(
                "$name floats the popup above the content faces: " + ratio(p.floatSurface, p.keySurface),
                luminance(p.floatSurface) > luminance(p.keySurface) || p.keySurface == 0xFFFFFFFF.toInt(),
            )
            assertTrue(
                "$name keeps the ink readable on the popup: " + ratio(p.keyLabel, p.floatSurface),
                ratio(p.keyLabel, p.floatSurface) >= 4.5,
            )
            assertNotEquals("$name must not paint the popup in the accent ink", p.accentLabel, p.floatSurface)
        }
    }

    @Test fun no_two_surfaces_of_a_palette_collide() {
        for ((name, p) in paths) {
            val faces = mapOf(
                "board" to p.keyboardBg,
                "letter face" to p.keySurface,
                "function face" to p.functionSurface,
                "panel face" to p.panelBg,
                "floating face" to p.floatSurface,
            )
            for ((an, a) in faces) {
                for ((bn, b) in faces) {
                    if (an >= bn) continue
                    if (setOf(an, bn) == setOf("letter face", "panel face")) continue
                    assertNotEquals("$name collides $an with $bn", a, b)
                }
            }
        }
    }

    @Test fun the_faces_follow_the_wallpaper_instead_of_fixed_values() {
        val light = ImePalette.from(ctx, dark = false)
        val dark = ImePalette.from(ctx, dark = true)
        assertNotEquals("the light palette must reach the dynamic path", ImePalette.STATIC_LIGHT, light)
        assertNotEquals("the dark palette must reach the dynamic path", ImePalette.STATIC_DARK, dark)

        val lightScheme = dynamicLightColorScheme(ctx)
        val darkScheme = dynamicDarkColorScheme(ctx)
        assertEquals(lightScheme.surfaceContainerLowest.toArgb(), light.keySurface)
        assertEquals(lightScheme.surfaceContainer.toArgb(), light.keyboardBg)
        assertEquals(darkScheme.surfaceContainer.toArgb(), dark.keyboardBg)
        assertEquals(darkScheme.surfaceContainerHighest.toArgb(), dark.functionSurface)
        assertTrue(
            "the light function face is derived below surfaceDim, not pinned to it",
            luminance(light.functionSurface) < luminance(lightScheme.surfaceDim.toArgb()),
        )
        assertTrue(
            "the dark letter face is derived above surfaceBright, not pinned to it",
            luminance(dark.keySurface) > luminance(darkScheme.surfaceBright.toArgb()),
        )
        assertEquals("both dark content faces share one value", dark.keySurface, dark.panelBg)
        assertTrue(
            "the dark floating face is derived above the letter face",
            luminance(dark.floatSurface) > luminance(dark.keySurface),
        )
        assertEquals(lightScheme.surfaceBright.toArgb(), light.floatSurface)
    }
}
