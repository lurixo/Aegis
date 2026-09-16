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
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorUndoReadBudgetTest {
    private class Host(view: View, private val knownOffset: Boolean) : BaseInputConnection(view, true) {
        var report: (() -> Unit)? = null
        var rewrite: String? = null
        var ignoreCommits = false
        var reads = 0
        var readChars = 0
        var largestRequest = 0
        var extractions = 0
        var selections = 0
        val text get() = editable!!.toString()

        fun reset() {
            reads = 0
            readChars = 0
            largestRequest = 0
            extractions = 0
            selections = 0
        }

        private fun counted(requested: Int, value: CharSequence?): CharSequence? {
            reads++
            largestRequest = maxOf(largestRequest, requested)
            readChars += value?.length ?: 0
            return value
        }

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? = counted(n, super.getTextBeforeCursor(n, flags))
        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? = counted(n, super.getTextAfterCursor(n, flags))
        override fun getSelectedText(flags: Int): CharSequence? = super.getSelectedText(flags).let { counted(it?.length ?: 0, it) }

        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText {
            extractions++
            val content = editable!!
            counted(content.length, content)
            return ExtractedText().also {
                it.text = content.subSequence(0, content.length)
                it.startOffset = 0
                it.partialStartOffset = -1
                it.partialEndOffset = -1
                it.selectionStart = Selection.getSelectionStart(content)
                it.selectionEnd = Selection.getSelectionEnd(content)
            }
        }

        override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): SurroundingText? {
            val content = editable!!
            val low = minOf(Selection.getSelectionStart(content), Selection.getSelectionEnd(content))
            val high = maxOf(Selection.getSelectionStart(content), Selection.getSelectionEnd(content))
            if (low < 0) return null
            val from = maxOf(0, low - beforeLength)
            val through = minOf(content.length, high + afterLength)
            val value = if (flags and InputConnection.GET_TEXT_WITH_STYLES != 0) content.subSequence(from, through)
                else content.substring(from, through)
            counted(maxOf(beforeLength, afterLength), value)
            return SurroundingText(value, low - from, high - from, if (knownOffset) from else -1)
        }

        override fun setSelection(start: Int, end: Int): Boolean {
            selections++
            return super.setSelection(start, end).also { report?.invoke() }
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            if (ignoreCommits) return true
            val accepted = super.commitText(text, newCursorPosition)
            rewrite?.let { super.commitText(it, 1) }
            report?.invoke()
            return accepted
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
            super.deleteSurroundingText(beforeLength, afterLength).also { report?.invoke() }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DEL) {
                val content = editable!!
                val start = Selection.getSelectionStart(content)
                val end = Selection.getSelectionEnd(content)
                if (start != end) super.commitText("", 1) else if (start > 0) super.deleteSurroundingText(1, 0)
                report?.invoke()
            }
            return true
        }
    }

    private class Fixture(choice: LayoutChoice, knownOffset: Boolean, document: String, caret: Int) {
        val service: AegisInputMethodService = Robolectric.buildService(AegisInputMethodService::class.java).get()
        val controller = KeyboardController(service, object : CandidateEngine {
            override val supportsChinese: Boolean get() = true
            override fun candidates(composing: String, t9: Boolean): List<String> =
                if (composing.isEmpty()) emptyList() else listOf("你好")
        }, null)
        val host = Host(View(service), knownOffset)

        init {
            service.javaClass.getDeclaredField("controller").apply { isAccessible = true; set(service, controller) }
            val info = EditorInfo().apply {
                packageName = "com.example.longdraft"
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
            controller.applyLayoutChoice(choice)
            host.report = {
                val start = Selection.getSelectionStart(host.editable)
                val end = Selection.getSelectionEnd(host.editable)
                Handler(Looper.getMainLooper()).post { service.onUpdateSelection(-1, -1, start, end, -1, -1) }
            }
            settle()
        }

        fun settle() { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3)) }

        fun edit(action: EditAction) {
            service.javaClass.getDeclaredMethod("handleEdit", EditAction::class.java).apply { isAccessible = true }.invoke(service, action)
            settle()
        }

        fun undo(): EditorUndoHistory =
            service.javaClass.getDeclaredField("editorUndo").apply { isAccessible = true }.get(service) as EditorUndoHistory
    }

    private val document = "长草稿里的一段正文，包含标点、English words 和数字 12345。\n".repeat(500).take(20_000)
    private val caret = 12_345

    private fun assertBounded(host: Host, label: String) {
        assertEquals("$label never extracts the whole document", 0, host.extractions)
        assertTrue("$label requests at most a local window: ${host.largestRequest}", host.largestRequest <= 256)
        assertTrue("$label reads a bounded number of characters: ${host.readChars}", host.readChars <= 1_024)
        assertTrue("$label issues a bounded number of reads: ${host.reads}", host.reads <= 8)
    }

    private class WebHost(view: View) : BaseInputConnection(view, true) {
        var deferred = false
        var extractions = 0
        var largestCursorRead = 0
        private val queued = ArrayDeque<() -> Unit>()

        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText {
            extractions++
            val content = editable!!
            return ExtractedText().also {
                it.text = content.toString()
                it.startOffset = 0
                it.partialStartOffset = -1
                it.selectionStart = Selection.getSelectionStart(content)
                it.selectionEnd = Selection.getSelectionEnd(content)
            }
        }

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? {
            largestCursorRead = maxOf(largestCursorRead, n)
            return super.getTextBeforeCursor(n, flags)
        }

        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? {
            largestCursorRead = maxOf(largestCursorRead, n)
            return super.getTextAfterCursor(n, flags)
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            if (!deferred) return super.commitText(text, newCursorPosition)
            queued.addLast { super.commitText(text, newCursorPosition) }
            return true
        }

        fun flush() { while (queued.isNotEmpty()) queued.removeFirst().invoke() }
    }

    @Test fun web_edits_take_one_snapshot_and_confirm_on_the_selection_report_instead_of_fast_polling() {
        val host = WebHost(View(RuntimeEnvironment.getApplication()))
        host.editable!!.append(document)
        Selection.setSelection(host.editable, caret)
        val history = EditorUndoHistory().apply { preferNativeUndo = true }
        val connection = history.wrap(host)
        host.deferred = true
        assertTrue(connection.commitText("你", 1))
        assertEquals("one snapshot before the edit", 1, host.extractions)
        assertTrue("consistency reads stay local: ${host.largestCursorRead}", host.largestCursorRead <= 256)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(64))
        assertEquals("no 32ms polling while an ordinary edit waits for the host", 1, host.extractions)
        host.deferred = false
        host.flush()
        history.selectionUpdated(caret + 1, caret + 1)
        assertEquals(2, host.extractions)
        assertTrue(history.hasUndo)
        assertTrue(history.undo(connection))
        assertEquals(document, host.editable.toString())
    }
}
