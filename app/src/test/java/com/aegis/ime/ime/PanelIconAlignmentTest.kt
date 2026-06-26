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

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeShapes
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PanelIconAlignmentTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val density = ctx.resources.displayMetrics.density

    @Test fun the_back_control_press_fills_the_control_that_starts_at_the_shared_edge_inset() {
        val back = PanelBackButton.control(ctx, "返回", ImePalette.STATIC_LIGHT.keyLabel) {}
        back.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec((PanelBackButton.HIT_DP * density).toInt(), View.MeasureSpec.EXACTLY),
        )
        back.layout(0, 0, back.measuredWidth, back.measuredHeight)
        back.draw(Canvas(Bitmap.createBitmap(back.width, back.height, Bitmap.Config.ARGB_8888)))
        val ripple = back.foreground as android.graphics.drawable.RippleDrawable
        val mask = requireNotNull(ripple.findDrawableByLayerId(android.R.id.mask))
        assertEquals("the press starts at the control's own edge", 0, mask.bounds.left)
        assertEquals(back.width, mask.bounds.right)
        assertEquals(
            "the leading padding gives way to the host's edge inset",
            (PanelBackButton.EDGE_DP * density).toInt() - (ImeShapes.edgeInsetDp * density).toInt(),
            back.paddingLeft,
        )
    }
}
