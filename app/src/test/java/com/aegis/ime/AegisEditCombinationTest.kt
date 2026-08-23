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
import android.icu.text.BreakIterator
import android.os.Handler
import android.os.Looper
import android.text.Editable
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
import com.aegis.ime.ime.EditorUndoHistory
import com.aegis.ime.ime.InputView
import com.aegis.ime.ime.KeyboardController
import com.aegis.ime.ime.LayoutChoice
import com.aegis.ime.user.ClipboardStore
import java.time.Duration
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowClipboardManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AegisEditCombinationTest {
    @Implements(ClipboardManager::class)
    class RefusingLargeClipboard : ShadowClipboardManager() {
        var refusedWrites = 0
        @Implementation override fun setPrimaryClip(clip: ClipData) {
            if (clip.itemCount > 0 && (clip.getItemAt(0).text?.length ?: 0) > ClipboardStore.BIG_THRESHOLD) {
                refusedWrites++
                throw IllegalStateException("clipboard transaction exceeds its text limit")
            }
            super.setPrimaryClip(clip)
        }
    }

    private enum class ExtractionMode { FULL, PARTIAL, OFFSET }

    private class Connection(val view: View, val knownOffset: Boolean, val webProjection: Boolean = false,
        val extractionMode: ExtractionMode = ExtractionMode.FULL) : BaseInputConnection(view, true) {
        override fun getEditable(): Editable = requireNotNull(super.getEditable())
        var notify: (() -> Unit)? = null
        var observeCalls = false
        var reads = 0
        val selectionRequests = ArrayList<Pair<Int, Int>>()
        val keyEvents = ArrayList<KeyEvent>()
        val contextMenuActions = ArrayList<Int>()
        var collapseMenuSelectionLater = false
        var requiresShiftKeyDown = false
        var shiftKeyDown = false
            private set
        var keyEventResponse: ((KeyEvent) -> Boolean?)? = null
        val clipboard = view.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        override fun setSelection(start: Int, end: Int): Boolean {
            if (observeCalls) selectionRequests.add(start to end)
            return super.setSelection(start, end).also { notify?.invoke() }
        }
        override fun commitText(text: CharSequence?, position: Int): Boolean =
            if (text != null && text.length > 65_536) false
            else super.commitText(text, position).also { notify?.invoke() }
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean = super.deleteSurroundingText(beforeLength, afterLength).also { notify?.invoke() }
        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
            super.deleteSurroundingTextInCodePoints(beforeLength, afterLength).also { notify?.invoke() }
        private fun projection(): SurroundingText {
            val model = editable.toString()
            val text = StringBuilder()
            val positions = IntArray(model.length + 1)
            var offset = 0
            val lines = model.split('\n')
            for ((index, line) in lines.withIndex()) {
                text.append(index + 1).append('\n')
                for (character in line) {
                    positions[offset++] = text.length
                    text.append(character)
                }
                positions[offset] = text.length
                if (line.isEmpty()) text.append('\u200b')
                if (index < lines.lastIndex) { text.append('\n'); offset++ }
            }
            return SurroundingText(text.toString(), positions[Selection.getSelectionStart(editable)],
                positions[Selection.getSelectionEnd(editable)], 0)
        }
        override fun getSelectedText(flags: Int): CharSequence? {
            if (observeCalls) reads++
            return super.getSelectedText(flags)?.takeIf { it.length <= 65_536 }
        }
        override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence? {
            if (observeCalls) reads++
            if (!webProjection) return super.getTextBeforeCursor(length, flags)?.takeIf { it.length <= 65_536 }
            val value = projection()
            val end = minOf(value.selectionStart, value.selectionEnd)
            return value.text.subSequence(maxOf(0, end - length), end)
        }
        override fun getTextAfterCursor(length: Int, flags: Int): CharSequence? {
            if (observeCalls) reads++
            if (!webProjection) return super.getTextAfterCursor(length, flags)?.takeIf { it.length <= 65_536 }
            val value = projection()
            val start = maxOf(value.selectionStart, value.selectionEnd)
            return value.text.subSequence(start, minOf(value.text.length, start + length))
        }
        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? {
            if (observeCalls) reads++
            if (editable.length > 65_536 || extractionMode != ExtractionMode.FULL && editable.length < 4) return null
            val value = if (webProjection) projection() else SurroundingText(editable.toString(),
                Selection.getSelectionStart(editable), Selection.getSelectionEnd(editable), 0)
            return ExtractedText().also {
                it.text = value.text
                it.startOffset = 0
                it.partialStartOffset = -1
                it.partialEndOffset = -1
                it.selectionStart = value.selectionStart
                it.selectionEnd = value.selectionEnd
                when (extractionMode) {
                    ExtractionMode.FULL -> Unit
                    ExtractionMode.PARTIAL -> {
                        it.text = value.text.subSequence(2, value.text.length - 2)
                        it.partialStartOffset = 2
                        it.partialEndOffset = value.text.length - 2
                    }
                    ExtractionMode.OFFSET -> {
                        it.text = value.text.subSequence(2, value.text.length)
                        it.startOffset = 2
                        it.selectionStart -= 2
                        it.selectionEnd -= 2
                    }
                }
            }
        }
        override fun getSurroundingText(before: Int, after: Int, flags: Int): SurroundingText? {
            if (observeCalls) reads++
            val value = if (webProjection) projection() else super.getSurroundingText(before, after, flags) ?: return null
            if (value.text.length > 65_536) return null
            return if (knownOffset) value else SurroundingText(value.text, value.selectionStart, value.selectionEnd, -1)
        }
        override fun performContextMenuAction(id: Int): Boolean {
            if (observeCalls) contextMenuActions.add(id)
            return when (id) {
                android.R.id.selectAll -> setSelection(0, editable.length).also {
                    if (collapseMenuSelectionLater) Handler(Looper.getMainLooper()).postDelayed({
                        Selection.setSelection(editable, editable.length, editable.length)
                        notify?.invoke()
                    }, 100L)
                }
                android.R.id.copy, android.R.id.cut -> {
                    val from = minOf(Selection.getSelectionStart(editable), Selection.getSelectionEnd(editable))
                    val to = maxOf(Selection.getSelectionStart(editable), Selection.getSelectionEnd(editable))
                    if (from == to) false else {
                        clipboard.setPrimaryClip(ClipData.newPlainText("selected", editable.subSequence(from, to).toString()))
                        if (id == android.R.id.cut) commitText("", 1)
                        true
                    }
                }
                else -> false
            }
        }
        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (observeCalls) keyEvents.add(KeyEvent(event))
            if (event.keyCode == KeyEvent.KEYCODE_SHIFT_LEFT) shiftKeyDown = event.action == KeyEvent.ACTION_DOWN
            keyEventResponse?.invoke(event)?.let { return it }
            if (event.keyCode == KeyEvent.KEYCODE_SHIFT_LEFT) return true
            if (event.action != KeyEvent.ACTION_DOWN) return true
            val start = Selection.getSelectionStart(editable)
            val end = Selection.getSelectionEnd(editable)
            val shifted = if (requiresShiftKeyDown) shiftKeyDown else event.isShiftPressed
            when {
                event.isCtrlPressed && event.keyCode == KeyEvent.KEYCODE_A -> setSelection(0, editable.length)
                event.keyCode == KeyEvent.KEYCODE_MOVE_HOME -> setSelection(if (event.isShiftPressed) start else 0, 0)
                event.keyCode == KeyEvent.KEYCODE_MOVE_END -> setSelection(if (event.isShiftPressed) start else editable.length, editable.length)
                event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN) -> {
                    val move = when (event.keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> EditAction.LEFT
                        KeyEvent.KEYCODE_DPAD_RIGHT -> EditAction.RIGHT
                        KeyEvent.KEYCODE_DPAD_UP -> EditAction.UP
                        else -> EditAction.DOWN
                    }
                    val low = minOf(start, end)
                    val high = maxOf(start, end)
                    val origin = if (shifted) end else if (move in listOf(EditAction.LEFT, EditAction.UP)) low else high
                    val next = if (low != high && !shifted && move in listOf(EditAction.LEFT, EditAction.RIGHT)) origin
                        else moved(editable.toString(), origin, move)
                    if (webProjection || requiresShiftKeyDown) Selection.setSelection(editable, if (shifted) start else next, next)
                    else setSelection(if (shifted) start else next, next)
                }
                else -> TextKeyListener.getInstance().onKeyDown(view, editable, event.keyCode, event)
            }
            notify?.invoke()
            return true
        }
    }

    private class Fixture(val choice: LayoutChoice, val knownOffset: Boolean, webEditor: Boolean = false,
        extractionMode: ExtractionMode = ExtractionMode.FULL) {
        val service = Robolectric.buildService(AegisInputMethodService::class.java).get()
        val controller = KeyboardController(service, object : CandidateEngine {
            override fun candidates(composing: String, t9: Boolean): List<String> = emptyList()
        }, null)
        val connection = Connection(View(service), knownOffset, webEditor, extractionMode)
        val info = EditorInfo().apply {
            packageName = "com.example.combinations"
            fieldId = 62
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                (if (webEditor) InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT else 0)
            initialSelStart = 0
            initialSelEnd = 0
        }
        val clipboard = connection.clipboard
        val clips get() = (field("clipboardStore\$delegate") as Lazy<*>).value as ClipboardStore
        val undo get() = field("editorUndo") as EditorUndoHistory
        val panel get() = field("editPanelView") as EditPanelView
        fun field(name: String): Any? = service.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(service)
        fun call(name: String) { service.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(service) }
        init {
            service.getSharedPreferences("aegis", Context.MODE_PRIVATE).edit().putBoolean("clip_history", false).commit()
            service.javaClass.getDeclaredField("controller").apply { isAccessible = true; set(service, controller) }
            val framework = service.javaClass.superclass!!
            framework.getDeclaredField("mInputEditorInfo").apply { isAccessible = true; set(service, info) }
            service.onStartInput(info, false)
            service.onCreateInputView()
            service.onStartInputView(info, false)
            for (name in listOf("mInputConnection", "mStartedInputConnection")) {
                framework.getDeclaredField(name).apply { isAccessible = true; set(service, connection) }
            }
            controller.applyLayoutChoice(choice)
            connection.notify = {
                val start = Selection.getSelectionStart(connection.editable)
                val end = Selection.getSelectionEnd(connection.editable)
                Handler(Looper.getMainLooper()).post { service.onUpdateSelection(-1, -1, start, end, -1, -1) }
            }
            call("showEditPanel")
        }
        fun settle() { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10)) }
        fun reset(text: String, from: Int, to: Int, clipboardText: String) {
            call("stopSelecting")
            undo.clear()
            connection.editable.replace(0, connection.editable.length, text)
            connection.setSelection(from, to)
            clipboard.setPrimaryClip(ClipData.newPlainText("fixture", clipboardText))
            settle()
            undo.clear()
            if (!(field("inputView") as InputView).isPanelShowing(panel)) call("showEditPanel")
        }
        fun dispatch(action: EditAction) {
            if (!(field("inputView") as InputView).isPanelShowing(panel)) call("showEditPanel")
            service.javaClass.getDeclaredMethod("handleEdit", EditAction::class.java).apply { isAccessible = true }.invoke(service, action)
        }
        fun edit(action: EditAction) { dispatch(action); settle() }
        fun text() = connection.editable.toString()
        fun undoEnabled() = requireNotNull(panel.actionViewForTest(EditAction.UNDO)).isEnabled
        fun panelShowing() = (field("inputView") as InputView).isPanelShowing(panel)
    }

    private data class State(val text: String, val from: Int, val to: Int)

    companion object {
        private fun moved(text: String, position: Int, action: EditAction): Int {
            val caret = position.coerceIn(0, text.length)
            return when (action) {
                EditAction.LEFT, EditAction.RIGHT -> {
                    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text) }
                    val next = if (action == EditAction.LEFT) iterator.preceding(caret) else iterator.following(caret)
                    if (next == BreakIterator.DONE) caret else next
                }
                EditAction.HOME -> 0
                EditAction.END -> text.length
                EditAction.UP, EditAction.DOWN -> {
                    val beginning = if (caret == 0) 0 else text.lastIndexOf('\n', caret - 1) + 1
                    val ending = text.indexOf('\n', caret).takeIf { it >= 0 } ?: text.length
                    val column = caret - beginning
                    if (action == EditAction.UP) {
                        if (beginning == 0) 0 else {
                            val previous = if (beginning <= 1) 0 else text.lastIndexOf('\n', beginning - 2) + 1
                            minOf(previous + column, beginning - 1)
                        }
                    } else if (ending == text.length) text.length else {
                        val nextEnd = text.indexOf('\n', ending + 1).takeIf { it >= 0 } ?: text.length
                        minOf(ending + 1 + column, nextEnd)
                    }
                }
                else -> caret
            }
        }
    }

    private fun assertSelection(f: Fixture, expected: Pair<Int, Int>, message: String) {
        val actual = Selection.getSelectionStart(f.connection.editable) to Selection.getSelectionEnd(f.connection.editable)
        assertEquals("$message selection start", minOf(expected.first, expected.second), minOf(actual.first, actual.second))
        assertEquals("$message selection end", maxOf(expected.first, expected.second), maxOf(actual.first, actual.second))
    }

    private fun exercise(f: Fixture, original: String, from: Int, to: Int, actions: List<EditAction>, context: String,
        clipboardText: String = "paste") {
        f.reset(original, from, to, clipboardText)
        val history = ArrayList<State>()
        var selecting = false
        var anchor = from
        var moving = to
        for ((index, action) in actions.withIndex()) {
            val before = f.text()
            val start = Selection.getSelectionStart(f.connection.editable)
            val end = Selection.getSelectionEnd(f.connection.editable)
            val low = minOf(start, end)
            val high = maxOf(start, end)
            val clip = f.clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
            val previous = if (action == EditAction.UNDO) history.removeLastOrNull() else null
            val expected = when (action) {
                EditAction.UNDO -> previous?.text ?: before
                EditAction.CUT -> if (low != high) before.removeRange(low, high) else before
                EditAction.TAB -> before.replaceRange(low, high, "\t")
                EditAction.PASTE -> before.replaceRange(low, high, clip)
                EditAction.DELETE -> if (low != high) before.removeRange(low, high)
                    else before.removeRange(moved(before, low, EditAction.LEFT), low)
                EditAction.FORWARD_DELETE -> if (low != high) before.removeRange(low, high)
                    else before.removeRange(high, moved(before, high, EditAction.RIGHT))
                else -> before
            }
            val expectedSelection = when (action) {
                EditAction.UNDO -> previous?.let { it.from to it.to } ?: (start to end)
                EditAction.CUT -> if (low != high) low to low else start to end
                EditAction.TAB -> (low + 1) to (low + 1)
                EditAction.PASTE -> (low + clip.length) to (low + clip.length)
                EditAction.DELETE -> (if (low != high) low else moved(before, low, EditAction.LEFT)).let { it to it }
                EditAction.FORWARD_DELETE -> low to low
                EditAction.SELECT_ALL -> 0 to before.length
                EditAction.UP, EditAction.DOWN, EditAction.LEFT, EditAction.RIGHT, EditAction.HOME, EditAction.END -> {
                    if (selecting) {
                        moving = moved(before, moving, action)
                        anchor to moving
                    } else {
                        val origin = if (action in listOf(EditAction.LEFT, EditAction.UP, EditAction.HOME)) low else high
                        val next = if (low != high && action in listOf(EditAction.LEFT, EditAction.RIGHT)) origin
                            else moved(before, origin, action)
                        next to next
                    }
                }
                else -> start to end
            }
            if (action == EditAction.START_SELECT) {
                selecting = !selecting
                if (selecting) { anchor = start; moving = end }
            } else if (action in listOf(EditAction.TAB, EditAction.DELETE, EditAction.UNDO, EditAction.FORWARD_DELETE,
                    EditAction.SELECT_ALL, EditAction.COPY, EditAction.CUT, EditAction.PASTE, EditAction.BACK)) selecting = false
            if (action != EditAction.UNDO && expected != before) history.add(State(before, start, end))
            f.edit(action)
            val message = "$context step=$index $action"
            assertTrue("$message exact text expectedLength=${expected.length} actualLength=${f.text().length}", expected == f.text())
            assertSelection(f, expectedSelection, message)
            assertEquals("$message selection mode", selecting, f.field("selecting"))
            val expectedClip = if (action in listOf(EditAction.COPY, EditAction.CUT) && low != high) before.substring(low, high) else clip
            assertTrue("$message clipboard content", expectedClip == f.clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty())
            assertEquals("$message panel visibility", action != EditAction.BACK, f.panelShowing())
            assertEquals("$message Undo availability", history.isNotEmpty(), f.undoEnabled())
        }
        while (history.isNotEmpty()) {
            val expected = history.removeLast()
            assertTrue("$context pending Undo", f.undoEnabled())
            f.edit(EditAction.UNDO)
            assertTrue("$context complete undo expectedLength=${expected.text.length} actualLength=${f.text().length}", expected.text == f.text())
            assertSelection(f, expected.from to expected.to, "$context complete undo")
            assertFalse("$context undo stops selection mode", f.field("selecting") as Boolean)
        }
        assertTrue("$context returns original", original == f.text())
        assertFalse("$context exhausts only this history", f.undoEnabled())
    }

    private fun report(scope: String, cases: Int, failures: List<String>) {
        val content = "cases=$cases failures=${failures.size}\n" + failures.joinToString("\n")
        System.getenv("AEGIS_COMBINATION_REPORT")?.let { path ->
            val base = java.io.File(path)
            val output = if (scope == "pairs") base else java.io.File(base.parentFile, "${base.nameWithoutExtension}-$scope.txt")
            output.writeText(content)
        }
        assertTrue("$scope ${failures.size}/$cases failures\n" + failures.take(40).joinToString("\n"), failures.isEmpty())
    }

    @Test fun all_ordered_panel_action_pairs_preserve_expected_text_and_undo() {
        val failures = ArrayList<String>()
        var cases = 0
        val small = "abc def\nghi jkl\nmnop\n"
        val long = small.repeat(7000)
        for (knownOffset in listOf(true, false)) for (choice in listOf(LayoutChoice.CN_ALPHA, LayoutChoice.CN_NINE)) {
            val f = Fixture(choice, knownOffset)
            try {
                for (text in listOf("", small, long)) {
                    val ranges = if (text.isEmpty()) listOf(0 to 0) else listOf(0 to 0, text.length to text.length, 4 to 4, 1 to 6, 0 to text.length, text.length to 0)
                    for ((from, to) in ranges) for (first in EditAction.entries) for (second in EditAction.entries) {
                        cases++
                        try {
                            exercise(f, text, from, to, listOf(first, second), "$choice offset=$knownOffset length=${text.length} $from..$to $first/$second")
                        } catch (error: AssertionError) { failures.add(error.message.orEmpty()) }
                    }
                }
            } finally { f.service.onFinishInput() }
        }
        report("pairs", cases, failures)
    }

    @Test fun destructive_chains_restore_every_intermediate_document() {
        val failures = ArrayList<String>()
        var cases = 0
        val text = "abc def\nghi jkl\nmnop\n".repeat(7000)
        for (knownOffset in listOf(true, false)) for (choice in listOf(LayoutChoice.CN_ALPHA, LayoutChoice.CN_NINE)) {
            val f = Fixture(choice, knownOffset)
            try {
                for (action in listOf(EditAction.DELETE, EditAction.FORWARD_DELETE, EditAction.TAB, EditAction.PASTE, EditAction.CUT)) {
                    cases++
                    try {
                        exercise(f, text, 0, text.length, listOf(action, EditAction.SELECT_ALL, EditAction.CUT, EditAction.PASTE,
                            EditAction.SELECT_ALL, EditAction.DELETE, EditAction.PASTE, EditAction.HOME, EditAction.RIGHT,
                            EditAction.TAB, EditAction.BACK), "$choice offset=$knownOffset long chain $action")
                    } catch (error: AssertionError) { failures.add(error.message.orEmpty()) }
                }
                for (value in listOf("abc def\nghi jkl\nmnop\n", text)) {
                    cases++
                    try {
                        exercise(f, value, 4, 4, listOf(EditAction.START_SELECT, EditAction.RIGHT, EditAction.DOWN, EditAction.LEFT,
                            EditAction.UP, EditAction.RIGHT, EditAction.COPY, EditAction.CUT, EditAction.PASTE, EditAction.TAB, EditAction.UNDO,
                            EditAction.UNDO, EditAction.UNDO, EditAction.HOME, EditAction.START_SELECT, EditAction.END,
                            EditAction.COPY, EditAction.START_SELECT, EditAction.HOME, EditAction.START_SELECT, EditAction.END,
                            EditAction.DELETE, EditAction.BACK), "$choice offset=$knownOffset select chain length=${value.length}")
                    } catch (error: AssertionError) { failures.add(error.message.orEmpty()) }
                }
            } finally { f.service.onFinishInput() }
        }
        report("chains", cases, failures)
    }

    @Test fun medium_and_chunk_boundary_replacements_preserve_selection_clipboard_and_undo() {
        val failures = ArrayList<String>()
        var cases = 0
        val text = "left\n" + "a".repeat(90_000) + "\ncenter\n" + "b".repeat(90_000) + "\nright\n"
        val replacements = listOf(40_000 to 40_000, 65_535 to 65_535, 65_536 to 65_536, 65_537 to 65_537,
            65_537 to 40_000, 40_000 to 65_537)
        for (knownOffset in listOf(true, false)) for (choice in listOf(LayoutChoice.CN_ALPHA, LayoutChoice.CN_NINE)) {
            val f = Fixture(choice, knownOffset)
            try {
                for ((removedLength, pastedLength) in replacements) for (reverse in listOf(false, true)) {
                    val low = 5
                    val high = low + removedLength
                    val from = if (reverse) high else low
                    val to = if (reverse) low else high
                    for (action in listOf(EditAction.DELETE, EditAction.FORWARD_DELETE, EditAction.TAB, EditAction.CUT, EditAction.PASTE)) {
                        cases++
                        try {
                            exercise(f, text, from, to, listOf(action, EditAction.SELECT_ALL, EditAction.COPY, EditAction.BACK,
                                EditAction.UNDO), "$choice offset=$knownOffset $from..$to $action clipboardLength=$pastedLength",
                                "P".repeat(pastedLength))
                        } catch (error: AssertionError) { failures.add(error.message.orEmpty()) }
                    }
                }
                val unicode = "left\n" + "x".repeat(65_402) + "😀\r\ny".repeat(20_000) + "\nright"
                for (action in listOf(EditAction.DELETE, EditAction.FORWARD_DELETE, EditAction.TAB, EditAction.CUT, EditAction.PASTE)) {
                    cases++
                    try {
                        exercise(f, unicode, 0, unicode.length, listOf(action, EditAction.UNDO),
                            "$choice offset=$knownOffset surrogate replay $action", "q".repeat(65_535) + "😀\r\n")
                    } catch (error: AssertionError) { failures.add(error.message.orEmpty()) }
                }
            } finally { f.service.onFinishInput() }
        }
        report("replacements", cases, failures)
    }

    @Test fun web_navigation_delegates_to_the_model_without_mapping_projected_dom_offsets() {
        val failures = ArrayList<String>()
        var cases = 0
        val text = "prefix two words\nline one\n\nline two suffix\ntail\n"
        val directions = listOf(EditAction.UP to KeyEvent.KEYCODE_DPAD_UP, EditAction.DOWN to KeyEvent.KEYCODE_DPAD_DOWN,
            EditAction.LEFT to KeyEvent.KEYCODE_DPAD_LEFT, EditAction.RIGHT to KeyEvent.KEYCODE_DPAD_RIGHT)
        for (web in listOf(true, false)) for (choice in listOf(LayoutChoice.CN_ALPHA, LayoutChoice.CN_NINE)) {
            val f = Fixture(choice, true, webEditor = web)
            try {
                for (selecting in listOf(false, true)) for ((action, keyCode) in directions) {
                    cases++
                    val context = "$choice web=$web selecting=$selecting $action"
                    try {
                        f.connection.observeCalls = false
                        f.reset(text, 4, 4, "paste")
                        if (selecting) f.edit(EditAction.START_SELECT)
                        val exposed = requireNotNull(f.connection.getSurroundingText(1024, 1024, 0))
                        if (web) {
                            assertNotEquals("$context projection differs from model", text, exposed.text.toString())
                            assertNotEquals("$context projection caret differs from model", 4, exposed.selectionStart)
                        }
                        f.connection.selectionRequests.clear()
                        f.connection.keyEvents.clear()
                        f.connection.reads = 0
                        f.connection.observeCalls = true
                        f.dispatch(action)
                        if (web) {
                            assertEquals("$context has one native key pair", listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP),
                                f.connection.keyEvents.map { it.action })
                            assertTrue("$context dispatches correct direction", f.connection.keyEvents.all { it.keyCode == keyCode })
                            val meta = if (selecting) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
                            assertTrue("$context dispatches exact selection modifier", f.connection.keyEvents.all { it.metaState == meta })
                            assertTrue("$context never maps raw selection", f.connection.selectionRequests.isEmpty())
                            assertEquals("$context does not read raw text to move", 0, f.connection.reads)
                        } else {
                            assertTrue("$context retains direct cursor navigation", f.connection.keyEvents.isEmpty())
                            assertEquals("$context sets one native selection", 1, f.connection.selectionRequests.size)
                            assertTrue("$context reads native cursor context", f.connection.reads > 0)
                        }
                        f.settle()
                        assertEquals("$context preserves document", text, f.text())
                        val next = moved(text, 4, action)
                        assertSelection(f, if (selecting) 4 to next else next to next, context)
                        assertEquals("$context retains selection mode", selecting, f.field("selecting"))
                        if (web) assertTrue("$context never maps raw selection after callbacks", f.connection.selectionRequests.isEmpty())
                        assertFalse("$context navigation creates no Undo entry", f.undoEnabled())
                    } catch (error: AssertionError) { failures.add(error.message.orEmpty()) }
                }
            } finally { f.service.onFinishInput() }
        }
        report("navigation", cases, failures)
    }

    @Test fun web_select_all_uses_one_model_command_and_remains_selected_when_repeated() {
        val text = "prefix words\n\nline two\n".repeat(3000).take(49_152)
        val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        for (web in listOf(true, false)) for (choice in listOf(LayoutChoice.CN_ALPHA, LayoutChoice.CN_NINE)) {
            val f = Fixture(choice, true, webEditor = web)
            try {
                f.connection.collapseMenuSelectionLater = web
                if (web) {
                    f.reset(text, 0, text.length, "paste")
                    f.connection.performContextMenuAction(android.R.id.selectAll)
                    for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                        f.connection.sendKeyEvent(KeyEvent(0, 0, action, KeyEvent.KEYCODE_A, 0, meta))
                    }
                    f.settle()
                    assertSelection(f, text.length to text.length, "$choice menu selection overrides a later Ctrl+A asynchronously")
                }
                for ((start, end) in listOf(4 to 4, 1 to 6, 0 to text.length, text.length to 0)) {
                    f.connection.observeCalls = false
                    f.reset(text, start, end, "paste")
                    for (attempt in 1..2) {
                        val context = "$choice web=$web $start..$end Select all attempt=$attempt"
                        f.connection.contextMenuActions.clear()
                        f.connection.keyEvents.clear()
                        f.connection.observeCalls = true
                        f.edit(EditAction.SELECT_ALL)
                        assertEquals("$context only native editors use menu selection",
                            if (web) emptyList<Int>() else listOf(android.R.id.selectAll), f.connection.contextMenuActions)
                        assertEquals("$context sends exactly one Ctrl+A pair",
                            listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), f.connection.keyEvents.map { it.action })
                        assertTrue("$context uses the model shortcut", f.connection.keyEvents.all {
                            it.keyCode == KeyEvent.KEYCODE_A && it.metaState == meta
                        })
                        assertEquals("$context preserves the full document", text, f.text())
                        assertSelection(f, 0 to text.length, context)
                        assertFalse("$context stops selection mode", f.field("selecting") as Boolean)
                        assertFalse("$context adds no Undo entry", f.undoEnabled())
                    }
                }
            } finally { f.service.onFinishInput() }
        }
    }

    @Test fun native_large_selection_navigation_holds_shift_for_the_whole_key_pair() {
        val text = "abc def\nghi jkl\nmnop\n".repeat(7000)
        val directions = listOf(EditAction.UP to KeyEvent.KEYCODE_DPAD_UP, EditAction.DOWN to KeyEvent.KEYCODE_DPAD_DOWN,
            EditAction.LEFT to KeyEvent.KEYCODE_DPAD_LEFT, EditAction.RIGHT to KeyEvent.KEYCODE_DPAD_RIGHT)
        for (choice in listOf(LayoutChoice.CN_ALPHA, LayoutChoice.CN_NINE)) {
            val f = Fixture(choice, true)
            try {
                f.connection.requiresShiftKeyDown = true
                f.reset(text, 0, text.length, "paste")
                val meta = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
                for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                    f.connection.sendKeyEvent(KeyEvent(0, 0, action, KeyEvent.KEYCODE_DPAD_LEFT, 0, meta))
                }
                assertSelection(f, 0 to 0, "$choice modifier flags alone collapse the host selection")
                for (reverse in listOf(false, true)) for (selecting in listOf(false, true)) for ((action, code) in directions) {
                    val from = if (reverse) text.length else 0
                    val to = if (reverse) 0 else text.length
                    val context = "$choice $from..$to selecting=$selecting $action"
                    f.connection.observeCalls = false
                    f.reset(text, from, to, "paste")
                    f.connection.selectionRequests.clear()
                    f.connection.keyEvents.clear()
                    f.connection.reads = 0
                    f.connection.observeCalls = true
                    if (selecting) {
                        f.dispatch(EditAction.START_SELECT)
                        assertEquals("$context toggling selection does not read the large selection", 0, f.connection.reads)
                        assertEquals("$context keeps the tracked anchor", from, f.field("selAnchor"))
                        assertEquals("$context keeps the tracked moving end", to, f.field("selMoving"))
                    }
                    f.dispatch(action)
                    val expectedEvents = if (selecting) listOf(
                        KeyEvent.KEYCODE_SHIFT_LEFT to KeyEvent.ACTION_DOWN, code to KeyEvent.ACTION_DOWN,
                        code to KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT to KeyEvent.ACTION_UP,
                    ) else listOf(code to KeyEvent.ACTION_DOWN, code to KeyEvent.ACTION_UP)
                    assertEquals("$context key lifecycle", expectedEvents, f.connection.keyEvents.map { it.keyCode to it.action })
                    val expectedMeta = if (selecting) listOf(meta, meta, meta, 0) else listOf(0, 0)
                    assertEquals("$context modifier state", expectedMeta, f.connection.keyEvents.map { it.metaState })
                    assertEquals("$context does not read the large selection to move", 0, f.connection.reads)
                    assertTrue("$context never maps the host selection", f.connection.selectionRequests.isEmpty())
                    assertFalse("$context releases Shift", f.connection.shiftKeyDown)
                    f.settle()
                    assertEquals("$context preserves the document", text, f.text())
                    val origin = if (selecting) to else if (action in listOf(EditAction.LEFT, EditAction.UP)) 0 else text.length
                    val next = if (!selecting && action in listOf(EditAction.LEFT, EditAction.RIGHT)) origin else moved(text, origin, action)
                    assertEquals("$context anchor", if (selecting) from else next, Selection.getSelectionStart(f.connection.editable))
                    assertEquals("$context moving end", next, Selection.getSelectionEnd(f.connection.editable))
                    assertEquals("$context selection mode", selecting, f.field("selecting"))
                    assertFalse("$context navigation creates no Undo entry", f.undoEnabled())
                }
            } finally { f.service.onFinishInput() }
        }
    }

    @Test fun native_navigation_releases_shift_when_event_delivery_fails() {
        val text = "abc def\nghi jkl\nmnop\n".repeat(7000)
        val shiftDown = KeyEvent.KEYCODE_SHIFT_LEFT to KeyEvent.ACTION_DOWN
        val leftDown = KeyEvent.KEYCODE_DPAD_LEFT to KeyEvent.ACTION_DOWN
        val leftUp = KeyEvent.KEYCODE_DPAD_LEFT to KeyEvent.ACTION_UP
        val shiftUp = KeyEvent.KEYCODE_SHIFT_LEFT to KeyEvent.ACTION_UP
        for (choice in listOf(LayoutChoice.CN_ALPHA, LayoutChoice.CN_NINE)) {
            val f = Fixture(choice, true)
            try {
                f.connection.requiresShiftKeyDown = true
                for (failedEvent in listOf(shiftDown, leftDown, leftUp)) for (throws in listOf(false, true)) {
                    val context = "$choice failedEvent=$failedEvent throws=$throws"
                    f.connection.observeCalls = false
                    f.reset(text, 0, text.length, "paste")
                    f.edit(EditAction.START_SELECT)
                    f.connection.keyEvents.clear()
                    f.connection.observeCalls = true
                    val failure = IllegalStateException("event delivery failed")
                    f.connection.keyEventResponse = { event ->
                        if ((event.keyCode to event.action) != failedEvent) null
                        else if (throws) throw failure else false
                    }
                    try {
                        if (throws) {
                            val error = assertThrows(java.lang.reflect.InvocationTargetException::class.java) { f.dispatch(EditAction.LEFT) }
                            assertSame("$context propagates the delivery failure", failure, error.cause)
                        } else f.dispatch(EditAction.LEFT)
                    } finally { f.connection.keyEventResponse = null }
                    val expectedEvents = if (throws && failedEvent == shiftDown) listOf(shiftDown, shiftUp)
                        else listOf(shiftDown, leftDown, leftUp, shiftUp)
                    assertEquals("$context still sends key releases", expectedEvents, f.connection.keyEvents.map { it.keyCode to it.action })
                    assertEquals("$context clears the release modifier", 0, f.connection.keyEvents.last().metaState)
                    assertFalse("$context cannot leave Shift held", f.connection.shiftKeyDown)
                    assertEquals("$context preserves the document", text, f.text())
                    f.settle()
                    assertFalse("$context failed navigation creates no Undo entry", f.undoEnabled())
                }
            } finally { f.service.onFinishInput() }
        }
    }

    @Test fun whitespace_system_clipboard_overrides_stale_text_history() {
        val original = "leftright"
        val stale = "old copied text"
        val blanks = listOf(" ", "\t", "\n", "\n\n", " \t\r\n", "\u2003\t\n")
        for (choice in listOf(LayoutChoice.CN_ALPHA, LayoutChoice.CN_NINE)) {
            val f = Fixture(choice, true)
            try {
                f.service.getSharedPreferences("aegis", Context.MODE_PRIVATE).edit().putBoolean("clip_history", true).commit()
                for (blank in blanks) {
                    val context = "$choice clipboard=${blank.map { it.code }}"
                    f.reset(original, 4, 4, blank)
                    f.clips.record(stale)
                    f.call("onSystemClipChanged")
                    assertEquals("$context history still has the older text", stale, f.clips.latestEntry()?.body())
                    assertEquals("$context system clipboard has the current whitespace", blank, f.clipboard.primaryClip?.getItemAt(0)?.text?.toString())
                    f.edit(EditAction.PASTE)
                    assertEquals("$context pastes exact system whitespace", "left${blank}right", f.text())
                    assertSelection(f, (4 + blank.length) to (4 + blank.length), context)
                    assertTrue("$context paste can be undone", f.undoEnabled())
                    f.edit(EditAction.UNDO)
                    assertEquals("$context Undo restores the document", original, f.text())
                    assertSelection(f, 4 to 4, "$context Undo")
                    assertFalse("$context Undo is exhausted", f.undoEnabled())
                    exercise(f, "left${blank}right", 4, 4 + blank.length,
                        listOf(EditAction.CUT, EditAction.PASTE, EditAction.UNDO, EditAction.UNDO), "$context cut/paste", stale)
                    assertEquals("$context cut whitespace did not replace the older history entry", stale, f.clips.latestEntry()?.body())
                }
            } finally {
                f.service.getSharedPreferences("aegis", Context.MODE_PRIVATE).edit().putBoolean("clip_history", false).commit()
                f.service.onFinishInput()
            }
        }
    }

    @Test fun nonblank_system_clipboard_preserves_the_local_large_text_fallback() {
        val original = "leftright"
        val large = "p".repeat(ClipboardStore.BIG_THRESHOLD + 1)
        for (choice in listOf(LayoutChoice.CN_ALPHA, LayoutChoice.CN_NINE)) {
            val f = Fixture(choice, true)
            try {
                f.reset(original, 4, 4, "old system clipboard")
                f.service.getSharedPreferences("aegis", Context.MODE_PRIVATE).edit().putBoolean("clip_history", true).commit()
                f.clips.record(large)
                assertTrue("$choice keeps a local large clip", f.clips.latestEntry()?.key?.startsWith("B\t") == true)
                f.edit(EditAction.PASTE)
                assertEquals("$choice uses the local large clip", "left${large}right", f.text())
                assertSelection(f, (4 + large.length) to (4 + large.length), "$choice large paste")
                assertTrue("$choice local large paste can be undone", f.undoEnabled())
                f.edit(EditAction.UNDO)
                assertEquals("$choice Undo restores the original", original, f.text())
                assertSelection(f, 4 to 4, "$choice large paste Undo")
                assertFalse("$choice Undo is exhausted", f.undoEnabled())
            } finally {
                f.service.getSharedPreferences("aegis", Context.MODE_PRIVATE).edit().putBoolean("clip_history", false).commit()
                f.service.onFinishInput()
            }
        }
    }

    @Test
    @Config(shadows = [RefusingLargeClipboard::class])
    fun failed_large_cut_keeps_the_local_clip_while_the_system_clip_is_unchanged() {
        val large = "abcdefghij\n".repeat(13_000)
        for (choice in listOf(LayoutChoice.CN_ALPHA, LayoutChoice.CN_NINE)) {
            val f = Fixture(choice, true)
            try {
                f.service.getSharedPreferences("aegis", Context.MODE_PRIVATE).edit().putBoolean("clip_history", true).commit()
                val refusing = Shadow.extract<RefusingLargeClipboard>(f.clipboard)
                for (oldClip in listOf(" ", "\t", "\n\n", "old system text")) {
                    f.reset(large, 0, large.length, oldClip)
                    val previousWrites = refusing.refusedWrites
                    val previousTimestamp = requireNotNull(f.clipboard.primaryClip).description.timestamp
                    f.edit(EditAction.CUT)
                    assertEquals("$choice attempts the system publication", previousWrites + 1, refusing.refusedWrites)
                    assertEquals("$choice rejected publication keeps the previous text", oldClip, f.clipboard.primaryClip?.getItemAt(0)?.text?.toString())
                    assertEquals("$choice rejected publication keeps the previous identity", previousTimestamp, f.clipboard.primaryClip?.description?.timestamp)
                    assertEquals("$choice Cut removed the full selection", "", f.text())
                    assertEquals("$choice retains the large local copy", large, f.clips.latestEntry()?.body())
                    assertNotNull("$choice tracks the failed publication", f.field("unpublishedClipboard"))
                    val localOrder = f.clips.latestEntry()?.captureOrder
                    f.call("onSystemClipChanged")
                    f.settle()
                    f.call("onSystemClipChanged")
                    assertEquals("$choice delayed old callbacks preserve the local entry", localOrder, f.clips.latestEntry()?.captureOrder)
                    f.edit(EditAction.PASTE)
                    assertEquals("$choice pastes the just-cut text despite the older system clip", large, f.text())
                    f.edit(EditAction.UNDO)
                    assertEquals("$choice Undo Paste restores the empty document", "", f.text())
                    assertSelection(f, 0 to 0, "$choice Undo Paste")
                    assertTrue("$choice Cut remains undoable", f.undoEnabled())
                    f.edit(EditAction.UNDO)
                    assertEquals("$choice Undo Cut restores the whole original", large, f.text())
                    assertSelection(f, 0 to large.length, "$choice Undo Cut")
                    assertFalse("$choice Undo is exhausted", f.undoEnabled())
                }
            } finally {
                f.service.getSharedPreferences("aegis", Context.MODE_PRIVATE).edit().putBoolean("clip_history", false).commit()
                f.service.onFinishInput()
            }
        }
    }

    @Test
    @Config(shadows = [RefusingLargeClipboard::class])
    fun changed_system_clip_or_local_entry_invalidates_the_failed_publication() {
        val large = "abcdefghij\n".repeat(13_000)
        val blank = " \t\n"
        for (choice in listOf(LayoutChoice.CN_ALPHA, LayoutChoice.CN_NINE)) {
            val f = Fixture(choice, true)
            try {
                f.service.getSharedPreferences("aegis", Context.MODE_PRIVATE).edit().putBoolean("clip_history", true).commit()
                for (change in listOf("system with callback", "system before callback", "local entry")) {
                    val context = "$choice $change"
                    f.reset(large, 0, large.length, blank)
                    f.edit(EditAction.COPY)
                    assertNotNull("$context tracks the failed Copy", f.field("unpublishedClipboard"))
                    f.edit(EditAction.HOME)
                    val timestamp = requireNotNull(f.clipboard.primaryClip).description.timestamp
                    if (change == "local entry") f.clips.record("new local entry") else {
                        f.clipboard.setPrimaryClip(ClipData.newPlainText("fixture", blank))
                        assertNotEquals("$context identical text still has a new system identity", timestamp, f.clipboard.primaryClip?.description?.timestamp)
                        if (change == "system with callback") f.call("onSystemClipChanged")
                    }
                    f.edit(EditAction.PASTE)
                    assertEquals("$context uses the current system whitespace", blank + large, f.text())
                    assertNull("$context discards the obsolete failed publication", f.field("unpublishedClipboard"))
                    f.edit(EditAction.UNDO)
                    assertEquals("$context Undo restores the document", large, f.text())
                    assertSelection(f, 0 to 0, "$context Undo")
                    assertFalse("$context Undo is exhausted", f.undoEnabled())
                }
            } finally {
                f.service.getSharedPreferences("aegis", Context.MODE_PRIVATE).edit().putBoolean("clip_history", false).commit()
                f.service.onFinishInput()
            }
        }
    }

    @Test
    @Config(shadows = [RefusingLargeClipboard::class])
    fun failed_panel_copy_keeps_the_local_large_clip_over_unchanged_system_whitespace() {
        val large = "abcdefghij\n".repeat(13_000)
        for (choice in listOf(LayoutChoice.CN_ALPHA, LayoutChoice.CN_NINE)) {
            val f = Fixture(choice, true)
            try {
                f.reset("leftright", 4, 4, "\n\n")
                f.service.getSharedPreferences("aegis", Context.MODE_PRIVATE).edit().putBoolean("clip_history", true).commit()
                val copied = f.service.javaClass.getDeclaredMethod("copyFromPanel", String::class.java, Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(f.service, large, R.string.edit_copy_done)
                assertEquals("$choice panel copy retains local content", true, copied)
                assertNotNull("$choice panel copy tracks the failed publication", f.field("unpublishedClipboard"))
                f.call("onSystemClipChanged")
                f.edit(EditAction.PASTE)
                assertEquals("$choice panel copy uses the local large clip", "left${large}right", f.text())
                f.edit(EditAction.UNDO)
                assertEquals("$choice Undo restores the original", "leftright", f.text())
                assertSelection(f, 4 to 4, "$choice Undo")
                assertFalse("$choice Undo is exhausted", f.undoEnabled())
            } finally {
                f.service.getSharedPreferences("aegis", Context.MODE_PRIVATE).edit().putBoolean("clip_history", false).commit()
                f.service.onFinishInput()
            }
        }
    }

    @Test fun partial_extractions_use_window_history_for_small_selected_edits() {
        val failures = ArrayList<String>()
        var cases = 0
        val text = "prefix alpha\nline one\nline two suffix\n"
        for (mode in listOf(ExtractionMode.PARTIAL, ExtractionMode.OFFSET)) {
            for (knownOffset in listOf(true, false)) for (choice in listOf(LayoutChoice.CN_ALPHA, LayoutChoice.CN_NINE)) {
                val f = Fixture(choice, knownOffset, extractionMode = mode)
                try {
                    for (action in listOf(EditAction.DELETE, EditAction.FORWARD_DELETE, EditAction.TAB, EditAction.CUT, EditAction.PASTE)) {
                        cases++
                        try {
                            exercise(f, text, 7, 11, listOf(action, EditAction.UNDO), "$choice offset=$knownOffset extraction=$mode $action")
                        } catch (error: AssertionError) { failures.add(error.message.orEmpty()) }
                    }
                } finally { f.service.onFinishInput() }
            }
        }
        report("partial-extractions", cases, failures)
    }
}
