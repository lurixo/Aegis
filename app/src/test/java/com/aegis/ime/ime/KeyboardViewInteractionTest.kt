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
import org.robolectric.annotation.Config

/**
 * REAL interaction tests for the self-drawn [KeyboardView] (A3) — Robolectric dispatches actual
 * MotionEvents on the JVM so the touch/scroll bugs that only surface at runtime (backspace, follow-finger
 * scroll, left-column hit-testing) are caught in CI, never again only on real hardware.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyboardViewInteractionTest {

    private val context = RuntimeEnvironment.getApplication()
    private val density = context.resources.displayMetrics.density
    private val gap = 6f * density
    private val u = 1f / 4.7f // debug.16 item5: widened left column → NINE_LEFT_U(1.0)|1|1|1|0.7 = 4.7 units

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

    /** I4: a laid-out 26-key (EN) view — the only layout carrying the shift key. */
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

    // --- geometry of the scroll column (mirrors Layouts.nine + KeyboardView.relayout) ---
    private fun KeyboardView.regTop() = gap
    private fun KeyboardView.cx() = (gap + (1.0f * u * width - gap)) / 2f // left column is now NINE_LEFT_U(1.0) wide
    private fun KeyboardView.cellH() = ((0.75f * height - gap) - gap) / 4f
    private fun KeyboardView.colCellY(i: Int) = regTop() + cellH() * (i + 0.5f)

    @Test fun tap_punctuation_in_scroll_column_commits_it() {
        var picked: Key? = null
        val v = nineView(Layouts.ninePunctuation(), composing = false).apply { onKey = { picked = it } }
        v.tap(v.cx(), v.colCellY(0)) // first row of the punctuation list
        assertNotNull("a tap in the scroll column must pick an item", picked)
        assertEquals("，", picked?.label)
    }

    @Test fun scroll_then_tap_picks_a_later_punctuation() {
        // Follow-finger: drag UP by 3 cells, then tap the TOP row — it must now pick a LATER punctuation,
        // proving the list actually scrolled (and that a small drag is enough — fixes 滑不动/不跟手).
        var picked: Key? = null
        val v = nineView(Layouts.ninePunctuation(), composing = false).apply { onKey = { picked = it } }
        val x = v.cx()
        v.send(MotionEvent.ACTION_DOWN, x, v.regTop() + v.cellH() * 3.5f, 0)
        v.send(MotionEvent.ACTION_MOVE, x, v.regTop() + v.cellH() * 0.5f, 16) // drag up 3 cells
        v.send(MotionEvent.ACTION_UP, x, v.regTop() + v.cellH() * 0.5f, 32)
        v.tap(x, v.colCellY(0)) // tap the top row after scrolling
        assertNotNull(picked)
        assertNotEquals("after scrolling, the top row is no longer the first punctuation", "，", picked?.label)
        // item index 3 in ，。？！…：；~.-@自定义 is ！
        assertEquals("！", picked?.label)
    }

    /** A long combo list (24 rows) — overflows the ~4-cell region so a fling has somewhere to go. */
    private fun longComboView(): KeyboardView {
        val combos = (1..24).map { Key("p$it", output = "p$it", action = KeyAction.PICK_READING) }
        return nineView(combos, composing = true)
    }

    /** A fast upward flick (≥2 MOVE samples, short dt) starting near the bottom of the visible region. */
    private fun KeyboardView.fastFlickUp() {
        val x = cx()
        send(MotionEvent.ACTION_DOWN, x, regTop() + cellH() * 3.5f, 0)
        send(MotionEvent.ACTION_MOVE, x, regTop() + cellH() * 2.0f, 8)
        send(MotionEvent.ACTION_MOVE, x, regTop() + cellH() * 0.5f, 16)
        send(MotionEvent.ACTION_UP, x, regTop() + cellH() * 0.5f, 16)
    }

    @Test fun a_fast_flick_flings_to_the_bottom_in_one_gesture() {
        // U7/U17: the core requirement — a single quick flick must hand off to a
        // momentum fling whose final resting position is the very bottom of the list.
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
        // U7/U17 (易误触): tapping a moving list halts the fling and must NOT commit whatever is under the
        // finger — otherwise stopping a scroll mis-types a combo.
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
        // Deliberate positioning: drag, then hold still before lifting → zero release velocity → the list
        // must rest exactly where the finger left it (no surprise momentum past the chosen row).
        val v = longComboView()
        val x = v.cx()
        v.send(MotionEvent.ACTION_DOWN, x, v.regTop() + v.cellH() * 3.5f, 0)
        v.send(MotionEvent.ACTION_MOVE, x, v.regTop() + v.cellH() * 2.5f, 200) // dragged up one cell
        v.send(MotionEvent.ACTION_MOVE, x, v.regTop() + v.cellH() * 2.5f, 500) // …then held still (v=0)
        v.send(MotionEvent.ACTION_UP, x, v.regTop() + v.cellH() * 2.5f, 500)
        assertFalse("a paused-before-release drag must not fling", v.isFlingingForTest())
        assertTrue("but it did scroll while dragging", v.scrollOffsetForTest() > 0f)
    }

    @Test fun tap_letter_key_outside_scroll_column_still_works() {
        // Regression guard: the scroll code must not swallow normal key taps. ABC (T9 digit "2") sits in
        // the middle grid, outside the left scroll region.
        var picked: Key? = null
        val v = nineView(Layouts.ninePunctuation(), composing = false).apply { onKey = { picked = it } }
        v.tap(2.5f * u * v.width, 0.125f * v.height) // ABC cell centre (main col 2 = x 2.0u, +0.5u to centre)
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
        v.tap(v.cx(), v.colCellY(1)) // second combo
        assertNotNull(picked)
        assertEquals(KeyAction.PICK_READING, picked?.action)
        assertEquals("gao", picked?.label)
    }

    @Test fun a_pure_tap_does_not_scroll() {
        // A tap (no drag) must pick, not scroll — guards against the tap being eaten as a micro-scroll.
        var picked: Key? = null
        val v = nineView(Layouts.ninePunctuation(), composing = false).apply { onKey = { picked = it } }
        v.tap(v.cx(), v.colCellY(1))
        assertEquals("。", picked?.label)
    }

    @Test fun non_scrollable_short_list_taps_still_resolve() {
        // A short combo list (fits, no scroll) must still pick the tapped row, never go dead.
        var picked: Key? = null
        val v = nineView(listOf(Key("ni", output = "ni", action = KeyAction.PICK_READING)), composing = true)
            .apply { onKey = { picked = it } }
        v.tap(v.cx(), v.colCellY(0))
        assertEquals("ni", picked?.label)
        assertNull("tapping below the single item picks nothing, never crashes", run {
            picked = null; v.tap(v.cx(), v.regTop() + v.cellH() * 3.5f); picked
        })
    }

    // ---- I4: shift single-tap (one-shot) vs double-tap (caps lock) + the stateful glyph ----

    private fun KeyboardView.tapAt(x: Float, y: Float, t: Long) {
        send(MotionEvent.ACTION_DOWN, x, y, t)
        send(MotionEvent.ACTION_UP, x, y, t + 10)
    }

    @Test fun a_quick_second_shift_tap_promotes_one_shot_to_caps_lock() {
        val emitted = mutableListOf<KeyAction>()
        val v = alphaView().apply { onKey = { emitted.add(it.action) } }
        val (sx, sy) = v.centerOfActionForTest(KeyAction.SHIFT)!!
        v.tapAt(sx, sy, 0)    // first tap → one-shot SHIFT
        v.tapAt(sx, sy, 100)  // within the 300ms double-tap window → SHIFT_LOCK
        assertEquals(listOf(KeyAction.SHIFT, KeyAction.SHIFT_LOCK), emitted)
    }

    @Test fun two_slow_shift_taps_stay_one_shot_never_lock() {
        val emitted = mutableListOf<KeyAction>()
        val v = alphaView().apply { onKey = { emitted.add(it.action) } }
        val (sx, sy) = v.centerOfActionForTest(KeyAction.SHIFT)!!
        v.tapAt(sx, sy, 0)    // SHIFT
        v.tapAt(sx, sy, 500)  // 490ms later, outside the window → SHIFT again, NOT a lock
        assertEquals(listOf(KeyAction.SHIFT, KeyAction.SHIFT), emitted)
    }

    @Test fun nine_key_is_slightly_taller_than_the_base_row_height() {
        // I3: the 9-key gets a small per-row bump; verify it is taller than the un-bumped 4-row base, but
        // only modestly ("别过").
        val v = nineView(Layouts.ninePunctuation(), composing = false)
        val rows = 4
        val baseHeight = rows * 52f * density + (rows + 1) * 6f * density // pre-I3 measure
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

    // ---- I5: the left-column scroll must track the finger 1:1, with a fling that matches the flick ----

    @Test fun dragging_scrolls_exactly_one_to_one_with_the_finger() {
        // The core I5 ask, quantified: a finger that moves X px scrolls the content exactly X px (to the
        // top/bottom clamp), no magnification or sluggishness.
        val v = longComboView()
        assertTrue("the list overflows so 1:1 has room", v.maxScrollForTest() > 120f)
        val x = v.cx()
        val y0 = v.regTop() + v.cellH() * 3.5f
        v.send(MotionEvent.ACTION_DOWN, x, y0, 0)
        v.send(MotionEvent.ACTION_MOVE, x, y0 - 80f, 16)               // finger up 80px
        assertEquals("content moved exactly 80px (1:1)", 80f, v.scrollOffsetForTest(), 0.5f)
        v.send(MotionEvent.ACTION_MOVE, x, y0 - 30f, 32)               // finger back down to +30px net
        assertEquals("still 1:1 after reversing direction", 30f, v.scrollOffsetForTest(), 0.5f)
        v.send(MotionEvent.ACTION_UP, x, y0 - 30f, 48)
    }

    @Test fun windowed_velocity_matches_a_steady_flick_speed() {
        // Quantified fling mapping: a steady ~1000 px/s flick yields ~1000 px/s release velocity (measured
        // over the window, not a noisy last pair).
        val v = longComboView()
        val x = v.cx()
        v.send(MotionEvent.ACTION_DOWN, x, 180f, 0)
        v.send(MotionEvent.ACTION_MOVE, x, 164f, 16)
        v.send(MotionEvent.ACTION_MOVE, x, 148f, 32)
        v.send(MotionEvent.ACTION_MOVE, x, 132f, 48)
        v.send(MotionEvent.ACTION_MOVE, x, 116f, 64)
        v.send(MotionEvent.ACTION_MOVE, x, 100f, 80)                   // 80px up over 80ms ≈ -1000 px/s
        assertEquals("release velocity ≈ the steady flick speed", -1000f, v.flingVelocityForTest(), 80f)
    }

    @Test fun a_flick_that_eases_off_at_the_very_end_still_flings() {
        // ROOT CAUSE of "要滑很长才到底": the old two-sample velocity read ~0 when the finger decelerated in
        // the last few ms before lifting, so a genuine flick produced no momentum. The windowed estimate
        // uses the whole flick, so a brief soft finish (< the velocity window) still flings.
        val v = longComboView()
        val x = v.cx()
        fun y(c: Float) = v.regTop() + v.cellH() * c
        v.send(MotionEvent.ACTION_DOWN, x, y(3.8f), 0)
        v.send(MotionEvent.ACTION_MOVE, x, y(3.0f), 16)
        v.send(MotionEvent.ACTION_MOVE, x, y(2.0f), 32)
        v.send(MotionEvent.ACTION_MOVE, x, y(1.0f), 48)               // fast up to here…
        v.send(MotionEvent.ACTION_MOVE, x, y(1.0f), 80)               // …then a brief flat finish (0 last delta)
        v.send(MotionEvent.ACTION_UP, x, y(1.0f), 80)
        assertTrue("a real flick with a soft finish still hands off to a fling", v.isFlingingForTest())
    }

    @Test fun reversing_after_overscroll_tracks_the_finger_immediately() {
        // I5 真做对: dragging PAST the bottom clamp must not bank an overshoot — reversing moves the content
        // right away (no dead zone). Incremental-delta drag makes this hold; the old absolute map failed it.
        val v = longComboView()
        val max = v.maxScrollForTest()
        assertTrue("the list overflows", max > 0f)
        val x = v.cx()
        val y0 = v.regTop() + v.cellH() * 3.5f
        v.send(MotionEvent.ACTION_DOWN, x, y0, 0)
        v.send(MotionEvent.ACTION_MOVE, x, y0 - (max + 200f), 16)             // overshoot way past the bottom
        assertEquals("pinned at the bottom", max, v.scrollOffsetForTest(), 0.5f)
        v.send(MotionEvent.ACTION_MOVE, x, y0 - (max + 200f) + 30f, 32)        // reverse 30px
        assertEquals("reverse tracks the finger 1:1, no absorbed overshoot", max - 30f, v.scrollOffsetForTest(), 0.5f)
        v.send(MotionEvent.ACTION_UP, x, y0 - (max + 200f) + 30f, 48)
    }

    // ---- I2: the numpad operator column reuses the same (1:1) scroll-column mechanism ----

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

    // numpad operator column: x=0, w=NINE_LEFT_U(1.0)*u (= the pinyin left-column width), full height,
    // cellHFrac=0.25 → 4 visible. debug.16 item6: was w=1/5, now aligned to the 9-key pinyin left column.
    private fun KeyboardView.opCx() = (1.0f * u * width) / 2f
    private fun KeyboardView.opCellH() = (height - 2 * gap) / 4f
    private fun KeyboardView.opCellY(i: Int) = gap + opCellH() * (i + 0.5f)

    @Test fun all_four_row_pages_share_one_height_so_switching_never_resizes() {
        // The design answer: 9-key, numpad, number and symbol are all 4-row and must measure the SAME
        // height so 9-key⇄123 (and any text⇄number/symbol) never resizes the IME window; the 5-row 26-key
        // keeps the base row height and is taller.
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

    @Test fun tapping_an_operator_in_the_numpad_column_commits_it() {
        var picked: Key? = null
        val v = numpadView().apply { onKey = { picked = it } }
        v.tap(v.opCx(), v.opCellY(0))
        assertEquals("+", picked?.label) // the first default operator
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
