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

import android.view.MotionEvent
import android.view.View
import com.aegis.ime.engine.CandidateEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CandidateBarChevronTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val density = ctx.resources.displayMetrics.density

    private fun barView(): CandidateView {
        val v = CandidateView(ctx)
        v.setContent(listOf("你好", "你", "拟"), "ni'hao")
        v.measure(
            View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((44 * density).toInt(), View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        return v
    }

    private fun CandidateView.tapChevron() {
        val cx = width - 20f * density
        val cy = height / 2f
        dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, cx, cy, 0))
        dispatchTouchEvent(MotionEvent.obtain(0, 10, MotionEvent.ACTION_UP, cx, cy, 0))
    }

    @Test fun collapsed_state_chevron_points_down_and_expands() {
        var expanded = false
        var collapsed = false
        val v = barView().apply { onExpand = { expanded = true }; onCollapseExpanded = { collapsed = true } }
        assertEquals("⌄", v.chevronGlyph())
        v.tapChevron()
        assertTrue("a tap on ⌄ expands the grid", expanded)
        assertFalse(collapsed)
    }

    @Test fun expanded_state_chevron_reverses_and_collapses() {
        var expanded = false
        var collapsed = false
        val v = barView().apply { onExpand = { expanded = true }; onCollapseExpanded = { collapsed = true } }
        v.setExpanded(true)
        assertEquals("the arrow direction reverses once expanded", "⌃", v.chevronGlyph())
        v.tapChevron()
        assertTrue("a tap on ⌃ collapses the grid", collapsed)
        assertFalse("it must NOT re-expand", expanded)
    }


    private fun attached(): InputView {
        val iv = InputView(ctx)
        val host = object : ImeHost {
            override fun commitText(text: CharSequence) {}
            override fun deleteBackward() {}
            override fun performEnter() {}
        }
        val engine = object : CandidateEngine {
            override fun candidates(composing: String, t9: Boolean): List<String> = emptyList()
        }
        KeyboardController(host, engine).attachView(iv)
        return iv
    }

    @Test fun inputview_flips_chevron_when_the_grid_opens_and_closes() {
        val iv = attached()
        iv.showCandidates(listOf("你好", "你"), "ni'hao", listOf("ni"))
        assertEquals("⌄", iv.barChevronGlyph())
        iv.showExpandedCandidates()
        assertTrue(iv.panelShown)
        assertEquals("grid open → chevron flips up", "⌃", iv.barChevronGlyph())
        iv.showPanel(null)
        assertEquals("grid closed → chevron back to down", "⌄", iv.barChevronGlyph())
    }
}
