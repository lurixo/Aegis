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

import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.Selection
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.SurroundingText
import com.aegis.ime.engine.CandidateEngine
import com.aegis.ime.ime.EditAction
import com.aegis.ime.ime.EditorUndoHistory
import com.aegis.ime.ime.KeyboardController
import com.aegis.ime.ime.LayoutChoice
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LargeSelectionUndoTest {
    private class Host(view: View, private val shared: android.text.Editable? = null) : BaseInputConnection(view, true) {
        override fun getEditable(): android.text.Editable = shared ?: super.getEditable()!!

        var report: (() -> Unit)? = null
        var ignoreDeletions = false
        var deferred = false
        private val queued = ArrayDeque<() -> Unit>()

        fun flush() { while (queued.isNotEmpty()) queued.removeFirst().invoke() }
        var extractions = 0
        val text get() = editable!!.toString()

        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText {
            extractions++
            val content = editable!!
            return ExtractedText().also {
                it.text = content.subSequence(0, content.length)
                it.startOffset = 0
                it.partialStartOffset = -1
                it.partialEndOffset = -1
                it.selectionStart = Selection.getSelectionStart(content)
                it.selectionEnd = Selection.getSelectionEnd(content)
            }
        }

        var trailingCap = -1

        override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): SurroundingText? {
            val content = editable!!
            val low = minOf(Selection.getSelectionStart(content), Selection.getSelectionEnd(content))
            val high = maxOf(Selection.getSelectionStart(content), Selection.getSelectionEnd(content))
            if (low < 0) return null
            val after = if (trailingCap >= 0) minOf(afterLength, trailingCap) else afterLength
            val from = maxOf(0, low - beforeLength)
            val through = minOf(content.length, high + after)
            val value = if (flags and InputConnection.GET_TEXT_WITH_STYLES != 0) content.subSequence(from, through)
                else content.substring(from, through)
            return SurroundingText(value, low - from, high - from, from)
        }

        override fun setSelection(start: Int, end: Int): Boolean =
            super.setSelection(start, end).also { report?.invoke() }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            if (deferred) {
                queued.addLast { super.commitText(text, newCursorPosition); report?.invoke() }
                return true
            }
            return super.commitText(text, newCursorPosition).also { report?.invoke() }
        }

        private fun applyBackspace() {
            val content = editable!!
            val start = Selection.getSelectionStart(content)
            val end = Selection.getSelectionEnd(content)
            if (start != end) super.commitText("", 1) else if (start > 0) super.deleteSurroundingText(1, 0)
            report?.invoke()
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (ignoreDeletions) return true
            if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DEL) {
                if (deferred) queued.addLast { applyBackspace() } else applyBackspace()
            }
            return true
        }
    }

    private class Fixture(document: String, caret: Int) {
        val service: AegisInputMethodService = Robolectric.buildService(AegisInputMethodService::class.java).get()
        val controller = KeyboardController(service, object : CandidateEngine {
            override val supportsChinese: Boolean get() = true
            override fun candidates(composing: String, t9: Boolean): List<String> =
                if (composing.isEmpty()) emptyList() else listOf("你好")
        }, null)
        var host = Host(View(service))

        init {
            service.javaClass.getDeclaredField("controller").apply { isAccessible = true; set(service, controller) }
            val info = EditorInfo().apply {
                packageName = "com.example.reader"
                fieldId = 71
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                initialSelStart = caret
                initialSelEnd = caret
            }
            val framework = service.javaClass.superclass!!
            framework.getDeclaredField("mInputEditorInfo").apply { isAccessible = true; set(service, info) }
            service.onStartInput(info, false)
            service.onCreateInputView()
            service.onStartInputView(info, false)
            host.editable!!.append(document)
            Selection.setSelection(host.editable, caret)
            for (name in listOf("mInputConnection", "mStartedInputConnection")) {
                framework.getDeclaredField(name).apply { isAccessible = true; set(service, host) }
            }
            controller.applyLayoutChoice(LayoutChoice.CN_ALPHA)
            host.report = {
                val start = Selection.getSelectionStart(host.editable)
                val end = Selection.getSelectionEnd(host.editable)
                Handler(Looper.getMainLooper()).post { service.onUpdateSelection(-1, -1, start, end, -1, -1) }
            }
            settle()
        }

        fun settle() { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3)) }

        /** Mimics an editor that restarts input and hands the IME a fresh connection. */
        fun restartConnection(text: String? = null) {
            val content = host.editable!!
            if (text != null) content.replace(0, content.length, text)
            val next = Host(View(service), content)
            next.report = host.report
            val framework = service.javaClass.superclass!!
            for (name in listOf("mInputConnection", "mStartedInputConnection")) {
                framework.getDeclaredField(name).apply { isAccessible = true; set(service, next) }
            }
            host = next
            settle()
        }

        /** Types and deletes one character so the local fast path learns the editor reports selections. */
        fun warmUp() {
            service.commitText("起")
            settle()
            service.deleteBackward()
            settle()
        }

        fun select(from: Int, to: Int) {
            Selection.setSelection(host.editable, from, to)
            host.report?.invoke()
            settle()
        }

        fun backspace() {
            controller.onKey(Key("", action = KeyAction.BACKSPACE))
            settle()
        }

        fun editNow(action: EditAction) {
            service.javaClass.getDeclaredMethod("handleEdit", EditAction::class.java).apply { isAccessible = true }.invoke(service, action)
        }

        fun edit(action: EditAction) {
            service.javaClass.getDeclaredMethod("handleEdit", EditAction::class.java).apply { isAccessible = true }.invoke(service, action)
            settle()
        }

        fun showPanel() {
            service.javaClass.getDeclaredMethod("showEditPanel").apply { isAccessible = true }.invoke(service)
            settle()
        }

        fun undoAvailable(): Boolean {
            val panel = service.javaClass.getDeclaredField("editPanelView").apply { isAccessible = true }.get(service)
                ?: return false
            return panel.javaClass.getDeclaredField("undoAvailable").apply { isAccessible = true }.getBoolean(panel)
        }

        fun undo(): EditorUndoHistory =
            service.javaClass.getDeclaredField("editorUndo").apply { isAccessible = true }.get(service) as EditorUndoHistory
    }

    private val document = "斗气大陆的少年在这一页里继续他的修炼，段落之间夹着标点、English 和数字 12345。\n".repeat(6_000)

    @Test fun any_stray_selection_report_after_a_mid_document_deletion_keeps_the_undo_step() {
        val from = 120_000
        val span = 4_000
        val strays = listOf(
            from to from + span, from to from + span / 2, from to from + span - 1,
            from + span to from + span, from - 1 to from - 1, from + 1 to from + 1,
            from to from, 0 to 0, from + 500 to from + 500, from - 200 to from + 200,
        )
        for (stray in strays) {
            val f = Fixture(document, from)
            f.warmUp()
            f.select(from, from + span)
            f.backspace()
            f.service.onUpdateSelection(-1, -1, stray.first, stray.second, -1, -1)
            f.settle()
            f.showPanel()
            assertTrue("stray=$stray keeps the undo step", f.undoAvailable())
            f.service.onFinishInput()
        }
    }

    @Test fun a_burst_of_stray_reports_after_a_deletion_keeps_the_undo_step() {
        val from = 120_000
        val span = 4_000
        val f = Fixture(document, from)
        f.warmUp()
        f.select(from, from + span)
        f.backspace()
        for (k in listOf(span, span / 2, span / 4, span / 8, 0)) {
            f.service.onUpdateSelection(-1, -1, from, from + k, -1, -1)
        }
        f.settle()
        f.showPanel()
        assertTrue("a burst of drag reports keeps the undo step", f.undoAvailable())
    }

    @Test fun a_host_that_returns_little_trailing_text_keeps_the_undo_step() {
        for (trailing in listOf(0, 16, 64, 127)) {
            val from = 120_000
            val span = 4_000
            val f = Fixture(document, from)
            f.host.trailingCap = trailing
            f.warmUp()
            f.select(from, from + span)
            f.backspace()
            assertTrue("trailing=$trailing records the deletion", f.undo().hasUndo)
            f.service.onUpdateSelection(-1, -1, from, from + span, -1, -1)
            f.settle()
            f.showPanel()
            assertTrue("trailing=$trailing keeps the undo step after a stray report", f.undoAvailable())
            f.service.onFinishInput()
        }
    }

    @Test fun a_restarted_input_connection_keeps_the_undo_step() {
        val from = 120_000
        val span = 4_000
        val f = Fixture(document, from)
        f.warmUp()
        f.select(from, from + span)
        val expected = f.host.text.removeRange(from, from + span)
        f.backspace()
        assertEquals("the selection is deleted", expected, f.host.text)
        assertTrue("the deletion is recorded", f.undo().hasUndo)
        f.restartConnection()
        f.showPanel()
        assertTrue("a fresh connection keeps the undo step", f.undoAvailable())
        f.edit(EditAction.UNDO)
        assertEquals("the undo still restores the text", document, f.host.text)
    }

    @Test fun a_connection_that_arrives_with_other_text_drops_the_undo_step() {
        val from = 120_000
        val span = 4_000
        val f = Fixture(document, from)
        f.warmUp()
        f.select(from, from + span)
        f.backspace()
        assertTrue("the deletion is recorded", f.undo().hasUndo)
        f.restartConnection("这是另一个编辑器里的文字，和刚才那一页毫无关系。\n".repeat(400))
        f.showPanel()
        assertFalse("a different document drops the undo step", f.undoAvailable())
    }

    @Test fun deleting_a_large_selection_keeps_the_undo_step() {
        for (span in listOf(20_000, 40_000)) {
            val f = Fixture(document, 100_000)
            f.warmUp()
            val from = 100_000
            f.select(from, from + span)
            val removed = f.host.text.substring(from, from + span)
            val expected = f.host.text.removeRange(from, from + span)
            f.backspace()
            assertEquals("span=$span deletes the selection", expected, f.host.text)
            assertTrue("span=$span keeps an undo step", f.undo().hasUndo)
            f.edit(EditAction.UNDO)
            assertEquals("span=$span restores the deleted text", expected.substring(0, from) + removed +
                expected.substring(from), f.host.text)
            f.service.onFinishInput()
        }
    }

    @Test fun a_late_selection_report_from_the_drag_does_not_drop_the_undo_step() {
        for (span in listOf(20_000, 40_000)) {
            val f = Fixture(document, 100_000)
            f.warmUp()
            val from = 100_000
            f.select(from, from + span)
            val expected = f.host.text.removeRange(from, from + span)
            f.controller.onKey(Key("", action = KeyAction.BACKSPACE))
            f.service.onUpdateSelection(-1, -1, from, from + span / 2, -1, -1)
            f.settle()
            assertEquals("span=$span deletes the selection", expected, f.host.text)
            assertTrue("span=$span keeps an undo step after a stale report", f.undo().hasUndo)
            f.edit(EditAction.UNDO)
            assertEquals("span=$span still restores the text", document, f.host.text)
            f.service.onFinishInput()
        }
    }

    @Test fun undo_stays_offered_while_a_large_deletion_is_still_being_written() {
        val f = Fixture(document, 100_000)
        f.warmUp()
        val from = 100_000
        val span = 40_000
        f.select(from, from + span)
        val whole = f.host.text
        f.showPanel()
        f.host.deferred = true
        f.controller.onKey(Key("", action = KeyAction.BACKSPACE))
        assertTrue("undo is offered while the deletion is still being written", f.undoAvailable())
        f.host.deferred = false
        f.host.flush()
        f.settle()
        assertEquals("the selection is gone", whole.removeRange(from, from + span), f.host.text)
        assertTrue("the finished deletion can be undone", f.undoAvailable())
        f.edit(EditAction.UNDO)
        assertEquals("undo restores the deleted text", whole, f.host.text)
        f.service.onFinishInput()
    }

    @Test fun an_undo_tapped_during_a_large_deletion_runs_once_it_finishes() {
        val f = Fixture(document, 100_000)
        f.warmUp()
        val from = 100_000
        val span = 40_000
        f.select(from, from + span)
        val whole = f.host.text
        f.showPanel()
        f.host.deferred = true
        f.controller.onKey(Key("", action = KeyAction.BACKSPACE))
        f.editNow(EditAction.UNDO)
        f.host.deferred = false
        f.host.flush()
        f.settle()
        f.settle()
        assertEquals("the queued undo restores the text", whole, f.host.text)
        f.service.onFinishInput()
    }

    @Test fun a_deletion_the_editor_ignored_is_dropped_once_a_report_contradicts_it() {
        val f = Fixture(document, 100_000)
        f.warmUp()
        val from = 100_000
        f.select(from, from + 20_000)
        val untouched = f.host.text
        f.host.ignoreDeletions = true
        f.controller.onKey(Key("", action = KeyAction.BACKSPACE))
        f.service.onUpdateSelection(-1, -1, from + 5, from + 5, -1, -1)
        f.settle()
        f.host.ignoreDeletions = false
        assertEquals("the editor kept its text", untouched, f.host.text)
        assertFalse("the unlanded deletion is dropped", f.undo().canUndo(f.host))
        f.edit(EditAction.UNDO)
        assertEquals("undo never rewrites the editor", untouched, f.host.text)
        f.service.onFinishInput()
    }

    @Test fun a_deletion_that_is_the_first_edit_after_a_fresh_start_records_it() {
        val from = 120_000
        val span = 4_000
        val f = Fixture(document, from)
        f.select(from, from + span)
        f.backspace()
        f.showPanel()
        assertTrue("the first edit of a session records its deletion", f.undoAvailable())
        f.service.onFinishInput()
    }

    @Test fun a_first_deletion_the_editor_applies_after_the_key_returns_records_it() {
        val from = 120_000
        val span = 4_000
        val f = Fixture(document, from)
        f.select(from, from + span)
        f.host.deferred = true
        f.controller.onKey(Key("", action = KeyAction.BACKSPACE))
        f.host.flush()
        f.settle()
        assertEquals("the editor removed the selection", document.length - span, f.host.text.length)
        f.showPanel()
        assertTrue("a deletion the editor applies late still records", f.undoAvailable())
        f.service.onFinishInput()
    }
}
