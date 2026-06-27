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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
    private val u = 1f / 4.4f

    private fun nineView(left: List<Key>, composing: Boolean): KeyboardView {
        val v = KeyboardView(context)
        v.setLayout(Layouts.nine(Lang.CN, left, composing), false, Lang.CN)
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
    private fun KeyboardView.cx() = (gap + (0.7f * u * width - gap)) / 2f
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

    @Test fun tap_letter_key_outside_scroll_column_still_works() {
        // Regression guard: the scroll code must not swallow normal key taps. ABC (T9 digit "2") sits in
        // the middle grid, outside the left scroll region.
        var picked: Key? = null
        val v = nineView(Layouts.ninePunctuation(), composing = false).apply { onKey = { picked = it } }
        v.tap(2.2f * u * v.width, 0.125f * v.height) // ABC cell centre
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
}
