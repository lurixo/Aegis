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

/**
 * ④ On the 9-key, a vertical swipe on a digit must resolve to a single click on the PRESSED key — never drift
 * to a neighbour (up 5→2) or the function row (down). Mechanically enumerates all 8 digit blocks × {up, down};
 * asserts a horizontal slide still retargets ("point at the right key"), and that the 26-key flick is untouched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NineVerticalSwipeTest {

    private val context = RuntimeEnvironment.getApplication()
    private val density = context.resources.displayMetrics.density
    private val swipeThreshold = 24f * density

    private fun laidOut(v: KeyboardView): KeyboardView {
        v.measure(
            View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        return v
    }

    private fun nineView(): KeyboardView = laidOut(KeyboardView(context).apply {
        setLayout(Layouts.nine(Lang.CN, Layouts.ninePunctuation(), composing = false), false, false, Lang.CN)
    })

    private fun alphaView(): KeyboardView = laidOut(KeyboardView(context).apply {
        setLayout(Layouts.forId(LayoutId.ALPHA, Lang.EN), false, false, Lang.EN)
    })

    private fun KeyboardView.send(action: Int, x: Float, y: Float, t: Long = 0) =
        dispatchTouchEvent(MotionEvent.obtain(0, t, action, x, y, 0))

    /** Press [label], drag straight [dyFrac]·height vertically, lift — return the emitted output. */
    private fun KeyboardView.verticalSwipe(label: String, dyFrac: Float): String? {
        var picked: String? = null
        onKey = { picked = it.output }
        val (x, y) = centerOfLabelForTest(label)!!
        send(MotionEvent.ACTION_DOWN, x, y, 0)
        send(MotionEvent.ACTION_MOVE, x, y + dyFrac * height, 12)
        send(MotionEvent.ACTION_UP, x, y + dyFrac * height, 24)
        return picked
    }

    private val digitOf = mapOf(
        "ABC" to "2", "DEF" to "3", "GHI" to "4", "JKL" to "5",
        "MNO" to "6", "PQRS" to "7", "TUV" to "8", "WXYZ" to "9",
    )

    @Test fun every_digit_block_up_and_down_swipe_commits_the_pressed_digit() {
        val fails = ArrayList<String>()
        for ((label, digit) in digitOf) {
            for (dir in listOf(-0.30f, 0.30f)) { // up, down — each crosses into a neighbour / the function row
                val v = nineView()
                val got = v.verticalSwipe(label, dir)
                if (got != digit) fails.add("$label ${if (dir < 0) "up" else "down"} → got=$got want=$digit")
            }
        }
        assertEquals("every 9-key vertical swipe = a single click on the pressed digit: $fails", emptyList<String>(), fails)
    }

    @Test fun the_reported_up_swipe_5_to_2_now_stays_on_5() {
        // The exact user report: an up-swipe on 5 (JKL) used to drift to 2 (ABC).
        assertEquals("5", nineView().verticalSwipe("JKL", -0.30f))
    }

    @Test fun a_down_swipe_from_the_bottom_digit_row_does_not_fall_into_the_function_row() {
        // PQRS/TUV/WXYZ sit just above 空格/中英/123 — a down-swipe used to commit those instead.
        for (label in listOf("PQRS", "TUV", "WXYZ")) {
            assertEquals("$label down-swipe stays on itself", digitOf[label], nineView().verticalSwipe(label, 0.30f))
        }
    }

    @Test fun a_horizontal_slide_to_a_neighbour_still_retargets() {
        // 指哪打哪: a deliberate horizontal slide from ABC onto DEF still commits DEF.
        var picked: String? = null
        val v = nineView().apply { onKey = { picked = it.output } }
        val (ax, ay) = v.centerOfLabelForTest("ABC")!!
        val (bx, _) = v.centerOfLabelForTest("DEF")!!
        v.send(MotionEvent.ACTION_DOWN, ax, ay, 0)
        v.send(MotionEvent.ACTION_MOVE, bx, ay, 12) // horizontal only (same y)
        v.send(MotionEvent.ACTION_UP, bx, ay, 24)
        assertEquals("3", picked)
    }

    @Test fun a_plain_tap_still_commits_the_pressed_digit() {
        var picked: String? = null
        val v = nineView().apply { onKey = { picked = it.output } }
        val (x, y) = v.centerOfLabelForTest("MNO")!!
        v.send(MotionEvent.ACTION_DOWN, x, y, 0)
        v.send(MotionEvent.ACTION_UP, x, y, 8)
        assertEquals("6", picked)
    }

    // ---- 26-key flick zero regression: the 9-key fix must not touch the ALPHA vertical flick ----

    @Test fun the_twentysix_key_vertical_flick_is_unaffected() {
        val up = ArrayList<String>()
        val v1 = alphaView().apply { onKey = { up.add(it.output) } }
        val (dx, dy) = v1.centerOfLabelForTest("d")!! // sub "@"
        v1.send(MotionEvent.ACTION_DOWN, dx, dy, 0)
        v1.send(MotionEvent.ACTION_MOVE, dx, dy - (swipeThreshold + 15f), 12)
        v1.send(MotionEvent.ACTION_UP, dx, dy - (swipeThreshold + 15f), 24)
        assertEquals("26-key up-flick still commits the symbol", listOf("@"), up)

        val down = ArrayList<String>()
        val v2 = alphaView().apply { onKey = { down.add(it.output) } }
        v2.send(MotionEvent.ACTION_DOWN, dx, dy, 0)
        v2.send(MotionEvent.ACTION_MOVE, dx, dy + (swipeThreshold + 15f), 12)
        v2.send(MotionEvent.ACTION_UP, dx, dy + (swipeThreshold + 15f), 24)
        assertEquals("26-key down-flick still commits the letter", listOf("d"), down)
    }
}
