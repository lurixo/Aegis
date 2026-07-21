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

import android.graphics.Rect
import android.view.View
import com.aegis.ime.ime.theme.ImePalette
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditPanelSelectAlignmentTest {

    private val ctx = RuntimeEnvironment.getApplication()

    private fun boundsIn(v: EditPanelView, action: EditAction): Rect {
        val target = requireNotNull(v.actionViewForTest(action))
        return Rect(0, 0, target.width, target.height).also { v.offsetDescendantRectToMyCoords(target, it) }
    }

    @Test fun start_select_shares_the_copy_row_center_at_every_panel_height() {
        for (height in listOf(240, 276, 320, 480)) {
            val v = EditPanelView(ctx).apply { applyPalette(ImePalette.STATIC_LIGHT) }
            v.measure(
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            v.layout(0, 0, v.measuredWidth, v.measuredHeight)

            val select = boundsIn(v, EditAction.START_SELECT)
            val copy = boundsIn(v, EditAction.COPY)
            assertTrue(
                "start-select must share the copy row center at height=$height: select=$select copy=$copy",
                abs(select.centerY() - copy.centerY()) <= 1,
            )
        }
    }
}
