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

/**
 * ① The press preview, SPLIT into a 9-key world (T9 + NUMPAD) and a 26-key world (qwerty + NUMBER + SYMBOL),
 * both defaulting OFF, plus the 补齐 gaps closed: the 9-key 分词 (SEGMENT) key, the left scroll column, and the
 * whole-block digit bubble. No sampling — every key TYPE on every layout is enumerated for previews-or-not, and
 * the two toggles are proven independent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyPreviewSplitTest {

    private val context = RuntimeEnvironment.getApplication()
    private val density = context.resources.displayMetrics.density
    private val gap = 6f * density
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

    /** Press [label]'s key, capture whether the preview armed (+ its label), release. */
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

    // --- geometry of the 9-key scroll column (mirrors Layouts.nine + relayout) ---
    private fun KeyboardView.scrollCx() = (gap + (1.0f * u * width - gap)) / 2f
    private fun KeyboardView.scrollCellH() = ((0.75f * height - gap) - gap) / 4f
    private fun KeyboardView.scrollCellY(i: Int) = gap + scrollCellH() * (i + 0.5f)

    // ---- defaults ----

    @Test fun both_previews_default_off_so_nothing_previews_on_either_keyboard() {
        // A fresh view (no toggle pushed) must show NO preview — the defaults are both false.
        val nine = nineView(composing = false)
        assertEquals(false to null, nine.previewOnLabel("ABC"))
        val alpha = view(LayoutId.ALPHA, Lang.EN)
        assertEquals(false to null, alpha.previewOnLabel("q"))
    }

    // ---- the two toggles are independent ----

    @Test fun the_nine_toggle_governs_only_the_nine_world() {
        val nine = nineView(composing = false).apply { previewNineEnabled = true; previewAlphaEnabled = false }
        assertEquals("9-key digit previews the whole block", true to "ABC", nine.previewOnLabel("ABC"))
        val numpad = view(LayoutId.NUMPAD).apply { previewNineEnabled = true; previewAlphaEnabled = false }
        assertEquals("numpad (9-key world) previews", true to "1", numpad.previewOnLabel("1"))
        // …but the 26-key world stays quiet with only the 9-key toggle on.
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
        // …but the 9-key world stays quiet with only the 26-key toggle on.
        val nine = nineView(composing = false).apply { previewNineEnabled = false; previewAlphaEnabled = true }
        assertEquals(false to null, nine.previewOnLabel("ABC"))
        val numpad = view(LayoutId.NUMPAD).apply { previewNineEnabled = false; previewAlphaEnabled = true }
        assertEquals(false to null, numpad.previewOnLabel("1"))
    }

    // ---- 补齐 (1): every 9-key digit block previews its WHOLE label (not the T9 digit) ----

    @Test fun every_nine_key_digit_block_previews_its_full_letters() {
        val nine = nineView(composing = false).apply { previewNineEnabled = true }
        for (block in listOf("ABC", "DEF", "GHI", "JKL", "MNO", "PQRS", "TUV", "WXYZ")) {
            assertEquals("$block previews the block, not its digit", true to block, nine.previewOnLabel(block))
        }
    }

    // ---- 补齐 (2): the 分词 (SEGMENT) key previews while composing ----

    @Test fun the_segment_key_previews_while_composing() {
        val nine = nineView(composing = true).apply { previewNineEnabled = true }
        assertEquals("分词 (SEGMENT) previews", true to "分词", nine.previewOnLabel("分词"))
        // and the SAME cell at rest (@#, SWITCH_SYMBOLS) does NOT preview — it is a control key.
        val rest = nineView(composing = false).apply { previewNineEnabled = true }
        assertEquals(false to null, rest.previewOnLabel("@#"))
    }

    // ---- 补齐 (3): the left scroll column previews the pressed punctuation ----

    @Test fun the_left_scroll_column_previews_the_pressed_punctuation() {
        val nine = nineView(composing = false).apply { previewNineEnabled = true }
        nine.down(nine.scrollCx(), nine.scrollCellY(0)) // first punctuation "，"
        assertTrue("scroll-column press arms a preview", nine.previewActiveForTest())
        assertEquals("，", nine.previewLabelForTest())
        nine.up(nine.scrollCx(), nine.scrollCellY(0))
        assertFalse("preview retracts on release", nine.previewActiveForTest())
    }

    @Test fun the_numpad_operator_column_previews_too_but_only_under_the_nine_toggle() {
        val numpad = view(LayoutId.NUMPAD).apply { previewNineEnabled = true }
        numpad.down(numpad.scrollCx(), numpad.scrollCellY(0)) // first operator "+"
        assertTrue(numpad.previewActiveForTest())
        assertEquals("+", numpad.previewLabelForTest())
        numpad.up(numpad.scrollCx(), numpad.scrollCellY(0))
    }

    // ---- functional keys stay exempt on BOTH worlds (census) ----

    @Test fun nine_key_functional_keys_never_preview_even_with_the_toggle_on() {
        val nine = nineView(composing = true).apply { previewNineEnabled = true }
        for (action in listOf(
            KeyAction.SHOW_SYMBOLS, KeyAction.SWITCH_NUMPAD, KeyAction.SPACE,
            KeyAction.TOGGLE_LANG, KeyAction.BACKSPACE, KeyAction.CLEAR_COMPOSING, KeyAction.ENTER,
        )) {
            assertFalse("$action must not preview on the 9-key", nine.previewOnAction(action))
        }
        // 自定义 (CUSTOM_SYMBOL) sits at the foot of the scroll column and must stay exempt too.
        val custom = Layouts.ninePunctuation().last()
        assertEquals("the scroll column's last item is the 自定义 button", KeyAction.CUSTOM_SYMBOL, custom.action)
    }

    @Test fun alpha_functional_keys_never_preview_even_with_the_toggle_on() {
        val alpha = view(LayoutId.ALPHA, Lang.EN).apply { previewAlphaEnabled = true }
        for (action in listOf(
            KeyAction.SHIFT, KeyAction.BACKSPACE, KeyAction.SPACE, KeyAction.ENTER,
            KeyAction.SHOW_SYMBOLS, KeyAction.SWITCH_NUMBERS, KeyAction.TOGGLE_LANG,
        )) {
            assertFalse("$action must not preview on the 26-key", alpha.previewOnAction(action))
        }
        // but the number-row digits and the direct comma/period ARE content (COMMIT) → they preview (EN comma = ",").
        assertEquals(true to "1", alpha.previewOnLabel("1"))
        assertEquals(true to ",", alpha.previewOnLabel(","))
    }
}
