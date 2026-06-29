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

/**
 * U14: in the expanded selection screen the right chevron must point the OTHER way (⌃, not ⌄) and a tap
 * must COLLAPSE the grid rather than (re)expand it. Real MotionEvents on the self-drawn [CandidateView]
 * plus the [InputView] wiring that flips the state when the A2 grid opens/closes.
 */
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

    /** Tap the fixed chevron band at the right edge (centre = width - expandW/2 = width - 20dp). */
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

    // --- end-to-end through InputView: opening/closing the A2 grid flips the chevron ---

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
        iv.showPanel(null) // collapse
        assertEquals("grid closed → chevron back to down", "⌄", iv.barChevronGlyph())
    }

    // --- debug.17 #66: the candidate strip's horizontal scroll now flings (shared FlingScroller) ---

    @Test fun a_horizontal_flick_hands_off_to_a_fling() {
        val v = CandidateView(ctx)
        v.setContent(List(40) { "候选$it" }, "ni") // many candidates → the strip overflows
        v.measure(
            View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((44 * density).toInt(), View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        assertTrue("the candidate list overflows so there is room to fling", v.maxScrollForTest() > 0f)
        val y = v.height / 2f
        var t = 0L
        fun send(action: Int, x: Float) { v.dispatchTouchEvent(MotionEvent.obtain(0, t, action, x, y, 0)); t += 16 }
        // a steady leftward flick (x: 300→220 over ~80ms ≈ -1000 px/s) in the candidate area (left of the chevron)
        send(MotionEvent.ACTION_DOWN, 300f)
        send(MotionEvent.ACTION_MOVE, 284f); send(MotionEvent.ACTION_MOVE, 268f); send(MotionEvent.ACTION_MOVE, 252f)
        send(MotionEvent.ACTION_MOVE, 236f); send(MotionEvent.ACTION_MOVE, 220f)
        v.dispatchTouchEvent(MotionEvent.obtain(0, t, MotionEvent.ACTION_UP, 220f, y, 0))
        assertTrue("a flick on the candidate strip starts a horizontal fling", v.isFlingingForTest())
        assertTrue("the windowed velocity reflects the leftward flick", v.flingVelocityForTest() < -300f)
    }

    // --- debug.17 FIX-1: new content must CANCEL a running fling so the strip renders from 0 ---

    @Test fun new_content_cancels_a_running_fling_and_renders_from_zero() {
        // Bug: flicking the strip into a fling then typing (new candidates) left scrollX reset to 0 but the
        // fling still running, so the NEXT computeScroll frame restored the stale offset over the 0 — the new
        // list rendered scrolled (could hide the green 首选). setContent must kill the fling, not just zero it.
        val v = CandidateView(ctx)
        v.setContent(List(40) { "候选$it" }, "ni") // overflow → room to fling
        v.measure(
            View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((44 * density).toInt(), View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        val y = v.height / 2f
        var t = 0L
        fun send(action: Int, x: Float) { v.dispatchTouchEvent(MotionEvent.obtain(0, t, action, x, y, 0)); t += 16 }
        send(MotionEvent.ACTION_DOWN, 300f)
        send(MotionEvent.ACTION_MOVE, 284f); send(MotionEvent.ACTION_MOVE, 268f); send(MotionEvent.ACTION_MOVE, 252f)
        send(MotionEvent.ACTION_MOVE, 236f); send(MotionEvent.ACTION_MOVE, 220f)
        v.dispatchTouchEvent(MotionEvent.obtain(0, t, MotionEvent.ACTION_UP, 220f, y, 0))
        assertTrue("precondition: a horizontal fling is running", v.isFlingingForTest())
        assertTrue("precondition: it scrolled away from the left edge", v.scrollXForTest() > 0f)

        v.setContent(List(40) { "新候选$it" }, "hao") // the user typed → new candidates
        assertFalse("new content cancels the fling", v.isFlingingForTest())
        assertEquals("the offset is reset to 0", 0f, v.scrollXForTest(), 0f)
        v.computeScroll() // simulate the next frame
        assertEquals("the next frame does NOT restore the stale fling offset", 0f, v.scrollXForTest(), 0f)
    }
}
