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
import com.aegis.ime.layout.KeyAction
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
        setLayout(Layouts.nine(Layouts.ninePunctuation(), composing = false), false, false, Lang.CN)
    })

    private fun alphaView(): KeyboardView = laidOut(KeyboardView(context).apply {
        setLayout(Layouts.forId(LayoutId.ALPHA, Lang.EN), false, false, Lang.EN)
    })

    private fun KeyboardView.send(action: Int, x: Float, y: Float, t: Long = 0) =
        dispatchTouchEvent(MotionEvent.obtain(0, t, action, x, y, 0))

    private fun KeyboardView.verticalSwipe(label: String, dyFrac: Float): Key? {
        var picked: Key? = null
        onKey = { picked = it }
        val (x, y) = centerOfLabelForTest(label)!!
        send(MotionEvent.ACTION_DOWN, x, y, 0)
        send(MotionEvent.ACTION_MOVE, x, y + dyFrac * height, 12)
        send(MotionEvent.ACTION_UP, x, y + dyFrac * height, 24)
        return picked
    }

    private val digitOf = linkedMapOf(
        "@#" to "1",
        "ABC" to "2", "DEF" to "3", "GHI" to "4", "JKL" to "5",
        "MNO" to "6", "PQRS" to "7", "TUV" to "8", "WXYZ" to "9",
    )

    @Test fun every_main_block_up_swipe_emits_its_literal_digit_and_down_swipe_keeps_the_tap_action() {
        val fails = ArrayList<String>()
        for ((label, digit) in digitOf) {
            val up = nineView().verticalSwipe(label, -0.30f)
            if (up?.output != digit || up?.preeditLiteral != true) {
                fails.add("$label up → got=${up?.output}/${up?.preeditLiteral} want=$digit/true")
            }
            val down = nineView().verticalSwipe(label, 0.30f)
            if (label == "@#") {
                if (down?.action != KeyAction.SWITCH_NUMBERS || down?.preeditLiteral == true) {
                    fails.add("$label down → got=${down?.action}/${down?.preeditLiteral}")
                }
            } else if (down?.output != digit || down?.preeditLiteral == true) {
                fails.add("$label down → got=${down?.output}/${down?.preeditLiteral} want=$digit/false")
            }
        }
        assertEquals("9-key main-block swipe contract: $fails", emptyList<String>(), fails)
    }

    @Test fun the_reported_up_swipe_5_to_2_now_stays_on_5() {
        assertEquals("5", nineView().verticalSwipe("JKL", -0.30f)?.output)
    }

    @Test fun a_down_swipe_from_the_bottom_digit_row_does_not_fall_into_the_function_row() {
        for (label in listOf("PQRS", "TUV", "WXYZ")) {
            assertEquals("$label down-swipe stays on itself", digitOf[label], nineView().verticalSwipe(label, 0.30f)?.output)
        }
    }

    @Test fun a_horizontal_slide_to_a_neighbour_still_retargets() {
        var picked: String? = null
        val v = nineView().apply { onKey = { picked = it.output } }
        val (ax, ay) = v.centerOfLabelForTest("ABC")!!
        val (bx, _) = v.centerOfLabelForTest("DEF")!!
        v.send(MotionEvent.ACTION_DOWN, ax, ay, 0)
        v.send(MotionEvent.ACTION_MOVE, bx, ay, 12)
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

    @Test fun redo_up_swipe_emits_zero_without_running_clear() {
        var key: Key? = null
        val view = nineView().apply { onKey = { key = it } }
        val (x, y) = view.centerOfActionForTest(KeyAction.CLEAR_COMPOSING)!!
        view.send(MotionEvent.ACTION_DOWN, x, y, 0)
        view.send(MotionEvent.ACTION_MOVE, x, y - 0.30f * view.height, 12)
        view.send(MotionEvent.ACTION_UP, x, y - 0.30f * view.height, 24)
        assertEquals("0", key?.output)
        assertEquals(KeyAction.COMMIT, key?.action)
        assertEquals(true, key?.preeditLiteral)
    }


    @Test fun the_twentysix_key_up_flick_is_marked_as_a_preedit_literal() {
        val up = ArrayList<Key>()
        val v1 = alphaView().apply { onKey = { up.add(it) } }
        val (dx, dy) = v1.centerOfLabelForTest("d")!!
        v1.send(MotionEvent.ACTION_DOWN, dx, dy, 0)
        v1.send(MotionEvent.ACTION_MOVE, dx, dy - (swipeThreshold + 15f), 12)
        v1.send(MotionEvent.ACTION_UP, dx, dy - (swipeThreshold + 15f), 24)
        assertEquals("@", up.single().output)
        assertEquals(true, up.single().preeditLiteral)

        val down = ArrayList<String>()
        val v2 = alphaView().apply { onKey = { down.add(it.output) } }
        v2.send(MotionEvent.ACTION_DOWN, dx, dy, 0)
        v2.send(MotionEvent.ACTION_MOVE, dx, dy + (swipeThreshold + 15f), 12)
        v2.send(MotionEvent.ACTION_UP, dx, dy + (swipeThreshold + 15f), 24)
        assertEquals("26-key down-flick still commits the letter", listOf("d"), down)
    }
}
