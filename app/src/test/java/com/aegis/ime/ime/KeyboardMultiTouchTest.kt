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
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.layout.Layouts
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyboardMultiTouchTest {

    private val context = RuntimeEnvironment.getApplication()
    private val density = context.resources.displayMetrics.density

    private fun laidOut(v: KeyboardView): KeyboardView {
        v.measure(
            View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        return v
    }

    private fun alphaView(): KeyboardView = laidOut(KeyboardView(context).apply {
        setLayout(Layouts.forId(LayoutId.ALPHA, Lang.EN), false, false, Lang.EN)
    })

    private fun nineView(): KeyboardView = laidOut(KeyboardView(context).apply {
        setLayout(Layouts.nine(Lang.CN, Layouts.ninePunctuation(), composing = false), false, false, Lang.CN)
    })

    private fun KeyboardView.dispatch(action: Int, t: Long, ids: IntArray, xs: FloatArray, ys: FloatArray): Boolean {
        val pp = Array(ids.size) {
            MotionEvent.PointerProperties().apply { id = ids[it]; toolType = MotionEvent.TOOL_TYPE_FINGER }
        }
        val pc = Array(ids.size) {
            MotionEvent.PointerCoords().apply { x = xs[it]; y = ys[it]; pressure = 1f; size = 1f }
        }
        val e = MotionEvent.obtain(0, t, action, ids.size, pp, pc, 0, 0, 1f, 1f, 0, 0, 0, 0)
        return dispatchTouchEvent(e)
    }

    private fun pointerDown(index: Int) =
        MotionEvent.ACTION_POINTER_DOWN or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
    private fun pointerUp(index: Int) =
        MotionEvent.ACTION_POINTER_UP or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    private val gap = 3f * density
    private val u = 1f / 4.7f
    private fun KeyboardView.cx() = (gap + (0.85f * u * width - gap)) / 2f
    private fun KeyboardView.cellH() = ((0.75f * height - gap) - gap) / 4f
    private fun KeyboardView.colCellY(i: Int) = gap + cellH() * (i + 0.5f)

    private fun KeyboardView.roll(ax: Float, ay: Float, bx: Float, by: Float, aFirst: Boolean): List<String> {
        val out = ArrayList<String>()
        onKey = { out.add(it.output) }
        dispatch(MotionEvent.ACTION_DOWN, 0, intArrayOf(1), floatArrayOf(ax), floatArrayOf(ay))
        dispatch(pointerDown(1), 10, intArrayOf(1, 2), floatArrayOf(ax, bx), floatArrayOf(ay, by))
        if (aFirst) {
            dispatch(pointerUp(0), 20, intArrayOf(1, 2), floatArrayOf(ax, bx), floatArrayOf(ay, by))
            dispatch(MotionEvent.ACTION_UP, 30, intArrayOf(2), floatArrayOf(bx), floatArrayOf(by))
        } else {
            dispatch(pointerUp(1), 20, intArrayOf(1, 2), floatArrayOf(ax, bx), floatArrayOf(ay, by))
            dispatch(MotionEvent.ACTION_UP, 30, intArrayOf(1), floatArrayOf(ax), floatArrayOf(ay))
        }
        return out
    }

    @Test fun rolling_two_letters_on_the_26key_commits_both_in_order_either_lift_order() {
        for (aFirst in listOf(true, false)) {
            val v = alphaView()
            val (ax, ay) = v.centerOfLabelForTest("q")!!
            val (bx, by) = v.centerOfLabelForTest("w")!!
            assertEquals("roll q→w (aFirst=$aFirst) commits both, press order", listOf("q", "w"), v.roll(ax, ay, bx, by, aFirst))
        }
    }

    @Test fun rolling_two_digits_on_the_9key_commits_both_in_order_either_lift_order() {
        for (aFirst in listOf(true, false)) {
            val v = nineView()
            val (ax, ay) = v.centerOfLabelForTest("ABC")!!
            val (bx, by) = v.centerOfLabelForTest("DEF")!!
            assertEquals("roll ABC→DEF (aFirst=$aFirst)", listOf("2", "3"), v.roll(ax, ay, bx, by, aFirst))
        }
    }

    @Test fun mechanical_enumeration_all_pairs_of_a_key_block_roll_cleanly() {
        val v0 = nineView()
        val labels = listOf("ABC", "DEF", "GHI", "JKL", "MNO", "PQRS", "TUV", "WXYZ")
        val out = labels.mapNotNull { v0.centerOfLabelForTest(it)?.let { c -> it to c } }
        val expectedByLabel = mapOf(
            "ABC" to "2", "DEF" to "3", "GHI" to "4", "JKL" to "5",
            "MNO" to "6", "PQRS" to "7", "TUV" to "8", "WXYZ" to "9",
        )
        val fails = ArrayList<String>()
        for ((la, ca) in out) for ((lb, cb) in out) {
            if (la == lb) continue
            for (aFirst in listOf(true, false)) {
                val v = nineView()
                val got = v.roll(ca.first, ca.second, cb.first, cb.second, aFirst)
                val want = listOf(expectedByLabel[la], expectedByLabel[lb])
                if (got != want) fails.add("$la→$lb aFirst=$aFirst got=$got")
            }
        }
        assertEquals("every rolled 9-key pair commits both digits: $fails", emptyList<String>(), fails)
    }

    @Test fun three_finger_roll_commits_all_three_in_press_order() {
        val v = nineView()
        val out = ArrayList<String>()
        v.onKey = { out.add(it.output) }
        val (ax, ay) = v.centerOfLabelForTest("ABC")!!
        val (bx, by) = v.centerOfLabelForTest("DEF")!!
        val (cx, cy) = v.centerOfLabelForTest("GHI")!!
        v.dispatch(MotionEvent.ACTION_DOWN, 0, intArrayOf(1), floatArrayOf(ax), floatArrayOf(ay))
        v.dispatch(pointerDown(1), 10, intArrayOf(1, 2), floatArrayOf(ax, bx), floatArrayOf(ay, by))
        v.dispatch(pointerDown(2), 20, intArrayOf(1, 2, 3), floatArrayOf(ax, bx, cx), floatArrayOf(ay, by, cy))
        v.dispatch(pointerUp(0), 30, intArrayOf(1, 2, 3), floatArrayOf(ax, bx, cx), floatArrayOf(ay, by, cy))
        v.dispatch(pointerUp(0), 40, intArrayOf(2, 3), floatArrayOf(bx, cx), floatArrayOf(by, cy))
        v.dispatch(MotionEvent.ACTION_UP, 50, intArrayOf(3), floatArrayOf(cx), floatArrayOf(cy))
        assertEquals("three-finger roll commits 2,3,4 in press order", listOf("2", "3", "4"), out)
    }

    @Test fun a_second_finger_retap_while_first_rests_does_not_double_emit() {
        val v = nineView()
        val out = ArrayList<String>()
        v.onKey = { out.add(it.output) }
        val (ax, ay) = v.centerOfLabelForTest("ABC")!!
        val (bx, by) = v.centerOfLabelForTest("DEF")!!
        v.dispatch(MotionEvent.ACTION_DOWN, 0, intArrayOf(1), floatArrayOf(ax), floatArrayOf(ay))
        v.dispatch(pointerDown(1), 10, intArrayOf(1, 2), floatArrayOf(ax, bx), floatArrayOf(ay, by))
        v.dispatch(pointerUp(1), 20, intArrayOf(1, 2), floatArrayOf(ax, bx), floatArrayOf(ay, by))
        v.dispatch(pointerDown(1), 30, intArrayOf(1, 2), floatArrayOf(ax, bx), floatArrayOf(ay, by))
        v.dispatch(pointerUp(1), 40, intArrayOf(1, 2), floatArrayOf(ax, bx), floatArrayOf(ay, by))
        v.dispatch(MotionEvent.ACTION_UP, 50, intArrayOf(1), floatArrayOf(ax), floatArrayOf(ay))
        assertEquals("no spurious double-emit: X, then Y once per real tap", listOf("2", "3", "3"), out)
    }

    @Test fun a_grid_tap_by_a_second_finger_commits_while_the_first_rests_on_the_column() {
        val v = nineView()
        val out = ArrayList<String>()
        v.onKey = { out.add(it.output) }
        val colX = v.cx()
        val colY = v.colCellY(0)
        val (bx, by) = v.centerOfLabelForTest("ABC")!!
        v.dispatch(MotionEvent.ACTION_DOWN, 0, intArrayOf(1), floatArrayOf(colX), floatArrayOf(colY))
        v.dispatch(pointerDown(1), 10, intArrayOf(1, 2), floatArrayOf(colX, bx), floatArrayOf(colY, by))
        v.dispatch(pointerUp(1), 20, intArrayOf(1, 2), floatArrayOf(colX, bx), floatArrayOf(colY, by))
        v.dispatch(MotionEvent.ACTION_UP, 30, intArrayOf(1), floatArrayOf(colX), floatArrayOf(colY))
        assertEquals("the grid tap commits and the resting column press still resolves on lift", listOf("2", "，"), out)
    }

    @Test fun a_column_tap_by_a_second_finger_registers_while_the_first_holds_a_grid_key() {
        val v = nineView()
        val out = ArrayList<String>()
        v.onKey = { out.add(it.output) }
        val (ax, ay) = v.centerOfLabelForTest("ABC")!!
        val colX = v.cx()
        val colY = v.colCellY(0)
        v.dispatch(MotionEvent.ACTION_DOWN, 0, intArrayOf(1), floatArrayOf(ax), floatArrayOf(ay))
        v.dispatch(pointerDown(1), 10, intArrayOf(1, 2), floatArrayOf(ax, colX), floatArrayOf(ay, colY))
        v.dispatch(pointerUp(1), 20, intArrayOf(1, 2), floatArrayOf(ax, colX), floatArrayOf(ay, colY))
        v.dispatch(MotionEvent.ACTION_UP, 30, intArrayOf(1), floatArrayOf(ax), floatArrayOf(ay))
        assertEquals("the column tap registers and the held grid key commits in press order", listOf("2", "，"), out)
    }

    @Test fun sequential_non_overlapping_taps_still_commit_both() {
        val v = nineView()
        val out = ArrayList<String>()
        v.onKey = { out.add(it.output) }
        val (ax, ay) = v.centerOfLabelForTest("ABC")!!
        val (bx, by) = v.centerOfLabelForTest("DEF")!!
        v.dispatch(MotionEvent.ACTION_DOWN, 0, intArrayOf(1), floatArrayOf(ax), floatArrayOf(ay))
        v.dispatch(MotionEvent.ACTION_UP, 10, intArrayOf(1), floatArrayOf(ax), floatArrayOf(ay))
        v.dispatch(MotionEvent.ACTION_DOWN, 20, intArrayOf(1), floatArrayOf(bx), floatArrayOf(by))
        v.dispatch(MotionEvent.ACTION_UP, 30, intArrayOf(1), floatArrayOf(bx), floatArrayOf(by))
        assertEquals(listOf("2", "3"), out)
    }
}
