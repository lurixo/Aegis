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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import android.text.InputType
import android.text.Selection
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.widget.FrameLayout
import com.aegis.ime.engine.CandidateEngine
import com.aegis.ime.ime.EditAction
import com.aegis.ime.ime.KeyboardController
import java.io.File
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebEditorClearTest {
    private class WebEditor(view: View) : BaseInputConnection(view, true) {
        var document = ""
            private set
        var acceptDelete = true
        var acceptNativePaste = false
        var rejectTextCommits = false
        var rejectedTextCommits = 0
        var exposeNonemptySnapshot = true
        var normalizeInput = false
        var mergeInputs = false
        var nativeUndoCalls = 0
        val documentChanges = ArrayList<String>()
        private val nativeUndo = ArrayDeque<String>()
        val nativeDeletions = ArrayList<String>()
        val nativeDeletionKeys = ArrayList<Int>()
        val nativeSelections = ArrayList<Pair<Int, Int>>()
        val selectionRequests = ArrayList<Pair<Int, Int>>()
        val invalidSelectionRequests = ArrayList<Pair<Int, Int>>()
        val contextMenuRequests = ArrayList<Int>()

        fun hold(text: String, caret: Int = text.length) {
            document = text
            documentChanges.add(text)
            val content = requireNotNull(editable)
            val raw = text.split('\n').joinToString("\n") { it.ifEmpty { "\u200b" } } + "\n"
            content.replace(0, content.length, raw)
            Selection.setSelection(content, rawOffset(caret))
        }

        fun rawText(): String = requireNotNull(editable).toString()

        fun rawOffset(modelOffset: Int): Int {
            require(modelOffset in 0..document.length)
            var modelStart = 0
            var rawStart = 0
            for (line in document.split('\n')) {
                if (modelOffset <= modelStart + line.length) return rawStart + modelOffset - modelStart
                modelStart += line.length + 1
                rawStart += maxOf(line.length, 1) + 1
            }
            error("Caret is outside the document")
        }

        fun caret(): Pair<Int, Int> =
            Selection.getSelectionStart(editable) to Selection.getSelectionEnd(editable)

        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? =
            if (!exposeNonemptySnapshot && document.isNotEmpty()) null else ExtractedText().apply {
                text = rawText()
                startOffset = 0
                partialStartOffset = -1
                partialEndOffset = -1
                selectionStart = caret().first
                selectionEnd = caret().second
            }

        override fun setSelection(start: Int, end: Int): Boolean {
            selectionRequests.add(start to end)
            if (start !in 0..rawText().length || end !in 0..rawText().length) {
                invalidSelectionRequests.add(start to end)
                return false
            }
            return super.setSelection(start, end)
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            if (rejectTextCommits) { rejectedTextCommits++; return false }
            if (normalizeInput) {
                val start = (0..document.length).lastOrNull { rawOffset(it) <= minOf(caret().first, caret().second) } ?: 0
                val end = (0..document.length).lastOrNull { rawOffset(it) <= maxOf(caret().first, caret().second) } ?: 0
                val inserted = text?.toString().orEmpty()
                val next = document.replaceRange(start, end, inserted)
                if (next != document && (!mergeInputs || nativeUndo.isEmpty())) nativeUndo.addLast(document)
                hold(next, start + inserted.length)
                return true
            }
            val accepted = super.commitText(text, newCursorPosition)
            val raw = rawText()
            document = raw.removeSuffix("\n").split('\n').joinToString("\n") { if (it == "\u200b") "" else it }
            documentChanges.add(document)
            return accepted
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action != KeyEvent.ACTION_DOWN) return true
            when {
                event.keyCode == KeyEvent.KEYCODE_A && event.isCtrlPressed -> {
                    Selection.setSelection(requireNotNull(editable), 0, rawOffset(document.length))
                    nativeSelections.add(caret())
                }
                event.keyCode == KeyEvent.KEYCODE_DEL || event.keyCode == KeyEvent.KEYCODE_FORWARD_DEL -> {
                    if (acceptDelete && document.isNotEmpty() && caret() == (0 to rawOffset(document.length))) {
                        nativeDeletions.add(document)
                        nativeDeletionKeys.add(event.keyCode)
                        nativeUndo.addLast(document)
                        hold("")
                    }
                }
                event.keyCode == KeyEvent.KEYCODE_Z && event.isCtrlPressed -> {
                    nativeUndoCalls++
                    nativeUndo.removeLastOrNull()?.let { hold(it, 0) }
                }
                event.keyCode == KeyEvent.KEYCODE_MOVE_END && event.isCtrlPressed ->
                    Selection.setSelection(requireNotNull(editable), rawOffset(document.length))
            }
            return true
        }

        override fun performContextMenuAction(id: Int): Boolean {
            contextMenuRequests.add(id)
            if (id == android.R.id.paste && acceptNativePaste) {
                val clipboard = RuntimeEnvironment.getApplication().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val inserted = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: return false
                val start = (0..document.length).lastOrNull { rawOffset(it) <= minOf(caret().first, caret().second) } ?: 0
                val end = (0..document.length).lastOrNull { rawOffset(it) <= maxOf(caret().first, caret().second) } ?: 0
                nativeUndo.addLast(document)
                hold(document.replaceRange(start, end, inserted), start + inserted.length)
                return true
            }
            return false
        }
    }

    private data class Fixture(val service: AegisInputMethodService, val editor: WebEditor)

    @Before fun cleanRestoreFile() {
        File(RuntimeEnvironment.getApplication().filesDir, "cleared_text.txt").delete()
    }

    private fun fixture(text: String, caret: Int = text.length): Fixture {
        val service = Robolectric.buildService(AegisInputMethodService::class.java).get()
        val engine = object : CandidateEngine {
            override fun candidates(composing: String, t9: Boolean): List<String> = emptyList()
        }
        service.javaClass.getDeclaredField("controller").apply {
            isAccessible = true
            set(service, KeyboardController(service, engine, null))
        }
        val info = EditorInfo().apply {
            packageName = "com.example.webeditor"
            fieldId = 27
            fieldName = "reply"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT
            initialSelStart = caret
            initialSelEnd = caret
        }
        val framework = requireNotNull(service.javaClass.superclass)
        framework.getDeclaredField("mInputEditorInfo").apply {
            isAccessible = true
            set(service, info)
        }
        service.onStartInput(info, false)
        val editor = WebEditor(FrameLayout(service)).apply { hold(text, caret) }
        for (name in listOf("mInputConnection", "mStartedInputConnection")) {
            framework.getDeclaredField(name).apply {
                isAccessible = true
                set(service, editor)
            }
        }
        return Fixture(service, editor)
    }

    private fun swipe(f: Fixture, up: Boolean, settle: Boolean = true) {
        f.service.javaClass.getDeclaredMethod("backspaceSwipe", Boolean::class.javaPrimitiveType).apply {
            isAccessible = true
            invoke(f.service, up)
        }
        if (settle) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
    }

    private fun canSwipe(f: Fixture, up: Boolean): Boolean =
        f.service.javaClass.getDeclaredMethod("canBackspaceSwipe", Boolean::class.javaPrimitiveType).run {
            isAccessible = true
            invoke(f.service, up) as Boolean
        }

    private fun undo(f: Fixture) {
        val before = f.editor.document
        f.editor.documentChanges.clear()
        f.service.javaClass.getDeclaredMethod("handleEdit", EditAction::class.java).apply {
            isAccessible = true
            invoke(f.service, EditAction.UNDO)
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        val after = f.editor.document
        assertTrue("Unexpected intermediate editor contents: ${f.editor.documentChanges}",
            f.editor.documentChanges.all { it == before || it == after })
    }

    private fun edit(f: Fixture, action: EditAction) {
        f.service.javaClass.getDeclaredMethod("handleEdit", EditAction::class.java).apply {
            isAccessible = true
            invoke(f.service, action)
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
    }

    @Test fun system_plain_text_paste_uses_the_web_editor_command_and_retains_native_undo() {
        val original = "prefix\nsuffix"
        val payload = "pasted line\n".repeat(4096)
        val f = fixture(original, 7)
        f.editor.acceptNativePaste = true
        f.editor.rejectTextCommits = true
        val clipboard = f.service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("test", payload))
        edit(f, EditAction.PASTE)
        assertTrue("Native paste inserts the entire payload", f.editor.document == "prefix\n" + payload + "suffix")
        assertEquals(listOf(android.R.id.paste), f.editor.contextMenuRequests)
        assertEquals("No chunk is committed into the rendered DOM", 0, f.editor.rejectedTextCommits)
        undo(f)
        assertEquals(original, f.editor.document)
        assertEquals(1, f.editor.nativeUndoCalls)
        assertEquals(0, f.editor.rejectedTextCommits)
    }

    @Test fun ordinary_input_can_be_undone_through_the_panel_until_the_document_is_empty() {
        for (merge in listOf(false, true)) for (prefix in listOf("", "P")) {
            val f = fixture(prefix)
            f.editor.normalizeInput = true
            f.editor.mergeInputs = merge
            for (character in "abc") f.service.commitText(character.toString())
            for (expected in listOf("ab", "a", "")) {
                undo(f)
                assertEquals("merge=$merge prefix=$prefix", prefix + expected, f.editor.document)
            }
            assertEquals(0, f.editor.nativeUndoCalls)
            undo(f)
            assertEquals(0, f.editor.nativeUndoCalls)
        }
    }

    @Test fun restoring_a_deleted_quote_preserves_the_preceding_individual_input_steps() {
        for (action in listOf(EditAction.DELETE, EditAction.FORWARD_DELETE)) {
            val prefix = "> quoted reply\n\n"
            val f = fixture(prefix)
            f.editor.normalizeInput = true
            f.editor.mergeInputs = true
            for (character in "abc") f.service.commitText(character.toString())
            edit(f, EditAction.SELECT_ALL)
            edit(f, action)
            assertEquals("", f.editor.document)
            for (expected in listOf("abc", "ab", "a", "")) {
                undo(f)
                assertEquals(prefix + expected, f.editor.document)
            }
        }
    }

    @Test fun selecting_and_deleting_quoted_text_from_the_panel_retains_ordinary_undo() {
        for (action in listOf(EditAction.DELETE, EditAction.FORWARD_DELETE)) {
            for (original in listOf("> quoted reply\n\n", "A\u200bB\n\n", "一")) {
                val f = fixture(original)
                f.editor.normalizeInput = true
                val case = "$action / ${original.replace("\n", "\\n")}"
                val originalRaw = f.editor.rawText()
                edit(f, EditAction.SELECT_ALL)
                edit(f, action)
                assertEquals(case, "", f.editor.document)
                val expectedKey = if (action == EditAction.DELETE) KeyEvent.KEYCODE_DEL else KeyEvent.KEYCODE_FORWARD_DEL
                assertEquals(case, listOf(expectedKey), f.editor.nativeDeletionKeys)
                undo(f)
                assertEquals(case, original, f.editor.document)
                assertEquals(case, originalRaw, f.editor.rawText())
                assertEquals(case, 1, f.editor.nativeUndoCalls)
            }
        }
    }

    @Test fun a_web_placeholder_that_matches_real_text_does_not_enable_unverified_panel_undo() {
        val f = fixture("\u200b")
        val beforeRaw = f.editor.rawText()
        edit(f, EditAction.SELECT_ALL)
        edit(f, EditAction.DELETE)
        assertEquals("", f.editor.document)
        assertEquals(beforeRaw, f.editor.rawText())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        undo(f)
        assertEquals("", f.editor.document)
        assertEquals(0, f.editor.nativeUndoCalls)
    }

    @Test fun new_input_after_swipe_clear_keeps_its_first_undo_step() {
        for (batched in listOf(false, true)) {
            val f = fixture("> quoted reply\n\n")
            f.editor.normalizeInput = true
            swipe(f, up = true)
            assertEquals("", f.editor.document)
            val connection = requireNotNull(f.service.currentInputConnection)
            if (batched) connection.beginBatchEdit()
            connection.commitText("a", 1)
            if (batched) connection.endBatchEdit()
            assertEquals("a", f.editor.document)
            undo(f)
            assertEquals("", f.editor.document)
            assertEquals(0, f.editor.nativeUndoCalls)
            undo(f)
            assertEquals(0, f.editor.nativeUndoCalls)
        }
    }

    @Test fun clear_uses_the_editors_document_range_instead_of_the_raw_dom_suffix() {
        for (caret in listOf(0, 3, 7)) {
            val f = fixture("AAA\nBBB", caret)
            assertEquals("AAA\nBBB\n", f.editor.rawText())

            swipe(f, up = true)

            assertEquals("", f.editor.document)
            assertEquals("\u200b\n", f.editor.rawText())
            assertEquals(listOf("AAA\nBBB"), f.editor.nativeDeletions)
            assertEquals(listOf(0 to 7, 0 to 0), f.editor.nativeSelections)
            assertTrue(f.editor.selectionRequests.isEmpty())
            assertFalse(f.editor.contextMenuRequests.contains(android.R.id.selectAll))
            assertTrue(canSwipe(f, up = false))
            assertNotSame(f.editor, f.service.currentInputConnection)
        }
    }

    @Test fun clear_obeys_the_native_end_when_empty_lines_have_separate_dom_placeholders() {
        val f = fixture("AAA\n\n")
        assertEquals(5, f.editor.document.length)
        assertEquals("AAA\n\u200b\n\u200b\n", f.editor.rawText())
        assertEquals(8, f.editor.rawText().length)
        assertEquals(6 to 6, f.editor.caret())

        swipe(f, up = true)

        assertEquals("", f.editor.document)
        assertEquals(listOf(0 to 6, 0 to 0), f.editor.nativeSelections)
        assertTrue(f.editor.selectionRequests.isEmpty())
        swipe(f, up = false)
        assertEquals("AAA\n\n", f.editor.document)
        assertEquals("AAA\n\u200b\n\u200b\n", f.editor.rawText())
        assertEquals(6 to 6, f.editor.caret())
    }

    @Test fun swipe_down_restores_without_the_undo_notice_that_the_panel_undo_shows() {
        val done = RuntimeEnvironment.getApplication().getString(R.string.edit_undo_done)
        val unavailable = RuntimeEnvironment.getApplication().getString(R.string.edit_undo_unavailable)
        val cleared = fixture("AAA\nBBB").apply { service.onCreateInputView() }
        swipe(cleared, up = true)
        assertEquals("", cleared.editor.document)
        swipe(cleared, up = false)
        assertEquals("AAA\nBBB", cleared.editor.document)
        assertEquals(1, cleared.editor.nativeUndoCalls)
        assertTrue(cleared.service.toastTextForTest() !in listOf(done, unavailable))

        val deleted = fixture("> quoted reply\n\n").apply { service.onCreateInputView() }
        deleted.editor.normalizeInput = true
        for (character in "abc") deleted.service.commitText(character.toString())
        edit(deleted, EditAction.SELECT_ALL)
        edit(deleted, EditAction.FORWARD_DELETE)
        assertEquals("", deleted.editor.document)
        assertTrue(canSwipe(deleted, up = false))
        swipe(deleted, up = false)
        assertEquals("> quoted reply\n\nabc", deleted.editor.document)
        assertTrue(deleted.service.toastTextForTest() !in listOf(done, unavailable))
        undo(deleted)
        assertEquals("> quoted reply\n\nab", deleted.editor.document)
        assertEquals(done, deleted.service.toastTextForTest())
    }

    @Test fun swipe_down_restores_real_trailing_newlines_and_a_single_character_exactly() {
        for (original in listOf("AAA\nBBB", "AAA\nBBB\n", "AAA\n\n", "\nAAA\n\n", "一")) {
            for (caret in listOf(0, original.length / 2, original.length).distinct()) {
                val f = fixture(original, caret)
                val rawBefore = f.editor.rawText()
                val rawEnd = f.editor.rawOffset(original.length)

                swipe(f, up = true)
                assertEquals("", f.editor.document)
                assertTrue(canSwipe(f, up = false))
                swipe(f, up = false)

                assertEquals(original, f.editor.document)
                assertEquals(rawBefore, f.editor.rawText())
                assertEquals(rawEnd to rawEnd, f.editor.caret())
                assertEquals(1, f.editor.nativeUndoCalls)
                assertTrue(f.editor.invalidSelectionRequests.isEmpty())
                assertFalse(f.editor.contextMenuRequests.contains(android.R.id.selectAll))
                assertFalse(canSwipe(f, up = false))
            }
        }
    }

    @Test fun editing_undo_restores_the_editor_document_and_collapses_its_selection_at_the_real_end() {
        for (original in listOf("AAA\n\n", "一")) {
            val f = fixture(original)
            val rawBefore = f.editor.rawText()
            val rawEnd = f.editor.rawOffset(original.length)

            swipe(f, up = true)
            undo(f)

            assertEquals(original, f.editor.document)
            assertEquals(rawBefore, f.editor.rawText())
            assertEquals(rawEnd to rawEnd, f.editor.caret())
            assertEquals(1, f.editor.nativeUndoCalls)
            assertTrue(f.editor.invalidSelectionRequests.isEmpty())
        }
    }

    @Test fun clearing_the_empty_placeholder_does_not_replace_the_previous_restore() {
        val f = fixture("AAA\nBBB\n")
        swipe(f, up = true)
        repeat(3) {
            swipe(f, up = true)
            assertEquals("", f.editor.document)
            assertEquals("\u200b\n", f.editor.rawText())
            assertTrue(canSwipe(f, up = false))
        }

        swipe(f, up = false)

        assertEquals("AAA\nBBB\n", f.editor.document)
        assertEquals(listOf("AAA\nBBB\n"), f.editor.nativeDeletions)
    }

    @Test fun an_unreadable_nonempty_snapshot_uses_native_undo_once_and_restores_the_real_end() {
        for (useEditingUndo in listOf(false, true)) {
            val original = "AAA\n\n"
            val f = fixture(original, 3)
            f.editor.exposeNonemptySnapshot = false
            val rawBefore = f.editor.rawText()

            swipe(f, up = true)
            assertEquals("", f.editor.document)
            assertTrue(canSwipe(f, up = false))
            swipe(f, up = true)
            assertTrue(canSwipe(f, up = false))
            if (useEditingUndo) undo(f) else swipe(f, up = false)

            assertEquals(original, f.editor.document)
            assertEquals(rawBefore, f.editor.rawText())
            val rawEnd = f.editor.rawOffset(original.length)
            assertEquals(rawEnd to rawEnd, f.editor.caret())
            assertEquals(1, f.editor.nativeUndoCalls)
            assertTrue(f.editor.selectionRequests.isEmpty())
            assertFalse(canSwipe(f, up = false))
            swipe(f, up = false)
            undo(f)
            assertEquals(1, f.editor.nativeUndoCalls)
            assertEquals(original, f.editor.document)
        }
    }

    @Test fun a_no_op_clear_does_not_invent_a_restore_or_leave_the_connection_intercepted() {
        for (original in listOf("AAA\nBBB\n", "")) {
            val f = fixture(original)
            f.editor.acceptDelete = false
            val rawBefore = f.editor.rawText()

            swipe(f, up = true, settle = false)
            assertSame(f.editor, f.service.currentInputConnection)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))

            assertEquals(original, f.editor.document)
            assertEquals(rawBefore, f.editor.rawText())
            assertFalse(canSwipe(f, up = false))
            assertTrue(canSwipe(f, up = true))
            assertNotSame(f.editor, f.service.currentInputConnection)
            assertFalse(File(f.service.filesDir, "cleared_text.txt").exists())
            assertTrue(f.editor.nativeDeletions.isEmpty())
            swipe(f, up = false)
            assertEquals(rawBefore, f.editor.rawText())
        }
    }
}
