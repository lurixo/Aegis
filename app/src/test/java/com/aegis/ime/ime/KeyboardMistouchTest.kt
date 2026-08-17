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

import com.aegis.ime.layout.KeyAction

import android.view.MotionEvent
import android.view.View
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.layout.Layouts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyboardMistouchTest {

    private val context = RuntimeEnvironment.getApplication()
    private val density = context.resources.displayMetrics.density
    private val rowHeight = 52f * density

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

    private fun KeyboardView.send(action: Int, x: Float, y: Float, t: Long = 0) =
        dispatchTouchEvent(MotionEvent.obtain(0, t, action, x, y, 0))

    private fun KeyboardView.tapPick(x: Float, y: Float): Key? {
        var picked: Key? = null
        onKey = { picked = it }
        send(MotionEvent.ACTION_DOWN, x, y, 0)
        send(MotionEvent.ACTION_UP, x, y, 10)
        return picked
    }


    @Test fun a_tap_in_a_narrow_inter_key_gap_still_snaps_to_a_key() {
        val v = nineView()
        val (ax, ay) = v.centerOfLabelForTest("ABC")!!
        val (bx, _) = v.centerOfLabelForTest("DEF")!!
        val gapX = (ax + bx) / 2f
        assertNotNull("a tap in the inter-key gap must still commit a key", v.tapPick(gapX, ay))
    }

    @Test fun a_tap_just_below_the_bottom_row_within_half_a_key_still_lands() {
        val v = nineView()
        val (sx, sy) = v.centerOfActionForTest(KeyAction.SPACE)!!
        val bottom = sy + (rowHeight * 0.30f)
        assertNotNull("a tap ~0.3 key below the bottom row commits the bottom key", v.tapPick(sx, bottom))
    }

    @Test fun a_tap_well_below_the_keyboard_is_dropped() {
        val v = nineView()
        val (sx, _) = v.centerOfActionForTest(KeyAction.SPACE)!!
        val farBelow = v.height + rowHeight
        assertNull("a tap well past the bottom edge is dropped, not pulled onto an edge key", v.tapPick(sx, farBelow))
    }

    @Test fun a_tap_far_off_the_left_edge_is_dropped() {
        val v = nineView()
        val (_, ay) = v.centerOfLabelForTest("ABC")!!
        assertNull("a tap a full key-height off the left edge is dropped", v.tapPick(-rowHeight, ay))
    }


    private fun KeyboardView.pressMoveUp(dx: Float, dy: Float, downX: Float, downY: Float, moveT: Long = 10, upT: Long = 20): Key? {
        var picked: Key? = null
        onKey = { picked = it }
        send(MotionEvent.ACTION_DOWN, downX, downY, 0)
        send(MotionEvent.ACTION_MOVE, downX + dx, downY + dy, moveT)
        send(MotionEvent.ACTION_UP, downX + dx, downY + dy, upT)
        return picked
    }

    @Test fun a_micro_move_within_the_pressed_key_still_commits_that_key() {
        val v = nineView()
        val (ax, ay) = v.centerOfLabelForTest("ABC")!!
        assertEquals("2", v.pressMoveUp(6f * density, 4f * density, ax, ay)?.output)
    }

    @Test fun a_slide_onto_the_neighbour_retargets_slide_to_correct() {
        val v = nineView()
        val (ax, ay) = v.centerOfLabelForTest("ABC")!!
        val (bx, by) = v.centerOfLabelForTest("DEF")!!
        assertEquals("3", v.pressMoveUp(bx - ax, by - ay, ax, ay, 200, 260)?.output)
    }

    @Test fun a_slide_that_stops_at_the_shared_edge_does_not_flip() {
        val v = nineView()
        val (ax, ay) = v.centerOfLabelForTest("ABC")!!
        val p = v.centerOfLabelForTest("ABC")!!
        assertEquals("2", v.pressMoveUp(20f * density, 0f, p.first, p.second)?.output)
    }
}
