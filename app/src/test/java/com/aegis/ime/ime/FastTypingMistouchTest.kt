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
class FastTypingMistouchTest {

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

    private fun nineView(): KeyboardView = laidOut(KeyboardView(context).apply {
        setLayout(Layouts.nine(Layouts.ninePunctuation(), composing = false), false, false, Lang.CN)
    })

    private fun alphaView(): KeyboardView = laidOut(KeyboardView(context).apply {
        setLayout(Layouts.forId(LayoutId.ALPHA, Lang.EN), false, false, Lang.EN)
    })

    private fun KeyboardView.send(action: Int, x: Float, y: Float, t: Long = 0) =
        dispatchTouchEvent(MotionEvent.obtain(0, t, action, x, y, 0))

    private fun KeyboardView.hitRightOf(label: String): Float =
        keyHitBoundsForTest().first { it.first.label == label }.second.right

    @Test fun a_fast_tap_lifting_past_the_9key_shared_edge_commits_the_down_key() {
        var picked: String? = null
        val v = nineView().apply { onKey = { picked = it.output } }
        val (ax, ay) = v.centerOfLabelForTest("ABC")!!
        val edge = v.hitRightOf("ABC")
        v.send(MotionEvent.ACTION_DOWN, ax, ay, 0)
        v.send(MotionEvent.ACTION_UP, edge + 10f * density, ay, 30)
        assertEquals("a fast tap whose UP drifts into DEF still commits the key under the DOWN", "2", picked)
    }

    @Test fun a_fast_micro_slide_across_the_9key_edge_commits_the_down_key() {
        var picked: String? = null
        val v = nineView().apply { onKey = { picked = it.output } }
        val (_, ay) = v.centerOfLabelForTest("ABC")!!
        val edge = v.hitRightOf("ABC")
        v.send(MotionEvent.ACTION_DOWN, edge - 6f * density, ay, 0)
        v.send(MotionEvent.ACTION_MOVE, edge + 8f * density, ay, 15)
        v.send(MotionEvent.ACTION_UP, edge + 8f * density, ay, 30)
        assertEquals("a fast micro-slide across the shared edge must not retarget the neighbour", "2", picked)
    }

    @Test fun a_fast_tap_on_the_26key_lifting_inside_the_neighbour_commits_the_down_key() {
        var picked: String? = null
        val v = alphaView().apply { onKey = { picked = it.output } }
        val (qx, qy) = v.centerOfLabelForTest("q")!!
        val edge = v.hitRightOf("q")
        v.send(MotionEvent.ACTION_DOWN, qx, qy, 0)
        v.send(MotionEvent.ACTION_UP, edge + 5f * density, qy, 30)
        assertEquals("a fast tap whose UP lands a few dp inside w still commits q", "q", picked)
    }

    @Test fun a_slow_slide_onto_the_9key_neighbour_still_retargets() {
        var picked: String? = null
        val v = nineView().apply { onKey = { picked = it.output } }
        val (ax, ay) = v.centerOfLabelForTest("ABC")!!
        val (bx, by) = v.centerOfLabelForTest("DEF")!!
        v.send(MotionEvent.ACTION_DOWN, ax, ay, 0)
        v.send(MotionEvent.ACTION_MOVE, bx, by, 200)
        v.send(MotionEvent.ACTION_UP, bx, by, 260)
        assertEquals("a held slide-to-correct still commits the neighbour", "3", picked)
    }

    @Test fun a_fast_long_drag_onto_the_9key_neighbour_still_retargets() {
        var picked: String? = null
        val v = nineView().apply { onKey = { picked = it.output } }
        val (ax, ay) = v.centerOfLabelForTest("ABC")!!
        val (bx, by) = v.centerOfLabelForTest("DEF")!!
        v.send(MotionEvent.ACTION_DOWN, ax, ay, 0)
        v.send(MotionEvent.ACTION_MOVE, bx, by, 20)
        v.send(MotionEvent.ACTION_UP, bx, by, 30)
        assertEquals("a fast drag of a full key width still retargets by distance", "3", picked)
    }

    @Test fun a_slow_micro_slide_across_the_9key_edge_still_retargets() {
        var picked: String? = null
        val v = nineView().apply { onKey = { picked = it.output } }
        val (_, ay) = v.centerOfLabelForTest("ABC")!!
        val edge = v.hitRightOf("ABC")
        v.send(MotionEvent.ACTION_DOWN, edge - 6f * density, ay, 0)
        v.send(MotionEvent.ACTION_MOVE, edge + 8f * density, ay, 200)
        v.send(MotionEvent.ACTION_UP, edge + 8f * density, ay, 260)
        assertEquals("a held micro-slide unlocks by time and retargets the neighbour", "3", picked)
    }

    @Test fun a_fast_tap_lifting_into_the_inter_key_gap_commits_the_down_key() {
        var picked: String? = null
        val v = alphaView().apply { onKey = { picked = it.output } }
        val (qx, qy) = v.centerOfLabelForTest("q")!!
        val gapX = (v.boundsOfLabelForTest("q")!!.right + v.boundsOfLabelForTest("w")!!.left) / 2f
        v.send(MotionEvent.ACTION_DOWN, qx, qy, 0)
        v.send(MotionEvent.ACTION_UP, gapX, qy, 30)
        assertEquals("a fast tap lifting into the q/w gap still commits q", "q", picked)
    }

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

    @Test fun a_fast_two_thumb_roll_with_a_micro_slide_commits_both_keys_in_order() {
        val v = nineView()
        val out = ArrayList<String>()
        v.onKey = { out.add(it.output) }
        val (_, ay) = v.centerOfLabelForTest("ABC")!!
        val (bx, by) = v.centerOfLabelForTest("DEF")!!
        val edge = v.hitRightOf("ABC")
        val ax = edge - 6f * density
        val slid = edge + 8f * density
        v.dispatch(MotionEvent.ACTION_DOWN, 0, intArrayOf(1), floatArrayOf(ax), floatArrayOf(ay))
        v.dispatch(MotionEvent.ACTION_MOVE, 20, intArrayOf(1), floatArrayOf(slid), floatArrayOf(ay))
        v.dispatch(pointerDown(1), 40, intArrayOf(1, 2), floatArrayOf(slid, bx), floatArrayOf(ay, by))
        v.dispatch(pointerUp(0), 50, intArrayOf(1, 2), floatArrayOf(slid, bx), floatArrayOf(ay, by))
        v.dispatch(MotionEvent.ACTION_UP, 60, intArrayOf(2), floatArrayOf(bx), floatArrayOf(by))
        assertEquals("a micro-slid first thumb must not steal the second thumb's key", listOf("2", "3"), out)
    }
}
