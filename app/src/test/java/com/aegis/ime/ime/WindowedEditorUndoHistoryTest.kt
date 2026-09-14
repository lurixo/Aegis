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

import android.text.InputFilter
import android.text.Selection
import android.text.SpannedString
import android.text.method.TextKeyListener
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.SurroundingText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WindowedEditorUndoHistoryTest {
    private class Editor(initial: String, val offsets: Boolean = true) : BaseInputConnection(View(RuntimeEnvironment.getApplication()), true) {
        val content get() = editable!!
        var extractions = 0
        var reads = 0
        var writes = 0
        var largestRequest = 0
        var largestResponse = 0
        var largestCommit = 0
        var deferred = false
        var acknowledgeOnRead = false
        var queueCapacity = Int.MAX_VALUE
        var responseCapacity = Int.MAX_VALUE
        var rejectedWrites = 0
        var rejectSelection = false
        var rejectWriteAt = Int.MAX_VALUE
        var dropWriteAt = Int.MAX_VALUE
        var stallWriteAt = Int.MAX_VALUE
        private val queued = ArrayDeque<() -> Boolean>()
        init { content.append(initial); Selection.setSelection(content, initial.length) }
        fun selection() = Selection.getSelectionStart(content) to Selection.getSelectionEnd(content)
        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? { extractions++; return null }
        override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): SurroundingText? {
            if (acknowledgeOnRead) flush()
            reads++
            largestRequest = maxOf(largestRequest, beforeLength, afterLength)
            val (start, end) = selection()
            val from = maxOf(0, minOf(start, end) - beforeLength)
            val through = minOf(content.length, maxOf(start, end) + afterLength)
            largestResponse = maxOf(largestResponse, through - from)
            if (through - from > responseCapacity) return null
            return SurroundingText(SpannedString(content.subSequence(from, through)), start - from, end - from, if (offsets) from else -1)
        }
        override fun setSelection(start: Int, end: Int): Boolean = !rejectSelection && super.setSelection(start, end)
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            writes++
            largestCommit = maxOf(largestCommit, text?.length ?: 0)
            if (writes == rejectWriteAt) { rejectedWrites++; return false }
            if (writes == dropWriteAt) return true
            if (writes == stallWriteAt) acknowledgeOnRead = false
            if (!deferred) return super.commitText(text, newCursorPosition)
            if (queued.size >= queueCapacity) { rejectedWrites++; return false }
            queued.addLast { super.commitText(text, newCursorPosition) }
            if (acknowledgeOnRead) android.os.Handler(Looper.getMainLooper()).post { if (acknowledgeOnRead) flush() }
            return true
        }
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            writes++
            if (!deferred) return super.deleteSurroundingText(beforeLength, afterLength)
            queued.addLast { super.deleteSurroundingText(beforeLength, afterLength) }
            return true
        }
        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN) {
                writes++
                TextKeyListener.getInstance().onKeyDown(View(RuntimeEnvironment.getApplication()), content, event.keyCode, event)
            }
            return true
        }
        override fun performContextMenuAction(id: Int): Boolean = when (id) {
            android.R.id.cut -> super.commitText("", 1)
            android.R.id.paste -> super.commitText("pasted", 1)
            else -> false
        }
        fun flush() { while (queued.isNotEmpty()) queued.removeFirst().invoke() }
    }

    private val text = "line one\r\n第二行 A🙂B\r\n".repeat(10_000)

    @Test fun local_edits_in_large_documents_undo_at_start_middle_and_end_without_full_extraction() {
        for (position in listOf(0, text.length / 2, text.length)) {
            val editor = Editor(text)
            editor.setSelection(position, position)
            val history = EditorUndoHistory()
            val connection = history.wrap(editor)
            connection.commitText("abc\t", 1)
            connection.deleteSurroundingText(1, 0)
            assertTrue(history.canUndo(connection))
            assertTrue(history.undo(connection))
            assertTrue(history.undo(connection))
            assertEquals(text, editor.content.toString())
            assertEquals(position to position, editor.selection())
            assertEquals(0, editor.extractions)
            assertTrue(editor.largestRequest <= 65_536)
        }
    }

    @Test fun forward_delete_and_reversed_selection_restore_original_text_and_selection() {
        val editor = Editor(text)
        val history = EditorUndoHistory()
        val connection = history.wrap(editor)
        editor.setSelection(100_010, 100_003)
        connection.commitText("XY", 1)
        val replaced = editor.content.toString()
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_FORWARD_DEL))
        assertTrue(history.undo(connection))
        assertEquals(replaced, editor.content.toString())
        assertTrue(history.undo(connection))
        assertEquals(text, editor.content.toString())
        assertEquals(100_010 to 100_003, editor.selection())
    }

    @Test fun small_cut_paste_and_navigation_keep_ordered_undo_without_host_undo() {
        val editor = Editor(text)
        val history = EditorUndoHistory()
        val connection = history.wrap(editor)
        editor.setSelection(100_000, 100_005)
        connection.performContextMenuAction(android.R.id.cut)
        val cut = editor.content.toString()
        connection.performContextMenuAction(android.R.id.paste)
        connection.setSelection(0, 0)
        assertTrue(history.canUndo(connection))
        assertTrue(history.undo(connection))
        assertEquals(cut, editor.content.toString())
        assertTrue(history.undo(connection))
        assertEquals(text, editor.content.toString())
    }

    @Test fun default_negative_offsets_use_the_editor_selection_provider() {
        val editor = Editor(text, offsets = false)
        editor.setSelection(100_000, 100_000)
        val history = EditorUndoHistory().apply { selectionProvider = editor::selection }
        val connection = history.wrap(editor)
        connection.commitText("字", 1)
        connection.setSelection(0, 0)
        assertTrue(history.canUndo(connection))
        assertTrue(history.undo(connection))
        assertEquals(text, editor.content.toString())
        assertEquals(100_000 to 100_000, editor.selection())
    }

    @Test fun delayed_edits_and_replay_wait_for_content_acknowledgements_without_resending() {
        val editor = Editor(text, offsets = false)
        editor.setSelection(100_000, 100_000)
        val history = EditorUndoHistory().apply { selectionProvider = editor::selection }
        val connection = history.wrap(editor)
        editor.deferred = true
        connection.commitText("字", 1)
        assertFalse(history.canUndo(connection))
        editor.flush()
        assertTrue(history.canUndo(connection))
        assertFalse(history.undo(connection))
        assertTrue(history.hasPendingUndo)
        val writes = editor.writes
        assertFalse(history.canUndo(connection))
        assertEquals(writes, editor.writes)
        editor.flush()
        assertFalse(history.canUndo(connection))
        assertFalse(history.hasPendingUndo)
        assertEquals(text, editor.content.toString())
    }

    @Test fun transformed_edits_and_changed_replay_context_do_not_overwrite_external_content() {
        val editor = Editor(text)
        editor.setSelection(100_000, 100_000)
        val history = EditorUndoHistory()
        val connection = history.wrap(editor)
        editor.content.filters = arrayOf(InputFilter.AllCaps())
        connection.commitText("a", 1)
        assertFalse(history.canUndo(connection))
        editor.content.filters = emptyArray()
        connection.commitText("!", 1)
        editor.content.replace(99_999, 100_000, "X")
        val original = editor.content.toString()
        val writes = editor.writes
        assertFalse(history.undo(connection))
        assertEquals(original, editor.content.toString())
        assertEquals(writes, editor.writes)
    }

    @Test fun batches_and_composition_remain_one_undo_step() {
        for (composition in listOf(false, true)) {
            val editor = Editor(text)
            editor.setSelection(100_000, 100_000)
            val history = EditorUndoHistory()
            val connection = history.wrap(editor)
            if (composition) {
                connection.setComposingText("n", 1)
                connection.setComposingText("ni", 1)
                connection.commitText("你", 1)
            } else {
                connection.beginBatchEdit()
                connection.commitText("（", 1)
                connection.commitText("）", 0)
                connection.endBatchEdit()
            }
            assertTrue(history.canUndo(connection))
            assertTrue(history.undo(connection))
            assertEquals(text, editor.content.toString())
            assertFalse(history.canUndo(connection))
        }
    }

    @Test fun delayed_selection_metadata_does_not_turn_a_paste_into_a_window_replacement() {
        val editor = Editor(text, offsets = false)
        editor.setSelection(100_000, 100_000)
        var selection = editor.selection()
        val history = EditorUndoHistory().apply { selectionProvider = { selection } }
        val connection = history.wrap(editor)
        connection.performContextMenuAction(android.R.id.paste)
        assertFalse(history.canUndo(connection))
        selection = editor.selection()
        assertTrue(history.canUndo(connection))
        assertFalse(history.undo(connection))
        selection = editor.selection()
        history.canUndo(connection)
        selection = editor.selection()
        history.canUndo(connection)
        selection = editor.selection()
        history.canUndo(connection)
        assertFalse(history.hasPendingUndo)
        assertEquals(text, editor.content.toString())
    }

    @Test fun unacknowledged_undo_times_out_without_repeating_the_edit() {
        val editor = Editor(text)
        editor.setSelection(100_000, 100_000)
        val history = EditorUndoHistory()
        val connection = history.wrap(editor)
        connection.commitText("!", 1)
        editor.deferred = true
        assertFalse(history.undo(connection))
        assertTrue(history.hasPendingUndo)
        val writes = editor.writes
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertFalse(history.hasPendingUndo)
        assertTrue(history.hasUndo)
        assertEquals(writes, editor.writes)
    }

    @Test fun captured_long_cut_and_following_paste_remain_separate_undo_steps() {
        val original = System.getenv("AEGIS_LARGE_EDITOR_SAMPLE")?.let { File(it).readText() }
            ?: "超大选区 abc🙂\r\n".repeat(120_000)
        assertTrue(original.length > 1_048_576)
        for (whole in listOf(false, true)) {
            val editor = Editor(original)
            val start = if (whole) 0 else 127
            val end = if (whole) original.length else original.length - 193
            editor.setSelection(end, start)
            val removed = original.substring(start, end)
            val history = WindowedEditorUndoHistory().apply { selectionProvider = editor::selection }
            assertTrue(history.trackDeletion(editor, start, removed, end, start) { editor.commitText("", 1) })
            val cut = original.substring(0, start) + original.substring(end)
            assertEquals(cut, editor.content.toString())
            assertTrue(history.canUndo(editor))
            assertTrue(history.track(editor, WindowEdit.Commit("pasted", 1)) { editor.commitText("pasted", 1) })
            assertTrue(history.undo(editor))
            assertEquals(cut, editor.content.toString())
            assertFalse(history.undo(editor))
            assertTrue(history.hasPendingUndo)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
            assertFalse(history.hasPendingUndo)
            assertFalse(history.hasUndo)
            assertEquals(original, editor.content.toString())
            assertEquals(end to start, editor.selection())
            assertEquals(0, editor.extractions)
            assertTrue(editor.largestRequest <= 65_536)
            assertTrue(editor.largestResponse <= 131_072)
            assertTrue(editor.largestCommit <= 65_536)
        }
    }

    @Test fun captured_cut_and_chunked_restore_wait_for_delayed_selection_and_content_acknowledgements() {
        val original = "前缀\r\n🙂 long selection ".repeat(10_000)
        val editor = Editor(original, offsets = false)
        editor.setSelection(original.length, 0)
        var reported = editor.selection()
        var completed: Boolean? = null
        val history = WindowedEditorUndoHistory().apply {
            selectionProvider = { reported }
            onUndoCompleted = { completed = it }
        }
        editor.deferred = true
        assertTrue(history.trackDeletion(editor, 0, original, original.length, 0) { editor.commitText("", 1) })
        assertFalse(history.canUndo(editor))
        editor.flush()
        assertFalse(history.canUndo(editor))
        reported = editor.selection()
        assertTrue(history.canUndo(editor))
        assertFalse(history.undo(editor))
        repeat(20) {
            if (history.hasPendingUndo) {
                val writes = editor.writes
                assertFalse(history.canUndo(editor))
                assertEquals(writes, editor.writes)
                editor.flush()
                reported = editor.selection()
                history.canUndo(editor)
            }
        }
        assertFalse(history.hasPendingUndo)
        assertEquals(true, completed)
        assertEquals(original, editor.content.toString())
        assertEquals(original.length to 0, editor.selection())
        assertTrue(editor.largestCommit <= 65_536)
        assertTrue(editor.largestResponse <= 131_072)
    }

    @Test fun chunked_restore_does_not_resend_or_continue_after_an_unacknowledged_chunk() {
        val original = "abcdef🙂".repeat(30_000)
        val editor = Editor(original)
        editor.setSelection(0, original.length)
        val history = WindowedEditorUndoHistory().apply { selectionProvider = editor::selection }
        assertTrue(history.trackDeletion(editor, 0, original, 0, original.length) { editor.commitText("", 1) })
        editor.deferred = true
        assertFalse(history.undo(editor))
        val writes = editor.writes
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertFalse(history.hasPendingUndo)
        assertTrue(history.hasUndo)
        assertEquals(writes, editor.writes)
        assertEquals("", editor.content.toString())
    }

    @Test fun captured_whole_cut_and_pasting_the_same_large_text_wait_for_a_single_pending_write() {
        val original = System.getenv("AEGIS_LARGE_EDITOR_SAMPLE")?.let { File(it).readText() }
            ?: "完整剪切粘贴 abc🙂\r\n".repeat(100_000)
        for (offsets in listOf(true, false)) {
            val editor = Editor(original, offsets)
            editor.setSelection(0, original.length)
            val inserted = mutableListOf<Boolean>()
            val undone = mutableListOf<Boolean>()
            val history = WindowedEditorUndoHistory().apply {
                selectionProvider = editor::selection
                onInsertionCompleted = { inserted.add(it) }
                onUndoCompleted = { undone.add(it) }
            }
            assertTrue(history.trackDeletion(editor, 0, original, 0, original.length) { editor.commitText("", 1) })
            assertEquals("", editor.content.toString())
            editor.deferred = true
            editor.queueCapacity = 1
            editor.acknowledgeOnRead = true
            assertTrue(history.insert(editor, original))
            assertTrue(history.hasPendingInsertion)
            assertFalse(history.hasPendingUndo)
            assertFalse(history.hasUndo)
            val writes = editor.writes
            assertFalse(history.insert(editor, "unrelated"))
            assertEquals(writes, editor.writes)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
            assertFalse(history.hasPendingInsertion)
            assertEquals(listOf(true), inserted)
            assertEquals(emptyList<Boolean>(), undone)
            assertEquals(original, editor.content.toString())
            assertEquals(original.length to original.length, editor.selection())
            assertTrue(history.canUndo(editor))
            assertFalse(history.undo(editor))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
            assertFalse(history.hasPendingUndo)
            assertEquals("", editor.content.toString())
            assertTrue(history.canUndo(editor))
            assertFalse(history.undo(editor))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
            assertFalse(history.hasPendingUndo)
            assertFalse(history.canUndo(editor))
            assertEquals(listOf(true), inserted)
            assertEquals(listOf(true, true), undone)
            assertEquals(original, editor.content.toString())
            assertEquals(0 to original.length, editor.selection())
            assertEquals(0, editor.rejectedWrites)
            assertTrue(editor.largestRequest <= 65_536)
            assertTrue(editor.largestResponse <= 131_072)
            assertTrue(editor.largestCommit <= 65_536)
        }
    }

    @Test fun large_insertion_undo_verifies_the_middle_before_deleting_any_content() {
        val inserted = "0123456789🙂\r\n".repeat(20_000)
        val editor = Editor("left|right")
        editor.setSelection(5, 5)
        val history = WindowedEditorUndoHistory().apply { selectionProvider = editor::selection }
        assertTrue(history.insert(editor, inserted))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        assertFalse(history.hasPendingInsertion)
        editor.content.replace(5 + inserted.length / 2, 6 + inserted.length / 2, "X")
        val changed = editor.content.toString()
        val selection = editor.selection()
        val writes = editor.writes
        assertFalse(history.undo(editor))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        assertFalse(history.hasPendingUndo)
        assertFalse(history.canUndo(editor))
        assertEquals(changed, editor.content.toString())
        assertEquals(selection, editor.selection())
        assertEquals(writes, editor.writes)
        assertTrue(editor.largestResponse <= 131_072)
    }

    @Test fun unacknowledged_insertion_times_out_without_resending_or_notifying_undo() {
        val editor = Editor("original").apply { deferred = true; queueCapacity = 1 }
        val inserted = mutableListOf<Boolean>()
        val undone = mutableListOf<Boolean>()
        val history = WindowedEditorUndoHistory().apply {
            selectionProvider = editor::selection
            onInsertionCompleted = { inserted.add(it) }
            onUndoCompleted = { undone.add(it) }
        }
        assertTrue(history.insert(editor, "large content".repeat(20_000)))
        assertTrue(history.hasPendingInsertion)
        val writes = editor.writes
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertFalse(history.hasPendingInsertion)
        assertFalse(history.hasPendingUndo)
        assertEquals(listOf(false), inserted)
        assertEquals(emptyList<Boolean>(), undone)
        assertEquals(writes, editor.writes)
        assertEquals(0, editor.rejectedWrites)
        assertEquals("original", editor.content.toString())
    }

    @Test fun rejected_insertion_does_not_retry_the_write() {
        val editor = Editor("original").apply { deferred = true; queueCapacity = 0 }
        val history = WindowedEditorUndoHistory().apply { selectionProvider = editor::selection }
        assertFalse(history.insert(editor, "large content".repeat(20_000)))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertFalse(history.hasPendingInsertion)
        assertFalse(history.hasPendingUndo)
        assertEquals(1, editor.writes)
        assertEquals(1, editor.rejectedWrites)
        assertEquals("original", editor.content.toString())
    }

    @Test fun insertion_keeps_its_accepted_result_when_state_refresh_completes_the_last_chunk() {
        val editor = Editor("prefix")
        val completed = mutableListOf<Boolean>()
        val history = WindowedEditorUndoHistory().apply {
            selectionProvider = editor::selection
            onInsertionCompleted = { completed.add(it) }
        }
        history.onChange = { history.canUndo(editor) }
        val inserted = "x".repeat(50_000)
        assertTrue(history.insert(editor, inserted))
        assertFalse(history.hasPendingInsertion)
        assertEquals(listOf(true), completed)
        assertEquals("prefix" + inserted, editor.content.toString())
    }

    @Test fun whole_cut_undo_rejects_an_unrecorded_document_instead_of_duplicating_it() {
        val original = "original text".repeat(10_000)
        val editor = Editor(original)
        editor.setSelection(0, original.length)
        val history = WindowedEditorUndoHistory().apply { selectionProvider = editor::selection }
        assertTrue(history.trackDeletion(editor, 0, original, 0, original.length) { editor.commitText("", 1) })
        editor.commitText("external text", 1)
        val writes = editor.writes
        assertFalse(history.undo(editor))
        assertFalse(history.hasPendingUndo)
        assertEquals("external text", editor.content.toString())
        assertEquals(writes, editor.writes)
    }

    @Test fun long_selected_edits_preserve_each_ordered_pair_and_both_selections_when_undone() {
        val operations = listOf("delete", "forward_delete", "cut", "tab", "paste_short", "paste_long")
        val pasted = "large paste🙂\r\n".repeat(6_000)
        for (offsets in listOf(true, false)) for (first in operations) for (second in operations) {
            val editor = Editor(text, offsets).apply { deferred = true; acknowledgeOnRead = true; queueCapacity = 1 }
            val history = WindowedEditorUndoHistory().apply { selectionProvider = editor::selection }
            val previous = mutableListOf<Pair<String, Pair<Int, Int>>>()
            for ((index, operation) in listOf(first, second).withIndex()) {
                val before = editor.content.toString()
                val start = 17_000
                val end = start + 70_000
                val selected = if (index == 0) end to start else start to end
                editor.setSelection(selected.first, selected.second)
                previous.add(before to selected)
                val inserted = when (operation) {
                    "tab" -> "\t"
                    "paste_short" -> "pasted"
                    "paste_long" -> pasted
                    else -> ""
                }
                val removed = before.substring(start, end)
                val accepted = if (operation == "cut") history.trackDeletion(editor, start, removed, selected.first, selected.second) {
                    editor.commitText("", 1)
                } else history.replace(editor, start, removed, inserted, selected.first, selected.second)
                val context = "offsets=$offsets $first/$second step=$index"
                assertTrue("$context accepted", accepted)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
                assertFalse("$context completed", history.hasPendingInsertion)
                assertTrue("$context enables Undo", history.canUndo(editor))
                assertEquals("$context exact content", before.substring(0, start) + inserted + before.substring(end), editor.content.toString())
                assertEquals("$context caret", start + inserted.length to start + inserted.length, editor.selection())
            }
            for ((before, selection) in previous.asReversed()) {
                history.undo(editor)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
                assertFalse(history.hasPendingUndo)
                assertEquals("offsets=$offsets $first/$second restores content", before, editor.content.toString())
                assertEquals("offsets=$offsets $first/$second restores selection", selection, editor.selection())
            }
            assertFalse(history.canUndo(editor))
            assertEquals(0, editor.rejectedWrites)
            assertTrue(editor.largestRequest <= 65_536)
            assertTrue(editor.largestResponse <= 131_072)
            assertTrue(editor.largestCommit <= 65_536)
        }
    }

    @Test fun selected_restore_includes_selection_length_in_the_read_budget() {
        for (offsets in listOf(true, false)) for (size in listOf(16_384, 49_152, 65_536, 65_537)) {
            val editor = Editor(text, offsets)
            val start = 50_000
            val end = start + size
            editor.setSelection(end, start)
            val history = WindowedEditorUndoHistory().apply { selectionProvider = editor::selection }
            val completed = mutableListOf<Boolean>()
            history.onUndoCompleted = { completed.add(it) }
            assertTrue(history.trackDeletion(editor, start, text.substring(start, end), end, start) { editor.commitText("", 1) })
            val synchronous = history.undo(editor)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
            assertTrue("offsets=$offsets size=$size completed successfully", synchronous || completed == listOf(true))
            assertFalse(history.hasPendingUndo)
            assertEquals(text, editor.content.toString())
            assertEquals(end to start, editor.selection())
            assertTrue("offsets=$offsets size=$size bounded response", editor.largestResponse <= 131_072)
        }
    }

    @Test fun repeated_whole_document_cut_paste_and_delete_retain_shared_text_for_all_undo_steps() {
        val original = System.getenv("AEGIS_LARGE_EDITOR_SAMPLE")?.let { File(it).readText() }
            ?: "全篇历史 abc🙂\r\n".repeat(120_000)
        val editor = Editor(original)
        val history = WindowedEditorUndoHistory().apply { selectionProvider = editor::selection }
        val previous = mutableListOf<Pair<String, Pair<Int, Int>>>()
        repeat(3) {
            editor.setSelection(original.length, 0)
            previous.add(editor.content.toString() to editor.selection())
            assertTrue(history.trackDeletion(editor, 0, original, original.length, 0) { editor.commitText("", 1) })
            assertEquals("", editor.content.toString())
            previous.add("" to editor.selection())
            assertTrue(history.insert(editor, original))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
            assertEquals(original, editor.content.toString())
        }
        for ((index, state) in previous.asReversed().withIndex()) {
            assertTrue("undo ${index + 1} remains available", history.canUndo(editor))
            history.undo(editor)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
            assertEquals("undo ${index + 1} restores content", state.first, editor.content.toString())
            assertEquals("undo ${index + 1} restores selection", state.second, editor.selection())
        }
        assertFalse(history.canUndo(editor))
    }

    @Test fun whole_document_delete_tab_and_paste_replacements_restore_every_prior_state() {
        val original = System.getenv("AEGIS_LARGE_EDITOR_SAMPLE")?.let { File(it).readText() }
            ?: "全篇替换 abc🙂\r\n".repeat(120_000)
        for (offsets in listOf(true, false)) {
            val editor = Editor(original, offsets).apply { deferred = true; acknowledgeOnRead = true; queueCapacity = 1 }
            val history = WindowedEditorUndoHistory().apply { selectionProvider = editor::selection }
            val previous = mutableListOf<Pair<String, Pair<Int, Int>>>()
            val completed = mutableListOf<Boolean>()
            history.onInsertionCompleted = { completed.add(it) }
            for (inserted in listOf("", original, "\t", original, "", original)) {
                val before = editor.content.toString()
                editor.setSelection(before.length, 0)
                previous.add(before to editor.selection())
                assertTrue(history.replace(editor, 0, before, inserted, before.length, 0))
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
                assertFalse(history.hasPendingInsertion)
                assertTrue(history.canUndo(editor))
                assertEquals(inserted, editor.content.toString())
                assertEquals(inserted.length to inserted.length, editor.selection())
            }
            assertEquals(List(6) { true }, completed)
            for ((index, state) in previous.asReversed().withIndex()) {
                assertTrue("offsets=$offsets undo ${index + 1}", history.canUndo(editor))
                history.undo(editor)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
                assertFalse(history.hasPendingUndo)
                assertEquals(state.first, editor.content.toString())
                assertEquals(state.second, editor.selection())
            }
            assertFalse(history.canUndo(editor))
            assertEquals(0, editor.rejectedWrites)
            assertTrue(editor.largestResponse <= 131_072)
            assertTrue(editor.largestCommit <= 65_536)
        }
    }

    @Test fun captured_replacement_rejects_a_changed_middle_before_any_write() {
        val editor = Editor(text)
        val start = 5_000
        val end = start + 140_000
        editor.setSelection(end, start)
        val removed = text.substring(start, end)
        editor.content.replace(start + 80_000, start + 80_001, "X")
        val changed = editor.content.toString()
        val history = WindowedEditorUndoHistory().apply { selectionProvider = editor::selection }
        val completed = mutableListOf<Boolean>()
        history.onInsertionCompleted = { completed.add(it) }
        assertTrue(history.replace(editor, start, removed, "\t", end, start))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        assertEquals(listOf(false), completed)
        assertFalse(history.hasPendingInsertion)
        assertEquals(0, editor.writes)
        assertEquals(changed, editor.content.toString())
        assertEquals(end to start, editor.selection())
    }

    @Test fun tab_then_delete_preserves_both_undo_steps_with_a_capped_surrounding_response() {
        val original = "abc def\nghi jkl\nmnop\n".repeat(7_000)
        for (offsets in listOf(true, false)) {
            val editor = Editor(original, offsets).apply { responseCapacity = 65_536 }
            editor.setSelection(0, 0)
            val history = WindowedEditorUndoHistory().apply { selectionProvider = editor::selection }
            assertTrue(history.track(editor, WindowEdit.Commit("\t", 1)) { editor.commitText("\t", 1) })
            val deletion = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)
            assertTrue(history.track(editor, WindowEdit.Key(deletion)) { editor.sendKeyEvent(deletion) })
            assertEquals(original, editor.content.toString())
            assertTrue(history.undo(editor))
            assertEquals("\t" + original, editor.content.toString())
            assertTrue(history.canUndo(editor))
            assertTrue(history.undo(editor))
            assertEquals(original, editor.content.toString())
            assertFalse(history.canUndo(editor))
            assertTrue(editor.largestResponse <= 65_536)
        }
    }

    @Test fun whole_document_deletion_and_undo_complete_with_a_capped_surrounding_response() {
        val original = "abc def\nghi jkl\nmnop\n".repeat(7_000)
        for (offsets in listOf(true, false)) {
            val editor = Editor(original, offsets).apply { responseCapacity = 65_536 }
            editor.setSelection(original.length, 0)
            val history = WindowedEditorUndoHistory().apply { selectionProvider = editor::selection }
            val completed = mutableListOf<Boolean>()
            history.onInsertionCompleted = { completed.add(it) }
            assertTrue(history.replace(editor, 0, original, "", original.length, 0))
            assertTrue(history.hasPendingInsertion)
            assertFalse(history.hasUndo)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
            assertEquals(listOf(true), completed)
            assertEquals("", editor.content.toString())
            assertTrue(history.canUndo(editor))
            assertFalse(history.undo(editor))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
            assertFalse(history.hasPendingUndo)
            assertEquals(original, editor.content.toString())
            assertEquals(original.length to 0, editor.selection())
            assertFalse(history.canUndo(editor))
            assertTrue(editor.largestResponse <= 65_536)
        }
    }

    @Test fun keyboard_edits_wait_until_large_deletion_undo_finishes() {
        val original = "原文 restore🙂\r\n".repeat(20_000)
        for (action in listOf("commit", "compose", "delete", "batch")) {
            val editor = Editor(original).apply { responseCapacity = 65_536 }
            editor.setSelection(0, original.length)
            val history = EditorUndoHistory().apply { selectionProvider = editor::selection }
            val connection = history.wrap(editor)
            assertTrue(action, history.replaceCapturedSelection(connection, 0, original, "", 0, original.length))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
            assertEquals(action, "", editor.content.toString())
            assertTrue(action, history.hasUndo)
            assertFalse(action, history.undo(connection))
            assertTrue(action, history.hasPendingUndo)
            val writes = editor.writes
            val accepted = when (action) {
                "commit" -> connection.commitText("x", 1)
                "compose" -> connection.setComposingText("x", 1)
                "delete" -> connection.deleteSurroundingText(1, 0)
                else -> {
                    connection.beginBatchEdit()
                    try { connection.commitText("x", 1) } finally { connection.endBatchEdit() }
                }
            }
            assertFalse(action, accepted)
            assertEquals(action, writes, editor.writes)
            assertTrue(action, history.hasPendingUndo)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
            assertFalse(action, history.hasPendingUndo)
            assertEquals(action, original, editor.content.toString())
            assertEquals(action, 0 to original.length, editor.selection())
            assertFalse(action, history.canUndo(connection))
        }
    }

    @Test fun interrupted_replacement_and_undo_keep_the_original_text_for_verified_recovery() {
        val original = System.getenv("AEGIS_LARGE_EDITOR_SAMPLE")?.let { File(it).readText() }
            ?: "失败恢复 abc🙂\r\n".repeat(30_000)
        fun digest(value: String) = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).toList()
        val originalHash = digest(original)
        val replacement = "新正文 replacement🙂\r\n".repeat(8_000)
        for (offsets in listOf(true, false)) for (forward in listOf(true, false)) for (failure in listOf("reject", "drop", "late")) {
            val label = "offsets=$offsets forward=$forward failure=$failure"
            val editor = Editor(original, offsets).apply { responseCapacity = 65_536 }
            editor.setSelection(original.length, 0)
            val history = WindowedEditorUndoHistory().apply { selectionProvider = editor::selection }
            val completed = mutableListOf<Boolean>()
            if (forward) history.onInsertionCompleted = { completed.add(it) } else {
                history.onUndoCompleted = { completed.add(it) }
                assertTrue(history.trackDeletion(editor, 0, original, original.length, 0) { editor.commitText("", 1) })
            }
            editor.deferred = true
            editor.acknowledgeOnRead = true
            editor.queueCapacity = 1
            val failedWrite = editor.writes + 2
            when (failure) {
                "reject" -> editor.rejectWriteAt = failedWrite
                "drop" -> editor.dropWriteAt = failedWrite
                else -> editor.stallWriteAt = failedWrite
            }
            if (forward) assertTrue(label, history.replace(editor, 0, original, replacement, original.length, 0))
            else assertFalse(label, history.undo(editor))
            val deferred = history.hasPendingInsertion || history.hasPendingUndo
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
            assertEquals(label, if (deferred) listOf(false) else emptyList<Boolean>(), completed)
            assertFalse(label, history.hasPendingInsertion)
            assertFalse(label, history.hasPendingUndo)
            assertTrue(label, history.hasUndo)
            val confirmed = editor.content.toString()
            assertTrue(label, confirmed.isNotEmpty())
            assertTrue(label, (if (forward) replacement else original).startsWith(confirmed))
            assertTrue(label, confirmed.length < (if (forward) replacement else original).length)
            assertEquals(label, failedWrite, editor.writes)
            editor.rejectWriteAt = Int.MAX_VALUE
            editor.dropWriteAt = Int.MAX_VALUE
            editor.stallWriteAt = Int.MAX_VALUE
            editor.acknowledgeOnRead = true
            assertTrue(label, history.canUndo(editor))
            history.undo(editor)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
            assertFalse(label, history.hasPendingUndo)
            assertEquals(label, originalHash, digest(editor.content.toString()))
            assertEquals(label, original.length to 0, editor.selection())
            assertFalse(label, history.canUndo(editor))
            assertTrue(label, editor.largestResponse <= 65_536)
            assertTrue(label, editor.largestCommit <= 65_536)
        }
    }

    @Test fun interrupted_replacement_does_not_overwrite_a_changed_middle_and_keeps_recovery() {
        val original = "原文 recovery🙂\r\n".repeat(20_000)
        val inserted = "replacement🙂\r\n".repeat(12_000)
        val editor = Editor(original).apply {
            deferred = true
            acknowledgeOnRead = true
            queueCapacity = 1
            responseCapacity = 65_536
            rejectWriteAt = 4
        }
        editor.setSelection(original.length, 0)
        val history = WindowedEditorUndoHistory().apply { selectionProvider = editor::selection }
        assertTrue(history.replace(editor, 0, original, inserted, original.length, 0))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        assertTrue(history.hasUndo)
        assertEquals(4, editor.writes)
        val confirmed = editor.content.toString()
        assertTrue(confirmed.length > 65_536)
        editor.content.replace(40_000, 40_001, "!")
        val changed = editor.content.toString()
        editor.rejectWriteAt = Int.MAX_VALUE
        assertFalse(history.undo(editor))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        assertEquals(4, editor.writes)
        assertEquals(changed, editor.content.toString())
        assertTrue(history.hasUndo)
        editor.content.replace(40_000, 40_001, confirmed.substring(40_000, 40_001))
        history.undo(editor)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        assertEquals(original, editor.content.toString())
        assertFalse(history.hasUndo)
    }

    @Test fun interrupted_replacement_does_not_mistake_a_repeating_suffix_for_an_acknowledged_chunk() {
        val removed = "old text🙂\r\n".repeat(8_000)
        val suffix = "A".repeat(100_000)
        val original = "L|" + removed + suffix
        for (offsets in listOf(true, false)) for (late in listOf(true, false)) {
            val label = "offsets=$offsets late=$late"
            val editor = Editor(original, offsets).apply {
                deferred = true
                acknowledgeOnRead = true
                queueCapacity = 1
                responseCapacity = 65_536
                if (late) stallWriteAt = 2 else rejectWriteAt = 2
            }
            editor.setSelection(2, 2 + removed.length)
            val history = WindowedEditorUndoHistory().apply { selectionProvider = editor::selection }
            assertTrue(label, history.replace(editor, 2, removed, "A".repeat(100_000), 2, 2 + removed.length))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
            assertEquals(label, 2, editor.writes)
            assertTrue(label, history.hasUndo)
            editor.rejectWriteAt = Int.MAX_VALUE
            editor.stallWriteAt = Int.MAX_VALUE
            editor.acknowledgeOnRead = true
            history.undo(editor)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
            assertEquals(label, original, editor.content.toString())
            assertEquals(label, 2 to 2 + removed.length, editor.selection())
            assertFalse(label, history.hasUndo)
        }
    }

    @Test fun unresolved_late_chunk_after_navigation_preserves_recovery_without_writing() {
        val removed = "old text🙂\r\n".repeat(8_000)
        val original = "L|" + removed + "A".repeat(100_000)
        for (offsets in listOf(true, false)) {
            val editor = Editor(original, offsets).apply {
                deferred = true
                acknowledgeOnRead = true
                queueCapacity = 1
                responseCapacity = 65_536
                stallWriteAt = 2
            }
            editor.setSelection(2, 2 + removed.length)
            val history = WindowedEditorUndoHistory().apply { selectionProvider = editor::selection }
            assertTrue(history.replace(editor, 2, removed, "A".repeat(100_000), 2, 2 + removed.length))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
            assertEquals(2, editor.writes)
            assertTrue(history.hasUndo)
            editor.stallWriteAt = Int.MAX_VALUE
            editor.acknowledgeOnRead = true
            editor.flush()
            val interrupted = editor.content.toString()
            editor.setSelection(0, 0)
            assertFalse(history.undo(editor))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
            assertEquals("offsets=$offsets cannot write an unresolved recovery", 2, editor.writes)
            assertEquals(interrupted, editor.content.toString())
            assertEquals(0 to 0, editor.selection())
            assertTrue(history.hasUndo)
            val acknowledgedEnd = interrupted.length - 100_000
            editor.setSelection(acknowledgedEnd, acknowledgedEnd)
            history.undo(editor)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
            assertEquals(original, editor.content.toString())
            assertEquals(2 to 2 + removed.length, editor.selection())
            assertFalse(history.hasUndo)
        }
    }
}
