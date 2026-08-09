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
import com.aegis.ime.ime.theme.ImePalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CandidateBarCompletenessTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val density = ctx.resources.displayMetrics.density

    private fun bar(items: List<String>): CandidateView {
        val v = CandidateView(ctx)
        v.applyPalette(ImePalette.STATIC_LIGHT)
        v.setContent(items, "ni")
        v.measure(
            View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((44 * density).toInt(), View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        v.paint()
        return v
    }

    private fun CandidateView.paint() {
        draw(Canvas(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)))
    }

    private fun CandidateView.dragToEnd(): Int {
        val y = height / 2f
        val x0 = width.toFloat()
        var dx = 0f
        var moves = 0
        dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x0, y, 0))
        while (scrollXForTest() < maxScrollForTest() && moves < 4000) {
            dx += width.toFloat() * 4f
            dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, x0 - dx, y, 0))
            moves++
        }
        dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, x0 - dx, y, 0))
        paint()
        return moves
    }

    private fun CandidateView.tapAt(x: Float, y: Float) {
        dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0))
        dispatchTouchEvent(MotionEvent.obtain(0, 10, MotionEvent.ACTION_UP, x, y, 0))
    }

    @Test fun the_strip_holds_every_candidate_the_decoder_produced() {
        val items = List(2120) { "候$it" }
        assertEquals(items.size, bar(items).itemCount())
    }

    @Test fun the_last_candidate_of_a_long_list_is_laid_out_and_tappable() {
        val items = List(1000) { "第${it}字" }
        val v = bar(items)
        var picked = -1
        v.onPick = { picked = it }

        assertTrue(
            "cells past the scrolled extent are not measured up front: ${v.laidOutCellsForTest()}",
            v.laidOutCellsForTest() < items.size,
        )

        val moves = v.dragToEnd()
        assertTrue("the strip scrolls beyond its first screen", moves > 0)
        assertEquals(v.maxScrollForTest(), v.scrollXForTest(), 0.01f)
        assertEquals("scrolling to the end lays out every cell", items.size, v.laidOutCellsForTest())

        val center = v.centerOfCandidateForTest(items.lastIndex)
        assertNotNull("the last candidate is laid out once scrolled to", center)
        val (cx, cy) = center!!
        assertTrue(
            "the last candidate sits inside the visible strip",
            cx >= 0f && cx < v.expandControlBoundsForTest().left,
        )
        v.tapAt(cx, cy)
        assertEquals("tapping the last candidate picks it", items.lastIndex, picked)
    }

    private fun CandidateView.hardFlick() {
        val y = height / 2f
        val x0 = width.toFloat()
        dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x0, y, 0))
        for (k in 1..5) {
            dispatchTouchEvent(MotionEvent.obtain(0, 16L * k, MotionEvent.ACTION_MOVE, x0 - 200f * k, y, 0))
        }
        dispatchTouchEvent(MotionEvent.obtain(0, 96, MotionEvent.ACTION_UP, x0 - 1000f, y, 0))
    }

    @Test fun a_hard_fling_is_not_clamped_by_the_lazy_layout_frontier() {
        val items = List(3000) { "候$it" }
        val v = bar(items)

        v.hardFlick()

        assertTrue("a hard flick hands off to a fling", v.isFlingingForTest())
        assertTrue(
            "cells past the flung extent are not measured up front: ${v.laidOutCellsForTest()}",
            v.laidOutCellsForTest() < items.size,
        )
        assertTrue(
            "the fling stops short of the measured frontier instead of reaching the list end: " +
                "final=${v.flingFinalForTest()} max=${v.maxScrollForTest()} " +
                "laidOut=${v.laidOutCellsForTest()} of ${items.size}",
            v.laidOutCellsForTest() == items.size || v.flingFinalForTest() < v.maxScrollForTest(),
        )
    }

    @Test fun hit_testing_stays_aligned_with_the_drawn_cells_after_scrolling() {
        val items = List(500) { "字$it" }
        val v = bar(items)
        var picked = -1
        v.onPick = { picked = it }
        v.dragToEnd()
        val visibleRight = v.expandControlBoundsForTest().left
        var checked = 0
        for (i in items.indices) {
            val (cx, cy) = v.centerOfCandidateForTest(i) ?: continue
            if (cx < 0f || cx >= visibleRight) continue
            picked = -1
            v.tapAt(cx, cy)
            assertEquals(i, picked)
            checked++
        }
        assertTrue("the end of the strip shows several candidates", checked >= 2)
    }
}
