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
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.Selection
import android.text.method.TextKeyListener
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.SurroundingText
import com.aegis.ime.engine.CandidateEngine
import com.aegis.ime.ime.EditAction
import com.aegis.ime.ime.EditPanelView
import com.aegis.ime.ime.KeyboardController
import com.aegis.ime.ime.LayoutChoice
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AegisLargeEditingIntegrationTest {
    private class Connection(val view: View, val knownOffset: Boolean) : BaseInputConnection(view, true) {
        var reportSelection: (() -> Unit)? = null
        var responseLimit = Int.MAX_VALUE
        var commitLimit = Int.MAX_VALUE
        var largestResponse = 0
        var largestCommit = 0
        override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): SurroundingText? {
            val value = super.getSurroundingText(beforeLength, afterLength, flags) ?: return null
            largestResponse = maxOf(largestResponse, value.text.length)
            if (value.text.length > responseLimit) return null
            return if (knownOffset) value else SurroundingText(value.text, value.selectionStart, value.selectionEnd, -1)
        }
        override fun setSelection(start: Int, end: Int): Boolean = super.setSelection(start, end).also { reportSelection?.invoke() }
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            largestCommit = maxOf(largestCommit, text?.length ?: 0)
            if (text != null && text.length > commitLimit) return false
            return super.commitText(text, newCursorPosition).also { reportSelection?.invoke() }
        }
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
            super.deleteSurroundingText(beforeLength, afterLength).also { reportSelection?.invoke() }
        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
            super.deleteSurroundingTextInCodePoints(beforeLength, afterLength).also { reportSelection?.invoke() }
        var extractions = 0
        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? {
            extractions++
            return null
        }
        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN) {
                TextKeyListener.getInstance().onKeyDown(view, editable, event.keyCode, event)
                reportSelection?.invoke()
            }
            return true
        }
        override fun performContextMenuAction(id: Int): Boolean = when (id) {
            android.R.id.cut -> commitText("", 1)
            else -> false
        }
    }

    private fun field(service: AegisInputMethodService, name: String): Any? =
        service.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(service)

    private fun invoke(service: AegisInputMethodService, name: String) {
        service.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(service)
    }

    @Test fun large_document_panel_edits_are_reversible_in_both_layouts_at_every_document_position() {
        val text = System.getenv("AEGIS_LARGE_EDITOR_SAMPLE")?.let { File(it).readText() }
            ?: "大文本撤销 abcdefghijklmnopqrstuvwxyz0123456789\r\n".repeat(4096)
        assertTrue("fixture exceeds full-snapshot limit", text.length > 131_072)
        val actions = listOf(EditAction.DELETE, EditAction.FORWARD_DELETE, EditAction.TAB, EditAction.PASTE, EditAction.CUT)
        for (knownOffset in listOf(true, false)) for (choice in listOf(LayoutChoice.CN_ALPHA, LayoutChoice.CN_NINE)) {
            for (position in listOf(4, text.length / 2, text.length - 4)) {
                val service = Robolectric.buildService(AegisInputMethodService::class.java).get()
                service.getSharedPreferences("aegis", Context.MODE_PRIVATE).edit().putBoolean("clip_history", false).commit()
                val engine = object : CandidateEngine {
                    override fun candidates(composing: String, t9: Boolean): List<String> = emptyList()
                }
                val controller = KeyboardController(service, engine, null)
                service.javaClass.getDeclaredField("controller").apply { isAccessible = true; set(service, controller) }
                val info = EditorInfo().apply {
                    packageName = "com.example.largeeditor"
                    fieldId = 42
                    inputType = InputType.TYPE_CLASS_TEXT
                    initialSelStart = position
                    initialSelEnd = position
                }
                val framework = service.javaClass.superclass!!
                framework.getDeclaredField("mInputEditorInfo").apply { isAccessible = true; set(service, info) }
                service.onStartInput(info, false)
                service.onCreateInputView()
                service.onStartInputView(info, false)
                val connection = Connection(View(service), knownOffset)
                connection.commitText(text, 1)
                connection.setSelection(position, position)
                for (name in listOf("mInputConnection", "mStartedInputConnection")) {
                    framework.getDeclaredField(name).apply { isAccessible = true; set(service, connection) }
                }
                connection.reportSelection = {
                    val start = Selection.getSelectionStart(connection.editable)
                    val end = Selection.getSelectionEnd(connection.editable)
                    Handler(Looper.getMainLooper()).post { service.onUpdateSelection(start, end, start, end, -1, -1) }
                }
                controller.applyLayoutChoice(choice)
                val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("test", "pasted"))
                invoke(service, "showEditPanel")
                val panel = field(service, "editPanelView") as EditPanelView
                fun selectionChanged() {
                    val start = Selection.getSelectionStart(connection.editable)
                    val end = Selection.getSelectionEnd(connection.editable)
                    service.onUpdateSelection(start, end, start, end, -1, -1)
                    shadowOf(Looper.getMainLooper()).idle()
                }
                fun edit(action: EditAction) {
                    service.javaClass.getDeclaredMethod("handleEdit", EditAction::class.java)
                        .apply { isAccessible = true }.invoke(service, action)
                    selectionChanged()
                }
                for (action in actions) {
                    connection.setSelection(position, if (action == EditAction.CUT) position + 3 else position)
                    selectionChanged()
                    edit(action)
                    val context = "$choice knownOffset=$knownOffset $position $action"
                    assertNotEquals("$context changed content", text, connection.editable.toString())
                    if (action == EditAction.PASTE) assertEquals("$context exact paste", text.substring(0, position) + "pasted" + text.substring(position), connection.editable.toString())
                    assertTrue("$context enables Undo", requireNotNull(panel.actionViewForTest(EditAction.UNDO)).isEnabled)
                    edit(EditAction.UNDO)
                    assertEquals("$context restores the whole document", text, connection.editable.toString())
                    assertEquals("$context restores selection start", position, Selection.getSelectionStart(connection.editable))
                    assertEquals("$context restores selection end", if (action == EditAction.CUT) position + 3 else position,
                        Selection.getSelectionEnd(connection.editable))
                }
                assertEquals("large documents do not request full extraction", 0, connection.extractions)
                service.onFinishInput()
            }
        }
    }
    @Test fun whole_document_and_long_partial_replacements_restore_after_the_host_becomes_small() {
        val text = System.getenv("AEGIS_LARGE_EDITOR_SAMPLE")?.let { File(it).readText() }
            ?: "大文本 abcdefghijklmnopqrstuvwxyz0123456789\r\n".repeat(4096)
        for (choice in listOf(LayoutChoice.CN_ALPHA, LayoutChoice.CN_NINE)) {
            for (action in listOf(EditAction.CUT, EditAction.DELETE, EditAction.FORWARD_DELETE, EditAction.TAB, EditAction.PASTE)) {
            for ((from, through) in listOf(0 to text.length, 5 to 70_005)) {
                val service = Robolectric.buildService(AegisInputMethodService::class.java).get()
                service.getSharedPreferences("aegis", Context.MODE_PRIVATE).edit().putBoolean("clip_history", false).commit()
                val engine = object : CandidateEngine {
                    override fun candidates(composing: String, t9: Boolean): List<String> = emptyList()
                }
                val controller = KeyboardController(service, engine, null)
                service.javaClass.getDeclaredField("controller").apply { isAccessible = true; set(service, controller) }
                val info = EditorInfo().apply {
                    packageName = "com.example.largeeditor"
                    fieldId = 43
                    inputType = InputType.TYPE_CLASS_TEXT
                    initialSelStart = from
                    initialSelEnd = through
                }
                val framework = service.javaClass.superclass!!
                framework.getDeclaredField("mInputEditorInfo").apply { isAccessible = true; set(service, info) }
                service.onStartInput(info, false)
                service.onCreateInputView()
                service.onStartInputView(info, false)
                val connection = Connection(View(service), false)
                connection.commitText(text, 1)
                connection.setSelection(from, through)
                for (name in listOf("mInputConnection", "mStartedInputConnection")) {
                    framework.getDeclaredField(name).apply { isAccessible = true; set(service, connection) }
                }
                connection.reportSelection = {
                    val start = Selection.getSelectionStart(connection.editable)
                    val end = Selection.getSelectionEnd(connection.editable)
                    Handler(Looper.getMainLooper()).post { service.onUpdateSelection(start, end, start, end, -1, -1) }
                }
                controller.applyLayoutChoice(choice)
                service.onUpdateSelection(from, through, from, through, -1, -1)
                invoke(service, "showEditPanel")
                val panel = field(service, "editPanelView") as EditPanelView
                fun edit(action: EditAction) {
                    service.javaClass.getDeclaredMethod("handleEdit", EditAction::class.java)
                        .apply { isAccessible = true }.invoke(service, action)
                    shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(10))
                }
                val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("test", "替换\t🙂\r\n"))
                if (action != EditAction.CUT) {
                    val inserted = when (action) {
                        EditAction.TAB -> "\t"
                        EditAction.PASTE -> "替换\t🙂\r\n"
                        else -> ""
                    }
                    edit(action)
                    assertTrue("$choice $from..$through $action exact replacement", connection.editable.toString() == text.replaceRange(from, through, inserted))
                    assertTrue("$choice $from..$through $action enables undo", requireNotNull(panel.actionViewForTest(EditAction.UNDO)).isEnabled)
                    edit(EditAction.UNDO)
                    assertTrue("$choice $from..$through $action restores all text", connection.editable.toString() == text)
                    assertEquals(from, Selection.getSelectionStart(connection.editable))
                    assertEquals(through, Selection.getSelectionEnd(connection.editable))
                    service.onFinishInput()
                    continue
                }
                edit(EditAction.CUT)
                assertTrue("$choice $from..$through exact cut", connection.editable.toString() == text.removeRange(from, through))
                assertTrue("$choice long cut enables undo", requireNotNull(panel.actionViewForTest(EditAction.UNDO)).isEnabled)
                edit(EditAction.PASTE)
                assertTrue("$choice pastes the complete captured selection", connection.editable.toString() == text)
                assertTrue("$choice long paste enables undo", requireNotNull(panel.actionViewForTest(EditAction.UNDO)).isEnabled)
                edit(EditAction.UNDO)
                assertTrue("$choice undo only removes the paste", connection.editable.toString() == text.removeRange(from, through))
                assertTrue("$choice cut remains undoable", requireNotNull(panel.actionViewForTest(EditAction.UNDO)).isEnabled)
                edit(EditAction.UNDO)
                assertTrue("$choice $from..$through restores complete text", connection.editable.toString() == text)
                assertEquals(from, Selection.getSelectionStart(connection.editable))
                assertEquals(through, Selection.getSelectionEnd(connection.editable))
                service.onFinishInput()
            }
            }
        }
    }

    @Test fun paste_boundaries_preserve_the_paste_and_previous_tab_undo_steps() {
        val original = "abc def\nghi jkl\nmnop\n".repeat(7_000)
        val failures = mutableListOf<String>()
        var cases = 0
        for (knownOffset in listOf(true, false)) for (choice in listOf(LayoutChoice.CN_ALPHA, LayoutChoice.CN_NINE)) {
            val service = Robolectric.buildService(AegisInputMethodService::class.java).get()
            service.getSharedPreferences("aegis", Context.MODE_PRIVATE).edit().putBoolean("clip_history", false).commit()
            val engine = object : CandidateEngine {
                override fun candidates(composing: String, t9: Boolean): List<String> = emptyList()
            }
            val controller = KeyboardController(service, engine, null)
            service.javaClass.getDeclaredField("controller").apply { isAccessible = true; set(service, controller) }
            for (position in listOf(0, 70_000)) for (span in listOf(0, 1, 32_767, 32_768)) {
                for (size in listOf(16_384, 16_385, 32_640, 32_641, 32_768, 40_000, 65_536, 65_537)) {
                    val label = "$choice offset=$knownOffset position=$position span=$span size=$size"
                    cases++
                    try {
                        val info = EditorInfo().apply {
                            packageName = "com.example.largeeditor"
                            fieldId = 44 + cases
                            inputType = InputType.TYPE_CLASS_TEXT
                            initialSelStart = position
                            initialSelEnd = position
                        }
                        val framework = service.javaClass.superclass!!
                        framework.getDeclaredField("mInputEditorInfo").apply { isAccessible = true; set(service, info) }
                        service.onStartInput(info, false)
                        if (field(service, "inputView") == null) service.onCreateInputView()
                        service.onStartInputView(info, false)
                        val connection = Connection(View(service), knownOffset)
                        connection.commitText(original, 1)
                        connection.setSelection(position, position)
                        connection.responseLimit = 65_536
                        connection.commitLimit = 65_536
                        connection.largestCommit = 0
                        for (name in listOf("mInputConnection", "mStartedInputConnection")) {
                            framework.getDeclaredField(name).apply { isAccessible = true; set(service, connection) }
                        }
                        connection.reportSelection = {
                            val start = Selection.getSelectionStart(connection.editable)
                            val end = Selection.getSelectionEnd(connection.editable)
                            Handler(Looper.getMainLooper()).post { service.onUpdateSelection(start, end, start, end, -1, -1) }
                        }
                        controller.applyLayoutChoice(choice)
                        service.onUpdateSelection(position, position, position, position, -1, -1)
                        val currentPanel = field(service, "editPanelView") as? EditPanelView
                        val inputView = field(service, "inputView") as com.aegis.ime.ime.InputView
                        if (currentPanel == null || !inputView.isPanelShowing(currentPanel)) invoke(service, "showEditPanel")
                        val panel = field(service, "editPanelView") as EditPanelView
                        fun edit(action: EditAction) {
                            service.javaClass.getDeclaredMethod("handleEdit", EditAction::class.java)
                                .apply { isAccessible = true }.invoke(service, action)
                            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(10))
                        }
                        edit(EditAction.TAB)
                        val withTab = original.substring(0, position) + "\t" + original.substring(position)
                        assertTrue("$label records Tab", connection.editable.toString() == withTab)
                        connection.setSelection(position, position + span)
                        shadowOf(Looper.getMainLooper()).idle()
                        val inserted = "P".repeat(size)
                        val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("test", inserted))
                        edit(EditAction.PASTE)
                        assertTrue("$label exact Paste", connection.editable.toString() == withTab.replaceRange(position, position + span, inserted))
                        assertTrue("$label enables Paste Undo", requireNotNull(panel.actionViewForTest(EditAction.UNDO)).isEnabled)
                        edit(EditAction.UNDO)
                        assertTrue("$label restores Paste", connection.editable.toString() == withTab)
                        assertEquals("$label restores selection start", position, Selection.getSelectionStart(connection.editable))
                        assertEquals("$label restores selection end", position + span, Selection.getSelectionEnd(connection.editable))
                        assertTrue("$label retains Tab Undo", requireNotNull(panel.actionViewForTest(EditAction.UNDO)).isEnabled)
                        edit(EditAction.UNDO)
                        assertTrue("$label restores original", connection.editable.toString() == original)
                        assertEquals(position, Selection.getSelectionStart(connection.editable))
                        assertEquals(position, Selection.getSelectionEnd(connection.editable))
                        assertFalse("$label consumes both entries", requireNotNull(panel.actionViewForTest(EditAction.UNDO)).isEnabled)
                        assertTrue("$label bounded response", connection.largestResponse <= 65_536)
                        assertTrue("$label bounded commit", connection.largestCommit <= 65_536)
                    } catch (error: AssertionError) { failures.add(error.message.orEmpty()) }
                    finally { service.onFinishInput() }
                }
            }
        }
        assertTrue("${failures.size}/$cases boundary cases failed\n" + failures.take(40).joinToString("\n"), failures.isEmpty())
    }
}
