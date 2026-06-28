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

import com.aegis.ime.ime.theme.ImeShapes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-port-mdpi")
class ImeSurfaceTopCornersTest {

    @Test fun surface_top_radius_sits_within_the_toolbar_pill() {
        assertEquals("surface top radius constant", 8f, ImeShapes.surfaceTopRadiusDp, 0f)
        val pillEffectiveRadiusDp = ((44f - 5f * 2f) / 2f)
        assertTrue(
            "surface corner must not out-round the toolbar pill end (${pillEffectiveRadiusDp}dp)",
            ImeShapes.surfaceTopRadiusDp <= pillEffectiveRadiusDp,
        )
    }
}
