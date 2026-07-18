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
import com.aegis.ime.engine.CandidateEngine
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.layout.Layouts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class LongPressCaseBoxTest {

    private val context = RuntimeEnvironment.getApplication()
    private val density = context.resources.displayMetrics.density
    private val swipeThreshold = 24f * density

    private fun alphaView(lang: Lang = Lang.EN): KeyboardView = KeyboardView(context).apply {
        setLayout(Layouts.forId(LayoutId.ALPHA, lang), false, false, lang)
        measure(
            View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        layout(0, 0, measuredWidth, measuredHeight)
    }

    private fun KeyboardView.send(action: Int, x: Float, y: Float, t: Long = 0) =
        dispatchTouchEvent(MotionEvent.obtain(0, t, action, x, y, 0))

    private fun holdOpen() = Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350))


    @Test fun a_long_press_opens_the_three_cell_box() {
        val v = alphaView()
        val (gx, gy) = v.centerOfLabelForTest("g")!!
        v.send(MotionEvent.ACTION_DOWN, gx, gy, 0)
        assertFalse("no box before the timer fires", v.caseBoxActiveForTest())
        holdOpen()
        assertTrue("the box opens after the long-press delay", v.caseBoxActiveForTest())
        assertEquals(listOf("G", "%", "g"), v.caseBoxLabelsForTest())
        v.send(MotionEvent.ACTION_UP, gx, gy, 400)
    }


    private fun scenario(afterOpen: (KeyboardView, Float, Float) -> Unit): List<String> {
        val out = ArrayList<String>()
        val v = alphaView().apply { onKey = { out.add(it.output) } }
        val (gx, gy) = v.centerOfLabelForTest("g")!!
        v.send(MotionEvent.ACTION_DOWN, gx, gy, 0)
        holdOpen()
        afterOpen(v, gx, gy)
        return out
    }

    @Test fun no_slide_commits_the_normal_letter() {
        val out = scenario { v, gx, gy ->
            assertTrue("the box did open (load-bearing: distinguishes box-lift from a plain tap)", v.caseBoxActiveForTest())
            assertEquals("no cell is selected without a slide", -1, v.caseBoxSelectedForTest())
            v.send(MotionEvent.ACTION_UP, gx, gy, 400)
        }
        assertEquals(listOf("g"), out)
    }

    @Test fun sliding_left_commits_the_uppercase_letter() {
        val out = scenario { v, _, gy ->
            v.send(MotionEvent.ACTION_MOVE, 1f, gy, 380)
            assertEquals("upper cell selected", 0, v.caseBoxSelectedForTest())
            v.send(MotionEvent.ACTION_UP, 1f, gy, 400)
        }
        assertEquals(listOf("G"), out)
    }

    @Test fun sliding_to_the_middle_commits_the_symbol() {
        val out = scenario { v, gx, gy ->
            v.send(MotionEvent.ACTION_MOVE, gx, gy - 40f * density, 380)
            assertEquals("symbol cell selected", 1, v.caseBoxSelectedForTest())
            v.send(MotionEvent.ACTION_UP, gx, gy - 40f * density, 400)
        }
        assertEquals(listOf("%"), out)
    }

    @Test fun sliding_right_commits_the_lowercase_letter() {
        val out = scenario { v, _, gy ->
            v.send(MotionEvent.ACTION_MOVE, v.width - 1f, gy, 380)
            assertEquals("lower cell selected", 2, v.caseBoxSelectedForTest())
            v.send(MotionEvent.ACTION_UP, v.width - 1f, gy, 400)
        }
        assertEquals(listOf("g"), out)
    }


    @Test fun dragging_a_cell_height_below_the_key_cancels_and_commits_nothing() {
        val out = ArrayList<String>()
        val v = alphaView().apply { onKey = { out.add(it.output) } }
        val rect = v.boundsOfLabelForTest("g")!!
        val (gx, gy) = v.centerOfLabelForTest("g")!!
        val cancelY = rect.bottom + rect.height() * 1.12f
        v.send(MotionEvent.ACTION_DOWN, gx, gy, 0)
        holdOpen()
        v.send(MotionEvent.ACTION_MOVE, gx, cancelY - 2f, 380)
        assertEquals("just inside the bound still selects a cell", 1, v.caseBoxSelectedForTest())
        v.send(MotionEvent.ACTION_MOVE, gx, cancelY + 2f, 390)
        assertEquals("past the bound the selection clears", -1, v.caseBoxSelectedForTest())
        v.send(MotionEvent.ACTION_UP, gx, cancelY + 2f, 400)
        assertEquals(emptyList<String>(), out)
    }

    @Test fun dragging_a_cell_height_above_the_box_cancels_and_commits_nothing() {
        val out = ArrayList<String>()
        val v = alphaView().apply { onKey = { out.add(it.output) } }
        val rect = v.boundsOfLabelForTest("g")!!
        val (gx, gy) = v.centerOfLabelForTest("g")!!
        val cellH = rect.height() * 1.12f
        val boxTop = (rect.top - cellH - 4f * density).coerceAtLeast(0f)
        val cancelY = boxTop - cellH
        v.send(MotionEvent.ACTION_DOWN, gx, gy, 0)
        holdOpen()
        v.send(MotionEvent.ACTION_MOVE, gx, cancelY + 2f, 380)
        assertEquals("just inside the bound still selects a cell", 1, v.caseBoxSelectedForTest())
        v.send(MotionEvent.ACTION_MOVE, gx, cancelY - 2f, 390)
        assertEquals("past the bound the selection clears", -1, v.caseBoxSelectedForTest())
        v.send(MotionEvent.ACTION_UP, gx, cancelY - 2f, 400)
        assertEquals(emptyList<String>(), out)
    }


    private class RecordingHost : ImeHost {
        val commits = mutableListOf<String>()
        override fun commitText(text: CharSequence) { commits.add(text.toString()) }
        override fun deleteBackward() {}
        override fun performEnter() {}
    }

    private fun lowercaseCellCommits(prime: KeyAction): List<String> {
        val host = RecordingHost()
        val controller = KeyboardController(host, object : CandidateEngine {
            override fun candidates(composing: String, t9: Boolean): List<String> = emptyList()
        })
        controller.onKey(Key("", action = KeyAction.TOGGLE_LANG))
        controller.onKey(Key("", action = prime))
        val v = alphaView().apply { onKey = { controller.onKey(it) } }
        val (gx, gy) = v.centerOfLabelForTest("g")!!
        v.send(MotionEvent.ACTION_DOWN, gx, gy, 0)
        holdOpen()
        v.send(MotionEvent.ACTION_MOVE, v.width - 1f, gy, 380)
        v.send(MotionEvent.ACTION_UP, v.width - 1f, gy, 400)
        return host.commits
    }

    @Test fun the_lowercase_cell_commits_lowercase_under_caps_lock() {
        assertEquals("the explicitly chosen cell wins over caps lock", listOf("g"), lowercaseCellCommits(KeyAction.SHIFT_LOCK))
    }

    @Test fun the_lowercase_cell_commits_lowercase_under_one_shot_shift() {
        assertEquals("the explicitly chosen cell wins over a pending shift", listOf("g"), lowercaseCellCommits(KeyAction.SHIFT))
    }


    @Test fun cn_long_press_does_not_open_the_box() {
        val v = alphaView(Lang.CN)
        val (gx, gy) = v.centerOfLabelForTest("g")!!
        v.send(MotionEvent.ACTION_DOWN, gx, gy, 0)
        holdOpen()
        assertFalse("CN never opens the case box (letters are pinyin)", v.caseBoxActiveForTest())
        v.send(MotionEvent.ACTION_UP, gx, gy, 400)
    }


    private fun flick(dy: Float): Pair<List<String>, Boolean> {
        val out = ArrayList<String>()
        val v = alphaView().apply { onKey = { out.add(it.output) } }
        val (gx, gy) = v.centerOfLabelForTest("g")!!
        v.send(MotionEvent.ACTION_DOWN, gx, gy, 0)
        v.send(MotionEvent.ACTION_MOVE, gx, gy + dy, 12)
        val boxDuringFlick = v.caseBoxActiveForTest()
        v.send(MotionEvent.ACTION_UP, gx, gy + dy, 20)
        return out to boxDuringFlick
    }

    @Test fun a_fast_up_flick_still_commits_the_symbol_and_never_opens_the_box() {
        val (out, box) = flick(-(swipeThreshold + 15f))
        assertEquals("up-flick commits the super-script symbol", listOf("%"), out)
        assertFalse("a fast flick must not open the box", box)
    }

    @Test fun a_fast_down_flick_still_commits_the_letter_and_never_opens_the_box() {
        val (out, box) = flick(swipeThreshold + 15f)
        assertEquals("down-flick commits the letter", listOf("g"), out)
        assertFalse(box)
    }

    @Test fun a_short_tap_commits_the_letter_and_never_opens_the_box() {
        val out = ArrayList<String>()
        val v = alphaView().apply { onKey = { out.add(it.output) } }
        val (gx, gy) = v.centerOfLabelForTest("g")!!
        v.send(MotionEvent.ACTION_DOWN, gx, gy, 0)
        v.send(MotionEvent.ACTION_UP, gx, gy, 80)
        assertEquals(listOf("g"), out)
        assertFalse(v.caseBoxActiveForTest())
    }

    @Test fun a_horizontal_slide_to_a_neighbour_still_retargets_and_never_opens_the_box() {
        val out = ArrayList<String>()
        val v = alphaView().apply { onKey = { out.add(it.output) } }
        val (gx, gy) = v.centerOfLabelForTest("g")!!
        val (hx, hy) = v.centerOfLabelForTest("h")!!
        v.send(MotionEvent.ACTION_DOWN, gx, gy, 0)
        v.send(MotionEvent.ACTION_MOVE, hx, hy, 12)
        assertFalse("a slide-to-correct must not open the box", v.caseBoxActiveForTest())
        v.send(MotionEvent.ACTION_UP, hx, hy, 20)
        assertEquals("slide-to-correct still commits the neighbour", listOf("h"), out)
    }
}
