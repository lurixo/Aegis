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

package com.aegis.ime

import android.text.Selection
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.SurroundingText
import android.widget.FrameLayout
import com.aegis.ime.ime.EditorSweep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorSweepTailTest {

    private class DeferredEditor(target: View, val ignoresDeletion: Boolean = false) : BaseInputConnection(target, true) {
        private val pending = ArrayDeque<() -> Unit>()
        var deletes = 0
        var absoluteOffsets = true

        override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): SurroundingText {
            val text = requireNotNull(editable)
            val low = minOf(Selection.getSelectionStart(text), Selection.getSelectionEnd(text))
            val high = maxOf(Selection.getSelectionStart(text), Selection.getSelectionEnd(text))
            val from = maxOf(0, low - beforeLength)
            val end = minOf(text.length, high + afterLength)
            return SurroundingText(text.subSequence(from, end), low - from, high - from, if (absoluteOffsets) from else -1)
        }

        fun hold(text: String, caret: Int = text.length) {
            requireNotNull(editable).append(text)
            Selection.setSelection(requireNotNull(editable), caret)
        }

        override fun beginBatchEdit(): Boolean = true

        override fun endBatchEdit(): Boolean {
            while (pending.isNotEmpty()) pending.removeFirst().invoke()
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            deletes++
            if (!ignoresDeletion) pending.addLast { super.deleteSurroundingText(beforeLength, afterLength) }
            return true
        }
    }

    @Test fun a_short_sentence_is_captured_once_when_deletions_wait_for_the_batch_to_end() {
        val sentence = "怎么就改不对呢？"
        val editor = DeferredEditor(FrameLayout(RuntimeEnvironment.getApplication())).apply { hold(sentence) }

        val swept = EditorSweep.clearCapturing(editor)

        assertEquals(sentence, swept.toString())
        assertEquals("", editor.editable.toString())
        assertEquals(1, editor.deletes)
    }

    @Test fun a_successful_return_without_a_deletion_does_not_create_a_duplicate_backup() {
        val sentence = "怎么就改不对呢？"
        val editor = DeferredEditor(FrameLayout(RuntimeEnvironment.getApplication()), ignoresDeletion = true).apply { hold(sentence) }

        val swept = EditorSweep.clearCapturing(editor)

        assertEquals("", swept.toString())
        assertEquals(sentence, editor.editable.toString())
        assertEquals(1, editor.deletes)
    }

    @Test fun repeated_long_text_is_not_mistaken_for_an_unchanged_read_on_either_side_of_the_caret() {
        val text = "怎么就改不对呢？".repeat(EditorSweep.CHUNK / 8 * 3)
        for (caret in listOf(0, text.length / 2, text.length)) {
            val editor = DeferredEditor(FrameLayout(RuntimeEnvironment.getApplication())).apply { hold(text, caret) }

            val swept = EditorSweep.clearCapturing(editor)

            assertEquals(text, swept.toString())
            assertEquals("", editor.editable.toString())
            assertTrue(editor.deletes < 10)
        }
    }

    @Test fun an_editor_without_absolute_positions_keeps_unverifiable_repeated_text() {
        val text = "怎么就改不对呢？".repeat(EditorSweep.CHUNK / 8 * 2)
        val editor = DeferredEditor(FrameLayout(RuntimeEnvironment.getApplication())).apply {
            hold(text)
            absoluteOffsets = false
        }

        val swept = EditorSweep.clearCapturing(editor)

        assertEquals("", swept.toString())
        assertEquals(text, editor.editable.toString())
        assertEquals(0, editor.deletes)
    }

    private class SlowEditor(target: View) : BaseInputConnection(target, true) {
        val queued = ArrayDeque<() -> Unit>()
        var selections = 0
        var deletions = 0
        var afterLimit = 2
        val content get() = requireNotNull(editable)

        fun hold(text: String, caret: Int) {
            content.append(text)
            Selection.setSelection(content, caret)
        }
        fun flush() { while (queued.isNotEmpty()) queued.removeFirst()() }
        override fun getTextAfterCursor(length: Int, flags: Int): CharSequence? =
            super.getTextAfterCursor(minOf(length, afterLimit), flags)
        override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): SurroundingText {
            val low = Selection.getSelectionStart(content)
            val high = Selection.getSelectionEnd(content)
            return SurroundingText(content.subSequence(low, high), 0, high - low, low)
        }
        override fun setSelection(start: Int, end: Int): Boolean {
            selections++
            queued.addLast { super.setSelection(start, end) }
            return true
        }
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            deletions++
            queued.addLast { super.deleteSurroundingText(beforeLength, afterLength) }
            return true
        }
    }

    @Test fun small_windows_and_late_selections_preserve_every_character_at_every_caret_position() {
        val text = "ABC\r\n".repeat(300) + "\u0000\r\n"
        for (caret in listOf(0, text.length / 2, text.length)) {
            val editor = SlowEditor(FrameLayout(RuntimeEnvironment.getApplication())).apply { hold(text, caret) }
            val capture = EditorSweep.Capture(editor)
            var steps = 0
            while (capture.advance() != EditorSweep.Progress.DONE) {
                assertTrue("capture must keep making progress", ++steps < 10_000)
                editor.flush()
            }
            assertEquals("", editor.content.toString())
            assertEquals(text, capture.text().toString())
        }
    }

    @Test fun a_pending_move_or_delete_is_dispatched_once_and_only_backed_up_after_confirmation() {
        val editor = SlowEditor(FrameLayout(RuntimeEnvironment.getApplication())).apply { hold("AB", 0) }
        val capture = EditorSweep.Capture(editor)
        repeat(4) { capture.advance() }
        repeat(3) { assertEquals(EditorSweep.Progress.WAITING, capture.advance()) }
        assertEquals(1, editor.selections)
        assertEquals(0, editor.deletions)
        assertEquals("", capture.text().toString())
        editor.flush()
        capture.advance()
        capture.advance()
        repeat(3) { assertEquals(EditorSweep.Progress.WAITING, capture.advance()) }
        assertEquals(1, editor.deletions)
        assertEquals("", capture.text().toString())
        editor.flush()
        capture.advance()
        assertEquals("AB", capture.text().toString())
        assertEquals(EditorSweep.Progress.DONE, capture.advance())
        assertEquals("", editor.content.toString())
    }

    @Test fun cancelling_after_a_temporary_move_restores_the_insertion_position() {
        val editor = SlowEditor(FrameLayout(RuntimeEnvironment.getApplication())).apply { hold("left-right", 4) }
        val capture = EditorSweep.Capture(editor)
        while (editor.selections == 0) {
            capture.advance()
            editor.flush()
        }
        capture.cancel()
        editor.flush()
        editor.commitText(capture.text(), 1)
        assertEquals("left-right", editor.content.toString())
    }

    private class Placeholder(target: View, val opens: Int) : BaseInputConnection(target, true) {
        private var emptied = false
        private var offered = false

        override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): SurroundingText {
            val text = requireNotNull(editable)
            val low = minOf(Selection.getSelectionStart(text), Selection.getSelectionEnd(text))
            val high = maxOf(Selection.getSelectionStart(text), Selection.getSelectionEnd(text))
            val from = maxOf(0, low - beforeLength)
            val end = minOf(text.length, high + afterLength)
            return SurroundingText(text.subSequence(from, end), low - from, high - from, from)
        }

        fun hold(text: CharSequence) {
            val content = requireNotNull(editable)
            content.replace(0, content.length, text)
            Selection.setSelection(content, 0)
        }

        override fun getTextAfterCursor(length: Int, flags: Int): CharSequence {
            val real = super.getTextAfterCursor(length, flags) ?: ""
            if (real.isNotEmpty()) return real
            if (emptied && !offered) {
                offered = true
                return "\n".repeat(opens)
            }
            return ""
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            val done = super.deleteSurroundingText(beforeLength, afterLength)
            if (requireNotNull(editable).isEmpty()) emptied = true
            return done
        }

        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText =
            ExtractedText().apply {
                val content = requireNotNull(editable)
                startOffset = 0
                text = content.subSequence(0, content.length)
                selectionStart = Selection.getSelectionStart(content)
                selectionEnd = Selection.getSelectionEnd(content)
            }
    }

    private fun editorHolding(text: CharSequence, opens: Int): Placeholder =
        Placeholder(FrameLayout(RuntimeEnvironment.getApplication()), opens).apply { hold(text) }

    @Test fun the_lines_an_editor_opens_while_being_emptied_are_not_kept() {
        val editor = editorHolding("AAA\nBBB", opens = 4)

        val swept = EditorSweep.clearCapturing(editor)

        assertEquals("the snapshot must end where the field ended", "AAA\nBBB", swept.toString())
    }

    @Test fun blank_lines_the_field_really_ended_with_are_kept() {
        val editor = editorHolding("AAA\nBBB\n\n", opens = 4)

        val swept = EditorSweep.clearCapturing(editor)

        assertEquals("what the field really ended with must survive", "AAA\nBBB\n\n", swept.toString())
    }

    @Test fun an_unreachable_field_end_does_not_add_unconfirmed_placeholder_lines() {
        val editor = editorHolding("A".repeat(EditorSweep.CHUNK * 2), opens = 4)

        val swept = EditorSweep.clearCapturing(editor)

        assertEquals(
            "only confirmed deletions belong in the backup",
            "A".repeat(EditorSweep.CHUNK * 2),
            swept.toString(),
        )
    }
}
