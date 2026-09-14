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

import android.graphics.Typeface
import android.graphics.Bitmap
import android.os.Looper
import android.os.Parcel
import android.text.InputFilter
import android.text.Selection
import android.text.Spanned
import android.text.SpannedString
import android.text.TextUtils
import android.text.method.TextKeyListener
import android.text.style.StyleSpan
import android.text.style.ImageSpan
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputConnection
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorUndoHistoryTest {
    private class ImageSender(val editor: Editor, id: String, private val markers: List<String>) {
        var calls = 0
        var deferred = false
        var rejected = false
        private val pending = ArrayDeque<() -> Unit>()
        val image = EditorUndoHistory.ImageContent(id) { connection ->
            val marker = markers[minOf(calls++, markers.lastIndex)]
            if (rejected) false else {
                val insert = {
                    val start = minOf(Selection.getSelectionStart(editor.content), Selection.getSelectionEnd(editor.content))
                    connection.commitText(marker, 1)
                    editor.content.setSpan(
                        ImageSpan(RuntimeEnvironment.getApplication(), Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)),
                        start, start + marker.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                if (deferred) pending.addLast(insert) else insert()
                true
            }
        }

        fun paste(history: EditorUndoHistory, connection: InputConnection) {
            history.beginRichContent(connection, image)
            history.finishRichContent(connection, image.paste(editor))
        }

        fun flush() { while (pending.isNotEmpty()) pending.removeFirst().invoke() }
    }

    private fun clearRich(history: EditorUndoHistory, connection: InputConnection) {
        assertEquals(EditorUndoHistory.ClearCapture.RICH, history.beginClearRestore(connection))
        assertTrue(history.clearCaptured(connection))
        assertTrue(history.hasClearedContent)
    }

    private class Editor(initial: String = "") : BaseInputConnection(View(RuntimeEnvironment.getApplication()), true) {
        val content get() = editable!!
        var readable = true
        var reads = 0
        var extractions = 0
        var deletionCalls = 0
        var parcelSnapshots = false
        var offset = 0
        var partial = -1
        var truncated = false
        var rejectSelection = false
        var ignoreSelection = false
        var rejectCommit = false
        var deferCommit = false
        var deferDeletion = false
        var deferKeys = false
        var deferContextPaste = false
        var contextPasteText = "pasted"
        var flushKeysDuringRead = false
        var pending: Pair<CharSequence?, Int>? = null
        private val pendingEdits = ArrayDeque<() -> Unit>()
        private var beforeRead: (() -> Unit)? = null
        val replacements = ArrayList<String>()
        val selectedRanges = ArrayList<Pair<Int, Int>>()
        val keyEvents = ArrayList<KeyEvent>()
        val contextActions = ArrayList<Int>()

        init {
            content.append(initial)
            Selection.setSelection(content, initial.length)
        }

        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? {
            reads++
            extractions++
            if (!readable) return null
            return ExtractedText().apply {
                val value = if (truncated) content.subSequence(0, content.length - 1) else SpannedString(content)
                text = if (parcelSnapshots) {
                    val parcel = Parcel.obtain()
                    try {
                        TextUtils.writeToParcel(value, parcel, 0)
                        parcel.setDataPosition(0)
                        TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(parcel)
                    } finally { parcel.recycle() }
                } else value
                startOffset = offset
                partialStartOffset = partial
                selectionStart = Selection.getSelectionStart(content)
                selectionEnd = Selection.getSelectionEnd(content)
            }
        }

        override fun setSelection(start: Int, end: Int): Boolean {
            selectedRanges.add(start to end)
            if (ignoreSelection) return true
            return !rejectSelection && super.setSelection(start, end)
        }

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? {
            reads++
            val action = beforeRead
            beforeRead = null
            action?.invoke()
            return super.getTextBeforeCursor(n, flags)
        }

        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? {
            reads++
            return super.getTextAfterCursor(n, flags)
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            if (rejectCommit) return false
            if (deferCommit) {
                pending = text to newCursorPosition
                return true
            }
            replacements.add(text.toString())
            return super.commitText(text, newCursorPosition)
        }

        fun flush() {
            while (pendingEdits.isNotEmpty()) pendingEdits.removeFirst().invoke()
            val next = pending ?: return
            pending = null
            super.commitText(next.first, next.second)
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            deletionCalls++
            if (!deferDeletion) return super.deleteSurroundingText(beforeLength, afterLength)
            pendingEdits.addLast { super.deleteSurroundingText(beforeLength, afterLength) }
            return true
        }

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
            if (!deferDeletion) return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
            pendingEdits.addLast { super.deleteSurroundingTextInCodePoints(beforeLength, afterLength) }
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            keyEvents.add(event)
            if (event.action != KeyEvent.ACTION_DOWN) return true
            if ((event.isCtrlPressed || event.isMetaPressed) && event.keyCode == KeyEvent.KEYCODE_C) return true
            if ((event.isCtrlPressed || event.isMetaPressed) && event.keyCode == KeyEvent.KEYCODE_A)
                return super.setSelection(0, content.length)
            val edit = {
                TextKeyListener.getInstance().onKeyDown(View(RuntimeEnvironment.getApplication()), content, event.keyCode, event)
                Unit
            }
            if (!deferKeys) edit() else pendingEdits.addLast(edit)
            if (deferKeys && flushKeysDuringRead) beforeRead = { flush() }
            return true
        }

        override fun performContextMenuAction(id: Int): Boolean {
            contextActions.add(id)
            return when (id) {
                android.R.id.selectAll -> super.setSelection(0, content.length)
                android.R.id.cut -> super.commitText("", 1)
                android.R.id.paste -> if (deferContextPaste) { pending = contextPasteText to 1; true }
                    else super.commitText(contextPasteText, 1)
                else -> false
            }
        }
    }

    @Test fun native_selected_tab_reports_pending_deletion_and_insertion_through_the_shared_callbacks() {
        for (rejected in listOf(false, true)) {
            val editor = Editor("selected long body ".repeat(8)).apply {
                Selection.setSelection(content, 0, content.length)
                deferKeys = true
                deferCommit = true
                rejectCommit = rejected
            }
            val history = EditorUndoHistory().apply { preferNativeUndo = true }
            val connection = history.wrap(editor)
            val completed = ArrayList<Boolean>()
            history.onInsertionCompleted = { completed.add(it) }
            assertTrue(connection.commitText("\t", 1))
            assertTrue(history.hasPendingInsertion)
            assertFalse(connection.setSelection(0, 0))
            assertFalse(connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MOVE_HOME)))
            assertFalse(connection.commitText("unexpected", 1))
            editor.flush()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            assertEquals("", editor.content.toString())
            assertEquals(!rejected, history.hasPendingInsertion)
            if (!rejected) {
                editor.flush()
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            }
            assertEquals(if (rejected) "" else "\t", editor.content.toString())
            assertEquals(listOf(!rejected), completed)
            assertFalse(history.hasPendingInsertion)
            assertTrue(history.canUndo(connection))
            assertEquals(1, editor.keyEvents.count { it.action == KeyEvent.ACTION_DOWN && it.keyCode == KeyEvent.KEYCODE_DEL })
        }
    }

    @Test fun inserts_and_deletes_undo_stepwise_without_host_undo_support() {
        val editor = Editor("one")
        val history = EditorUndoHistory()
        val connection = history.wrap(editor)
        connection.commitText(" two", 1)
        connection.deleteSurroundingText(3, 0)
        assertEquals("one ", editor.content.toString())
        assertTrue(history.undo(connection))
        assertEquals("one two", editor.content.toString())
        assertTrue(history.undo(connection))
        assertEquals("one", editor.content.toString())
        assertFalse(history.undo(connection))
    }

    @Test fun native_navigation_waits_for_pending_edits_and_only_wraps_known_moves() {
        val editor = Editor("base").apply { deferCommit = true }
        val history = EditorUndoHistory().apply { preferNativeUndo = true }
        val connection = history.wrap(editor)
        assertTrue(connection.commitText("!", 1))
        assertFalse(connection.setSelection(0, 0))
        assertFalse(connection.performContextMenuAction(android.R.id.selectAll))
        assertTrue(editor.contextActions.isEmpty())
        assertTrue(editor.selectedRanges.isEmpty())
        val moves = listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END)
        for (key in moves) {
            assertFalse(connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key)))
            assertFalse(connection.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, key, 0, KeyEvent.META_SHIFT_ON)))
        }
        for (meta in listOf(KeyEvent.META_CTRL_ON, KeyEvent.META_META_ON)) {
            assertFalse(connection.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0, meta)))
        }
        assertTrue(editor.keyEvents.isEmpty())
        for (key in moves) assertTrue(connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key)))
        assertTrue(connection.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C, 0, KeyEvent.META_CTRL_ON)))
        assertEquals(7, editor.keyEvents.size)
        assertEquals("base", editor.content.toString())
        editor.deferCommit = false
        editor.flush()
        assertTrue(history.canUndo(connection))
        assertTrue(connection.performContextMenuAction(android.R.id.selectAll))
        assertEquals(listOf(android.R.id.selectAll), editor.contextActions)
        for (meta in listOf(KeyEvent.META_CTRL_ON, KeyEvent.META_META_ON)) {
            assertTrue(connection.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0, meta)))
        }
        assertEquals(0, Selection.getSelectionStart(editor.content))
        assertEquals(editor.content.length, Selection.getSelectionEnd(editor.content))
        assertTrue(connection.setSelection(0, 0))
        assertEquals(listOf(0 to 0), editor.selectedRanges)
        assertTrue(history.canUndo(connection))
        assertTrue(history.undo(connection))
        assertEquals("base", editor.content.toString())
    }

    @Test fun plain_navigation_keeps_the_direct_connection_path() {
        val editor = Editor("base")
        val history = EditorUndoHistory()
        val connection = history.wrap(editor)
        val reads = editor.reads
        assertTrue(connection.setSelection(0, 0))
        assertTrue(connection.performContextMenuAction(android.R.id.selectAll))
        assertTrue(connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MOVE_END)))
        assertTrue(connection.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0, KeyEvent.META_CTRL_ON)))
        assertEquals(reads, editor.reads)
        assertEquals(listOf(0 to 0), editor.selectedRanges)
        assertEquals(listOf(android.R.id.selectAll), editor.contextActions)
        assertEquals(2, editor.keyEvents.size)
    }

    @Test fun stale_native_connection_navigation_does_not_rebind_the_current_history() {
        val history = EditorUndoHistory().apply { preferNativeUndo = true }
        val previous = Editor("previous")
        val stale = history.wrap(previous)
        val current = Editor("current").apply { deferCommit = true }
        val active = history.wrap(current)
        assertTrue(active.commitText("!", 1))
        assertTrue(stale.setSelection(0, 0))
        assertTrue(stale.performContextMenuAction(android.R.id.selectAll))
        assertTrue(stale.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MOVE_END)))
        assertTrue(stale.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0, KeyEvent.META_META_ON)))
        assertEquals(listOf(0 to 0), previous.selectedRanges)
        assertEquals(listOf(android.R.id.selectAll), previous.contextActions)
        assertEquals(2, previous.keyEvents.size)
        current.deferCommit = false
        current.flush()
        assertTrue(history.canUndo(active))
        assertTrue(history.undo(active))
        assertEquals("current", current.content.toString())
    }

    @Test fun native_context_paste_binds_the_captured_body_until_acknowledged() {
        val captured = StringBuilder("captured")
        val editor = Editor("base").apply { contextPasteText = captured.toString(); deferContextPaste = true }
        val history = EditorUndoHistory().apply { preferNativeUndo = true }
        val connection = history.wrap(editor)
        assertTrue(history.pasteCopiedText(connection, captured))
        assertEquals(listOf(android.R.id.paste), editor.contextActions)
        assertEquals("base", editor.content.toString())
        captured.append(" changed")
        assertFalse(history.canUndo(connection))
        editor.flush()
        assertEquals("basecaptured", editor.content.toString())
        assertTrue(history.canUndo(connection))
        assertEquals(listOf(android.R.id.paste), editor.contextActions)
    }

    @Test fun native_context_paste_does_not_accept_an_unexpected_body_as_the_captured_edit() {
        val editor = Editor("base").apply { contextPasteText = "different" }
        val history = EditorUndoHistory().apply { preferNativeUndo = true }
        val connection = history.wrap(editor)
        assertTrue(history.pasteCopiedText(connection, "expected"))
        assertEquals("basedifferent", editor.content.toString())
        assertEquals(listOf(android.R.id.paste), editor.contextActions)
        assertFalse(history.canUndo(connection))
    }

    @Test fun plain_context_paste_retains_the_existing_non_native_undo_path() {
        val editor = Editor("base")
        val history = EditorUndoHistory()
        val connection = history.wrap(editor)
        assertTrue(history.pasteCopiedText(connection, "native binding is unused"))
        assertEquals("basepasted", editor.content.toString())
        assertEquals(listOf(android.R.id.paste), editor.contextActions)
        assertTrue(history.canUndo(connection))
        assertTrue(history.undo(connection))
        assertEquals("base", editor.content.toString())
    }

    @Test fun undo_restores_reversed_selection_after_cursor_navigation_and_only_replaces_the_edit() {
        val editor = Editor("abcdef")
        editor.setSelection(4, 1)
        val history = EditorUndoHistory()
        val connection = history.wrap(editor)
        connection.commitText("XY", 1)
        connection.setSelection(0, 0)
        assertTrue(history.canUndo(connection))
        assertTrue(history.undo(connection))
        assertEquals("abcdef", editor.content.toString())
        assertEquals("bcd", editor.replacements.last())
        assertEquals(4, Selection.getSelectionStart(editor.content))
        assertEquals(1, Selection.getSelectionEnd(editor.content))
    }

    @Test fun batch_insertion_is_one_step_and_nested_batches_do_not_split_it() {
        val editor = Editor("a")
        val history = EditorUndoHistory()
        val connection = history.wrap(editor)
        connection.beginBatchEdit()
        connection.commitText("（", 1)
        connection.beginBatchEdit()
        connection.commitText("）", 0)
        connection.endBatchEdit()
        connection.endBatchEdit()
        assertEquals("a（）", editor.content.toString())
        assertTrue(history.undo(connection))
        assertEquals("a", editor.content.toString())
        assertFalse(history.canUndo(connection))
    }

    @Test fun composing_updates_are_one_step_and_undo_removes_composing_spans() {
        val editor = Editor("a")
        val history = EditorUndoHistory()
        val connection = history.wrap(editor)
        connection.setComposingText("n", 1)
        connection.setComposingText("ni", 1)
        connection.commitText("你", 1)
        assertEquals("a你", editor.content.toString())
        assertTrue(history.undo(connection))
        assertEquals("a", editor.content.toString())
        assertEquals(-1, BaseInputConnection.getComposingSpanStart(editor.content))
        assertFalse(history.canUndo(connection))
    }

    @Test fun composing_batches_can_be_undone_before_or_after_finishing() {
        for (finish in listOf(false, true)) {
            val editor = Editor("a")
            val history = EditorUndoHistory()
            val connection = history.wrap(editor)
            for (text in listOf("n", "ni", "你")) {
                connection.beginBatchEdit()
                connection.setComposingText(text, 1)
                connection.endBatchEdit()
            }
            if (finish) connection.finishComposingText()
            assertTrue(history.canUndo(connection))
            assertTrue(history.undo(connection))
            assertEquals("a", editor.content.toString())
            assertFalse(history.canUndo(connection))
        }
    }

    @Test fun external_content_changes_invalidate_history_but_a_new_edit_can_be_undone() {
        val editor = Editor("a")
        val history = EditorUndoHistory()
        val connection = history.wrap(editor)
        connection.commitText("b", 1)
        editor.content.replace(0, editor.content.length, "external")
        editor.setSelection(editor.content.length, editor.content.length)
        assertFalse(history.undo(connection))
        assertEquals("external", editor.content.toString())
        connection.commitText("!", 1)
        assertTrue(history.undo(connection))
        assertEquals("external", editor.content.toString())
        assertFalse(history.canUndo(connection))
    }

    @Test fun external_content_changes_during_composition_are_not_undone() {
        val editor = Editor("a")
        val history = EditorUndoHistory()
        val connection = history.wrap(editor)
        connection.setComposingText("ni", 1)
        editor.content.insert(0, "external")
        assertFalse(history.undo(connection))
        assertEquals("externalani", editor.content.toString())
    }

    @Test fun large_documents_are_rejected_before_requesting_an_unbounded_extracted_snapshot() {
        val text = "text\r\n".repeat(1000)
        for (caret in listOf(0, text.length / 2, text.length)) {
            val editor = Editor(text)
            Selection.setSelection(editor.content, caret)
            val history = EditorUndoHistory(maxTextLength = 1024)
            history.beginClearRestore(editor)
            assertEquals("long fields must not be copied through getExtractedText", 0, editor.extractions)
            assertFalse(history.clearCaptured(editor))
            assertEquals(text, editor.content.toString())
        }
    }

    @Test fun unreadable_partial_truncated_and_oversized_editors_fail_without_writes() {
        for (case in 0..4) {
            val editor = Editor("abcd")
            val history = EditorUndoHistory(maxTextLength = 8)
            val connection = history.wrap(editor)
            connection.commitText("e", 1)
            when (case) {
                0 -> editor.readable = false
                1 -> editor.offset = 1
                2 -> editor.partial = 0
                3 -> { editor.setSelection(1, 1); editor.truncated = true }
                4 -> { editor.content.append("long text"); editor.setSelection(1, 1) }
            }
            val original = editor.content.toString()
            val writes = editor.replacements.size
            assertFalse(history.undo(connection))
            assertEquals(original, editor.content.toString())
            assertEquals(writes, editor.replacements.size)
            assertFalse(history.hasUndo)
        }
    }

    @Test fun rejected_selection_and_commit_do_not_delete_or_replace_text() {
        for (rejectSelection in listOf(true, false)) {
            val editor = Editor("a")
            val history = EditorUndoHistory()
            val connection = history.wrap(editor)
            connection.commitText("b", 1)
            editor.rejectSelection = rejectSelection
            editor.rejectCommit = !rejectSelection
            assertFalse(history.undo(connection))
            assertEquals("ab", editor.content.toString())
        }
    }

    @Test fun delayed_commit_is_only_recorded_when_its_expected_result_is_observed() {
        val editor = Editor("a")
        val history = EditorUndoHistory()
        val connection = history.wrap(editor)
        editor.deferCommit = true
        connection.commitText("b", 1)
        assertFalse(history.canUndo(connection))
        editor.flush()
        editor.deferCommit = false
        assertTrue(history.canUndo(connection))
        assertTrue(history.undo(connection))
        assertEquals("a", editor.content.toString())
    }

    @Test fun ignored_selection_cannot_redirect_the_undo_replacement() {
        val editor = Editor("a")
        val history = EditorUndoHistory()
        val connection = history.wrap(editor)
        connection.commitText("b", 1)
        editor.ignoreSelection = true
        val writes = editor.replacements.size
        assertFalse(history.undo(connection))
        assertEquals("ab", editor.content.toString())
        assertEquals(writes, editor.replacements.size)
        assertFalse(history.hasUndo)
    }

    @Test fun a_delayed_clear_captures_the_short_sentence_once_and_restores_it_once() {
        val text = "怎么就改不对呢？"
        val editor = Editor(text)
        val history = EditorUndoHistory()
        val connection = history.wrap(editor)
        var held: String? = null
        history.onClearCompleted = { _, swept -> held = swept.toString() }
        editor.deferDeletion = true
        assertEquals(EditorUndoHistory.ClearCapture.PLAIN, history.beginClearRestore(connection))
        assertTrue(history.clearCaptured(connection))
        assertEquals(text, editor.content.toString())
        assertTrue(history.hasPendingClear)
        assertNull(held)
        assertEquals(1, editor.deletionCalls)
        assertTrue("one clear must not repeatedly query a stale editor: ${editor.reads}", editor.reads <= 6)
        editor.flush()
        assertTrue(history.canUndo(connection))
        assertFalse(history.hasPendingClear)
        assertEquals(text, held)
        assertEquals("", editor.content.toString())
        assertTrue(history.hasUndo)
        assertFalse(history.hasDeletionToRestore)
        assertTrue(history.undo(connection))
        assertEquals(text, editor.content.toString())
        assertFalse(history.hasDeletionToRestore)
        assertFalse(history.undo(connection))
        assertEquals(text, editor.content.toString())
    }

    @Test fun queued_backspace_after_tab_undo_is_observed_and_can_be_undone() {
        val editor = Editor("Undo baseline.")
        val history = EditorUndoHistory()
        val connection = history.wrap(editor)
        connection.commitText("\t", 1)
        assertTrue(history.undo(connection))
        assertFalse(history.hasUndo)
        editor.deferKeys = true
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
        assertEquals("Undo baseline.", editor.content.toString())
        assertTrue(history.hasUndo)
        assertFalse(history.canUndo(connection))
        editor.flush()
        assertEquals("Undo baseline", editor.content.toString())
        assertTrue(history.canUndo(connection))
        assertTrue(history.undo(connection))
        assertEquals("Undo baseline.", editor.content.toString())
        assertFalse(history.hasUndo)
    }

    @Test fun queued_forward_delete_and_selected_backspace_restore_the_original_selection() {
        for (key in listOf(KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL)) {
            val editor = Editor("abc")
            editor.setSelection(2, 1)
            editor.deferKeys = true
            val history = EditorUndoHistory()
            val connection = history.wrap(editor)
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
            editor.flush()
            assertEquals("ac", editor.content.toString())
            assertTrue(history.canUndo(connection))
            assertTrue(history.undo(connection))
            assertEquals("abc", editor.content.toString())
            assertEquals(2, Selection.getSelectionStart(editor.content))
            assertEquals(1, Selection.getSelectionEnd(editor.content))
        }
    }

    @Test fun delayed_surrounding_deletions_preserve_selected_text_and_count_code_points() {
        for (codePoints in listOf(false, true)) {
            val editor = Editor("a😀bc🧪z")
            editor.setSelection(5, 3)
            editor.deferDeletion = true
            val history = EditorUndoHistory()
            val connection = history.wrap(editor)
            if (codePoints) connection.deleteSurroundingTextInCodePoints(1, 1)
            else connection.deleteSurroundingText(2, 2)
            assertTrue(history.hasUndo)
            editor.flush()
            assertEquals("abcz", editor.content.toString())
            assertTrue(history.canUndo(connection))
            assertTrue(history.undo(connection))
            assertEquals("a😀bc🧪z", editor.content.toString())
            assertEquals(5, Selection.getSelectionStart(editor.content))
            assertEquals(3, Selection.getSelectionEnd(editor.content))
        }
    }

    @Test fun a_key_applied_between_extracted_and_surrounding_reads_gets_a_consistent_snapshot() {
        val editor = Editor("abc")
        editor.deferKeys = true
        editor.flushKeysDuringRead = true
        val history = EditorUndoHistory()
        val connection = history.wrap(editor)
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        assertEquals("ab", editor.content.toString())
        assertTrue(history.canUndo(connection))
        assertTrue(history.undo(connection))
        assertEquals("abc", editor.content.toString())
    }

    @Test fun multiple_queued_backspaces_remain_separate_steps_once_observed() {
        val editor = Editor("abc")
        editor.deferKeys = true
        val history = EditorUndoHistory()
        val connection = history.wrap(editor)
        repeat(2) {
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            editor.flush()
            assertTrue(history.canUndo(connection))
        }
        assertEquals("a", editor.content.toString())
        assertTrue(history.undo(connection))
        assertEquals("ab", editor.content.toString())
        assertTrue(history.undo(connection))
        assertEquals("abc", editor.content.toString())
    }

    @Test fun queued_deletion_does_not_claim_external_changes_or_create_a_step_at_the_text_boundary() {
        val editor = Editor("abc")
        editor.deferKeys = true
        val history = EditorUndoHistory()
        val connection = history.wrap(editor)
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        editor.flush()
        editor.content.insert(0, "external")
        assertFalse(history.undo(connection))
        assertEquals("externalab", editor.content.toString())
        connection.setSelection(0, 0)
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        editor.flush()
        assertFalse(history.canUndo(connection))
        assertFalse(history.hasUndo)
    }

    @Test fun input_filter_changes_are_not_reported_as_successful_undo() {
        val editor = Editor("long")
        editor.setSelection(0, 4)
        val history = EditorUndoHistory()
        val connection = history.wrap(editor)
        connection.commitText("x", 1)
        editor.content.filters = arrayOf(InputFilter.LengthFilter(2))
        assertFalse(history.undo(connection))
        assertEquals("lo", editor.content.toString())
        assertFalse(history.hasUndo)
    }

    @Test fun a_delayed_commit_does_not_claim_unrelated_external_text() {
        val editor = Editor("a")
        val history = EditorUndoHistory()
        val connection = history.wrap(editor)
        editor.deferCommit = true
        connection.commitText("b", 1)
        editor.content.append("external")
        editor.setSelection(editor.content.length, editor.content.length)
        assertFalse(history.undo(connection))
        assertEquals("aexternal", editor.content.toString())
    }

    @Test fun restoring_deleted_text_retains_its_formatting() {
        val editor = Editor("aboldz").apply { parcelSnapshots = true }
        editor.content.setSpan(StyleSpan(Typeface.BOLD), 1, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        editor.setSelection(1, 5)
        val history = EditorUndoHistory()
        val connection = history.wrap(editor)
        connection.commitText("", 1)
        assertTrue(history.undo(connection))
        assertEquals("aboldz", editor.content.toString())
        val span = editor.content.getSpans(1, 5, StyleSpan::class.java).single()
        assertEquals(Typeface.BOLD, span.style)
        assertEquals(1, editor.content.getSpanStart(span))
        assertEquals(5, editor.content.getSpanEnd(span))
    }

    @Test fun clearing_or_changing_fields_drops_history_and_old_connections_cannot_repopulate_it() {
        val first = Editor("a")
        val history = EditorUndoHistory()
        val old = history.wrap(first)
        assertSame(old, history.wrap(first))
        assertSame(old, history.wrap(old))
        old.commitText("b", 1)
        history.clear()
        assertFalse(history.hasUndo)
        val second = Editor("new")
        val current = history.wrap(second)
        old.commitText("c", 1)
        assertFalse(history.canUndo(current))
        current.commitText("!", 1)
        assertTrue(history.undo(current))
        assertEquals("new", second.content.toString())
        assertEquals("abc", first.content.toString())
    }

    @Test fun cut_paste_and_clear_actions_are_individually_reversible() {
        val editor = Editor("abc")
        editor.setSelection(0, 3)
        val history = EditorUndoHistory()
        val connection = history.wrap(editor)
        connection.performContextMenuAction(android.R.id.cut)
        connection.performContextMenuAction(android.R.id.paste)
        connection.setSelection(0, editor.content.length)
        connection.commitText("", 1)
        assertTrue(history.undo(connection))
        assertEquals("pasted", editor.content.toString())
        assertTrue(history.undo(connection))
        assertEquals("", editor.content.toString())
        assertTrue(history.undo(connection))
        assertEquals("abc", editor.content.toString())
        assertEquals(0, Selection.getSelectionStart(editor.content))
        assertEquals(3, Selection.getSelectionEnd(editor.content))
    }

    @Test fun emoji_replacements_do_not_split_surrogate_pairs() {
        val editor = Editor("a😀z")
        editor.setSelection(1, 3)
        val history = EditorUndoHistory()
        val connection = history.wrap(editor)
        connection.commitText("😁", 1)
        assertTrue(history.undo(connection))
        assertEquals("a😀z", editor.content.toString())
        assertEquals("😀", editor.replacements.last())
    }

    @Test fun cleared_mixed_content_restores_at_the_current_selection_and_that_restore_can_be_undone() {
        val editor = Editor("L").apply { parcelSnapshots = true }
        val history = EditorUndoHistory()
        val connection = history.wrap(editor)
        val first = ImageSender(editor, "first", listOf("old-1", "new-first-object"))
        val second = ImageSender(editor, "second", listOf("old-2", "new-second-object-longer"))
        first.paste(history, connection)
        connection.commitText("M", 1)
        second.paste(history, connection)
        connection.commitText("R", 1)
        clearRich(history, connection)
        assertEquals("", editor.content.toString())
        connection.commitText("before TARGET after", 1)
        connection.setSelection(7, 13)
        assertTrue(history.restoreClearedContent(connection))
        val restored = "Lnew-first-objectMnew-second-object-longerR"
        assertEquals("before $restored after", editor.content.toString())
        assertEquals(7 + restored.length, Selection.getSelectionStart(editor.content))
        assertEquals(7 + restored.length, Selection.getSelectionEnd(editor.content))
        assertEquals(2, editor.content.getSpans(0, editor.content.length, ImageSpan::class.java).size)
        assertFalse(history.hasClearedContent)
        assertTrue(history.undo(connection))
        assertEquals("before TARGET after", editor.content.toString())
        assertEquals(7, Selection.getSelectionStart(editor.content))
        assertEquals(13, Selection.getSelectionEnd(editor.content))
        assertFalse(history.restoreClearedContent(connection))
        assertEquals(2, first.calls)
        assertEquals(2, second.calls)
    }

    @Test fun undoing_the_clear_does_not_consume_the_independent_rich_restore_snapshot() {
        val editor = Editor().apply { parcelSnapshots = true }
        val history = EditorUndoHistory()
        val connection = history.wrap(editor)
        val sender = ImageSender(editor, "image", listOf("old", "returned", "duplicate"))
        sender.paste(history, connection)
        clearRich(history, connection)
        assertTrue(history.undo(connection))
        assertEquals("returned", editor.content.toString())
        assertTrue(history.hasClearedContent)
        assertTrue(history.restoreClearedContent(connection))
        assertEquals("returnedduplicate", editor.content.toString())
        assertEquals(2, editor.content.getSpans(0, editor.content.length, ImageSpan::class.java).size)
        assertTrue(history.undo(connection))
        assertEquals("returned", editor.content.toString())
        assertEquals(1, editor.content.getSpans(0, editor.content.length, ImageSpan::class.java).size)
    }

    @Test fun a_cleared_image_stays_retained_after_normal_history_eviction_and_through_async_restore() {
        val editor = Editor().apply { parcelSnapshots = true }
        val history = EditorUndoHistory()
        val connection = history.wrap(editor)
        val sender = ImageSender(editor, "image", listOf("old", "new-object"))
        var retained = emptySet<String>()
        val clearedCompletions = ArrayList<Boolean>()
        val undoCompletions = ArrayList<Boolean>()
        history.onRetainedImagesChanged = { retained = it }
        history.onClearedContentRestored = { clearedCompletions.add(it) }
        history.onUndoCompleted = { undoCompletions.add(it) }
        sender.paste(history, connection)
        clearRich(history, connection)
        repeat(60) { connection.commitText("x", 1) }
        assertEquals(setOf("image"), retained)
        sender.deferred = true
        assertFalse(history.restoreClearedContent(connection))
        assertTrue(history.hasPendingUndo)
        assertFalse(history.hasClearedContent)
        assertEquals(setOf("image"), retained)
        assertFalse(history.restoreClearedContent(connection))
        assertFalse(history.canUndo(connection))
        assertEquals(2, sender.calls)
        sender.flush()
        history.canUndo(connection)
        assertEquals(listOf(true), clearedCompletions)
        assertTrue(undoCompletions.isEmpty())
        assertEquals("x".repeat(60) + "new-object", editor.content.toString())
        assertEquals(editor.content.length, Selection.getSelectionStart(editor.content))
        assertTrue(history.undo(connection))
        assertEquals("x".repeat(60), editor.content.toString())
        assertEquals(emptySet<String>(), retained)
    }

    @Test fun failed_partial_or_unconfirmed_clears_do_not_create_a_rich_restore_snapshot() {
        for (failure in 0..3) {
            val editor = Editor("L").apply { parcelSnapshots = true }
            val history = EditorUndoHistory()
            val connection = history.wrap(editor)
            val sender = ImageSender(editor, "image", listOf("object"))
            sender.paste(history, connection)
            val original = editor.content.toString()
            assertEquals(EditorUndoHistory.ClearCapture.RICH, history.beginClearRestore(connection))
            when (failure) {
                0 -> { editor.rejectCommit = true; connection.setSelection(0, editor.content.length); connection.commitText("", 1) }
                1 -> { connection.setSelection(0, 1); connection.commitText("", 1) }
                2 -> { connection.setSelection(0, editor.content.length); connection.commitText("", 1) }
                3 -> { editor.deferDeletion = true; connection.deleteSurroundingText(editor.content.length, 0) }
            }
            assertFalse(history.finishClearRestore(connection, if (failure == 2) "incomplete" else original))
            assertFalse(history.hasClearedContent)
            editor.flush()
            assertFalse(history.hasClearedContent)
            assertFalse(history.restoreClearedContent(connection))
            assertEquals(1, sender.calls)
        }
    }

    @Test fun rich_restore_keeps_its_snapshot_when_selection_is_rejected_but_consumes_a_partial_attempt() {
        val editor = Editor("L").apply { parcelSnapshots = true }
        val history = EditorUndoHistory()
        val connection = history.wrap(editor)
        val sender = ImageSender(editor, "image", listOf("object"))
        sender.paste(history, connection)
        connection.commitText("R", 1)
        clearRich(history, connection)
        editor.rejectSelection = true
        assertFalse(history.restoreClearedContent(connection))
        assertTrue(history.hasClearedContent)
        assertEquals("", editor.content.toString())
        assertEquals(1, sender.calls)
        editor.rejectSelection = false
        sender.rejected = true
        assertFalse(history.restoreClearedContent(connection))
        assertEquals("L", editor.content.toString())
        assertFalse(history.hasClearedContent)
        assertFalse(history.hasPendingUndo)
        assertFalse(history.restoreClearedContent(connection))
        assertEquals("L", editor.content.toString())
        assertEquals(2, sender.calls)
    }

    @Test fun unknown_rich_content_is_not_captured_as_text_and_large_plain_clears_keep_the_legacy_path() {
        val editor = Editor().apply { parcelSnapshots = true }
        val history = EditorUndoHistory()
        val connection = history.wrap(editor)
        history.beginRichContent(connection)
        editor.commitText("private-object", 1)
        history.finishRichContent(connection, true)
        assertEquals(EditorUndoHistory.ClearCapture.UNSUPPORTED, history.beginClearRestore(connection))
        connection.setSelection(0, editor.content.length)
        connection.commitText("", 1)
        assertFalse(history.finishClearRestore(connection, "private-object"))
        assertFalse(history.hasClearedContent)
        history.clear()
        val plain = Editor("p".repeat(70_000))
        val plainConnection = history.wrap(plain)
        assertEquals(EditorUndoHistory.ClearCapture.PLAIN, history.beginClearRestore(plainConnection))
        assertFalse(history.hasClearedContent)
    }

    @Test fun a_later_plain_clear_replaces_the_rich_backup_and_session_clear_releases_it() {
        val editor = Editor().apply { parcelSnapshots = true }
        val history = EditorUndoHistory()
        val connection = history.wrap(editor)
        val sender = ImageSender(editor, "image", listOf("object"))
        sender.paste(history, connection)
        clearRich(history, connection)
        connection.commitText("plain", 1)
        assertEquals(EditorUndoHistory.ClearCapture.PLAIN, history.beginClearRestore(connection))
        connection.setSelection(0, editor.content.length)
        connection.commitText("", 1)
        history.finishClearRestore(connection, "plain")
        assertFalse(history.hasClearedContent)
        sender.paste(history, connection)
        clearRich(history, connection)
        history.clear()
        assertFalse(history.hasClearedContent)
        assertFalse(history.restoreClearedContent(connection))
    }

    @Test fun restoring_cleared_content_finishes_new_composition_and_remains_independently_undoable() {
        val editor = Editor().apply { parcelSnapshots = true }
        val history = EditorUndoHistory()
        val connection = history.wrap(editor)
        val sender = ImageSender(editor, "image", listOf("old", "restored"))
        sender.paste(history, connection)
        clearRich(history, connection)
        connection.setComposingText("n", 1)
        connection.setComposingText("ni", 1)
        assertTrue(history.restoreClearedContent(connection))
        assertEquals("nirestored", editor.content.toString())
        assertTrue(history.undo(connection))
        assertEquals("ni", editor.content.toString())
        assertTrue(history.undo(connection))
        assertEquals("", editor.content.toString())
    }

    @Test fun known_image_deletion_replays_the_image_and_keeps_earlier_text_undo_with_new_marker_lengths() {
        val editor = Editor("AB").apply { parcelSnapshots = true }
        editor.setSelection(1, 1)
        val history = EditorUndoHistory()
        val connection = history.wrap(editor)
        val sender = ImageSender(editor, "photo", listOf("old", "new-long-image-reference"))
        sender.paste(history, connection)
        connection.setSelection(editor.content.length, editor.content.length)
        connection.commitText(" tail", 1)
        connection.setSelection(1, 4)
        connection.commitText("", 1)
        assertEquals("AB tail", editor.content.toString())
        assertTrue(history.undo(connection))
        assertEquals(2, sender.calls)
        assertEquals("Anew-long-image-referenceB tail", editor.content.toString())
        assertEquals(1, editor.content.getSpans(0, editor.content.length, ImageSpan::class.java).size)
        assertTrue(history.undo(connection))
        assertEquals("Anew-long-image-referenceB", editor.content.toString())
        assertTrue(history.undo(connection))
        assertEquals("AB", editor.content.toString())
        assertEquals(0, editor.content.getSpans(0, editor.content.length, ImageSpan::class.java).size)
    }

    @Test fun mixed_text_and_multiple_known_images_are_restored_in_order_after_replacement() {
        val editor = Editor("left ").apply { parcelSnapshots = true }
        val history = EditorUndoHistory()
        val connection = history.wrap(editor)
        val first = ImageSender(editor, "first", listOf("one", "first-new-object"))
        val second = ImageSender(editor, "second", listOf("two", "second-new-object-longer"))
        first.paste(history, connection)
        connection.commitText(" middle ", 1)
        second.paste(history, connection)
        connection.commitText(" right", 1)
        connection.setSelection(0, editor.content.length)
        connection.commitText("replacement", 1)
        assertTrue(history.undo(connection))
        assertEquals("left first-new-object middle second-new-object-longer right", editor.content.toString())
        assertEquals(2, first.calls)
        assertEquals(2, second.calls)
        assertEquals(2, editor.content.getSpans(0, editor.content.length, ImageSpan::class.java).size)
        assertEquals(0, Selection.getSelectionStart(editor.content))
        assertEquals(editor.content.length, Selection.getSelectionEnd(editor.content))
    }

    @Test fun known_object_characters_and_replacement_spans_can_be_restored_as_images() {
        for (marker in listOf("\uFFFC", "image")) {
            val editor = Editor("A")
            val history = EditorUndoHistory()
            val connection = history.wrap(editor)
            val sender = ImageSender(editor, "image", listOf(marker))
            sender.paste(history, connection)
            connection.setSelection(1, editor.content.length)
            connection.commitText("", 1)
            assertTrue(history.undo(connection))
            assertEquals("A$marker", editor.content.toString())
            assertEquals(2, sender.calls)
            assertEquals(1, editor.content.getSpans(1, editor.content.length, ImageSpan::class.java).size)
        }
    }

    @Test fun delayed_image_paste_and_undo_are_confirmed_once_without_repeating_the_dispatch() {
        val editor = Editor("A").apply { parcelSnapshots = true }
        val history = EditorUndoHistory()
        val connection = history.wrap(editor)
        val sender = ImageSender(editor, "image", listOf("old", "new-image"))
        val completions = ArrayList<Boolean>()
        history.onUndoCompleted = { completions.add(it) }
        sender.deferred = true
        sender.paste(history, connection)
        assertFalse(history.canUndo(connection))
        sender.flush()
        assertTrue(history.canUndo(connection))
        connection.setSelection(1, 4)
        connection.commitText("", 1)
        assertFalse(history.undo(connection))
        assertTrue(history.hasPendingUndo)
        assertEquals(2, sender.calls)
        assertFalse(history.canUndo(connection))
        assertEquals(2, sender.calls)
        sender.flush()
        history.canUndo(connection)
        assertFalse(history.hasPendingUndo)
        assertEquals(listOf(true), completions)
        assertEquals("Anew-image", editor.content.toString())
        assertEquals(1, editor.content.getSpans(1, editor.content.length, ImageSpan::class.java).size)
    }

    @Test fun mixed_restore_waits_for_the_image_before_writing_its_following_text() {
        val editor = Editor("left ").apply { parcelSnapshots = true }
        val history = EditorUndoHistory()
        val connection = history.wrap(editor)
        val sender = ImageSender(editor, "image", listOf("old", "new-object"))
        sender.paste(history, connection)
        connection.commitText(" right", 1)
        connection.setSelection(0, editor.content.length)
        connection.commitText("replacement", 1)
        sender.deferred = true
        assertFalse(history.undo(connection))
        assertEquals("left t", editor.content.toString())
        assertTrue(history.hasPendingUndo)
        sender.flush()
        history.canUndo(connection)
        assertFalse(history.hasPendingUndo)
        assertEquals("left new-object right", editor.content.toString())
        assertEquals(2, sender.calls)
    }

    @Test fun rejected_image_restore_never_substitutes_the_old_marker_as_plain_text() {
        val editor = Editor("AB").apply { parcelSnapshots = true }
        editor.setSelection(1, 1)
        val history = EditorUndoHistory()
        val connection = history.wrap(editor)
        val sender = ImageSender(editor, "image", listOf("private-object"))
        sender.paste(history, connection)
        connection.setSelection(1, editor.content.length - 1)
        connection.commitText("", 1)
        val writes = editor.replacements.size
        sender.rejected = true
        assertFalse(history.undo(connection))
        assertEquals("AB", editor.content.toString())
        assertFalse(history.hasPendingUndo)
        assertEquals(writes, editor.replacements.size)
        assertFalse(history.hasUndo)
    }

    @Test fun known_images_stay_retained_through_dispatch_deletion_and_restore_until_session_clear() {
        val editor = Editor().apply { parcelSnapshots = true }
        val history = EditorUndoHistory()
        val connection = history.wrap(editor)
        val sender = ImageSender(editor, "image", listOf("object"))
        val retained = ArrayList<Set<String>>()
        history.onRetainedImagesChanged = { retained.add(it.toSet()) }
        history.beginRichContent(connection, sender.image)
        assertEquals(setOf("image"), retained.last())
        history.finishRichContent(connection, sender.image.paste(editor))
        connection.setSelection(0, editor.content.length)
        connection.commitText("", 1)
        assertEquals(setOf("image"), retained.last())
        assertTrue(history.undo(connection))
        assertEquals(setOf("image"), retained.last())
        history.clear()
        assertEquals(emptySet<String>(), retained.last())
    }

    @Test fun beginning_an_image_retains_it_while_capture_confirms_the_previous_pending_image() {
        val editor = Editor().apply { parcelSnapshots = true }
        val history = EditorUndoHistory()
        val connection = history.wrap(editor)
        val first = ImageSender(editor, "first", listOf("first-object"))
        val second = ImageSender(editor, "second", listOf("second-object"))
        first.deferred = true
        first.paste(history, connection)
        first.flush()
        val retained = ArrayList<Set<String>>()
        history.onRetainedImagesChanged = { retained.add(it.toSet()) }
        history.beginRichContent(connection, second.image)
        assertTrue(retained.isNotEmpty())
        assertTrue(retained.all { it == setOf("first", "second") })
        history.finishRichContent(connection, false)
        assertEquals(setOf("first"), retained.last())
        assertTrue(history.canUndo(connection))
        history.clear()
        assertEquals(emptySet<String>(), retained.last())
    }

    @Test fun overlapping_deferred_images_never_bind_the_first_result_to_the_last_image() {
        val editor = Editor("AB").apply { parcelSnapshots = true }
        editor.setSelection(1, 1)
        val history = EditorUndoHistory()
        val connection = history.wrap(editor)
        val first = ImageSender(editor, "first", listOf("first-object")).apply { deferred = true }
        val second = ImageSender(editor, "second", listOf("second-object")).apply { deferred = true }
        var retained = emptySet<String>()
        history.onRetainedImagesChanged = { retained = it }
        first.paste(history, connection)
        second.paste(history, connection)
        assertEquals(setOf("first", "second"), retained)
        first.flush()
        assertFalse(history.canUndo(connection))
        assertFalse(history.undo(connection))
        assertEquals(1, first.calls)
        assertEquals(1, second.calls)
        connection.setSelection(1, 13)
        assertTrue(history.selectionContainsRichContent(connection))
        connection.commitText("", 1)
        assertFalse(history.undo(connection))
        assertEquals("AB", editor.content.toString())
        second.flush()
        assertFalse(history.canUndo(connection))
        assertEquals(setOf("first", "second"), retained)
        history.clear()
        assertEquals(emptySet<String>(), retained)
    }

    @Test fun repeated_external_attachments_are_dispatched_once_and_keep_normal_composition_undo() {
        val editor = Editor()
        val history = EditorUndoHistory()
        val connection = history.wrap(editor)
        val calls = IntArray(3)
        var retained = emptySet<String>()
        history.onRetainedImagesChanged = { retained = it }
        repeat(3) { index ->
            val image = EditorUndoHistory.ImageContent("image-$index") { calls[index]++; true }
            history.beginRichContent(connection, image)
            history.finishRichContent(connection, image.paste(editor))
        }
        assertTrue(calls.all { it == 1 })
        assertEquals(setOf("image-0", "image-1", "image-2"), retained)
        connection.setComposingText("n", 1)
        connection.setComposingText("ni", 1)
        connection.commitText("你", 1)
        assertTrue(history.undo(connection))
        assertEquals("", editor.content.toString())
        assertTrue(calls.all { it == 1 })
    }

    @Test fun anonymous_dispatches_cover_every_pending_caret_before_a_later_image_arrives() {
        val editor = Editor("AB").apply { parcelSnapshots = true }
        editor.setSelection(0, 0)
        val history = EditorUndoHistory()
        val connection = history.wrap(editor)
        val first = ImageSender(editor, "first", listOf("first-object")).apply { deferred = true }
        val second = ImageSender(editor, "second", listOf("second-object")).apply { deferred = true }
        val third = ImageSender(editor, "third", listOf("third-object")).apply { deferred = true }
        var retained = emptySet<String>()
        history.onRetainedImagesChanged = { retained = it }
        first.paste(history, connection)
        connection.setSelection(2, 2)
        second.paste(history, connection)
        third.paste(history, connection)
        second.flush()
        assertEquals("ABsecond-object", editor.content.toString())
        assertFalse(history.canUndo(connection))
        assertFalse(history.undo(connection))
        assertEquals(1, first.calls)
        assertEquals(1, second.calls)
        assertEquals(1, third.calls)
        connection.setSelection(2, editor.content.length)
        assertTrue(history.selectionContainsRichContent(connection))
        connection.commitText("", 1)
        assertFalse(history.undo(connection))
        assertEquals("AB", editor.content.toString())
        assertEquals(setOf("first", "second", "third"), retained)
    }

    @Test fun anonymous_image_references_use_the_existing_fifty_step_bound() {
        val editor = Editor()
        val history = EditorUndoHistory()
        val connection = history.wrap(editor)
        var retained = emptySet<String>()
        history.onRetainedImagesChanged = { retained = it }
        repeat(52) { index ->
            val image = EditorUndoHistory.ImageContent("image-$index") { true }
            history.beginRichContent(connection, image)
            history.finishRichContent(connection, image.paste(editor))
        }
        assertEquals((2 until 52).map { "image-$it" }.toSet(), retained)
        history.clear()
        assertEquals(emptySet<String>(), retained)
    }

    @Test fun confirmed_images_and_text_outside_an_anonymous_group_keep_undo() {
        val editor = Editor("AB").apply { parcelSnapshots = true }
        editor.setSelection(0, 0)
        val history = EditorUndoHistory()
        val connection = history.wrap(editor)
        val confirmed = ImageSender(editor, "confirmed", listOf("K", "known-new"))
        confirmed.paste(history, connection)
        connection.setSelection(editor.content.length, editor.content.length)
        val first = ImageSender(editor, "first", listOf("P")).apply { deferred = true }
        val second = ImageSender(editor, "second", listOf("Q")).apply { deferred = true }
        first.paste(history, connection)
        second.paste(history, connection)
        first.flush()
        history.canUndo(connection)
        second.flush()
        history.canUndo(connection)
        connection.commitText(" tail", 1)
        assertTrue(history.undo(connection))
        assertEquals("KABPQ", editor.content.toString())
        connection.setSelection(0, 1)
        connection.commitText("", 1)
        assertTrue(history.undo(connection))
        assertEquals("known-newABPQ", editor.content.toString())
        assertEquals(2, confirmed.calls)
        connection.setSelection(0, 0)
        val later = ImageSender(editor, "later", listOf("Z", "ZZ"))
        later.paste(history, connection)
        connection.setSelection(0, 1)
        connection.commitText("", 1)
        assertTrue(history.undo(connection))
        assertEquals("ZZknown-newABPQ", editor.content.toString())
        assertEquals(2, later.calls)
        assertEquals(1, first.calls)
        assertEquals(1, second.calls)
    }

    @Test fun atomic_host_backspace_of_a_known_image_is_reversible_even_when_its_token_is_long() {
        val editor = Editor("A").apply { parcelSnapshots = true }
        val host = object : InputConnectionWrapper(editor, false) {
            override fun sendKeyEvent(event: KeyEvent): Boolean {
                editor.setSelection(1, editor.content.length)
                return editor.commitText("", 1)
            }
        }
        val history = EditorUndoHistory()
        val connection = history.wrap(host)
        val sender = ImageSender(editor, "image", listOf("long-private-object", "new-object"))
        sender.paste(history, connection)
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        assertEquals("A", editor.content.toString())
        assertTrue(history.undo(connection))
        assertEquals("Anew-object", editor.content.toString())
        assertEquals(2, sender.calls)
    }

    @Test fun identical_known_images_keep_separate_instances_when_one_is_deleted_and_restored() {
        for (position in 0..1) {
            val editor = Editor().apply { parcelSnapshots = true }
            val history = EditorUndoHistory()
            val connection = history.wrap(editor)
            val sender = ImageSender(editor, "same-file", listOf("X"))
            repeat(2) { sender.paste(history, connection) }
            connection.setSelection(position, position + 1)
            connection.commitText("", 1)
            assertTrue(history.undo(connection))
            assertEquals("XX", editor.content.toString())
            assertEquals(3, sender.calls)
            assertEquals(2, editor.content.getSpans(0, editor.content.length, ImageSpan::class.java).size)
            assertTrue(history.undo(connection))
            assertEquals("X", editor.content.toString())
        }
    }

    @Test fun composition_outside_a_known_image_remains_an_independent_text_undo_step() {
        val editor = Editor().apply { parcelSnapshots = true }
        val history = EditorUndoHistory()
        val connection = history.wrap(editor)
        val sender = ImageSender(editor, "image", listOf("object"))
        sender.paste(history, connection)
        connection.setComposingText("n", 1)
        connection.setComposingText("ni", 1)
        connection.commitText("你", 1)
        assertTrue(history.undo(connection))
        assertEquals("object", editor.content.toString())
        assertEquals(1, sender.calls)
        assertEquals(1, editor.content.getSpans(0, editor.content.length, ImageSpan::class.java).size)
    }

    @Test fun image_spans_lost_during_parcel_transport_cannot_make_private_tokens_replayable() {
        val marker = "opaque-image-object-123.jpg"
        val editor = Editor("AB").apply { parcelSnapshots = true }
        editor.setSelection(1, 1)
        val history = EditorUndoHistory()
        val connection = history.wrap(editor)
        history.beginRichContent(connection)
        editor.commitText(marker, 1)
        editor.content.setSpan(
            ImageSpan(RuntimeEnvironment.getApplication(), Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)),
            1, 1 + marker.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        val transported = editor.getExtractedText(null, 0)!!.text as Spanned
        assertEquals(0, transported.getSpans(0, transported.length, ImageSpan::class.java).size)
        assertEquals("A${marker}B", transported.toString())
        history.finishRichContent(connection, true)
        connection.setSelection(1, 1 + marker.length)
        assertTrue(history.selectionContainsRichContent(connection))
        connection.commitText("", 1)
        val writes = editor.replacements.size
        assertEquals("AB", editor.content.toString())
        assertFalse(history.undo(connection))
        assertEquals(writes, editor.replacements.size)
        assertFalse(history.hasUndo)
    }

    @Test fun replacement_spans_and_object_characters_are_not_text_snapshots() {
        for (placeholder in listOf("\uFFFC", "image")) {
            val editor = Editor(placeholder)
            if (placeholder == "image") editor.content.setSpan(
                ImageSpan(RuntimeEnvironment.getApplication(), Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)),
                0, placeholder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            val history = EditorUndoHistory()
            val connection = history.wrap(editor)
            connection.setSelection(0, placeholder.length)
            connection.commitText("", 1)
            assertFalse(history.undo(connection))
            assertEquals("", editor.content.toString())
        }
    }

    @Test fun an_immediate_host_deletion_that_exceeds_the_requested_key_is_not_recorded() {
        val editor = Editor("private-image-token")
        val host = object : InputConnectionWrapper(editor, false) {
            override fun sendKeyEvent(event: KeyEvent): Boolean {
                editor.setSelection(0, editor.content.length)
                return editor.commitText("", 1)
            }
        }
        val history = EditorUndoHistory()
        val connection = history.wrap(host)
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        assertEquals("", editor.content.toString())
        assertFalse(history.undo(connection))
        assertFalse(history.hasUndo)
    }

    @Test fun native_rich_paste_rejection_without_dispatch_preserves_the_old_text_history() {
        val editor = Editor("a")
        val history = EditorUndoHistory()
        val connection = history.wrap(editor)
        connection.commitText("b", 1)
        history.beginRichContent(connection)
        assertFalse(history.canUndo(connection))
        history.finishRichContent(connection, false)
        assertTrue(history.canUndo(connection))
        assertTrue(history.undo(connection))
        assertEquals("a", editor.content.toString())
    }

    @Test fun rich_replacement_without_a_text_change_still_protects_the_selected_range() {
        val editor = Editor("AopaqueB")
        editor.setSelection(1, 7)
        val history = EditorUndoHistory()
        val connection = history.wrap(editor)
        history.beginRichContent(connection)
        history.finishRichContent(connection, true)
        connection.commitText("", 1)
        assertEquals("AB", editor.content.toString())
        assertFalse(history.undo(connection))
    }

    @Test fun immediate_and_batched_commits_must_match_the_known_expected_text() {
        for (batch in listOf(false, true)) {
            val editor = Editor("a")
            editor.content.filters = arrayOf(InputFilter.AllCaps())
            val history = EditorUndoHistory()
            val connection = history.wrap(editor)
            if (batch) connection.beginBatchEdit()
            connection.commitText("b", 1)
            if (batch) connection.endBatchEdit()
            assertEquals("aB", editor.content.toString())
            assertFalse(history.canUndo(connection))
            assertFalse(history.undo(connection))
        }
    }

    @Test fun a_delayed_rich_paste_is_located_before_a_selected_token_deletion() {
        val editor = Editor("AB")
        editor.setSelection(1, 1)
        val history = EditorUndoHistory()
        val connection = history.wrap(editor)
        history.beginRichContent(connection)
        history.finishRichContent(connection, true)
        editor.commitText("private-image", 1)
        connection.setSelection(1, 14)
        connection.commitText("", 1)
        assertEquals("AB", editor.content.toString())
        assertFalse(history.undo(connection))
        assertFalse(history.hasUndo)
    }

    @Test fun ordinary_text_outside_the_rich_range_keeps_insertion_and_deletion_undo() {
        val editor = Editor("AB")
        editor.setSelection(1, 1)
        val history = EditorUndoHistory()
        val connection = history.wrap(editor)
        history.beginRichContent(connection)
        editor.commitText("opaque", 1)
        history.finishRichContent(connection, true)
        connection.setSelection(editor.content.length, editor.content.length)
        connection.commitText("xy", 1)
        connection.deleteSurroundingText(1, 0)
        assertTrue(history.undo(connection))
        assertEquals("AopaqueBxy", editor.content.toString())
        assertTrue(history.undo(connection))
        assertEquals("AopaqueB", editor.content.toString())
        connection.setSelection(1, 7)
        assertTrue(history.selectionContainsRichContent(connection))
        connection.setSelection(0, 1)
        assertFalse(history.selectionContainsRichContent(connection))
        connection.commitText("C", 1)
        assertTrue(history.undo(connection))
        assertEquals("AopaqueB", editor.content.toString())
    }

    @Test fun a_partial_token_deletion_keeps_the_remaining_token_protected() {
        val editor = Editor("AB")
        editor.setSelection(1, 1)
        val history = EditorUndoHistory()
        val connection = history.wrap(editor)
        history.beginRichContent(connection)
        editor.commitText("opaque", 1)
        history.finishRichContent(connection, true)
        connection.setSelection(3, 5)
        connection.commitText("", 1)
        assertEquals("AopueB", editor.content.toString())
        assertFalse(history.undo(connection))
        connection.setSelection(1, 5)
        assertTrue(history.selectionContainsRichContent(connection))
        connection.commitText("", 1)
        assertFalse(history.undo(connection))
        assertEquals("AB", editor.content.toString())
    }

    @Test fun identical_plain_text_cannot_disguise_deletion_of_an_opaque_character() {
        for (position in 0..1) {
            val editor = Editor("X")
            editor.setSelection(position, position)
            val history = EditorUndoHistory()
            val connection = history.wrap(editor)
            history.beginRichContent(connection)
            editor.commitText("X", 1)
            history.finishRichContent(connection, true)
            connection.setSelection(position, position + 1)
            connection.commitText("", 1)
            assertEquals("X", editor.content.toString())
            assertFalse(history.undo(connection))
            connection.setSelection(0, 1)
            assertTrue(history.selectionContainsRichContent(connection))
            connection.commitText("", 1)
            assertFalse(history.undo(connection))
            connection.commitText("plain", 1)
            assertTrue(history.undo(connection))
            assertEquals("", editor.content.toString())
        }
    }

    @Test fun deleting_one_of_two_identical_rich_objects_keeps_the_other_protected() {
        for (position in 0..1) {
            val editor = Editor()
            val history = EditorUndoHistory()
            val connection = history.wrap(editor)
            repeat(2) {
                history.beginRichContent(connection)
                editor.commitText("X", 1)
                history.finishRichContent(connection, true)
            }
            connection.setSelection(position, position + 1)
            connection.commitText("", 1)
            assertEquals("X", editor.content.toString())
            assertFalse(history.undo(connection))
            connection.setSelection(0, 1)
            assertTrue(history.selectionContainsRichContent(connection))
            connection.commitText("", 1)
            assertFalse(history.undo(connection))
            assertEquals("", editor.content.toString())
        }
    }

    @Test fun a_new_connection_for_the_same_field_keeps_the_rich_boundary_until_session_reset() {
        val editor = Editor("AB")
        editor.setSelection(1, 1)
        val history = EditorUndoHistory()
        val first = history.wrap(editor)
        history.beginRichContent(first)
        editor.commitText("opaque", 1)
        history.finishRichContent(first, true)
        val second = history.wrap(InputConnectionWrapper(editor, false))
        second.setSelection(1, 7)
        assertTrue(history.selectionContainsRichContent(second))
        second.commitText("", 1)
        assertFalse(history.undo(second))
        history.clear()
        editor.content.replace(0, editor.content.length, "plain")
        editor.setSelection(5, 5)
        val third = history.wrap(editor)
        third.commitText("!", 1)
        assertTrue(history.undo(third))
        assertEquals("plain", editor.content.toString())
    }

    @Test fun images_outside_the_text_composer_do_not_disable_ordinary_text_undo() {
        val editor = Editor("a")
        val history = EditorUndoHistory()
        val connection = history.wrap(editor)
        history.beginRichContent(connection)
        history.finishRichContent(connection, true)
        connection.commitText("bc", 1)
        connection.deleteSurroundingText(1, 0)
        assertTrue(history.undo(connection))
        assertEquals("abc", editor.content.toString())
        assertTrue(history.undo(connection))
        assertEquals("a", editor.content.toString())
        connection.setComposingText("n", 1)
        connection.setComposingText("ni", 1)
        connection.commitText("你", 1)
        assertTrue(history.undo(connection))
        assertEquals("a", editor.content.toString())
    }

    @Test fun composing_after_an_external_image_attachment_can_undo_to_empty_text() {
        for (finish in listOf(false, true)) {
            val editor = Editor()
            val history = EditorUndoHistory()
            val connection = history.wrap(editor)
            history.beginRichContent(connection)
            history.finishRichContent(connection, true)
            connection.setComposingText("n", 1)
            connection.setComposingText("ni", 1)
            if (finish) {
                connection.setComposingText("你", 1)
                connection.finishComposingText()
            } else connection.commitText("你", 1)
            assertTrue(history.undo(connection))
            assertEquals("", editor.content.toString())
            assertFalse(history.hasUndo)
        }
    }

    @Test fun composing_outside_a_known_rich_range_keeps_text_undo() {
        val editor = Editor("AB")
        editor.setSelection(1, 1)
        val history = EditorUndoHistory()
        val connection = history.wrap(editor)
        history.beginRichContent(connection)
        editor.commitText("opaque", 1)
        history.finishRichContent(connection, true)
        connection.setSelection(editor.content.length, editor.content.length)
        connection.setComposingText("n", 1)
        connection.setComposingText("ni", 1)
        connection.commitText("你", 1)
        assertTrue(history.undo(connection))
        assertEquals("AopaqueB", editor.content.toString())
        connection.setSelection(1, 7)
        assertTrue(history.selectionContainsRichContent(connection))
    }

    @Test fun a_late_image_after_normal_typing_is_protected_without_guessing_its_token_format() {
        val editor = Editor("AB")
        editor.setSelection(1, 1)
        val history = EditorUndoHistory()
        val connection = history.wrap(editor)
        history.beginRichContent(connection)
        history.finishRichContent(connection, true)
        connection.commitText("x", 1)
        editor.commitText("unrecognized-object", 1)
        connection.setSelection(2, 21)
        assertTrue(history.selectionContainsRichContent(connection))
        connection.commitText("", 1)
        assertEquals("AxB", editor.content.toString())
        assertFalse(history.undo(connection))
    }

    @Test fun an_unlocated_rich_result_blocks_replay_until_the_document_is_cleared() {
        val editor = Editor("original")
        val history = EditorUndoHistory()
        val connection = history.wrap(editor)
        history.beginRichContent(connection)
        editor.content.replace(0, editor.content.length, "unrelated-private-image")
        editor.setSelection(editor.content.length, editor.content.length)
        history.finishRichContent(connection, true)
        connection.setSelection(0, editor.content.length)
        assertTrue(history.selectionContainsRichContent(connection))
        connection.commitText("", 1)
        assertFalse(history.undo(connection))
        connection.commitText("plain", 1)
        assertTrue(history.undo(connection))
        assertEquals("", editor.content.toString())
    }

    @Test fun unreadable_rich_selections_are_blocked_only_after_a_known_rich_operation() {
        val editor = Editor("a")
        val history = EditorUndoHistory()
        val connection = history.wrap(editor)
        editor.readable = false
        assertFalse(history.selectionContainsRichContent(connection))
        editor.readable = true
        history.beginRichContent(connection)
        history.finishRichContent(connection, true)
        editor.readable = false
        assertTrue(history.selectionContainsRichContent(connection))
    }

    @Test fun step_and_character_limits_evict_only_the_oldest_entries() {
        val history = TextUndoHistory(maxEntries = 2, maxCharacters = 12)
        fun state(text: String) = EditorTextSnapshot(text, text.length, text.length)
        history.record(state("a"), state("ab"))
        history.record(state("ab"), state("abc"))
        history.record(state("abc"), state("abcd"))
        assertEquals("abc", history.peek(state("abcd"))!!.before.text.toString())
        history.pop()
        assertEquals("ab", history.peek(state("abc"))!!.before.text.toString())
        history.pop()
        assertFalse(history.hasUndo)
        history.record(state("abcdefghijk"), state("abcdefghijkl"))
        assertFalse(history.hasUndo)
    }
}
