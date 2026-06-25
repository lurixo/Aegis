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

import androidx.core.graphics.ColorUtils
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DynamicPaletteContrastTest {

    private val ctx = RuntimeEnvironment.getApplication()

    private fun faces(p: ImePalette) = listOf(
        "board" to p.keyboardBg,
        "letter faces" to p.keySurface,
        "function faces" to p.functionSurface,
        "panel" to p.panelBg,
        "float" to p.floatSurface,
    )

    @Test fun the_dynamic_palettes_are_what_a_supported_device_actually_draws_with() {
        for (dark in listOf(false, true)) {
            val fallback = if (dark) ImePalette.STATIC_DARK else ImePalette.STATIC_LIGHT
            assertNotEquals("dark=$dark resolves a dynamic scheme", fallback, ImePalette.from(ctx, dark))
        }
    }

    @Test fun dynamic_hint_ink_clears_its_floor_on_every_face_it_writes_on() {
        for (dark in listOf(false, true)) {
            val p = ImePalette.from(ctx, dark)
            for ((where, face) in faces(p)) {
                val contrast = ColorUtils.calculateContrast(p.keyHint, face)
                assertTrue("dark=$dark hint ink on the $where: $contrast", contrast >= ImePalette.HINT_INK_FLOOR)
            }
        }
    }

    @Test fun dynamic_sub_ink_clears_its_floor_and_stays_under_the_hint_ink() {
        for (dark in listOf(false, true)) {
            val p = ImePalette.from(ctx, dark)
            val faces = listOf(
                "letter faces" to p.keySurface,
            )
            for ((where, face) in faces) {
                val sub = ColorUtils.calculateContrast(p.keySub, face)
                val hint = ColorUtils.calculateContrast(p.keyHint, face)
                assertTrue("dark=$dark sub ink on the $where: $sub", sub >= ImePalette.HINT_INK_FLOOR)
                assertTrue("dark=$dark sub $sub vs hint $hint on the $where", sub <= hint)
            }
        }
    }

    @Test fun dynamic_hint_ink_stays_under_the_secondary_label() {
        for (dark in listOf(false, true)) {
            val p = ImePalette.from(ctx, dark)
            for ((where, face) in faces(p)) {
                val hint = ColorUtils.calculateContrast(p.keyHint, face)
                val secondary = ColorUtils.calculateContrast(p.keyLabelSecondary, face)
                assertTrue("dark=$dark hint $hint vs secondary $secondary on the $where", hint <= secondary)
            }
        }
    }
}
