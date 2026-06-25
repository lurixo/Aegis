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
import org.junit.Assert.assertNotNull
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
    private val u = 1f / 4.7f

    private fun nineView(left: List<Key>, composing: Boolean): KeyboardView {
        val v = KeyboardView(context)
        v.setLayout(Layouts.nine(left, composing), false, false, Lang.CN)
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

    @Test fun tap_letter_key_outside_scroll_column_still_works() {
        var picked: Key? = null
        val v = nineView(Layouts.ninePunctuation(), composing = false).apply { onKey = { picked = it } }
        v.tap(2.5f * u * v.width, 0.125f * v.height)
        assertNotNull(picked)
        assertEquals("ABC", picked?.label)
        assertEquals("2", picked?.output)
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
        val nineH = measuredH(Layouts.nine(Layouts.ninePunctuation()))
        assertEquals("numpad matches the 9-key (no 9-key⇄123 resize)", nineH, measuredH(Layouts.numpad()))
        assertEquals("number page matches", nineH, measuredH(Layouts.forId(com.aegis.ime.layout.LayoutId.NUMBER, Lang.CN)))
        assertEquals("symbol page matches", nineH, measuredH(Layouts.forId(com.aegis.ime.layout.LayoutId.SYMBOL, Lang.CN)))
        assertEquals("the 26-key is now four rows and shares the one height", nineH,
            measuredH(Layouts.forId(com.aegis.ime.layout.LayoutId.ALPHA, Lang.CN)))
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

    @Test fun a_quick_tap_emits_exactly_once_no_repeat() {
        val emitted = mutableListOf<String>()
        val v = nineView(Layouts.ninePunctuation(), composing = false).apply { onKey = { emitted.add(it.output) } }
        val (x, y) = v.centerOfActionForTest(KeyAction.COMMIT)!!
        v.send(MotionEvent.ACTION_DOWN, x, y, 0)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        v.send(MotionEvent.ACTION_UP, x, y, 100)
        assertEquals("a quick tap emits exactly once", 1, emitted.size)
    }
}
