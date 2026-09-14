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

import android.text.Selection
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebEditorTabInputTest {
    private class Editor(initial: String = "1\na\n", caret: Int = initial.length - 1) :
        BaseInputConnection(View(RuntimeEnvironment.getApplication()), true) {
        val events = ArrayList<String>()
        var ignoreSelection = false
        var mutateOnSelection: String? = null
        var partial = false
        private var batches = 0

        init { render(initial, caret) }

        fun raw(): String = requireNotNull(editable).toString()

        fun render(text: String, caret: Int) {
            requireNotNull(editable).replace(0, requireNotNull(editable).length, text)
            Selection.setSelection(editable, caret)
        }

        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText = ExtractedText().apply {
            text = raw()
            startOffset = 0
            partialStartOffset = if (partial) 0 else -1
            selectionStart = Selection.getSelectionStart(editable)
            selectionEnd = Selection.getSelectionEnd(editable)
        }

        override fun beginBatchEdit(): Boolean { events.add("begin"); batches++; return true }
        override fun endBatchEdit(): Boolean { events.add("end"); return --batches > 0 }

        override fun setSelection(start: Int, end: Int): Boolean {
            events.add("select:$start:$end")
            mutateOnSelection?.let { render(it, minOf(end, it.length)) }
            return ignoreSelection || super.setSelection(start, end)
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            events.add("commit:$text")
            return super.commitText(text, newCursorPosition)
        }
    }

    private fun insertTab(helper: WebEditorTabInput, editor: Editor) {
        assertTrue(helper.apply(requireNotNull(helper.prepare(editor, "\t", 1))))
        editor.events.clear()
    }

    @Test fun text_after_rendered_tab_replaces_only_the_proven_tab_in_one_batch() {
        for (input in listOf("b", "中文候选", "👨‍👩‍👧‍👦")) {
            val helper = WebEditorTabInput()
            val editor = Editor()
            insertTab(helper, editor)
            editor.render("1\na   \n", 6)
            val edit = requireNotNull(helper.prepare(editor, input, 1))
            assertEquals(6, edit.before.selectionStart)
            assertEquals(6, edit.before.selectionEnd)
            assertEquals(WindowEdit.Replacement(3, 6, "\t"), edit.commit.replacement)
            assertTrue(helper.apply(edit))
            assertEquals("1\na\t$input\n", editor.raw())
            assertEquals(listOf("select:3:6", "commit:\t$input"), editor.events.filter { it.startsWith("select:") || it.startsWith("commit:") })
            assertEquals("begin", editor.events.first())
            assertEquals("end", editor.events.last())
            assertNull(helper.prepare(editor, "next", 1))
        }
    }

    @Test fun tab_width_is_observed_without_assuming_four_columns() {
        for (width in listOf(1, 3, 7, 16)) {
            val helper = WebEditorTabInput()
            val editor = Editor()
            insertTab(helper, editor)
            editor.render("1\na${" ".repeat(width)}\n", 3 + width)
            val edit = requireNotNull(helper.prepare(editor, "b", 1))
            assertEquals(WindowEdit.Replacement(3, 3 + width, "\t"), edit.commit.replacement)
            assertTrue(helper.apply(edit))
            assertEquals("1\na\tb\n", editor.raw())
        }
    }

    @Test fun consecutive_tabs_keep_the_exact_number_of_literal_tabs() {
        val helper = WebEditorTabInput()
        val editor = Editor()
        insertTab(helper, editor)
        editor.render("1\na   \n", 6)
        assertTrue(helper.apply(requireNotNull(helper.prepare(editor, "\t", 1))))
        editor.render("1\na       \n", 10)
        val edit = requireNotNull(helper.prepare(editor, "b", 1))
        assertEquals(WindowEdit.Replacement(3, 10, "\t\t"), edit.commit.replacement)
        assertEquals("\t\tb", edit.commit.text)
        assertTrue(helper.apply(edit))
        assertEquals("1\na\t\tb\n", editor.raw())
    }

    @Test fun a_new_trailing_tab_keeps_only_its_own_range() {
        val helper = WebEditorTabInput()
        val editor = Editor()
        insertTab(helper, editor)
        editor.render("1\na   \n", 6)
        assertTrue(helper.apply(requireNotNull(helper.prepare(editor, "x\t", 1))))
        editor.render("1\na   x   \n", 10)
        val edit = requireNotNull(helper.prepare(editor, "中", 1))
        assertEquals(WindowEdit.Replacement(7, 10, "\t"), edit.commit.replacement)
        assertTrue(helper.apply(edit))
        assertEquals("1\na   x\t中\n", editor.raw())
    }

    @Test fun literal_tabs_work_before_the_editor_has_redrawn_them() {
        val helper = WebEditorTabInput()
        val editor = Editor()
        insertTab(helper, editor)
        val edit = requireNotNull(helper.prepare(editor, "b", 1))
        assertEquals(WindowEdit.Replacement(3, 4, "\t"), edit.commit.replacement)
        assertTrue(helper.apply(edit))
        assertEquals("1\na\tb\n", editor.raw())
    }

    @Test fun empty_line_placeholder_can_disappear_from_either_side_of_the_caret() {
        for (caret in listOf(2, 3)) {
            val helper = WebEditorTabInput()
            val editor = Editor("1\n\u200b\n", caret)
            insertTab(helper, editor)
            editor.render("1\n    \n", 6)
            val edit = requireNotNull(helper.prepare(editor, "b", 1))
            assertEquals(WindowEdit.Replacement(2, 6, "\t"), edit.commit.replacement)
            assertTrue(helper.apply(edit))
            assertEquals("1\n\tb\n", editor.raw())
        }
    }

    @Test fun replacing_an_original_selection_preserves_both_anchors() {
        val helper = WebEditorTabInput()
        val editor = Editor("1\naOLDz\n", 3)
        editor.setSelection(3, 6)
        insertTab(helper, editor)
        editor.render("1\na   z\n", 6)
        assertTrue(helper.apply(requireNotNull(helper.prepare(editor, "b", 1))))
        assertEquals("1\na\tbz\n", editor.raw())
    }

    @Test fun ordinary_trailing_spaces_and_unowned_tabs_are_never_rewritten() {
        for (text in listOf("1\na   \n", "1\na\t\n")) {
            val helper = WebEditorTabInput()
            val editor = Editor(text)
            assertNull(helper.prepare(editor, "b", 1))
            assertEquals(emptyList<String>(), editor.events)
        }
    }

    @Test fun a_changed_prefix_suffix_or_tab_body_invalidates_the_marker() {
        for ((text, caret) in listOf("1\nz   \n" to 6, "1\na   z\n" to 6, "1\na b \n" to 6,
            "1\na\t\t\n" to 5, "1\na   \n" to 5)) {
            val helper = WebEditorTabInput()
            val editor = Editor()
            insertTab(helper, editor)
            editor.render(text, caret)
            assertNull(helper.prepare(editor, "b", 1))
            assertEquals(emptyList<String>(), editor.events)
        }
    }

    @Test fun a_changed_target_or_noncollapsed_selection_invalidates_the_marker() {
        val helper = WebEditorTabInput()
        val first = Editor()
        insertTab(helper, first)
        assertNull(helper.prepare(Editor("1\na   \n", 6), "b", 1))
        insertTab(helper, first)
        first.render("1\na       \n", 10)
        first.setSelection(3, 10)
        assertNull(helper.prepare(first, "b", 1))
    }

    @Test fun incomplete_snapshots_do_not_establish_or_use_a_marker() {
        val helper = WebEditorTabInput()
        val editor = Editor()
        editor.partial = true
        assertNull(helper.prepare(editor, "\t", 1))
        editor.partial = false
        insertTab(helper, editor)
        editor.render("1\na   \n", 6)
        editor.partial = true
        assertNull(helper.prepare(editor, "b", 1))
    }

    @Test fun ignored_selection_does_not_commit_the_tab_twice() {
        val helper = WebEditorTabInput()
        val editor = Editor()
        insertTab(helper, editor)
        editor.render("1\na   \n", 6)
        val edit = requireNotNull(helper.prepare(editor, "b", 1))
        editor.ignoreSelection = true
        assertFalse(helper.apply(edit))
        assertEquals("1\na   \n", editor.raw())
        assertFalse(editor.events.any { it.startsWith("commit:") })
        assertEquals("end", editor.events.last())
    }

    @Test fun a_concurrent_document_change_is_never_overwritten() {
        val helper = WebEditorTabInput()
        val editor = Editor()
        insertTab(helper, editor)
        editor.render("1\na   \n", 6)
        val edit = requireNotNull(helper.prepare(editor, "b", 1))
        editor.mutateOnSelection = "1\nother\n"
        assertFalse(helper.apply(edit))
        assertEquals("1\nother\n", editor.raw())
        assertFalse(editor.events.any { it.startsWith("commit:") })
    }

    @Test fun explicit_clear_drops_the_marker() {
        val helper = WebEditorTabInput()
        val editor = Editor()
        insertTab(helper, editor)
        editor.render("1\na   \n", 6)
        helper.clear()
        assertNull(helper.prepare(editor, "b", 1))
    }

    @Test fun external_connection_invalidations_leave_the_next_commit_unchanged() {
        val invalidations: List<(EditorUndoHistory, android.view.inputmethod.InputConnection) -> Unit> = listOf(
            { _, ic -> ic.setSelection(6, 6) },
            { _, ic -> ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)) },
            { _, ic -> ic.finishComposingText() },
            { _, ic -> ic.setComposingRegion(6, 6) },
            { history, _ -> history.cancelWebTabInput() },
        )
        for (invalidate in invalidations) {
            val editor = Editor()
            val history = EditorUndoHistory().apply { preferNativeUndo = true }
            val ic = history.wrap(editor)
            assertTrue(ic.commitText("\t", 1))
            editor.render("1\na   \n", 6)
            invalidate(history, ic)
            editor.events.clear()
            ic.commitText("b", 1)
            assertEquals(listOf("commit:b"), editor.events.filter { it.startsWith("select:") || it.startsWith("commit:") })
            history.clear()
        }
    }

    @Test fun non_web_connections_keep_the_original_commit_path() {
        val editor = Editor()
        val history = EditorUndoHistory()
        val ic = history.wrap(editor)
        assertTrue(ic.commitText("\t", 1))
        editor.render("1\na   \n", 6)
        editor.events.clear()
        assertTrue(ic.commitText("b", 1))
        assertEquals(listOf("commit:b"), editor.events.filter { it.startsWith("select:") || it.startsWith("commit:") })
        history.clear()
    }
}
