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

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.aegis.ime.ime.theme.ImePalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
  * Chinese IME behavior note.
 * truncated/illegible). It must now grow to at most 4 wrapped lines and scroll vertically beyond that.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditBarViewTest {

    private val ctx = RuntimeEnvironment.getApplication()

    private fun textViews(root: View): List<TextView> {
        val out = ArrayList<TextView>()
        fun walk(x: View) { if (x is TextView) out.add(x); if (x is ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i)) }
        walk(root); return out
    }
    /** The buffer field is the TextView carrying the trailing caret marker. */
    private fun field(v: EditBarView): TextView = textViews(v).first { it.text?.toString()?.endsWith("▏") == true }

    private fun layout(v: View, w: Int = 480) {
        v.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
    }

    @Test fun field_viewport_is_capped_at_four_lines() {
        val v = EditBarView(ctx).apply { applyPalette(ImePalette.STATIC_LIGHT); setTitle("编辑常用语"); setText("短") }
        val f = field(v)
        assertEquals("the viewport is capped at 4 lines (maxHeight, not maxLines)",
            f.lineHeight * 4 + f.paddingTop + f.paddingBottom, f.maxHeight)
    }

    @Test fun long_content_overflows_four_lines_and_becomes_scrollable() {
        // Explicit newlines give deterministic lines (Robolectric's fake font metrics don't wrap a long single
        // line); a real device wraps a long phrase the same way (setHorizontallyScrolling(false)).
        val v = EditBarView(ctx).apply { applyPalette(ImePalette.STATIC_LIGHT); setTitle("编辑常用语") }
        v.setText((1..20).joinToString("\n") { "第${it}行内容" })
        layout(v)
        val f = field(v)
        // maxHeight (NOT maxLines) means the FULL text lays out — so the overflow can be scrolled to, not truncated.
        assertTrue("the full text lays out to more than 4 lines", f.lineCount > 4)
        assertTrue("scrollable via ScrollingMovementMethod", f.movementMethod is android.text.method.ScrollingMovementMethod)
        assertTrue("the viewport is capped near 4 lines, not the full content",
            f.height <= f.lineHeight * 4 + f.paddingTop + f.paddingBottom + 1)
        val contentHeight = f.layout.height + f.paddingTop + f.paddingBottom
        assertTrue("the content overflows the capped 4-line viewport (so it scrolls)", contentHeight > f.height)
    }

    @Test fun short_content_does_not_scroll() {
        val v = EditBarView(ctx).apply { applyPalette(ImePalette.STATIC_LIGHT); setTitle("新建分类") }
        v.setText("工作")
        layout(v)
        val f = field(v)
        assertFalse("a short name needs no scrolling", f.canScrollVertically(1) || f.canScrollVertically(-1))
    }
}
