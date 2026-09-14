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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PanelTextUndoTest {
    private class Field(var text: String) : PanelEditable {
        var start = text.length
        var end = start
        override fun snapshot(): String = text
        override fun selectionStart(): Int = start
        override fun selectionEnd(): Int = end
        override fun setSelection(start: Int, end: Int) {
            this.start = start
            this.end = end
        }
        override fun replace(start: Int, end: Int, text: CharSequence) {
            this.text = this.text.replaceRange(start, end, text)
            setSelection(start + text.length, start + text.length)
        }
    }

    @Test fun insertion_backspace_forward_delete_and_paste_undo_in_order() {
        val field = Field("a😀b")
        val input = PanelTextInput().apply { begin(field) }
        input.commit("x")
        input.backspace()
        field.setSelection(1, 1)
        input.deleteForward()
        input.commit("pasted")
        for (expected in listOf("ab", "a😀b", "a😀bx", "a😀b")) {
            assertTrue(input.undo())
            assertEquals(expected, field.text)
        }
        assertFalse(input.canUndo())
        assertFalse(input.undo())
    }

    @Test fun undo_after_navigation_restores_the_original_reversed_selection() {
        val field = Field("abcdef").apply { setSelection(4, 1) }
        val input = PanelTextInput().apply { begin(field) }
        input.commit("X")
        input.move(SelectionMath.Move.HOME, false)
        assertTrue(input.undo())
        assertEquals("abcdef", field.text)
        assertEquals(4, field.start)
        assertEquals(1, field.end)
    }

    @Test fun paired_symbol_is_one_undo_step_and_select_all_clear_preserves_its_selection() {
        val field = Field("正文")
        val input = PanelTextInput().apply { begin(field) }
        input.commitSymbol("（")
        assertEquals("正文（）", field.text)
        input.selectAll()
        input.deleteSelection()
        assertTrue(input.undo())
        assertEquals("正文（）", field.text)
        assertEquals(0, field.start)
        assertEquals(4, field.end)
        assertTrue(input.undo())
        assertEquals("正文", field.text)
        assertEquals(2, field.start)
        assertFalse(input.canUndo())
    }

    @Test fun hidden_closed_or_replaced_fields_do_not_keep_undo_history() {
        var shown = true
        val field = Field("a")
        val input = PanelTextInput().apply { begin(field) { shown } }
        input.commit("b")
        assertTrue(input.canUndo())
        shown = false
        assertFalse(input.undo())
        shown = true
        input.begin(field)
        assertFalse(input.canUndo())
        input.commit("c")
        input.end()
        assertFalse(input.undo())
        input.begin(Field("other"))
        assertFalse(input.undo())
        assertEquals("abc", field.text)
    }

    @Test fun external_changes_and_noop_deletes_cannot_become_undo_steps() {
        val field = Field("")
        val input = PanelTextInput().apply { begin(field) }
        input.backspace()
        input.deleteForward()
        assertFalse(input.canUndo())
        input.commit("a")
        field.text = "external"
        field.setSelection(8, 8)
        assertFalse(input.undo())
        input.replaceBefore(8, "new")
        assertTrue(input.undo())
        assertEquals("external", field.text)
        assertFalse(input.canUndo())
    }
}
