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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PanelTextInputTest {

    private class FakeEditable(initial: String = "", caret: Int = initial.length) : PanelEditable {
        private val buf = StringBuilder(initial)
        private var selStart = caret
        private var selEnd = caret

        override fun snapshot(): String = buf.toString()

        override fun selectionStart(): Int = selStart

        override fun selectionEnd(): Int = selEnd

        override fun setSelection(start: Int, end: Int) {
            selStart = start.coerceIn(0, buf.length)
            selEnd = end.coerceIn(0, buf.length)
        }

        override fun replace(start: Int, end: Int, text: CharSequence) {
            val s = start.coerceIn(0, buf.length)
            val e = end.coerceIn(s, buf.length)
            buf.replace(s, e, text.toString())
            val at = (s + text.length).coerceIn(0, buf.length)
            selStart = at
            selEnd = at
        }
    }

    private fun open(initial: String = "", caret: Int = initial.length): Pair<PanelTextInput, FakeEditable> {
        val target = FakeEditable(initial, caret)
        return PanelTextInput().apply { begin(target) } to target
    }

    @Test fun inactive_consumes_nothing_so_normal_typing_reaches_the_editor() {
        val p = PanelTextInput()
        assertFalse(p.active)
        assertFalse("commit must NOT be consumed when inactive", p.commit("a"))
        assertFalse("backspace must NOT be consumed when inactive", p.backspace())
        assertFalse("newline must NOT be consumed when inactive", p.newline())
        assertFalse("replaceBefore must NOT be consumed when inactive", p.replaceBefore(1, "x"))
        assertFalse("selectAll must NOT be consumed when inactive", p.selectAll())
        assertFalse("move must NOT be consumed when inactive", p.move(SelectionMath.Move.LEFT, false))
        assertNull("textBefore null → host queries the real editor", p.textBefore(5))
        assertNull(p.selectedText())
        assertFalse(p.hasSelection())
        assertEquals("", p.text())
    }

    @Test fun a_field_that_is_no_longer_presented_releases_routing_to_the_editor() {
        var shown = true
        val target = FakeEditable("ab")
        val p = PanelTextInput().apply { begin(target) { shown } }
        assertTrue(p.active)
        assertTrue(p.commit("c"))
        shown = false
        assertFalse("a hidden field must not swallow typing", p.commit("d"))
        assertFalse(p.active)
        assertFalse(p.backspace())
        assertNull(p.textBefore(3))
        assertEquals("", p.text())
        assertEquals("the hidden field keeps what it had", "abc", target.snapshot())
        shown = true
        assertFalse("routing stays released until the host binds again", p.commit("e"))
    }

    @Test fun end_restores_normal_routing() {
        val (p, _) = open("xy")
        assertTrue(p.commit("z"))
        p.end()
        assertFalse(p.active)
        assertFalse("after end, commit must fall through to the editor", p.commit("q"))
        assertFalse("after end, backspace must fall through", p.backspace())
        assertNull("after end, textBefore is null again", p.textBefore(3))
        assertEquals("", p.text())
    }

    @Test fun commit_inserts_at_the_caret_not_at_the_end() {
        val (p, t) = open("abcd", caret = 2)
        assertTrue(p.commit("XY"))
        assertEquals("abXYcd", p.text())
        assertEquals("caret follows the inserted text", 4, t.selectionStart())
        assertEquals(4, t.selectionEnd())
    }

    @Test fun commit_replaces_the_current_selection() {
        val (p, t) = open("abcd")
        t.setSelection(1, 3)
        assertTrue(p.commit("Z"))
        assertEquals("aZd", p.text())
        assertEquals(2, t.selectionStart())
    }

    @Test fun newline_inserts_a_line_break_at_the_caret() {
        val (p, _) = open("ab", caret = 1)
        assertTrue(p.newline())
        assertEquals("a\nb", p.text())
    }

    @Test fun backspace_deletes_the_cluster_before_the_caret() {
        val (p, t) = open("abcd", caret = 3)
        assertTrue(p.backspace())
        assertEquals("abd", p.text())
        assertEquals(2, t.selectionStart())
    }

    @Test fun backspace_deletes_a_whole_grapheme_cluster() {
        for (emoji in listOf("😀", "🇨🇳", "0️⃣", "❤️", "👨‍👩‍👧‍👦", "👋🏽", "🏳️‍🌈")) {
            val (p, _) = open("a$emoji")
            assertTrue(p.backspace())
            assertEquals("$emoji must delete whole, not half a surrogate", "a", p.text())
        }
    }

    @Test fun backspace_on_an_empty_buffer_is_still_consumed() {
        val (p, _) = open("")
        assertTrue(p.backspace())
        assertEquals("", p.text())
    }

    @Test fun backspace_removes_the_selection_when_there_is_one() {
        val (p, t) = open("abcdef")
        t.setSelection(1, 4)
        assertTrue(p.backspace())
        assertEquals("aef", p.text())
    }

    @Test fun text_before_and_replace_before_are_caret_relative() {
        val (p, _) = open("12345", caret = 3)
        assertEquals("123", p.textBefore(5))
        assertEquals("23", p.textBefore(2))
        assertTrue(p.replaceBefore(3, "1+1=2"))
        assertEquals("1+1=245", p.text())
    }

    @Test fun horizontal_moves_step_by_cluster() {
        val (p, t) = open("a😀b")
        p.move(SelectionMath.Move.LEFT, false)
        assertEquals(3, t.selectionStart())
        p.move(SelectionMath.Move.LEFT, false)
        assertEquals("a whole emoji is one step", 1, t.selectionStart())
        p.move(SelectionMath.Move.RIGHT, false)
        assertEquals(3, t.selectionStart())
    }

    @Test fun home_and_end_stay_on_the_current_line() {
        val (p, t) = open("one\ntwo\nthree", caret = 5)
        p.move(SelectionMath.Move.HOME, false)
        assertEquals(4, t.selectionStart())
        p.move(SelectionMath.Move.END, false)
        assertEquals(7, t.selectionStart())
    }

    @Test fun vertical_moves_cross_lines() {
        val (p, t) = open("one\ntwo\nthree", caret = 5)
        p.move(SelectionMath.Move.UP, false)
        assertEquals(1, t.selectionStart())
        p.move(SelectionMath.Move.DOWN, false)
        assertEquals(5, t.selectionStart())
        p.move(SelectionMath.Move.DOWN, false)
        assertEquals(9, t.selectionStart())
    }

    @Test fun extending_a_move_grows_the_selection_from_the_anchor() {
        val (p, t) = open("abcdef", caret = 2)
        p.move(SelectionMath.Move.RIGHT, true)
        p.move(SelectionMath.Move.RIGHT, true)
        assertEquals(2, t.selectionStart())
        assertEquals(4, t.selectionEnd())
        assertEquals("cd", p.selectedText())
        assertTrue(p.hasSelection())
    }

    @Test fun a_plain_horizontal_move_collapses_an_existing_selection() {
        val (p, t) = open("abcdef")
        t.setSelection(1, 4)
        p.move(SelectionMath.Move.LEFT, false)
        assertEquals(1, t.selectionStart())
        assertEquals(1, t.selectionEnd())
        t.setSelection(1, 4)
        p.move(SelectionMath.Move.RIGHT, false)
        assertEquals(4, t.selectionStart())
        assertEquals(4, t.selectionEnd())
    }

    @Test fun select_all_then_delete_clears_the_field() {
        val (p, _) = open("abc\ndef")
        assertTrue(p.selectAll())
        assertEquals("abc\ndef", p.selectedText())
        assertTrue(p.deleteSelection())
        assertEquals("", p.text())
        assertFalse(p.hasSelection())
        assertNull(p.selectedText())
        assertFalse("nothing left to delete", p.deleteSelection())
    }

    @Test fun a_selection_past_the_end_is_clamped_instead_of_throwing() {
        val (p, t) = open("abc")
        t.setSelection(2, 99)
        assertEquals("the editor clamps what it is handed", 3, t.selectionEnd())
        assertTrue(p.commit("Z"))
        assertEquals("abZ", p.text())
        assertEquals("caret stays inside the buffer", 3, t.selectionStart())
    }

    @Test fun a_reversed_selection_still_replaces_the_covered_run() {
        val (p, t) = open("abcdef")
        t.setSelection(4, 1)
        assertTrue(p.commit("Z"))
        assertEquals("aZef", p.text())
    }
}
