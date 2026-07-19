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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyPreviewSplitTest {

    private val context = RuntimeEnvironment.getApplication()
    private val density = context.resources.displayMetrics.density
    private val gap = 3f * density
    private val u = 1f / 4.7f

    private fun laidOut(v: KeyboardView): KeyboardView {
        v.measure(
            View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        return v
    }

    private fun view(id: LayoutId, lang: Lang = Lang.CN): KeyboardView =
        laidOut(KeyboardView(context).apply { setLayout(Layouts.forId(id, lang), false, false, lang) })

    private fun nineView(composing: Boolean): KeyboardView =
        laidOut(KeyboardView(context).apply {
            setLayout(Layouts.nine(Lang.CN, Layouts.ninePunctuation(), composing), false, false, Lang.CN)
        })

    private fun KeyboardView.down(x: Float, y: Float) = dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0))
    private fun KeyboardView.up(x: Float, y: Float) = dispatchTouchEvent(MotionEvent.obtain(0, 10, MotionEvent.ACTION_UP, x, y, 0))

    private fun KeyboardView.previewOnLabel(label: String): Pair<Boolean, String?> {
        val (x, y) = centerOfLabelForTest(label) ?: return false to null
        down(x, y)
        val armed = previewActiveForTest() to previewLabelForTest()
        up(x, y)
        return armed
    }

    private fun KeyboardView.previewOnAction(action: KeyAction): Boolean {
        val (x, y) = centerOfActionForTest(action) ?: return false
        down(x, y)
        val armed = previewActiveForTest()
        up(x, y)
        return armed
    }

    private fun KeyboardView.scrollCx() = (gap + (0.85f * u * width - gap)) / 2f
    private fun KeyboardView.scrollCellH() = ((0.75f * height - gap) - gap) / 4f
    private fun KeyboardView.scrollCellY(i: Int) = gap + scrollCellH() * (i + 0.5f)


    @Test fun both_previews_default_off_so_nothing_previews_on_either_keyboard() {
        val nine = nineView(composing = false)
        assertEquals(false to null, nine.previewOnLabel("ABC"))
        val alpha = view(LayoutId.ALPHA, Lang.EN)
        assertEquals(false to null, alpha.previewOnLabel("q"))
    }


    @Test fun the_nine_toggle_governs_only_the_nine_world() {
        val nine = nineView(composing = false).apply { previewNineEnabled = true; previewAlphaEnabled = false }
        assertEquals("9-key digit previews the whole block", true to "ABC", nine.previewOnLabel("ABC"))
        val numpad = view(LayoutId.NUMPAD).apply { previewNineEnabled = true; previewAlphaEnabled = false }
        assertEquals("numpad (9-key world) previews", true to "1", numpad.previewOnLabel("1"))
        val alpha = view(LayoutId.ALPHA, Lang.EN).apply { previewNineEnabled = true; previewAlphaEnabled = false }
        assertEquals(false to null, alpha.previewOnLabel("q"))
        val number = view(LayoutId.NUMBER).apply { previewNineEnabled = true; previewAlphaEnabled = false }
        assertEquals(false to null, number.previewOnLabel("@"))
    }

    @Test fun the_alpha_toggle_governs_only_the_alpha_world() {
        val alpha = view(LayoutId.ALPHA, Lang.EN).apply { previewNineEnabled = false; previewAlphaEnabled = true }
        assertEquals("26-key letter previews", true to "q", alpha.previewOnLabel("q"))
        val number = view(LayoutId.NUMBER).apply { previewNineEnabled = false; previewAlphaEnabled = true }
        assertEquals("number page (26-key world) previews", true to "@", number.previewOnLabel("@"))
        val symbol = view(LayoutId.SYMBOL).apply { previewNineEnabled = false; previewAlphaEnabled = true }
        assertEquals("symbol page (26-key world) previews", true to "π", symbol.previewOnLabel("π"))
        val nine = nineView(composing = false).apply { previewNineEnabled = false; previewAlphaEnabled = true }
        assertEquals(false to null, nine.previewOnLabel("ABC"))
        val numpad = view(LayoutId.NUMPAD).apply { previewNineEnabled = false; previewAlphaEnabled = true }
        assertEquals(false to null, numpad.previewOnLabel("1"))
    }


    @Test fun every_nine_key_digit_block_previews_its_full_letters() {
        val nine = nineView(composing = false).apply { previewNineEnabled = true }
        for (block in listOf("ABC", "DEF", "GHI", "JKL", "MNO", "PQRS", "TUV", "WXYZ")) {
            assertEquals("$block previews the block, not its digit", true to block, nine.previewOnLabel(block))
        }
    }


    @Test fun the_segment_key_previews_while_composing() {
        val nine = nineView(composing = true).apply { previewNineEnabled = true }
        assertEquals("SEGMENT key previews while composing", true, nine.previewOnAction(KeyAction.SEGMENT))
        val rest = nineView(composing = false).apply { previewNineEnabled = true }
        assertEquals(false to null, rest.previewOnLabel("@#"))
    }


    @Test fun the_left_scroll_column_previews_the_pressed_punctuation() {
        val nine = nineView(composing = false).apply { previewNineEnabled = true }
        nine.down(nine.scrollCx(), nine.scrollCellY(0))
        assertTrue("scroll-column press arms a preview", nine.previewActiveForTest())
        assertEquals("，", nine.previewLabelForTest())
        nine.up(nine.scrollCx(), nine.scrollCellY(0))
        assertFalse("preview retracts on release", nine.previewActiveForTest())
    }

    @Test fun the_numpad_operator_column_previews_too_but_only_under_the_nine_toggle() {
        val numpad = view(LayoutId.NUMPAD).apply { previewNineEnabled = true }
        numpad.down(numpad.scrollCx(), numpad.scrollCellY(0))
        assertTrue(numpad.previewActiveForTest())
        assertEquals("+", numpad.previewLabelForTest())
        numpad.up(numpad.scrollCx(), numpad.scrollCellY(0))
    }


    @Test fun nine_key_functional_keys_never_preview_even_with_the_toggle_on() {
        val nine = nineView(composing = true).apply { previewNineEnabled = true }
        for (action in listOf(
            KeyAction.SHOW_SYMBOLS, KeyAction.SWITCH_NUMPAD, KeyAction.SPACE,
            KeyAction.TOGGLE_LANG, KeyAction.BACKSPACE, KeyAction.CLEAR_COMPOSING, KeyAction.ENTER,
        )) {
            assertFalse("$action must not preview on the 9-key", nine.previewOnAction(action))
        }
        val custom = Layouts.ninePunctuation().last()
        assertEquals("the scroll column's last item is the 自定义 button", KeyAction.CUSTOM_SYMBOL, custom.action)
    }

    @Test fun alpha_functional_keys_never_preview_even_with_the_toggle_on() {
        val alpha = view(LayoutId.ALPHA, Lang.EN).apply { previewAlphaEnabled = true }
        for (action in listOf(
            KeyAction.SHIFT, KeyAction.BACKSPACE, KeyAction.SPACE, KeyAction.ENTER,
            KeyAction.SHOW_SYMBOLS, KeyAction.SWITCH_NUMPAD, KeyAction.TOGGLE_LANG,
        )) {
            assertFalse("$action must not preview on the 26-key", alpha.previewOnAction(action))
        }
        assertEquals(true to "q", alpha.previewOnLabel("q"))
        assertEquals(true to ",", alpha.previewOnLabel(","))
    }
}
