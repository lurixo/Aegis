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

import android.os.Looper
import android.view.MotionEvent
import android.view.View
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.Layouts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyboardViewInteractionTest {

    private val context = RuntimeEnvironment.getApplication()
    private val density = context.resources.displayMetrics.density
    private val gap = 3f * density
    private val u = 1f / 4.7f

    private fun nineView(left: List<Key>, composing: Boolean): KeyboardView {
        val v = KeyboardView(context)
        v.setLayout(Layouts.nine(Lang.CN, left, composing), false, false, Lang.CN)
        v.measure(
            View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        return v
    }

    private fun alphaView(): KeyboardView {
        val v = KeyboardView(context)
        v.setLayout(Layouts.forId(com.aegis.ime.layout.LayoutId.ALPHA, Lang.EN), false, false, Lang.EN)
        v.measure(
            View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        return v
    }

    private fun KeyboardView.send(action: Int, x: Float, y: Float, t: Long = 0) =
        dispatchTouchEvent(MotionEvent.obtain(0, t, action, x, y, 0))

    private fun KeyboardView.tap(x: Float, y: Float) {
        send(MotionEvent.ACTION_DOWN, x, y, 0)
        send(MotionEvent.ACTION_UP, x, y, 10)
    }

    private fun KeyboardView.regTop() = gap
    private fun KeyboardView.cx() = (gap + (0.85f * u * width - gap)) / 2f
    private fun KeyboardView.cellH() = ((0.75f * height - gap) - gap) / 4f
    private fun KeyboardView.colCellY(i: Int) = regTop() + cellH() * (i + 0.5f)

    @Test fun tap_punctuation_in_scroll_column_commits_it() {
        var picked: Key? = null
        val v = nineView(Layouts.ninePunctuation(), composing = false).apply { onKey = { picked = it } }
        v.tap(v.cx(), v.colCellY(0))
        assertNotNull("a tap in the scroll column must pick an item", picked)
        assertEquals("，", picked?.label)
    }

    @Test fun scroll_then_tap_picks_a_later_punctuation() {
        var picked: Key? = null
        val v = nineView(Layouts.ninePunctuation(), composing = false).apply { onKey = { picked = it } }
        val x = v.cx()
        v.send(MotionEvent.ACTION_DOWN, x, v.regTop() + v.cellH() * 3.5f, 0)
        v.send(MotionEvent.ACTION_MOVE, x, v.regTop() + v.cellH() * 0.5f, 16)
        v.send(MotionEvent.ACTION_UP, x, v.regTop() + v.cellH() * 0.5f, 32)
        v.tap(x, v.colCellY(0))
        assertNotNull(picked)
        assertNotEquals("after scrolling, the top row is no longer the first punctuation", "，", picked?.label)
        assertEquals("！", picked?.label)
    }

    private fun longComboView(): KeyboardView {
        val combos = (1..24).map { Key("p$it", output = "p$it", action = KeyAction.PICK_READING) }
        return nineView(combos, composing = true)
    }

    private fun KeyboardView.fastFlickUp() {
        val x = cx()
        send(MotionEvent.ACTION_DOWN, x, regTop() + cellH() * 3.5f, 0)
        send(MotionEvent.ACTION_MOVE, x, regTop() + cellH() * 2.0f, 8)
        send(MotionEvent.ACTION_MOVE, x, regTop() + cellH() * 0.5f, 16)
        send(MotionEvent.ACTION_UP, x, regTop() + cellH() * 0.5f, 16)
    }

    @Test fun a_fast_flick_flings_to_the_bottom_in_one_gesture() {
        val v = longComboView()
        assertTrue("the list overflows so there is somewhere to scroll", v.maxScrollForTest() > 0f)
        v.fastFlickUp()
        assertTrue("a fast flick starts a momentum fling", v.isFlingingForTest())
        assertEquals(
            "the fling reaches the bottom in ONE gesture",
            v.maxScrollForTest(), v.flingFinalForTest(), 1f,
        )
    }

    @Test fun tapping_during_a_fling_stops_it_without_picking() {
        val v = longComboView()
        var picked: Key? = null
        v.onKey = { picked = it }
        v.fastFlickUp()
        assertTrue("precondition: a fling is running", v.isFlingingForTest())
        v.tap(v.cx(), v.colCellY(0))
        assertFalse("the tap halts the fling", v.isFlingingForTest())
        assertNull("halting a fling must not select an item", picked)
    }

    @Test fun a_drag_that_pauses_before_release_does_not_fling() {
        val v = longComboView()
        val x = v.cx()
        v.send(MotionEvent.ACTION_DOWN, x, v.regTop() + v.cellH() * 3.5f, 0)
        v.send(MotionEvent.ACTION_MOVE, x, v.regTop() + v.cellH() * 2.5f, 200)
        v.send(MotionEvent.ACTION_MOVE, x, v.regTop() + v.cellH() * 2.5f, 500)
        v.send(MotionEvent.ACTION_UP, x, v.regTop() + v.cellH() * 2.5f, 500)
        assertFalse("a paused-before-release drag must not fling", v.isFlingingForTest())
        assertTrue("but it did scroll while dragging", v.scrollOffsetForTest() > 0f)
    }

    @Test fun tap_letter_key_outside_scroll_column_still_works() {
        var picked: Key? = null
        val v = nineView(Layouts.ninePunctuation(), composing = false).apply { onKey = { picked = it } }
        v.tap(2.5f * u * v.width, 0.125f * v.height)
        assertNotNull(picked)
        assertEquals("ABC", picked?.label)
        assertEquals("2", picked?.output)
    }

    @Test fun tap_combo_in_composing_column_fires_pick_reading() {
        val combos = listOf(
            Key("hao", output = "hao", action = KeyAction.PICK_READING),
            Key("gao", output = "gao", action = KeyAction.PICK_READING),
        )
        var picked: Key? = null
        val v = nineView(combos, composing = true).apply { onKey = { picked = it } }
        v.tap(v.cx(), v.colCellY(1))
        assertNotNull(picked)
        assertEquals(KeyAction.PICK_READING, picked?.action)
        assertEquals("gao", picked?.label)
    }

    @Test fun a_pure_tap_does_not_scroll() {
        var picked: Key? = null
        val v = nineView(Layouts.ninePunctuation(), composing = false).apply { onKey = { picked = it } }
        v.tap(v.cx(), v.colCellY(1))
        assertEquals("。", picked?.label)
    }

    @Test fun non_scrollable_short_list_taps_still_resolve() {
        var picked: Key? = null
        val v = nineView(listOf(Key("ni", output = "ni", action = KeyAction.PICK_READING)), composing = true)
            .apply { onKey = { picked = it } }
        v.tap(v.cx(), v.colCellY(0))
        assertEquals("ni", picked?.label)
        assertNull("tapping below the single item picks nothing, never crashes", run {
            picked = null; v.tap(v.cx(), v.regTop() + v.cellH() * 3.5f); picked
        })
    }


    private fun KeyboardView.tapAt(x: Float, y: Float, t: Long) {
        send(MotionEvent.ACTION_DOWN, x, y, t)
        send(MotionEvent.ACTION_UP, x, y, t + 10)
    }

    @Test fun a_quick_second_shift_tap_promotes_one_shot_to_caps_lock() {
        val emitted = mutableListOf<KeyAction>()
        val v = alphaView().apply { onKey = { emitted.add(it.action) } }
        val (sx, sy) = v.centerOfActionForTest(KeyAction.SHIFT)!!
        v.tapAt(sx, sy, 0)
        v.tapAt(sx, sy, 100)
        assertEquals(listOf(KeyAction.SHIFT, KeyAction.SHIFT_LOCK), emitted)
    }

    @Test fun two_slow_shift_taps_stay_one_shot_never_lock() {
        val emitted = mutableListOf<KeyAction>()
        val v = alphaView().apply { onKey = { emitted.add(it.action) } }
        val (sx, sy) = v.centerOfActionForTest(KeyAction.SHIFT)!!
        v.tapAt(sx, sy, 0)
        v.tapAt(sx, sy, 500)
        assertEquals(listOf(KeyAction.SHIFT, KeyAction.SHIFT), emitted)
    }

    @Test fun nine_key_is_slightly_taller_than_the_base_row_height() {
        val v = nineView(Layouts.ninePunctuation(), composing = false)
        val rows = 4
        val baseHeight = rows * 52f * density + (rows + 1) * 6f * density
        val h = v.measuredHeight.toFloat()
        assertTrue("9-key taller than the un-bumped base ($h vs $baseHeight)", h > baseHeight + 1f)
        assertTrue("…but only slightly (≤ +40dp total)", h <= baseHeight + 40f * density)
    }

    @Test fun shift_glyph_state_is_off_once_or_lock() {
        val alpha = Layouts.forId(com.aegis.ime.layout.LayoutId.ALPHA, Lang.EN)
        val v = alphaView()
        v.setLayout(alpha, false, false, Lang.EN); assertEquals("OFF", v.shiftRenderState())
        v.setLayout(alpha, true, false, Lang.EN); assertEquals("ONCE (hollow arrow)", "ONCE", v.shiftRenderState())
        v.setLayout(alpha, true, true, Lang.EN); assertEquals("LOCK (solid arrow)", "LOCK", v.shiftRenderState())
    }


    @Test fun dragging_scrolls_exactly_one_to_one_with_the_finger() {
        val v = longComboView()
        assertTrue("the list overflows so 1:1 has room", v.maxScrollForTest() > 120f)
        val x = v.cx()
        val y0 = v.regTop() + v.cellH() * 3.5f
        v.send(MotionEvent.ACTION_DOWN, x, y0, 0)
        v.send(MotionEvent.ACTION_MOVE, x, y0 - 80f, 16)
        assertEquals("content moved exactly 80px (1:1)", 80f, v.scrollOffsetForTest(), 0.5f)
        v.send(MotionEvent.ACTION_MOVE, x, y0 - 30f, 32)
        assertEquals("still 1:1 after reversing direction", 30f, v.scrollOffsetForTest(), 0.5f)
        v.send(MotionEvent.ACTION_UP, x, y0 - 30f, 48)
    }

    @Test fun windowed_velocity_matches_a_steady_flick_speed() {
        val v = longComboView()
        val x = v.cx()
        val y0 = v.regTop() + 100f
        v.send(MotionEvent.ACTION_DOWN, x, y0, 0)
        v.send(MotionEvent.ACTION_MOVE, x, y0 - 16f, 16)
        v.send(MotionEvent.ACTION_MOVE, x, y0 - 32f, 32)
        v.send(MotionEvent.ACTION_MOVE, x, y0 - 48f, 48)
        v.send(MotionEvent.ACTION_MOVE, x, y0 - 64f, 64)
        v.send(MotionEvent.ACTION_MOVE, x, y0 - 80f, 80)
        assertEquals("release velocity ≈ the steady flick speed", -1000f, v.flingVelocityForTest(), 80f)
    }

    @Test fun a_flick_that_eases_off_at_the_very_end_still_flings() {
        val v = longComboView()
        val x = v.cx()
        fun y(c: Float) = v.regTop() + v.cellH() * c
        v.send(MotionEvent.ACTION_DOWN, x, y(3.8f), 0)
        v.send(MotionEvent.ACTION_MOVE, x, y(3.0f), 16)
        v.send(MotionEvent.ACTION_MOVE, x, y(2.0f), 32)
        v.send(MotionEvent.ACTION_MOVE, x, y(1.0f), 48)
        v.send(MotionEvent.ACTION_MOVE, x, y(1.0f), 80)
        v.send(MotionEvent.ACTION_UP, x, y(1.0f), 80)
        assertTrue("a real flick with a soft finish still hands off to a fling", v.isFlingingForTest())
    }

    @Test fun new_column_content_cancels_a_running_fling_and_renders_from_zero() {
        val v = longComboView()
        v.fastFlickUp()
        assertTrue("precondition: a fling is running", v.isFlingingForTest())
        assertTrue("precondition: it scrolled away from the top", v.scrollOffsetForTest() > 0f)

        val other = (1..24).map { Key("q$it", output = "q$it", action = KeyAction.PICK_READING) }
        v.setLayout(Layouts.nine(Lang.CN, other, composing = true), false, false, Lang.CN)
        assertFalse("the content-change reset cancels the fling", v.isFlingingForTest())
        assertEquals("the offset is reset to 0", 0f, v.scrollOffsetForTest(), 0f)
        v.computeScroll()
        assertEquals("the next frame does NOT restore the stale fling offset", 0f, v.scrollOffsetForTest(), 0f)
    }

    @Test fun reversing_after_overscroll_tracks_the_finger_immediately() {
        val v = longComboView()
        val max = v.maxScrollForTest()
        assertTrue("the list overflows", max > 0f)
        val x = v.cx()
        val y0 = v.regTop() + v.cellH() * 3.5f
        v.send(MotionEvent.ACTION_DOWN, x, y0, 0)
        v.send(MotionEvent.ACTION_MOVE, x, y0 - (max + 200f), 16)
        assertEquals("pinned at the bottom", max, v.scrollOffsetForTest(), 0.5f)
        v.send(MotionEvent.ACTION_MOVE, x, y0 - (max + 200f) + 30f, 32)
        assertEquals("reverse tracks the finger 1:1, no absorbed overshoot", max - 30f, v.scrollOffsetForTest(), 0.5f)
        v.send(MotionEvent.ACTION_UP, x, y0 - (max + 200f) + 30f, 48)
    }


    private fun numpadView(): KeyboardView {
        val v = KeyboardView(context)
        v.setLayout(Layouts.numpad(Layouts.numpadOperators()), false, false, Lang.CN)
        v.measure(
            View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        return v
    }

    private fun KeyboardView.opCx() = (0.85f * u * width) / 2f
    private fun KeyboardView.opCellH() = (height - 2 * gap) / 4f
    private fun KeyboardView.opCellY(i: Int) = gap + opCellH() * (i + 0.5f)

    @Test fun all_four_row_pages_share_one_height_so_switching_never_resizes() {
        fun measuredH(layout: com.aegis.ime.layout.KeyboardLayout): Int {
            val v = KeyboardView(context)
            v.setLayout(layout, false, false, Lang.CN)
            v.measure(
                View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            return v.measuredHeight
        }
        val nineH = measuredH(Layouts.nine(Lang.CN, Layouts.ninePunctuation()))
        assertEquals("numpad matches the 9-key (no 9-key⇄123 resize)", nineH, measuredH(Layouts.numpad()))
        assertEquals("number page matches", nineH, measuredH(Layouts.forId(com.aegis.ime.layout.LayoutId.NUMBER, Lang.CN)))
        assertEquals("symbol page matches", nineH, measuredH(Layouts.forId(com.aegis.ime.layout.LayoutId.SYMBOL, Lang.CN)))
        assertTrue("the 5-row 26-key keeps the base height and stays taller",
            measuredH(Layouts.forId(com.aegis.ime.layout.LayoutId.ALPHA, Lang.CN)) > nineH)
    }


    private fun KeyboardView.holdFirstAction(action: KeyAction, holdMs: Long): List<String> {
        val emitted = mutableListOf<String>()
        onKey = { emitted.add(it.output) }
        val (x, y) = centerOfActionForTest(action)!!
        send(MotionEvent.ACTION_DOWN, x, y, 0)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(holdMs))
        send(MotionEvent.ACTION_UP, x, y, holdMs)
        return emitted
    }

    @Test fun a_held_9key_digit_does_NOT_auto_repeat() {
        val emitted = nineView(Layouts.ninePunctuation(), composing = false).holdFirstAction(KeyAction.COMMIT, 700)
        assertEquals("a held 9-key digit emits exactly once (no repeat)", 1, emitted.size)
    }

    @Test fun a_held_english_letter_does_NOT_auto_repeat() {
        val emitted = alphaView().holdFirstAction(KeyAction.COMMIT, 700)
        assertEquals("a held English letter emits exactly once (no repeat)", 1, emitted.size)
    }

    @Test fun a_held_backspace_still_auto_repeats() {
        val emitted = alphaView().holdFirstAction(KeyAction.BACKSPACE, 700)
        assertTrue("a held backspace auto-repeats (got ${emitted.size})", emitted.size >= 3)
    }

    @Test fun a_quick_tap_emits_exactly_once_no_repeat() {
        val emitted = mutableListOf<String>()
        val v = nineView(Layouts.ninePunctuation(), composing = false).apply { onKey = { emitted.add(it.output) } }
        val (x, y) = v.centerOfActionForTest(KeyAction.COMMIT)!!
        v.send(MotionEvent.ACTION_DOWN, x, y, 0)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        v.send(MotionEvent.ACTION_UP, x, y, 100)
        assertEquals("a quick tap emits exactly once", 1, emitted.size)
    }

    @Test fun tapping_an_operator_in_the_numpad_column_commits_it() {
        var picked: Key? = null
        val v = numpadView().apply { onKey = { picked = it } }
        v.tap(v.opCx(), v.opCellY(0))
        assertEquals("+", picked?.label)
    }

    @Test fun the_numpad_operator_column_scrolls_one_to_one_too() {
        val v = numpadView()
        assertTrue("9 operators over 4 visible → it overflows", v.maxScrollForTest() > 40f)
        val x = v.opCx(); val y0 = v.opCellY(3)
        v.send(MotionEvent.ACTION_DOWN, x, y0, 0)
        v.send(MotionEvent.ACTION_MOVE, x, y0 - 40f, 16)
        assertEquals("the operator strip tracks the finger 1:1", 40f, v.scrollOffsetForTest(), 0.5f)
        v.send(MotionEvent.ACTION_UP, x, y0 - 40f, 32)
    }
}
