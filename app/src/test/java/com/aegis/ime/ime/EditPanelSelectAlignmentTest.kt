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

    @Test fun selection_stays_at_the_center_of_the_direction_pad_when_toggled() {
        val density = ctx.resources.displayMetrics.density
        for ((width, height) in listOf(320 to 200, 320 to 290, 411 to 324, 640 to 220)) {
            val v = EditPanelView(ctx).apply { applyPalette(ImePalette.STATIC_LIGHT) }
            v.measure(
                View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((height * density).toInt(), View.MeasureSpec.EXACTLY),
            )
            v.layout(0, 0, v.measuredWidth, v.measuredHeight)
            val select = boundsIn(v, EditAction.START_SELECT)
            val before = listOf(EditAction.UP, EditAction.DOWN, EditAction.LEFT, EditAction.RIGHT).associateWith { boundsIn(v, it) }
            assertTrue(abs(select.centerX() - requireNotNull(before[EditAction.UP]).centerX()) <= 1)
            assertTrue(abs(select.centerX() - requireNotNull(before[EditAction.DOWN]).centerX()) <= 1)
            assertTrue(abs(select.centerY() - requireNotNull(before[EditAction.LEFT]).centerY()) <= 1)
            assertTrue(abs(select.centerY() - requireNotNull(before[EditAction.RIGHT]).centerY()) <= 1)
            assertTrue(requireNotNull(before[EditAction.LEFT]).right <= select.left)
            assertTrue(select.right <= requireNotNull(before[EditAction.RIGHT]).left)
            for (selecting in listOf(true, false)) {
                v.setSelecting(selecting)
                org.junit.Assert.assertEquals(select, boundsIn(v, EditAction.START_SELECT))
                for ((action, bounds) in before) org.junit.Assert.assertEquals(bounds, boundsIn(v, action))
            }
        }
    }
}
