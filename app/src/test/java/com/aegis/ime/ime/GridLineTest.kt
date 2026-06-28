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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GridLineTest {

    @Test fun the_grid_line_is_half_a_dp_rounded_to_whole_pixels_and_never_thinner_than_one() {
        assertEquals(1f, ImeShapes.gridLinePx(1f), 0f)
        assertEquals(1f, ImeShapes.gridLinePx(2f), 0f)
        assertEquals(1f, ImeShapes.gridLinePx(2.625f), 0f)
        assertEquals(2f, ImeShapes.gridLinePx(3.5f), 0f)
    }
}
