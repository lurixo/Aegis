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
import android.view.MotionEvent
import android.view.View
import com.aegis.ime.engine.CandidateEngine
import kotlin.math.abs
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

    private fun idleBar(widthDp: Int): CandidateView {
        val view = CandidateView(ctx)
        view.setContent(emptyList(), "")
        view.measure(
            View.MeasureSpec.makeMeasureSpec((widthDp * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((44 * density).toInt(), View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        view.draw(Canvas(Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)))
        return view
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

    @Test fun five_idle_toolbar_controls_have_equal_centered_bounds_and_actions() {
        for (widthDp in listOf(320, 480)) {
            val view = idleBar(widthDp)
            val controls = view.toolbarControlBoundsForTest()
            assertEquals(5, controls.size)
            assertTrue(controls.all { abs(it.width() - controls.first().width()) <= 0.01f })
            assertTrue(controls.all { abs(it.height() - controls.first().height()) <= 0.01f })
            val spacing = controls.zipWithNext { left, right -> right.centerX() - left.centerX() }
            assertTrue(spacing.all { abs(it - spacing.first()) <= 0.01f })
            assertEquals(view.width / 2f, (controls.first().left + controls.last().right) / 2f, 0.01f)
        }
        val view = idleBar(360)
        val actions = ArrayList<String>()
        view.onFunction = { actions += it.name }
        view.onCollapse = { actions += "COLLAPSE" }
        for ((index, rect) in view.toolbarControlBoundsForTest().withIndex()) {
            view.dispatchTouchEvent(MotionEvent.obtain(0, index * 20L, MotionEvent.ACTION_DOWN, rect.centerX(), rect.centerY(), 0))
            view.dispatchTouchEvent(MotionEvent.obtain(0, index * 20L + 10L, MotionEvent.ACTION_UP, rect.centerX(), rect.centerY(), 0))
        }
        assertEquals(listOf("BRAND", "EMOJI", "EDIT", "CLIPBOARD", "COLLAPSE"), actions)
    }


    @Test fun a_horizontal_flick_hands_off_to_a_fling() {
        val v = CandidateView(ctx)
        v.setContent(List(40) { "候选$it" }, "ni")
        v.measure(
            View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((44 * density).toInt(), View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        assertTrue("the candidate list overflows so there is room to fling", v.maxScrollForTest() > 0f)
        val y = v.height / 2f
        var t = 0L
        fun send(action: Int, x: Float) { v.dispatchTouchEvent(MotionEvent.obtain(0, t, action, x, y, 0)); t += 16 }
        send(MotionEvent.ACTION_DOWN, 300f)
        send(MotionEvent.ACTION_MOVE, 284f); send(MotionEvent.ACTION_MOVE, 268f); send(MotionEvent.ACTION_MOVE, 252f)
        send(MotionEvent.ACTION_MOVE, 236f); send(MotionEvent.ACTION_MOVE, 220f)
        v.dispatchTouchEvent(MotionEvent.obtain(0, t, MotionEvent.ACTION_UP, 220f, y, 0))
        assertTrue("a flick on the candidate strip starts a horizontal fling", v.isFlingingForTest())
        assertTrue("the windowed velocity reflects the leftward flick", v.flingVelocityForTest() < -300f)
    }


    @Test fun new_content_cancels_a_running_fling_and_renders_from_zero() {
        val v = CandidateView(ctx)
        v.setContent(List(40) { "候选$it" }, "ni")
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

        v.setContent(List(40) { "新候选$it" }, "hao")
        assertFalse("new content cancels the fling", v.isFlingingForTest())
        assertEquals("the offset is reset to 0", 0f, v.scrollXForTest(), 0f)
        v.computeScroll()
        assertEquals("the next frame does NOT restore the stale fling offset", 0f, v.scrollXForTest(), 0f)
    }
}
