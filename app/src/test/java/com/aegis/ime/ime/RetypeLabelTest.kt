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
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import com.aegis.ime.R
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeType
import com.aegis.ime.layout.KeyAction
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.layout.Layouts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetypeLabelTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val density = ctx.resources.displayMetrics.density

    private fun inkIn(view: View, region: RectF): Rect {
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        val inset = 6f * density
        val scan = RectF(region).apply { inset(inset, inset) }
        val counts = HashMap<Int, Int>()
        for (y in scan.top.toInt() until scan.bottom.toInt()) {
            for (x in scan.left.toInt() until scan.right.toInt()) {
                counts.merge(bmp.getPixel(x, y), 1, Int::plus)
            }
        }
        val background = counts.maxBy { it.value }.key
        var l = view.width
        var t = view.height
        var r = -1
        var b = -1
        for (y in scan.top.toInt() until scan.bottom.toInt()) {
            for (x in scan.left.toInt() until scan.right.toInt()) {
                if (bmp.getPixel(x, y) == background) continue
                if (x < l) l = x
                if (x > r) r = x
                if (y < t) t = y
                if (y > b) b = y
            }
        }
        return Rect(l, t, r + 1, b + 1)
    }

    @Test fun the_nine_key_retype_spells_out_its_label() {
        val view = KeyboardView(ctx).apply {
            applyPalette(ImePalette.STATIC_LIGHT)
            setLayout(Layouts.forId(LayoutId.NINE, Lang.CN), false, false, Lang.CN)
            measure(
                View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((250 * density).toInt(), View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, measuredWidth, measuredHeight)
        }
        val cell = requireNotNull(view.boundsOfActionForTest(KeyAction.CLEAR_COMPOSING))

        val ink = inkIn(view, cell)
        assertTrue("the retype key must draw something: $ink", ink.width() > 0 && ink.height() > 0)
        assertTrue(
            "a spelled-out label runs wider than it is tall, unlike the old brush: $ink",
            ink.width() > ink.height(),
        )
        assertTrue(
            "the label is centred in its key: $ink in $cell",
            kotlin.math.abs(ink.centerX() - cell.centerX()) <= 2 * density,
        )
    }

    @Test fun the_expanded_retype_spells_out_its_label() {
        val grid = CandidateGridView(ctx).apply { applyPalette(ImePalette.STATIC_LIGHT) }
        grid.measure(
            View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((250 * density).toInt(), View.MeasureSpec.EXACTLY),
        )
        grid.layout(0, 0, grid.measuredWidth, grid.measuredHeight)
        val button = grid.clearButtonForTest()

        assertEquals("the face spells out the retype name", ctx.getString(R.string.kbd_redo), button.text.toString())
        assertNull("no glyph is left beside the words", button.compoundDrawables.firstOrNull { it != null })
        assertEquals("it is set at the panel action size", ImeType.body * density, button.textSize, 0.01f)
    }
}
