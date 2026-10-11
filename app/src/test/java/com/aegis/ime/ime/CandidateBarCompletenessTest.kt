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
import android.graphics.Paint
import android.os.Looper
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.ime.theme.ImeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

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
        val x0 = expandControlBoundsForTest().left - 1f
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
        val x0 = expandControlBoundsForTest().left - 1f
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

    @Test fun a_candidate_many_strips_wide_is_laid_out_whole_and_scrolls_to_its_end() {
        val long = "很长的候选".repeat(120) + "abc"
        val items = listOf("你好", long, "末")
        val v = bar(items)
        var picked = -1
        v.onPick = { picked = it }
        val visibleRight = v.expandControlBoundsForTest().left
        val padding = 14f * density
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, ImeType.candidate, ctx.resources.displayMetrics)
        }

        val (firstCenter, y) = v.centerOfCandidateForTest(0)!!
        val firstWidth = firstCenter * 2f
        val longWidth = (v.centerOfCandidateForTest(1)!!.first - firstWidth) * 2f
        val lastWidth = paint.measureText("末") + padding * 2f
        assertEquals("the cell spans the whole candidate", paint.measureText(long) + padding * 2f, longWidth, 0.5f)
        assertTrue("the candidate is wider than the strip lays out ahead", longWidth > visibleRight * 8f)
        v.tapAt(firstWidth + padding, y)
        assertEquals("the start of the long candidate picks it", 1, picked)

        v.dragToEnd()
        assertEquals(
            "the strip scrolls by everything that did not fit",
            firstWidth + longWidth + lastWidth - visibleRight,
            v.maxScrollForTest(),
            1f,
        )
        assertEquals(v.maxScrollForTest(), v.scrollXForTest(), 0.01f)
        val longRight = firstWidth + longWidth - v.scrollXForTest()
        assertTrue("the end of the long candidate is on screen: $longRight", longRight > 0f && longRight < visibleRight)
        assertEquals("the last candidate follows it", longRight + lastWidth / 2f, v.centerOfCandidateForTest(2)!!.first, 1f)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        val shot = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(shot))
        fun ink(from: Float, to: Float): Int {
            var count = 0
            for (x in from.toInt() until to.toInt()) {
                for (py in 0 until shot.height) if (shot.getPixel(x, py) != ImePalette.STATIC_LIGHT.keyboardBg) count++
            }
            return count
        }
        val textRight = longRight - padding
        assertTrue(
            "the last characters are painted at the end of the cell",
            ink(textRight - paint.measureText("abc"), textRight) > 0,
        )
        assertEquals("nothing is painted in the padding after them", 0, ink(textRight + 2f, longRight - 2f))

        picked = -1
        v.tapAt(longRight - padding, y)
        assertEquals("the end of the long candidate picks it", 1, picked)
        v.tapAt(longRight + lastWidth / 2f, y)
        assertEquals("the candidate after it is picked on its own cell", 2, picked)
    }
}
