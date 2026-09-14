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

import android.os.Handler
import android.os.Looper
import android.text.Selection
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.SurroundingText
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NativeEditorUndoHistoryTest {
    private data class Mutation(val start: Int, val end: Int, val text: String)
    private enum class MatrixEdit { INPUT, TAB, BACKSPACE, FORWARD_DELETE, CUT, PASTE }

    private class Editor(initial: String = "", val web: Boolean = true, val numbered: Boolean = false) :
        BaseInputConnection(View(RuntimeEnvironment.getApplication()), true) {
        var document = initial
            private set
        var mergeInputs = false
        var deferEdits = false
        var deferUndo = false
        var ignoreUndo = false
        var undoOverride: String? = null
        val observedDocuments = ArrayList<String>()
        val mutations = ArrayList<Mutation>()
        var undoCalls = 0
        var redoCalls = 0
        private var composing: Pair<Int, Int>? = null
        private val undo = ArrayDeque<String>()
        private val redo = ArrayDeque<String>()
        private val queue = ArrayDeque<() -> Unit>()
        private var lastOrigin: String? = null

        init { hold(initial) }

        fun raw(): String = requireNotNull(editable).toString()

        fun renderRaw(raw: String, caret: Int) {
            requireNotNull(editable).replace(0, requireNotNull(editable).length, raw)
            Selection.setSelection(editable, caret)
        }

        fun rawOffset(position: Int): Int {
            if (!web) return position
            var modelStart = 0
            var rawStart = if (numbered) 2 else 0
            for ((index, line) in document.split('\n').withIndex()) {
                if (position <= modelStart + line.length) return rawStart + position - modelStart
                modelStart += line.length + 1
                rawStart += maxOf(1, line.length) + 1 + if (numbered) (index + 2).toString().length + 1 else 0
            }
            return raw().length
        }

        private fun modelOffset(position: Int): Int =
            (0..document.length).lastOrNull { rawOffset(it) <= position } ?: 0

        fun hold(text: String, caret: Int = text.length) {
            document = text
            observedDocuments.add(text)
            val raw = if (web) text.split('\n').mapIndexed { index, line ->
                (if (numbered) "${index + 1}\n" else "") + line.ifEmpty { "\u200b" }
            }.joinToString("\n") + "\n" else text
            requireNotNull(editable).replace(0, requireNotNull(editable).length, raw)
            Selection.setSelection(editable, rawOffset(caret))
        }

        fun selectAll() { Selection.setSelection(editable, 0, rawOffset(document.length)) }

        private fun replace(text: String, compose: Boolean, separateHistory: Boolean = false, origin: String = "+input") {
            val selected = composing ?: (modelOffset(Selection.getSelectionStart(editable)) to
                modelOffset(Selection.getSelectionEnd(editable)))
            val from = minOf(selected.first, selected.second)
            val through = maxOf(selected.first, selected.second)
            val before = document
            mutations.add(Mutation(from, through, text))
            val changed = before.replaceRange(from, through, text)
            if (changed != before) {
                if (composing == null && (separateHistory || !mergeInputs || undo.isEmpty() || lastOrigin != origin)) undo.addLast(before)
                redo.clear()
                lastOrigin = origin
                hold(changed, from + text.length)
            }
            composing = if (compose) from to from + text.length else null
        }

        private fun edit(action: () -> Unit): Boolean {
            if (deferEdits) queue.addLast(action) else action()
            return true
        }

        fun flush() { while (queue.isNotEmpty()) queue.removeFirst().invoke() }

        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText = ExtractedText().apply {
            text = raw()
            startOffset = 0
            partialStartOffset = -1
            selectionStart = Selection.getSelectionStart(editable)
            selectionEnd = Selection.getSelectionEnd(editable)
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean = edit { replace(text.toString(), false) }

        override fun performContextMenuAction(id: Int): Boolean =
            if (id == android.R.id.cut) edit { replace("", false, separateHistory = true, origin = "cut") } else false

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean = delete(beforeLength, afterLength, false)

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean = delete(beforeLength, afterLength, true)

        private fun delete(beforeLength: Int, afterLength: Int, codePoints: Boolean): Boolean = edit {
            val first = modelOffset(minOf(Selection.getSelectionStart(editable), Selection.getSelectionEnd(editable)))
            val last = modelOffset(maxOf(Selection.getSelectionStart(editable), Selection.getSelectionEnd(editable)))
            val from = if (codePoints) Character.offsetByCodePoints(document, first,
                -minOf(beforeLength, Character.codePointCount(document, 0, first))) else maxOf(0, first - beforeLength)
            val through = if (codePoints) Character.offsetByCodePoints(document, last,
                minOf(afterLength, Character.codePointCount(document, last, document.length))) else minOf(document.length, last + afterLength)
            val selected = document.substring(first, last)
            Selection.setSelection(editable, rawOffset(from), rawOffset(through))
            replace(selected, false)
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean = edit { replace(text.toString(), true) }

        override fun finishComposingText(): Boolean { composing = null; return true }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action != KeyEvent.ACTION_DOWN) return true
            if (event.keyCode == KeyEvent.KEYCODE_Z && event.isCtrlPressed) {
                val action = {
                    lastOrigin = null
                    if (event.isShiftPressed) {
                        redoCalls++
                        redo.removeLastOrNull()?.let { value -> undo.addLast(document); hold(value) }
                    } else {
                        undoCalls++
                        if (!ignoreUndo) {
                            val previous = undoOverride ?: undo.removeLastOrNull()
                            undoOverride = null
                            previous?.let { value -> redo.addLast(document); hold(value) }
                        }
                    }
                    Unit
                }
                if (deferUndo) queue.addLast(action) else action()
                return true
            }
            if (event.keyCode in intArrayOf(KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL)) return edit {
                val first = modelOffset(minOf(Selection.getSelectionStart(editable), Selection.getSelectionEnd(editable)))
                val last = modelOffset(maxOf(Selection.getSelectionStart(editable), Selection.getSelectionEnd(editable)))
                if (first == last) {
                    val from = if (event.keyCode == KeyEvent.KEYCODE_DEL) GraphemeText.previousCluster(document, first) else first
                    val through = if (event.keyCode == KeyEvent.KEYCODE_FORWARD_DEL) GraphemeText.nextCluster(document, last) else last
                    Selection.setSelection(editable, rawOffset(from), rawOffset(through))
                }
                replace("", false, origin = "+delete")
            }
            return true
        }
    }

    private class ViewportEditor(initial: String, private val numbered: Boolean = false) : BaseInputConnection(View(RuntimeEnvironment.getApplication()), true) {
        private data class State(val text: String, val first: Int, val last: Int, val start: Int, val end: Int?)
        var document = initial
            private set
        var first = initial.length
        var last = initial.length
        var windowStart = 0
        var windowEnd: Int? = null
        var deferNative = false
        var deferDelete = false
        var deferCommit = false
        var ignoreDelete = false
        var rejectCommit = false
        private val editQueue = ArrayDeque<() -> Unit>()
        fun flushEdits() { while (editQueue.isNotEmpty()) editQueue.removeFirst().invoke() }
        private val nativeQueue = ArrayDeque<() -> Unit>()
        var onEdit: ((String) -> Unit)? = null
        var onNative: ((Boolean) -> Unit)? = null
        var deferBatchWindow = false
        var undoCalls = 0
        var redoCalls = 0
        val writes = ArrayList<String>()
        private val undo = ArrayDeque<State>()
        private val redo = ArrayDeque<State>()
        private var batch = false
        private var batchRecorded = false
        private var delayed: EditorTextSnapshot? = null

        private fun state() = State(document, first, last, windowStart, windowEnd)
        private fun restore(state: State) {
            document = state.text
            first = state.first
            last = state.last
            windowStart = state.start
            windowEnd = state.end
            delayed = null
        }
        fun flushWindow(start: Int) { delayed = null; windowStart = start }
        fun external(text: String, undoable: Boolean = false) {
            if (undoable) undo.addLast(state())
            document = text; first = text.length; last = first; windowStart = 0; windowEnd = null
        }
        fun flushNative() { while (nativeQueue.isNotEmpty()) nativeQueue.removeFirst().invoke() }
        private fun rendered(): Pair<EditorTextSnapshot, List<Int>> {
            val raw = StringBuilder()
            val offsets = ArrayList<Int>()
            var column = 0
            var number = document.take(windowStart).count { it == '\n' } + 1
            val end = (windowEnd ?: document.length).coerceIn(windowStart..document.length)
            for (index in windowStart..end) {
                if (numbered && (index == windowStart || document[index - 1] == '\n')) raw.append(number++).append('\n')
                offsets.add(raw.length)
                if (index == end) break
                val character = document[index]
                when (character) {
                    '\n' -> { if (column == 0) raw.append('\u200b'); raw.append('\n'); column = 0 }
                    '\t' -> { val spaces = 4 - column % 4; repeat(spaces) { raw.append(' ') }; column += spaces }
                    else -> { raw.append(character); column++ }
                }
            }
            if (column == 0) raw.append('\u200b')
            raw.append('\n')
            fun position(value: Int) = offsets[(value - windowStart).coerceIn(offsets.indices)]
            return EditorTextSnapshot(raw.toString(), position(first), position(last)) to offsets
        }
        private fun snapshot() = delayed ?: rendered().first
        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int) = ExtractedText().apply {
            val value = snapshot()
            text = value.text
            startOffset = 0
            partialStartOffset = -1
            selectionStart = value.selectionStart
            selectionEnd = value.selectionEnd
        }
        override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence {
            val value = snapshot()
            return value.text.substring(0, minOf(value.selectionStart, value.selectionEnd)).takeLast(length)
        }
        override fun getTextAfterCursor(length: Int, flags: Int): CharSequence {
            val value = snapshot()
            return value.text.substring(maxOf(value.selectionStart, value.selectionEnd)).take(length)
        }
        override fun setSelection(start: Int, end: Int): Boolean {
            val offsets = rendered().second
            val from = offsets.indexOf(start)
            val through = offsets.indexOf(end)
            if (from < 0 || through < 0) return false
            first = windowStart + from
            last = windowStart + through
            return true
        }
        override fun beginBatchEdit(): Boolean { batch = true; batchRecorded = false; return true }
        override fun endBatchEdit(): Boolean { batch = false; return true }
        override fun finishComposingText() = true
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            if (rejectCommit) return false
            if (deferCommit) { editQueue.addLast { applyText(text.toString()) }; return true }
            return applyText(text.toString())
        }
        private fun applyText(text: String): Boolean {
            if (!batch || !batchRecorded) { undo.addLast(state()); batchRecorded = true }
            redo.clear()
            val from = minOf(first, last)
            val through = maxOf(first, last)
            windowEnd = windowEnd?.let { if (it >= through) it + text.toString().length - (through - from) else it }
            document = document.replaceRange(from, through, text.toString())
            first = from + text.toString().length
            last = first
            windowStart = minOf(windowStart, document.length)
            writes.add(text.toString())
            onEdit?.invoke(text.toString())
            if (batch && deferBatchWindow && delayed == null) delayed = rendered().first
            return true
        }
        override fun performContextMenuAction(id: Int): Boolean = when (id) {
            android.R.id.cut -> commitText("", 1)
            android.R.id.selectAll -> { first = 0; last = document.length; true }
            else -> false
        }
        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DEL && !ignoreDelete) {
                if (deferDelete) editQueue.addLast { applyText("") } else applyText("")
            }
            if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_Z && event.isCtrlPressed) {
                val apply = {
                    if (event.isShiftPressed) {
                        redoCalls++
                        redo.removeLastOrNull()?.let { previous -> undo.addLast(state()); restore(previous) }
                    } else {
                        undoCalls++
                        undo.removeLastOrNull()?.let { previous -> redo.addLast(state()); restore(previous) }
                    }
                    onNative?.invoke(event.isShiftPressed)
                    Unit
                }
                if (deferNative) nativeQueue.addLast(apply) else apply()
            }
            return true
        }
    }

    @Test fun tab_expanding_the_virtual_window_rebases_only_the_local_inverse_and_retains_cut() {
        val removed = (1..200).joinToString("\n", prefix = "\n") { "old line $it 中🙂" }
        val original = ">\nLE" + removed + "\nright\nlast"
        val editor = ViewportEditor(original).apply {
            first = 4
            last = 4 + removed.length
            windowStart = original.lastIndexOf("old line 190")
            onEdit = { text -> windowStart = if (text == "\t") 0 else 2 }
        }
        val history = history()
        val connection = history.wrap(editor)
        assertTrue(history.cutCopiedSelection(connection, removed))
        val afterCut = editor.document
        assertEquals(">\nLE\nright\nlast", afterCut)
        assertTrue(history.canUndo(connection))
        assertTrue(connection.commitText("\t", 1))
        assertEquals(">\nLE\t\nright\nlast", editor.document)
        assertTrue(history.canUndo(connection))
        editor.writes.clear()
        undoStep(history, connection, "expanded viewport Tab")
        assertEquals(afterCut, editor.document)
        assertEquals(listOf(""), editor.writes)
        assertEquals(0, editor.undoCalls)
        undoStep(history, connection, "earlier virtual selection cut")
        assertEquals(original, editor.document)
        assertTrue(editor.undoCalls in 1..3)
        assertEquals(listOf(""), editor.writes)
    }

    @Test fun the_captured_nodeseek_tab_window_can_expand_after_the_immediate_commit_was_already_recorded() {
        val quote = "> quoted words: alpha **bold** and `inline code` with two words, omega 中文🙂.\n>\n"
        val before = quote.repeat(5) + "> quoted words: alga 中文🙂.\n>\n" + quote.repeat(8)
        assertEquals(1056, before.length)
        val original = ">\n" + before.dropLast(1)
        val editor = ViewportEditor(original).apply { windowStart = 2; first = 415; last = 415 }
        val history = history()
        val connection = history.wrap(editor)
        assertEquals(before, editor.getExtractedText(null, 0).text.toString())
        assertTrue(connection.commitText("\t", 1))
        assertTrue(history.canUndo(connection))
        editor.windowStart = 0
        val visible = editor.getExtractedText(null, 0)
        assertEquals(1060, visible.text.length)
        assertEquals(417, visible.selectionEnd)
        assertTrue(history.canUndo(connection))
        editor.onEdit = { editor.windowStart = 2 }
        editor.writes.clear()
        undoStep(history, connection)
        assertEquals(original, editor.document)
        assertEquals(listOf(""), editor.writes)
        assertEquals(0, editor.undoCalls)
    }

    @Test fun native_cut_restore_accepts_the_repeated_nodeseek_window_only_at_the_selection_anchored_offset() {
        for (reversed in listOf(false, true)) for (deferred in listOf(false, true)) for (variant in listOf("valid", "wrong anchor", "changed text")) {
            val quote = "> quoted words: alpha **bold** and `inline code` with two words, omega 中文🙂.\n>\n"
            val hidden = "earlier unseen text\n".repeat(220)
            val restoredWindow = ">\n" + quote.repeat(18)
            val original = hidden + restoredWindow.dropLast(1)
            val beforeWindow = quote.repeat(14)
            val editor = ViewportEditor(original).apply {
                windowStart = hidden.length + 318
                first = if (reversed) hidden.length + 781 else 0
                last = if (reversed) 0 else hidden.length + 781
                onEdit = { windowStart = 0 }
            }
            val history = history()
            val connection = history.wrap(editor)
            val before = editor.getExtractedText(null, 0)
            assertEquals(1106, before.text.length)
            assertEquals(beforeWindow, before.text.toString())
            assertEquals(if (reversed) 463 else 0, before.selectionStart)
            assertEquals(if (reversed) 0 else 463, before.selectionEnd)
            assertTrue(history.cutCopiedSelection(connection, original.substring(0, hidden.length + 781)))
            val cut = editor.document
            assertTrue(history.canUndo(connection))
            editor.onNative = { redo ->
                if (!redo) {
                    if (variant == "changed text") {
                        editor.external(original.replaceRange(hidden.length + 600, hidden.length + 601, "X"))
                        editor.first = if (reversed) hidden.length + 781 else 0
                        editor.last = if (reversed) 0 else hidden.length + 781
                    }
                    editor.windowStart = hidden.length
                    if (variant == "wrong anchor") {
                        if (reversed) editor.first-- else editor.last--
                    }
                }
            }
            editor.deferNative = deferred
            editor.writes.clear()
            var completed: Boolean? = null
            history.onUndoCompleted = { completed = it }
            val immediate = history.undo(connection)
            repeat(100) {
                editor.flushNative()
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            }
            val context = "reversed=$reversed deferred=$deferred variant=$variant"
            assertFalse(context, history.hasPendingUndo)
            if (variant == "valid") {
                assertTrue(context, immediate || completed == true)
                assertEquals(context, original, editor.document)
                assertEquals(context, restoredWindow, editor.getExtractedText(null, 0).text.toString())
                assertEquals(context, 0, editor.redoCalls)
            } else {
                assertFalse(context, immediate)
                assertEquals(context, cut, editor.document)
                assertEquals(context, 1, editor.redoCalls)
            }
            assertEquals(context, 1, editor.undoCalls)
            assertTrue(context, editor.writes.isEmpty())
        }
    }

    @Test fun native_full_clear_restores_a_clipped_selected_window_and_keeps_the_paste_and_cut_undo_steps() {
        for (numbered in listOf(false, true)) for (deferred in listOf(false, true)) for (variant in listOf("valid", "changed text", "partial selection")) {
            val quote = "> quoted words: alpha **bold** and `inline code` with two words, omega 中文🙂.\n>\n"
            val original = quote.repeat(16_384 / quote.length).padEnd(16_384, 'x')
            val copied = original.substring(4126, 12313)
            val editor = ViewportEditor(original, numbered).apply {
                windowStart = 11850
                windowEnd = 12955
                first = 4126
                last = 12313
                onEdit = { text ->
                    windowStart = when {
                        document.isEmpty() -> 0
                        text.isEmpty() -> 3713
                        else -> 11532
                    }
                }
            }
            val target = object : InputConnectionWrapper(editor, false) {
                override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                    throw AssertionError("Undo must not write a DOM projection or replay paste")
                }
                override fun performContextMenuAction(id: Int): Boolean =
                    if (id == android.R.id.paste) editor.commitText(copied, 1) else super.performContextMenuAction(id)
            }
            val history = NativeEditorUndoHistory()
            assertTrue(history.cut(target, copied))
            val cut = editor.document
            assertEquals(8197, cut.length)
            assertTrue(history.paste(target, copied))
            assertEquals(original, editor.document)
            assertTrue(history.navigate(target, selectAll = true) { editor.first = 0; editor.last = original.length; true })
            val selected = editor.getExtractedText(null, 0)
            assertEquals(if (numbered) 1572 else 1424, selected.text.length)
            assertEquals(if (numbered) 4 else 0, selected.selectionStart)
            assertEquals(if (numbered) 1571 else 1423, selected.selectionEnd)
            assertTrue(history.track(target, expected = { it.text.toString().removeRange(it.selectionStart, it.selectionEnd) },
                operation = WindowEdit.Key(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))) { editor.commitText("", 1) })
            assertEquals("", editor.document)
            editor.onNative = { redo ->
                if (!redo && editor.undoCalls == 1) {
                    if (variant == "changed text") {
                        editor.external(original.replaceRange(200, 201, "X"))
                        editor.first = 0
                        editor.last = original.length
                    }
                    editor.windowStart = 0
                    editor.windowEnd = 710
                    if (variant == "partial selection") editor.last = 709
                } else if (!redo && editor.undoCalls == 2) {
                    editor.windowStart = 3395
                    editor.windowEnd = 4452
                } else if (!redo && editor.undoCalls == 3) {
                    editor.windowStart = 11532
                    editor.windowEnd = 12955
                }
            }
            editor.deferNative = deferred
            editor.writes.clear()
            fun undo(expected: String, succeeds: Boolean) {
                var completed: Boolean? = null
                history.onUndoCompleted = { completed = it }
                val immediate = history.undo(target)
                repeat(100) {
                    editor.flushNative()
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
                }
                val context = "numbered=$numbered deferred=$deferred variant=$variant expectedLength=${expected.length}"
                assertEquals(context, succeeds, immediate || completed == true)
                assertEquals(context, expected, editor.document)
                assertFalse(context, history.hasPendingUndo)
                assertTrue(context, editor.writes.isEmpty())
            }
            if (variant == "valid") {
                undo(original, true)
                assertEquals(if (numbered) 756 else 711, editor.getExtractedText(null, 0).text.length)
                assertTrue(history.canUndo(target))
                undo(cut, true)
                assertTrue(history.canUndo(target))
                undo(original, true)
                assertFalse(history.canUndo(target))
                assertEquals(3, editor.undoCalls)
                assertEquals(0, editor.redoCalls)
            } else {
                undo("", false)
                assertFalse(history.canUndo(target))
                assertEquals(1, editor.undoCalls)
                assertEquals(1, editor.redoCalls)
            }
        }
    }

    @Test fun an_unverified_disjoint_navigation_window_uses_only_one_native_undo_to_its_recorded_before() {
        for (delayed in listOf(false, true)) {
            val original = (1..100).joinToString("\n") { "line $it" }
            val editor = ViewportEditor(original).apply {
                windowStart = original.indexOf("line 20")
                windowEnd = original.indexOf("line 30") - 1
                first = original.indexOf("line 25")
                last = first
            }
            val history = NativeEditorUndoHistory()
            assertTrue(history.track(editor, expected = { it.text.toString().replaceRange(it.selectionStart, it.selectionEnd, "x") },
                operation = WindowEdit.Commit("x", 1)) { editor.commitText("x", 1) })
            val move = {
                editor.windowStart = editor.document.indexOf("line 80")
                editor.windowEnd = editor.document.indexOf("line 90") - 1
                editor.first = editor.document.indexOf("line 85")
                editor.last = editor.first
            }
            assertTrue(history.navigate(editor) { if (!delayed) move(); true })
            if (delayed) move()
            assertTrue(history.canUndo(editor))
            assertTrue(history.undo(editor))
            assertEquals(original, editor.document)
            assertEquals(1, editor.undoCalls)
            assertEquals(0, editor.redoCalls)
            assertEquals(listOf("x"), editor.writes)
        }
    }

    @Test fun unknown_text_during_deferred_navigation_is_not_adopted_as_a_verified_edit_or_traversed_past_with_undo() {
        for (deferredUndo in listOf(false, true)) {
            val editor = ViewportEditor("original")
            val history = NativeEditorUndoHistory()
            assertTrue(history.track(editor, expected = { "originalx\n" }, operation = WindowEdit.Commit("x", 1)) {
                editor.commitText("x", 1)
            })
            assertTrue(history.navigate(editor) { true })
            editor.external("external content", undoable = true)
            assertTrue(history.canUndo(editor))
            editor.deferNative = deferredUndo
            assertFalse(history.undo(editor))
            if (deferredUndo) {
                assertTrue(history.hasPendingUndo)
                editor.flushNative()
                assertEquals("originalx", editor.document)
                assertFalse(history.canUndo(editor))
                assertTrue(history.hasPendingUndo)
                editor.flushNative()
                settle()
            }
            assertEquals("external content", editor.document)
            assertEquals(1, editor.undoCalls)
            assertEquals(1, editor.redoCalls)
            assertEquals(listOf("x"), editor.writes)
            assertFalse(history.hasPendingUndo)
            assertFalse(history.hasUndo)
        }
    }

    @Test fun a_batched_large_paste_waits_for_the_complete_guarded_tail_window_and_restores_both_cut_steps() {
        for (partial in listOf(false, true)) {
            val payload = (1..1600).joinToString("\n") { "payload $it 中🙂 with text" } + "\nunique payload ending"
            val prefix = if (partial) "left\n" else ""
            val suffix = if (partial) "\nright\nlast" else ""
            val original = prefix + payload + suffix
            val editor = ViewportEditor(original).apply {
                first = prefix.length
                last = first + payload.length
                windowStart = original.lastIndexOf("payload 1590")
                onEdit = { windowStart = 0 }
            }
            val history = history()
            val connection = history.wrap(editor)
            assertTrue(history.cutCopiedSelection(connection, payload))
            assertEquals(prefix + suffix, editor.document)
            assertTrue(history.canUndo(connection))
            editor.deferBatchWindow = true
            connection.beginBatchEdit()
            for (chunk in payload.chunked(16_384)) assertTrue(connection.commitText(chunk, 1))
            connection.endBatchEdit()
            assertFalse("partial=$partial staged prefix must remain pending", history.canUndo(connection))
            editor.deferBatchWindow = false
            editor.flushWindow(editor.document.lastIndexOf("payload 1590"))
            assertTrue("partial=$partial completed tail must preserve history", history.canUndo(connection))
            editor.writes.clear()
            undoStep(history, connection, "partial=$partial paste")
            assertEquals(prefix + suffix, editor.document)
            assertTrue(editor.writes.isEmpty())
            undoStep(history, connection, "partial=$partial cut")
            assertEquals(original, editor.document)
            assertTrue(editor.writes.isEmpty())
            assertEquals(2, editor.undoCalls)
        }
    }

    @Test fun native_paste_binds_one_context_action_and_undo_never_replays_clipboard_or_dom_projection() {
        for (partial in listOf(false, true)) for (deferred in listOf(false, true)) {
            val payload = "A\n".repeat(24_575) + "A"
            val prefix = if (partial) "left\n" else ""
            val suffix = if (partial) "\nright\nlast" else ""
            val original = prefix + payload + suffix
            val editor = ViewportEditor(original).apply {
                first = prefix.length
                last = first + payload.length
                windowStart = original.length - suffix.length - 39
                onEdit = { windowStart = 0 }
            }
            var clipboard = payload
            var pasteCalls = 0
            var queued: (() -> Unit)? = null
            val target = object : InputConnectionWrapper(editor, false) {
                override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                    throw AssertionError("Native paste must not commit text")
                }
                override fun performContextMenuAction(id: Int): Boolean {
                    if (id != android.R.id.paste) return super.performContextMenuAction(id)
                    pasteCalls++
                    val captured = clipboard
                    val apply = {
                        editor.commitText(captured, 1)
                        editor.flushWindow(editor.document.length - suffix.length - 39)
                        Unit
                    }
                    if (deferred) queued = apply else apply()
                    return true
                }
            }
            val history = history()
            val connection = history.wrap(target)
            assertTrue(history.cutCopiedSelection(connection, payload))
            assertEquals(prefix + suffix, editor.document)
            val native = NativeEditorUndoHistory()
            assertTrue(native.paste(target, payload))
            assertEquals(1, pasteCalls)
            if (deferred) {
                assertFalse(native.canUndo(target))
                assertFalse(native.paste(target, payload))
                assertEquals(1, pasteCalls)
                queued!!.invoke()
            }
            assertEquals(original, editor.document)
            assertTrue(native.canUndo(target))
            clipboard = "CHANGED CLIPBOARD"
            editor.writes.clear()
            assertTrue(native.undo(target))
            assertEquals(prefix + suffix, editor.document)
            assertEquals(1, editor.undoCalls)
            assertEquals(0, editor.redoCalls)
            assertTrue(editor.writes.isEmpty())
            assertEquals(1, pasteCalls)
            assertTrue(history.canUndo(connection))
            undoStep(history, connection)
            assertEquals(original, editor.document)
            assertTrue(editor.writes.isEmpty())
        }
    }

    @Test fun explicit_navigation_retains_history_across_a_changed_virtual_window_but_later_unknown_drift_invalidates_it() {
        for (delayed in listOf(false, true)) for (drift in listOf(false, true)) {
            val original = (1..100).joinToString("\n") { "line $it" }
            val editor = ViewportEditor(original).apply { windowStart = original.indexOf("line 80") }
            val history = NativeEditorUndoHistory()
            assertTrue(history.track(editor, expected = { it.text.toString().replaceRange(it.selectionStart, it.selectionEnd, "x") },
                operation = WindowEdit.Commit("x", 1)) { editor.commitText("x", 1) })
            assertTrue(history.navigate(editor) { if (!delayed) editor.windowStart = 0; true })
            if (delayed) editor.windowStart = 0
            assertTrue(history.canUndo(editor))
            if (drift) {
                editor.external("unrelated document")
                assertFalse(history.canUndo(editor))
                assertFalse(history.undo(editor))
                assertEquals(0, editor.undoCalls)
            } else {
                assertTrue(history.undo(editor))
                assertEquals(original, editor.document)
                assertEquals(1, editor.undoCalls)
            }
            assertEquals(listOf("x"), editor.writes)
        }
    }

    @Test fun a_repeated_tail_does_not_confirm_a_large_prefix_and_native_undo_shortcuts_remain_available() {
        val payload = "A\n".repeat(24_575) + "A"
        val editor = ViewportEditor("")
        val history = history()
        val connection = history.wrap(editor)
        editor.deferBatchWindow = true
        connection.beginBatchEdit()
        for (chunk in payload.chunked(16_384)) assertTrue(connection.commitText(chunk, 1))
        connection.endBatchEdit()
        editor.deferBatchWindow = false
        editor.flushWindow(payload.length - 39)
        assertFalse(history.canUndo(connection))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_100))
        assertFalse(history.canUndo(connection))
        assertFalse(history.undo(connection))
        assertEquals(payload, editor.document)
        connection.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Z, 0, KeyEvent.META_CTRL_ON))
        assertEquals("", editor.document)
        assertEquals(1, editor.undoCalls)
    }

    @Test fun clipped_selected_tab_uses_observed_native_delete_and_keeps_one_undo_across_a_new_viewport() {
        for (numbered in listOf(false, true)) for (deferred in listOf(false, true)) for (partial in listOf(false, true)) {
            val block = "alpha words 中文🙂 " + "middle words ".repeat(10) + "suffix\n\n"
            val original = block.repeat(49_152 / block.length).padEnd(49_152, 'x')
            val editor = ViewportEditor(original, numbered).apply {
                first = if (partial) block.length * 4 + 6 else 0
                last = if (partial) block.length * 30 + 9 else original.length
                windowStart = if (partial) block.length * 28 else 0
                windowEnd = if (partial) block.length * 32 - 1 else block.length * 10 - 1
                onEdit = { text ->
                    if (document.isEmpty() || document == "\t") { windowStart = 0; windowEnd = document.length }
                    else if (text.isEmpty()) { windowStart = block.length * 3; windowEnd = block.length * 7 - 1 }
                }
                deferDelete = deferred
                deferCommit = deferred
                deferNative = deferred
            }
            val first = editor.first
            val last = editor.last
            val deleted = original.removeRange(first, last)
            val expected = original.replaceRange(first, last, "\t")
            val history = history()
            val connection = history.wrap(editor)
            if (!partial && numbered) assertTrue(connection.performContextMenuAction(android.R.id.selectAll))
            val completions = ArrayList<Boolean>()
            history.onInsertionCompleted = { completions.add(it) }
            assertTrue(connection.commitText("\t", 1))
            if (deferred) {
                assertTrue(history.hasPendingInsertion)
                assertFalse(connection.setSelection(0, 0))
                assertFalse(connection.commitText("unexpected", 1))
                editor.flushEdits()
                assertEquals(deleted, editor.document)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
                assertTrue(history.hasPendingInsertion)
                editor.flushEdits()
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            }
            assertEquals("numbered=$numbered partial=$partial deferred=$deferred", expected, editor.document)
            assertFalse(history.hasPendingInsertion)
            assertEquals(if (deferred) listOf(true) else emptyList<Boolean>(), completions)
            assertTrue(history.canUndo(connection))
            editor.writes.clear()
            editor.onNative = { redo ->
                if (!redo && editor.undoCalls == 2 && !partial) editor.windowEnd = block.length * 8 - 1
            }
            var completed: Boolean? = null
            history.onUndoCompleted = { completed = it }
            val immediate = history.undo(connection)
            repeat(100) { editor.flushNative(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32)) }
            assertTrue("numbered=$numbered partial=$partial deferred=$deferred", immediate || completed == true)
            assertEquals(original, editor.document)
            assertEquals("numbered=$numbered partial=$partial deferred=$deferred", 2, editor.undoCalls)
            assertEquals(0, editor.redoCalls)
            assertTrue(editor.writes.isEmpty())
            assertFalse(history.canUndo(connection))
        }
    }

    @Test fun selected_tab_failure_retains_applied_deletion_and_does_not_accept_a_viewport_change_as_insertion() {
        for (failure in listOf("ignored delete", "rejected insertion", "viewport only")) {
            val original = "alpha words\n\n".repeat(80)
            val editor = ViewportEditor(original).apply {
                first = 0; last = original.length - if (failure == "viewport only") 26 else 0
                windowEnd = 300
                ignoreDelete = failure == "ignored delete"
                rejectCommit = failure == "rejected insertion"
                onEdit = { windowStart = 0; windowEnd = document.length }
            }
            val target = object : InputConnectionWrapper(editor, false) {
                override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                    if (failure != "viewport only") return super.commitText(text, newCursorPosition)
                    editor.windowStart = 13
                    return true
                }
            }
            val history = history()
            val connection = history.wrap(target)
            var completed: Boolean? = null
            history.onInsertionCompleted = { completed = it }
            val accepted = connection.commitText("\t", 1)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2200))
            assertFalse(history.hasPendingInsertion)
            assertTrue(!accepted || completed == false)
            if (failure == "ignored delete") {
                assertEquals(original, editor.document)
                assertFalse(history.hasUndo)
                assertTrue(editor.writes.isEmpty())
            } else {
                assertEquals(if (failure == "viewport only") original.takeLast(26) else "", editor.document)
                assertTrue(history.canUndo(connection))
                editor.writes.clear()
                undoStep(history, connection, failure)
                assertEquals(original, editor.document)
                assertTrue(editor.writes.isEmpty())
                assertEquals(1, editor.undoCalls)
            }
        }
    }

    @Test fun a_native_only_large_paste_is_never_used_as_a_local_clear_replacement() {
        val line = "alpha words 中文🙂 " + "middle words ".repeat(10) + "suffix\n\n"
        val original = line.repeat(49_152 / line.length).padEnd(49_152, 'x')
        val editor = ViewportEditor("").apply {
            onEdit = { if (document.isEmpty()) { windowStart = 0; windowEnd = 0 }
                else { windowStart = document.lastIndexOf("alpha words"); windowEnd = null } }
        }
        val target = object : InputConnectionWrapper(editor, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean =
                throw AssertionError("Native-only paste cannot supply a DOM replacement")
            override fun performContextMenuAction(id: Int): Boolean =
                if (id == android.R.id.paste) editor.commitText(original, 1) else super.performContextMenuAction(id)
        }
        val history = NativeEditorUndoHistory()
        assertTrue(history.paste(target, original))
        assertTrue(history.navigate(target) { editor.first = 0; editor.last = original.length; true })
        assertTrue(history.track(target, expected = { "\n" },
            operation = WindowEdit.Key(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))) {
            editor.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        })
        editor.writes.clear()
        assertTrue(history.undo(target))
        assertEquals(original, editor.document)
        assertEquals(1, editor.undoCalls)
        assertTrue(editor.writes.isEmpty())
        assertTrue(history.canUndo(target))
        assertTrue(history.undo(target))
        assertEquals("", editor.document)
        assertTrue(editor.writes.isEmpty())
    }

    @Test fun opposite_clipped_selection_ends_use_captured_cut_text_only_to_confirm_native_restoration() {
        for (numbered in listOf(false, true)) for (changed in listOf(false, true)) {
            val quote = "> quoted words: alpha **bold** and `inline code` with two words, omega 中文🙂.\n>\n"
            val original = quote.repeat(16_384 / quote.length).padEnd(16_384, 'x')
            val copied = original.substring(4126, 12313)
            val editor = ViewportEditor(original, numbered).apply {
                first = 12313; last = 4126; windowStart = 3711; windowEnd = 4816
                onEdit = { windowStart = 3711; windowEnd = 4768 }
            }
            val history = history()
            val connection = history.wrap(editor)
            assertTrue(history.cutCopiedSelection(connection, copied))
            val cut = editor.document
            editor.onNative = { redo -> if (!redo) {
                if (changed) {
                    editor.external(original.replaceRange(12000, 12001, "X"))
                    editor.first = 12313; editor.last = 4126
                }
                editor.windowStart = 11532; editor.windowEnd = 12955
            } }
            editor.writes.clear()
            var completed: Boolean? = null
            history.onUndoCompleted = { completed = it }
            val immediate = history.undo(connection)
            settle()
            assertEquals(!changed, immediate || completed == true)
            assertEquals(if (changed) cut else original, editor.document)
            assertEquals(if (changed) 1 else 0, editor.redoCalls)
            assertTrue(editor.writes.isEmpty())
        }
    }

    @Test fun native_undo_preserves_the_host_range_when_identical_viewports_have_different_raw_endpoints() {
        for (reversed in listOf(false, true)) {
            val original = "ab cde\n".repeat(400)
            val editor = ViewportEditor(original).apply {
                first = if (reversed) 1500 else 50
                last = if (reversed) 50 else 1500
                windowStart = 0
                windowEnd = 100
            }
            var selectionWritesAfterNativeUndo = 0
            val target = object : InputConnectionWrapper(editor, false) {
                override fun setSelection(start: Int, end: Int): Boolean {
                    if (editor.undoCalls > 0) selectionWritesAfterNativeUndo++
                    return super.setSelection(start, end)
                }
            }
            val history = history()
            val connection = history.wrap(target)
            assertTrue(history.cutCopiedSelection(connection, original.substring(50, 1500)))
            editor.onNative = { redo -> if (!redo) { editor.windowStart = 49; editor.windowEnd = 149 } }
            editor.writes.clear()
            undoStep(history, connection)
            assertEquals(original, editor.document)
            assertEquals(if (reversed) 1500 else 50, editor.first)
            assertEquals(if (reversed) 50 else 1500, editor.last)
            assertEquals(1, editor.undoCalls)
            assertEquals(0, selectionWritesAfterNativeUndo)
            assertTrue(editor.writes.isEmpty())
            assertFalse(history.hasPendingUndo)
            assertFalse(history.canUndo(connection))
        }
    }

    @Test fun cutting_blank_lines_undoes_the_native_transaction_without_writing_newlines() {
        val original = "prefix line\n\n\n\nsuffix line\ntail\n"
        val editor = ViewportEditor(original).apply { first = 12; last = 14 }
        val history = history()
        val connection = history.wrap(editor)
        assertTrue(history.cutCopiedSelection(connection, "\n\n"))
        assertTrue(connection.commitText("\t", 1))
        undoStep(history, connection)
        editor.writes.clear()
        undoStep(history, connection)
        assertEquals(original, editor.document)
        assertTrue(editor.writes.isEmpty())
        assertTrue(editor.undoCalls >= 1)
    }

    @Test fun numbered_native_restore_preserves_real_numeric_lines_and_rejects_unbound_pseudo_gutters() {
        for (numbered in listOf(false, true)) for (selectedAll in listOf(false, true)) {
            val original = ("1\nfirst actual numeric body line with words\n2\nsecond actual numeric body line\n3\nlast numeric body line\n").repeat(8)
            val editor = ViewportEditor(original, numbered).apply { first = 0; last = original.length }
            val history = NativeEditorUndoHistory()
            if (selectedAll) assertTrue(history.navigate(editor, selectAll = true) { editor.first = 0; editor.last = original.length; true })
            assertTrue(history.track(editor, expected = { "\n" },
                operation = WindowEdit.Key(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))) {
                editor.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            })
            editor.onNative = { redo -> if (!redo) {
                editor.external(original.replaceRange(0, 1, "9"))
                editor.first = 0; editor.last = original.length
            } }
            editor.writes.clear()
            var completed: Boolean? = null
            history.onUndoCompleted = { completed = it }
            assertFalse(history.undo(editor))
            settle()
            assertEquals(false, completed)
            assertEquals("", editor.document)
            assertEquals(1, editor.redoCalls)
            assertTrue(editor.writes.isEmpty())
        }
    }

    @Test fun a_gutter_shaped_selection_without_select_all_or_matching_copied_text_cannot_validate_a_new_window() {
        for (source in listOf("unbound", "numeric copy")) {
            val original = ("alpha body line with words\n\n").repeat(80)
            val editor = ViewportEditor(original, true).apply {
                first = 0; last = original.length; windowEnd = 216
                onEdit = { windowStart = 0; windowEnd = document.length }
            }
            val history = NativeEditorUndoHistory()
            if (source == "numeric copy") {
                val raw = editor.getExtractedText(null, 0)
                val copied = raw.text.substring(raw.selectionStart, raw.selectionEnd)
                assertTrue(history.cut(editor, copied))
            } else assertTrue(history.track(editor, expected = { "\n" },
                operation = WindowEdit.Key(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))) {
                editor.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            })
            editor.onNative = { redo -> if (!redo) editor.windowEnd = 135 }
            editor.writes.clear()
            var completed: Boolean? = null
            history.onUndoCompleted = { completed = it }
            assertFalse(history.undo(editor))
            settle()
            assertEquals(false, completed)
            assertEquals("", editor.document)
            assertTrue(editor.writes.isEmpty())
        }
    }

    @Test fun a_host_selection_change_invalidates_the_previous_select_all_evidence_for_real_numeric_body_lines() {
        for (numbered in listOf(false, true)) {
            val original = "1\nfirst body line with more than thirty two characters\n2\nsecond unchanged body line\n3\nlast unchanged body line"
            val changed = original.replace("1\n", "4\n").replace("2\n", "5\n").replace("3\n", "6\n")
            val editor = ViewportEditor(original, numbered)
            val history = NativeEditorUndoHistory()
            assertTrue(history.navigate(editor, selectAll = true) { editor.first = 0; editor.last = original.length; true })
            editor.first = 2
            assertTrue(history.track(editor, expected = { it.text.toString().removeRange(it.selectionStart, it.selectionEnd) },
                operation = WindowEdit.Key(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))) {
                editor.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            })
            assertEquals("1\n", editor.document)
            editor.onNative = { redo -> if (!redo) {
                editor.external(changed)
                editor.first = 2; editor.last = changed.length
            } }
            editor.writes.clear()
            var completed: Boolean? = null
            history.onUndoCompleted = { completed = it }
            assertFalse(history.undo(editor))
            settle()
            assertEquals(false, completed)
            assertEquals("1\n", editor.document)
            assertEquals(1, editor.redoCalls)
            assertTrue(editor.writes.isEmpty())
        }
    }

    private fun history(): EditorUndoHistory = EditorUndoHistory().apply { preferNativeUndo = true }

    private fun settle() { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600)) }

    private fun undoStep(history: EditorUndoHistory, connection: InputConnection, label: String = "") {
        var completed: Boolean? = null
        history.onUndoCompleted = { completed = it }
        val immediate = history.undo(connection)
        repeat(200) {
            if (history.hasPendingUndo) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
        }
        assertFalse("$label undo remains pending", history.hasPendingUndo)
        assertTrue("$label undo did not complete successfully", immediate || completed == true)
    }

    private fun matrixEdit(operation: MatrixEdit, history: EditorUndoHistory, connection: InputConnection, editor: Editor): String {
        val before = editor.document
        fun select(start: Int, end: Int = start) {
            assertTrue(connection.setSelection(editor.rawOffset(start), editor.rawOffset(end)))
        }
        val expected = when (operation) {
            MatrixEdit.INPUT -> {
                select(before.length)
                assertTrue(connection.commitText("中🙂", 1))
                before + "中🙂"
            }
            MatrixEdit.TAB -> {
                select(before.length)
                assertTrue(connection.commitText("\t", 1))
                before + "\t"
            }
            MatrixEdit.BACKSPACE -> {
                select(before.length)
                assertTrue(connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)))
                before.substring(0, GraphemeText.previousCluster(before, before.length))
            }
            MatrixEdit.FORWARD_DELETE -> {
                select(0)
                assertTrue(connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_FORWARD_DEL)))
                before.substring(GraphemeText.nextCluster(before, 0))
            }
            MatrixEdit.CUT -> {
                val innerStart = GraphemeText.nextCluster(before, 0)
                val innerEnd = GraphemeText.previousCluster(before, before.length)
                val from = if (innerStart < innerEnd) innerStart else 0
                val through = if (innerStart < innerEnd) innerEnd else innerStart
                select(from, through)
                assertTrue(history.cutCopiedSelection(connection, before.substring(from, through)))
                before.removeRange(from, through)
            }
            MatrixEdit.PASTE -> {
                val through = GraphemeText.nextCluster(before, 0)
                select(0, through)
                assertTrue(connection.commitText("pasted words🙂", 1))
                "pasted words🙂" + before.substring(through)
            }
        }
        assertEquals("$operation updates the document", expected, editor.document)
        return before
    }

    @Test fun all_ordered_pairs_of_six_text_mutations_preserve_both_undo_steps() {
        val failures = mutableListOf<String>()
        for (numbered in listOf(false, true)) for (initial in listOf("prefix two words suffix", "prefix line one\n\nline two suffix\ntail")) {
            for (first in MatrixEdit.entries) for (second in MatrixEdit.entries) {
                val label = "numbered=$numbered lines=${initial.count { it == '\n' } + 1} $first -> $second"
                try {
                    val editor = Editor(initial, numbered = numbered).apply { mergeInputs = true }
                    val history = history()
                    val connection = history.wrap(editor)
                    val before = listOf(matrixEdit(first, history, connection, editor), matrixEdit(second, history, connection, editor))
                    for (expected in before.asReversed()) {
                        assertTrue("history is actionable", history.canUndo(connection))
                        undoStep(history, connection)
                        assertEquals("undo restores its own before", expected, editor.document)
                    }
                    assertFalse(history.canUndo(connection))
                } catch (failure: AssertionError) {
                    failures += "$label: ${failure.message}"
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun cursor_selection_copy_and_composition_finish_do_not_add_or_remove_text_undo_steps() {
        for (numbered in listOf(false, true)) {
            val editor = Editor("prefix\n\ntail", numbered = numbered)
            val target = object : InputConnectionWrapper(editor, false) {
                override fun performContextMenuAction(id: Int): Boolean {
                    assertTrue(id == android.R.id.copy || id == android.R.id.selectAll)
                    if (id == android.R.id.selectAll) editor.selectAll()
                    return true
                }
            }
            val history = history()
            val connection = history.wrap(target)
            val states = mutableListOf(editor.document)
            repeat(30) { index ->
                assertTrue(connection.setSelection(editor.rawOffset(editor.document.length), editor.rawOffset(editor.document.length)))
                assertTrue(connection.commitText(if (index % 2 == 0) "🙂" else "中", 1))
                states += editor.document
                for ((start, end) in listOf(0 to 0, 3 to 3, 0 to 3, 3 to 0, 7 to 7, 0 to editor.document.length)) {
                    assertTrue(connection.setSelection(editor.rawOffset(start), editor.rawOffset(end)))
                    assertTrue(connection.finishComposingText())
                }
                assertTrue(connection.performContextMenuAction(android.R.id.selectAll))
                assertTrue(connection.performContextMenuAction(android.R.id.copy))
            }
            for (expected in states.dropLast(1).asReversed()) {
                undoStep(history, connection)
                assertEquals(expected, editor.document)
            }
            assertEquals(0, editor.undoCalls)
            assertFalse(history.canUndo(connection))
        }
    }

    @Test fun partial_selected_deletion_and_replacement_preserve_original_text() {
        val failures = mutableListOf<String>()
        for (numbered in listOf(false, true)) for (reverse in listOf(false, true)) {
            for (removed in listOf("two words", "line one\n\nline two", "\t", "🙂", " ", "\n")) {
                for (operation in listOf("backspace", "forwardDelete", "emptyCommit", "paste")) {
                    val label = "numbered=$numbered reverse=$reverse removed=${removed.toList()} $operation"
                    try {
                        val initial = "prefix $removed suffix\ntail"
                        val editor = Editor(initial, numbered = numbered).apply { mergeInputs = true }
                        val history = history()
                        val connection = history.wrap(editor)
                        val start = editor.rawOffset(7)
                        val end = editor.rawOffset(7 + removed.length)
                        assertTrue(connection.setSelection(if (reverse) end else start, if (reverse) start else end))
                        val inserted = if (operation == "paste") "pasted\n\n🙂" else ""
                        assertTrue(when (operation) {
                            "backspace" -> connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
                            "forwardDelete" -> connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_FORWARD_DEL))
                            else -> connection.commitText(inserted, 1)
                        })
                        assertEquals("mutation", initial.replaceRange(7, 7 + removed.length, inserted), editor.document)
                        assertTrue("history is actionable after mutation", history.canUndo(connection))
                        undoStep(history, connection)
                        assertEquals("undo restores selected original text", initial, editor.document)
                        assertFalse(history.canUndo(connection))
                    } catch (failure: AssertionError) {
                        failures += "$label: ${failure.message}"
                    }
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun structural_cut_undo_crosses_only_observed_native_states_after_a_mixed_local_undo_chain() {
        for (deferred in listOf(false, true)) {
            val initial = "prefix line one\n\nline two suffix\ntail"
            val editor = Editor(initial, numbered = true).apply { mergeInputs = true }
            val handler = Handler(Looper.getMainLooper())
            val target = object : InputConnectionWrapper(editor, false) {
                override fun sendKeyEvent(event: KeyEvent): Boolean {
                    if (deferred && event.keyCode == KeyEvent.KEYCODE_Z && event.isCtrlPressed) {
                        handler.postDelayed({ editor.sendKeyEvent(event) }, 80)
                        return true
                    }
                    return editor.sendKeyEvent(event)
                }
            }
            val history = history()
            val connection = history.wrap(target)
            editor.setSelection(editor.rawOffset(7), editor.rawOffset(25))
            assertTrue(history.cutCopiedSelection(connection, initial.substring(7, 25)))
            val afterCut = editor.document
            val states = ArrayList<String>()
            repeat(3) {
                for (operation in listOf(MatrixEdit.INPUT, MatrixEdit.BACKSPACE, MatrixEdit.TAB, MatrixEdit.FORWARD_DELETE, MatrixEdit.PASTE)) {
                    states += matrixEdit(operation, history, connection, editor)
                }
            }
            val known = states + editor.document
            for (expected in states.asReversed()) {
                undoStep(history, connection)
                assertEquals(expected, editor.document)
            }
            assertEquals(afterCut, editor.document)
            editor.observedDocuments.clear()
            editor.mutations.clear()
            undoStep(history, connection)
            assertEquals(initial, editor.document)
            assertTrue(editor.observedDocuments.toString(), editor.observedDocuments.all { it == initial || it in known })
            assertTrue(editor.mutations.isEmpty())
            assertTrue(editor.undoCalls > 1)
            assertEquals(0, editor.redoCalls)
            assertFalse(history.canUndo(connection))
        }
    }

    @Test fun structural_cut_undo_rolls_back_every_verified_step_when_async_native_history_reaches_unknown_text() {
        val initial = "prefix line one\n\nline two suffix\ntail"
        val editor = Editor(initial, numbered = true).apply { mergeInputs = true }
        val handler = Handler(Looper.getMainLooper())
        var nativeRequests = 0
        val target = object : InputConnectionWrapper(editor, false) {
            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_Z && event.isCtrlPressed) {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        val unknown = !event.isShiftPressed && ++nativeRequests == 2
                        handler.postDelayed({
                            if (unknown) editor.undoOverride = "unexpected editor history"
                            editor.sendKeyEvent(event)
                        }, 80)
                    }
                    return true
                }
                return editor.sendKeyEvent(event)
            }
        }
        val history = history()
        val connection = history.wrap(target)
        editor.setSelection(editor.rawOffset(7), editor.rawOffset(25))
        assertTrue(history.cutCopiedSelection(connection, initial.substring(7, 25)))
        val afterCut = editor.document
        matrixEdit(MatrixEdit.BACKSPACE, history, connection, editor)
        val afterDelete = editor.document
        undoStep(history, connection)
        assertEquals(afterCut, editor.document)
        editor.observedDocuments.clear()
        editor.mutations.clear()
        var completed: Boolean? = null
        history.onUndoCompleted = { completed = it }
        assertFalse(history.undo(connection))
        repeat(200) {
            if (history.hasPendingUndo) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
        }
        assertFalse(history.hasPendingUndo)
        assertEquals(false, completed)
        assertEquals(afterCut, editor.document)
        assertEquals(listOf(afterDelete, "unexpected editor history", afterDelete, afterCut), editor.observedDocuments)
        assertTrue(editor.mutations.isEmpty())
        assertEquals(2, editor.undoCalls)
        assertEquals(2, editor.redoCalls)
        assertFalse(history.canUndo(connection))
    }

    @Test fun merged_replacements_rebuild_only_recorded_input_before_restoring_each_undo_step() {
        for (numbered in listOf(false, true)) for (deferred in listOf(false, true)) {
            for (removed in listOf("two words", "line one\n\nline two", "\t", "🙂", " ", "\n")) {
                val initial = "prefix $removed suffix\ntail"
                val editor = Editor(initial, numbered = numbered).apply { mergeInputs = true }
                val handler = Handler(Looper.getMainLooper())
                var restoring = false
                val target = object : InputConnectionWrapper(editor, false) {
                    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                        if (restoring && deferred) {
                            handler.postDelayed({ editor.commitText(text, newCursorPosition) }, 80)
                            return true
                        }
                        return editor.commitText(text, newCursorPosition)
                    }

                    override fun sendKeyEvent(event: KeyEvent): Boolean {
                        if (restoring && deferred && event.keyCode == KeyEvent.KEYCODE_Z && event.isCtrlPressed) {
                            handler.postDelayed({ editor.sendKeyEvent(event) }, 80)
                            return true
                        }
                        return editor.sendKeyEvent(event)
                    }
                }
                val history = history()
                val connection = history.wrap(target)
                assertTrue(connection.commitText("!", 1))
                assertTrue(connection.setSelection(editor.rawOffset(7), editor.rawOffset(7 + removed.length)))
                assertTrue(connection.commitText("replacement\n\n🙂", 1))
                editor.observedDocuments.clear()
                editor.mutations.clear()
                restoring = true
                undoStep(history, connection, "numbered=$numbered deferred=$deferred removed=${removed.toList()}")
                assertEquals(initial + "!", editor.document)
                if (editor.undoCalls == 0) {
                    assertEquals(listOf(initial + "!"), editor.observedDocuments)
                    assertEquals(listOf(removed), editor.mutations.map { it.text })
                } else {
                    assertEquals(listOf(initial, initial + "!"), editor.observedDocuments)
                    assertEquals(listOf(Mutation(initial.length, initial.length, "!")), editor.mutations)
                }
                undoStep(history, connection, "prior input numbered=$numbered deferred=$deferred removed=${removed.toList()}")
                assertEquals(initial, editor.document)
                assertFalse(history.canUndo(connection))
            }
        }
    }

    @Test fun successive_original_space_deletions_undo_individually_when_the_editor_merges_delete_history() {
        for (numbered in listOf(false, true)) for (forward in listOf(false, true)) {
            val initial = "a b c d\ne f g h "
            val editor = Editor(initial, numbered = numbered).apply { mergeInputs = true }
            val history = history()
            val connection = history.wrap(editor)
            val states = ArrayList<String>()
            repeat(7) {
                states += editor.document
                val index = editor.document.lastIndexOf(' ')
                val caret = if (forward) index else index + 1
                connection.setSelection(editor.rawOffset(caret), editor.rawOffset(caret))
                assertTrue(connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,
                    if (forward) KeyEvent.KEYCODE_FORWARD_DEL else KeyEvent.KEYCODE_DEL)))
                assertEquals(states.last().removeRange(index, index + 1), editor.document)
            }
            val known = states + editor.document
            for (expected in states.asReversed()) {
                editor.observedDocuments.clear()
                editor.mutations.clear()
                undoStep(history, connection)
                assertEquals(expected, editor.document)
                assertTrue(editor.observedDocuments.toString(), editor.observedDocuments.all { it in known })
                assertTrue(editor.mutations.all { it.text.isEmpty() })
            }
            assertEquals(initial, editor.document)
            assertFalse(history.canUndo(connection))
        }
    }

    @Test fun repeated_text_waits_for_async_native_undo_before_rebuilding_prior_input() {
        val initial = "A "
        val editor = Editor(initial).apply { mergeInputs = true }
        val handler = Handler(Looper.getMainLooper())
        val target = object : InputConnectionWrapper(editor, false) {
            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_Z && event.isCtrlPressed) {
                    handler.postDelayed({ editor.sendKeyEvent(event) }, 80)
                    return true
                }
                return editor.sendKeyEvent(event)
            }
        }
        val history = history()
        val connection = history.wrap(target)
        assertTrue(connection.commitText("B", 1))
        editor.selectAll()
        assertTrue(connection.commitText(initial, 1))
        editor.observedDocuments.clear()
        editor.mutations.clear()
        var completed: Boolean? = null
        history.onUndoCompleted = { completed = it }
        assertFalse(history.undo(connection))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
        assertTrue(editor.mutations.isEmpty())
        repeat(200) {
            if (history.hasPendingUndo) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
        }
        assertEquals(true, completed)
        assertEquals("A B", editor.document)
        assertEquals(listOf(initial, "A B"), editor.observedDocuments)
        assertEquals(listOf(Mutation(initial.length, initial.length, "B")), editor.mutations)
        undoStep(history, connection)
        assertEquals(initial, editor.document)
    }

    @Test fun rejected_rebuild_selection_redoes_native_history_before_any_forward_write() {
        val initial = "two words"
        val editor = Editor(initial).apply { mergeInputs = true }
        var rejectSelection = false
        val target = object : InputConnectionWrapper(editor, false) {
            override fun setSelection(start: Int, end: Int): Boolean =
                if (rejectSelection) false else editor.setSelection(start, end)
        }
        val history = history()
        val connection = history.wrap(target)
        connection.setSelection(editor.rawOffset(0), editor.rawOffset(0))
        assertTrue(connection.commitText("prefix ", 1))
        connection.setSelection(editor.rawOffset(7), editor.rawOffset(editor.document.length))
        assertTrue(connection.commitText("other", 1))
        val edited = editor.document
        editor.observedDocuments.clear()
        editor.mutations.clear()
        rejectSelection = true
        assertFalse(history.undo(connection))
        settle()
        assertFalse(history.hasPendingUndo)
        assertEquals(edited, editor.document)
        assertEquals(listOf(initial, edited), editor.observedDocuments)
        assertTrue(editor.mutations.isEmpty())
        assertEquals(1, editor.undoCalls)
        assertEquals(1, editor.redoCalls)
        assertFalse(history.canUndo(connection))
    }

    @Test fun individual_characters_undo_to_the_original_empty_editor() {
        for (web in listOf(true, false)) {
            val editor = Editor(web = web)
            val history = history()
            val connection = history.wrap(editor)
            for (character in "abc") {
                connection.commitText(character.toString(), 1)
                assertTrue(history.canUndo(connection))
            }
            for (expected in listOf("ab", "a", "")) {
                assertTrue(history.undo(connection))
                assertEquals(expected, editor.document)
            }
            assertFalse(history.canUndo(connection))
        }
    }

    @Test fun a_known_tab_is_removed_locally_when_webview_renders_it_as_spaces() {
        for (prefix in listOf("", "ab", "中")) {
            val editor = Editor()
            val placeholder = if (prefix.isEmpty()) "\u200b" else ""
            val original = "1\n$prefix$placeholder\n"
            var document = prefix
            val states = ArrayList<String>()
            val writes = ArrayList<Mutation>()
            editor.renderRaw(original, 2 + prefix.length)
            val rendering = object : InputConnectionWrapper(editor, false) {
                override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                    val start = Selection.getSelectionStart(editor.editable)
                    val end = Selection.getSelectionEnd(editor.editable)
                    writes.add(Mutation(start, end, text.toString()))
                    when (text.toString()) {
                        "\t" -> {
                            document = prefix + "\t"
                            editor.renderRaw("1\n$prefix\t$placeholder\n", 3 + prefix.length)
                        }
                        "" -> {
                            assertEquals(2 + prefix.length, start)
                            assertEquals(editor.raw().lastIndexOf('\n'), end)
                            document = prefix
                            editor.renderRaw(original, 2 + prefix.length)
                        }
                        else -> error("Unexpected local replacement")
                    }
                    states.add(document)
                    return true
                }

                override fun sendKeyEvent(event: KeyEvent): Boolean {
                    assertFalse(event.keyCode == KeyEvent.KEYCODE_Z && event.isCtrlPressed)
                    return true
                }
            }
            val history = history()
            val connection = history.wrap(rendering)
            connection.commitText("\t", 1)
            assertEquals(prefix + "\t", document)
            val spaces = " ".repeat(4 - prefix.length % 4)
            editor.renderRaw("1\n$prefix$spaces\n", 2 + prefix.length + spaces.length)
            assertTrue(history.canUndo(connection))
            states.clear()
            writes.clear()
            undoStep(history, connection)
            assertEquals(prefix, document)
            assertEquals(listOf(prefix), states)
            assertEquals(listOf(Mutation(2 + prefix.length, 2 + prefix.length + spaces.length, "")), writes)
            assertEquals(original, editor.raw())
            assertFalse(history.canUndo(connection))
        }
    }

    @Test fun local_undo_restores_the_pre_edit_caret_after_navigation_and_waits_for_delayed_selection() {
        for (numbered in listOf(false, true)) for (delayed in listOf(false, true)) for (rerender in listOf(false, true)) {
            val prefix = if (numbered) "1\nprefix line\n2\n\u200b\n3\n\u200b\n4\n" else "prefix line\n\u200b\n\u200b\n"
            val suffix = if (numbered) "\n5\nsuffix line\n6\ntail\n7\n\u200b\n" else "\nsuffix line\ntail\n\u200b\n"
            val original = prefix + "\u200b" + suffix
            val caret = prefix.length
            val editor = Editor().apply { renderRaw(original, caret) }
            val writes = ArrayList<String>()
            var restoredBody = false
            var selectionRepairs = 0
            val target = object : InputConnectionWrapper(editor, false) {
                override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                    writes.add(text.toString())
                    when (text.toString()) {
                        "\t" -> editor.renderRaw(prefix + "\t\u200b" + suffix, caret + 1)
                        "" -> {
                            assertEquals(caret, Selection.getSelectionStart(editor.editable))
                            assertEquals(caret + 4, Selection.getSelectionEnd(editor.editable))
                            editor.renderRaw(original, caret)
                            Selection.setSelection(editor.editable, caret, original.length - 2)
                            restoredBody = true
                            if (rerender) Handler(Looper.getMainLooper()).postDelayed({
                                Selection.setSelection(editor.editable, caret, original.length - 2)
                            }, 80)
                        }
                        else -> error("Unexpected text replay")
                    }
                    return true
                }
                override fun setSelection(start: Int, end: Int): Boolean {
                    if (restoredBody) {
                        selectionRepairs++
                        if (delayed) {
                            Handler(Looper.getMainLooper()).postDelayed({ Selection.setSelection(editor.editable, start, end) }, 80)
                            return true
                        }
                    }
                    return super.setSelection(start, end)
                }
                override fun sendKeyEvent(event: KeyEvent): Boolean {
                    assertFalse(event.keyCode == KeyEvent.KEYCODE_Z && event.isCtrlPressed)
                    return true
                }
            }
            val history = history()
            val connection = history.wrap(target)
            assertTrue(connection.commitText("\t", 1))
            val expanded = prefix + "    " + suffix
            editor.renderRaw(expanded, caret + 4)
            assertTrue(history.canUndo(connection))
            assertTrue(connection.setSelection(caret + 4, expanded.length - 2))
            assertTrue(history.canUndo(connection))
            writes.clear()
            var completed: Boolean? = null
            history.onUndoCompleted = { completed = it }
            val immediate = history.undo(connection)
            assertFalse(immediate)
            assertTrue(history.hasPendingUndo)
            assertEquals(null, completed)
            settle()
            assertEquals(original, editor.raw())
            assertEquals(caret, Selection.getSelectionStart(editor.editable))
            assertEquals(caret, Selection.getSelectionEnd(editor.editable))
            assertEquals(listOf(""), writes)
            assertEquals(if (rerender && !delayed) 2 else 1, selectionRepairs)
            assertEquals(true, completed)
            assertFalse(history.hasPendingUndo)
            assertFalse(history.canUndo(connection))
        }
    }

    @Test fun failed_cursor_repair_never_repeats_a_completed_text_undo_or_overwrites_external_changes() {
        for (behavior in listOf("reject", "ignore", "external")) {
            val editor = Editor("abc").apply { hold("abc", 1) }
            var undoing = false
            var repair = false
            val target = object : InputConnectionWrapper(editor, false) {
                override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                    val accepted = super.commitText(text, newCursorPosition)
                    if (undoing) {
                        repair = true
                        Selection.setSelection(editor.editable, 0, editor.rawOffset(editor.document.length))
                    }
                    return accepted
                }
                override fun setSelection(start: Int, end: Int): Boolean {
                    if (!repair) return super.setSelection(start, end)
                    if (behavior == "external") editor.hold("external")
                    return behavior != "reject"
                }
            }
            val history = history()
            val connection = history.wrap(target)
            connection.commitText("x", 1)
            connection.commitText("y", 1)
            assertEquals("axybc", editor.document)
            editor.mutations.clear()
            undoing = true
            val accepted = history.undo(connection)
            assertTrue(accepted || history.hasPendingUndo)
            settle()
            assertEquals(listOf(Mutation(2, 3, "")), editor.mutations)
            assertEquals(0, editor.undoCalls)
            assertFalse(history.hasPendingUndo)
            if (behavior == "external") {
                assertEquals("external", editor.document)
                assertFalse(history.canUndo(connection))
            } else {
                assertEquals("axbc", editor.document)
                assertTrue(history.canUndo(connection))
                undoing = false
                repair = false
                undoStep(history, connection)
                assertEquals("abc", editor.document)
                assertFalse(history.canUndo(connection))
            }
        }
    }

    @Test fun tab_rendering_does_not_accept_unrelated_text_or_reinterpret_an_inserted_space() {
        for ((inserted, observed) in listOf("\t" to "    external", " " to "    ")) {
            val editor = Editor()
            editor.renderRaw("1\n\u200b\n", 2)
            val rendering = object : InputConnectionWrapper(editor, false) {
                override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                    editor.renderRaw("1\n$inserted\u200b\n", 2 + inserted.length)
                    return true
                }
            }
            val history = history()
            val connection = history.wrap(rendering)
            connection.commitText(inserted, 1)
            editor.renderRaw("1\n$observed\n", 2 + observed.length)
            assertFalse(history.canUndo(connection))
            assertFalse(history.undo(connection))
            assertEquals("1\n$observed\n", editor.raw())
        }
    }

    @Test fun a_verified_tab_replacement_restores_the_real_tab_in_one_local_write() {
        for (stableInput in listOf(false, true)) for (stableUndo in listOf(false, true)) {
            val editor = Editor()
            var document = "a"
            var expandedWrites = false
            var offsets = intArrayOf()
            val states = ArrayList<String>()
            val writes = ArrayList<Mutation>()
            fun render(expanded: Boolean, caret: Int = document.length) {
                val raw = StringBuilder("1\n")
                offsets = IntArray(document.length + 1)
                offsets[0] = raw.length
                var column = 0
                for ((index, character) in document.withIndex()) {
                    if (character == '\t') {
                        val width = 4 - column % 4
                        raw.append(if (expanded) " ".repeat(width) else "\t")
                        column += width
                    } else { raw.append(character); column++ }
                    offsets[index + 1] = raw.length
                }
                raw.append('\n')
                editor.renderRaw(raw.toString(), offsets[caret])
            }
            render(false)
            val target = object : InputConnectionWrapper(editor, false) {
                override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                    val start = offsets.indexOf(Selection.getSelectionStart(editor.editable))
                    val end = offsets.indexOf(Selection.getSelectionEnd(editor.editable))
                    assertTrue(start >= 0 && end >= start)
                    writes.add(Mutation(start, end, text.toString()))
                    document = document.replaceRange(start, end, text.toString())
                    states.add(document)
                    render(expandedWrites, start + text.toString().length)
                    return true
                }

                override fun sendKeyEvent(event: KeyEvent): Boolean {
                    assertFalse(event.keyCode == KeyEvent.KEYCODE_Z && event.isCtrlPressed)
                    return true
                }
            }
            val history = NativeEditorUndoHistory()
            history.track(target, expected = { it.text.toString().replaceRange(it.selectionStart, it.selectionEnd, "\t") },
                operation = WindowEdit.Commit("\t", 1)) { target.commitText("\t", 1) }
            render(true)
            assertTrue(history.canUndo(target))
            val removed = StringBuilder("\t")
            history.track(target, expected = { it.text.toString().replaceRange(3, 6, "\tb") },
                operation = WindowEdit.Commit("\tb", 1, replacement = WindowEdit.Replacement(3, 6, removed))) {
                target.beginBatchEdit()
                try { target.setSelection(3, 6) && target.commitText("\tb", 1) }
                finally { target.endBatchEdit() }
            }
            removed.replace(0, removed.length, "incorrect")
            if (stableInput) render(true)
            assertEquals("a\tb", document)
            assertTrue(history.canUndo(target))
            expandedWrites = stableUndo
            var refreshing = false
            history.onChange = {
                if (!refreshing) {
                    refreshing = true
                    try { history.canUndo(target) } finally { refreshing = false }
                }
            }
            states.clear()
            writes.clear()
            assertTrue(history.undo(target))
            assertEquals("a\t", document)
            assertEquals(listOf("a\t"), states)
            assertEquals(listOf(Mutation(1, 3, "\t")), writes)
            assertTrue(history.canUndo(target))
            states.clear()
            writes.clear()
            assertTrue(history.undo(target))
            assertEquals("a", document)
            assertEquals(listOf("a"), states)
            assertEquals(listOf(Mutation(1, 2, "")), writes)
            assertFalse(history.canUndo(target))
        }
    }

    @Test fun editor_grouping_does_not_remove_prior_input_during_a_local_undo() {
        val editor = Editor().apply { mergeInputs = true }
        val history = history()
        val connection = history.wrap(editor)
        for (character in "abc") connection.commitText(character.toString(), 1)
        for (expected in listOf("ab", "a", "")) {
            val before = editor.document
            editor.observedDocuments.clear()
            editor.mutations.clear()
            undoStep(history, connection)
            assertEquals(expected, editor.document)
            assertTrue(editor.observedDocuments.all { it == before || it == expected })
            assertEquals(listOf(Mutation(expected.length, before.length, "")), editor.mutations)
        }
        assertEquals(0, editor.undoCalls)
        assertFalse(history.canUndo(connection))
    }

    @Test fun rapid_inputs_keep_placeholder_origins_until_rendering_and_undo_finish() {
        for (inputStyle in listOf("rapid", "immediate", "paced")) for (replayDelay in listOf(true, false)) {
            val editor = Editor()
            var document = ""
            var delayed = inputStyle != "immediate"
            val nativeUndo = ArrayDeque<String>()
            val observed = ArrayList<String>()
            val mutations = ArrayList<Mutation>()
            val handler = Handler(Looper.getMainLooper())
            fun render(placeholder: Boolean) {
                observed.add(document)
                val suffix = if (document.isEmpty() || placeholder) "\u200b" else ""
                editor.renderRaw("1\n$document$suffix\n", 2 + document.length)
            }
            val cleanup = Runnable { render(false) }
            render(false)
            val target = object : InputConnectionWrapper(editor, false) {
                override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                    val start = (minOf(Selection.getSelectionStart(editor.editable), Selection.getSelectionEnd(editor.editable)) - 2).coerceIn(0, document.length)
                    val end = (maxOf(Selection.getSelectionStart(editor.editable), Selection.getSelectionEnd(editor.editable)) - 2).coerceIn(0, document.length)
                    mutations.add(Mutation(start, end, text.toString()))
                    val inheritedPlaceholder = editor.raw().endsWith("\u200b\n")
                    if (nativeUndo.isEmpty()) nativeUndo.addLast(document)
                    document = document.replaceRange(start, end, text.toString())
                    handler.removeCallbacks(cleanup)
                    render(delayed && inheritedPlaceholder)
                    if (delayed) handler.postDelayed(cleanup, 80)
                    return true
                }

                override fun sendKeyEvent(event: KeyEvent): Boolean {
                    if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_Z && event.isCtrlPressed) {
                        assertFalse(event.isShiftPressed)
                        document = nativeUndo.removeLast()
                        handler.removeCallbacks(cleanup)
                        render(false)
                    }
                    return true
                }
            }
            val history = history()
            val connection = history.wrap(target)
            for (character in "abc") {
                connection.commitText(character.toString(), 1)
                if (inputStyle == "paced") {
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(90))
                    assertTrue(history.canUndo(connection))
                }
            }
            if (inputStyle == "rapid") assertEquals("1\nabc\u200b\n", editor.raw())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(90))
            assertEquals("1\nabc\n", editor.raw())
            assertTrue("inputStyle=$inputStyle replayDelay=$replayDelay", history.canUndo(connection))
            delayed = replayDelay
            var refreshing = false
            val refreshed = ArrayList<Pair<String, Boolean>>()
            history.onChange = {
                if (!refreshing) {
                    refreshing = true
                    try {
                        val enabled = history.canUndo(connection)
                        if (!history.hasPendingUndo) refreshed.add(document to enabled)
                    } finally { refreshing = false }
                }
            }
            for (expected in listOf("ab", "a", "")) {
                refreshed.clear()
                observed.clear()
                mutations.clear()
                val before = document
                undoStep(history, connection)
                assertTrue(observed.all { it == before || it == expected })
                assertEquals(listOf(Mutation(expected.length, before.length, "")), mutations)
                assertEquals("inputStyle=$inputStyle replayDelay=$replayDelay", expected, document)
                assertTrue("inputStyle=$inputStyle replayDelay=$replayDelay callbacks=$refreshed",
                    refreshed.contains(expected to expected.isNotEmpty()))
                assertEquals(expected.isNotEmpty(), history.canUndo(connection))
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(90))
            }
            assertFalse(history.canUndo(connection))
        }
    }

    @Test fun a_literal_inserted_zero_width_space_is_not_inherited_from_the_blank_placeholder() {
        val editor = Editor()
        val history = history()
        val connection = history.wrap(editor)
        connection.commitText("A\u200b", 1)
        connection.commitText("B", 1)
        assertEquals("A\u200bB", editor.document)
        editor.hold("AB")
        assertFalse(history.canUndo(connection))
        assertFalse(history.undo(connection))
        assertEquals(0, editor.undoCalls)
    }

    @Test fun context_cut_keeps_prior_input_undo_steps_when_restored_through_native_history() {
        for (deferred in listOf(false, true)) {
            val editor = Editor().apply { mergeInputs = true }
            val target = object : InputConnectionWrapper(editor, false) {
                override fun performContextMenuAction(id: Int): Boolean {
                    assertEquals(android.R.id.cut, id)
                    return editor.performContextMenuAction(id)
                }
            }
            val history = history()
            val connection = history.wrap(target)
            for (letter in listOf("a", "b", "c")) connection.commitText(letter, 1)
            editor.setSelection(editor.rawOffset(1), editor.rawOffset(2))
            editor.deferEdits = deferred
            connection.performContextMenuAction(android.R.id.cut)
            editor.flush()
            editor.deferEdits = false
            assertEquals("ac", editor.document)
            editor.observedDocuments.clear()
            undoStep(history, connection)
            assertEquals("abc", editor.document)
            assertEquals(listOf("abc"), editor.observedDocuments)
            undoStep(history, connection)
            assertEquals("ab", editor.document)
            assertEquals(1, editor.undoCalls)
        }
    }

    @Test fun copied_text_restores_spaces_tabs_and_multiline_selections_through_native_history() {
        for (deferred in listOf(false, true)) for (removed in listOf("two words", "line one\n\nline two", "\n\n", "\t")) {
            val original = "prefix " + removed + " suffix"
            val editor = Editor(original)
            val target = object : InputConnectionWrapper(editor, false) {
                override fun performContextMenuAction(id: Int): Boolean = editor.commitText("", 1)
            }
            val history = history()
            val connection = history.wrap(target)
            editor.setSelection(editor.rawOffset(7), editor.rawOffset(7 + removed.length))
            editor.deferEdits = deferred
            assertTrue(history.cutCopiedSelection(connection, removed))
            if (deferred) assertEquals(original, editor.document)
            editor.flush()
            editor.deferEdits = false
            assertEquals("prefix  suffix", editor.document)
            editor.observedDocuments.clear()
            undoStep(history, connection)
            assertEquals(original, editor.document)
            assertEquals(listOf(original), editor.observedDocuments)
            assertEquals(1, editor.undoCalls)
        }
    }

    @Test fun a_rewritten_clipboard_is_not_replayed_when_native_cut_undo_restores_the_original_transaction() {
        val original = "prefix two words suffix"
        for (copied in listOf("COPIED", "two words attribution", "", "two\u200b words")) {
            val editor = Editor(original).apply { mergeInputs = true }
            var cutCalls = 0
            val target = object : InputConnectionWrapper(editor, false) {
                override fun performContextMenuAction(id: Int): Boolean {
                    cutCalls++
                    return editor.performContextMenuAction(id)
                }
            }
            val history = history()
            val connection = history.wrap(target)
            connection.commitText("!", 1)
            editor.setSelection(editor.rawOffset(7), editor.rawOffset(16))
            editor.observedDocuments.clear()
            assertTrue(history.cutCopiedSelection(connection, copied))
            assertEquals(1, cutCalls)
            assertEquals("prefix  suffix!", editor.document)
            assertTrue(history.canUndo(connection))
            editor.observedDocuments.clear()
            editor.mutations.clear()
            undoStep(history, connection)
            assertEquals(original + "!", editor.document)
            assertEquals(listOf(original + "!"), editor.observedDocuments)
            assertTrue(editor.mutations.isEmpty())
            assertEquals(1, editor.undoCalls)
            undoStep(history, connection)
            assertEquals(original, editor.document)
            assertEquals(1, editor.undoCalls)
        }
    }

    @Test fun multiline_cuts_restore_the_native_transaction_across_line_number_reflow_without_replaying_input() {
        for (tail in listOf("", "\ntail")) for (deferredCut in listOf(false, true)) for (deferredUndo in listOf(false, true)) {
            val removed = "line one\n\nline two"
            val original = "prefix " + removed + " suffix" + tail
            val editor = Editor(original, numbered = true).apply { mergeInputs = true }
            val history = history()
            val connection = history.wrap(editor)
            connection.commitText("x", 1)
            connection.commitText("y", 1)
            editor.setSelection(editor.rawOffset(7), editor.rawOffset(7 + removed.length))
            assertEquals(9, Selection.getSelectionStart(editor.editable))
            assertEquals(32, Selection.getSelectionEnd(editor.editable))
            editor.deferEdits = deferredCut
            assertTrue(history.cutCopiedSelection(connection, removed))
            if (deferredCut) assertEquals(original + "xy", editor.document)
            editor.flush()
            editor.deferEdits = false
            assertEquals("prefix  suffix" + tail + "xy", editor.document)
            val expectedAfter = if (tail.isEmpty()) "1\nprefix  suffixxy\n" else "1\nprefix  suffix\n2\ntailxy\n"
            assertEquals(expectedAfter, editor.raw())
            assertTrue(history.canUndo(connection))
            assertTrue(history.hasUndo)
            editor.observedDocuments.clear()
            editor.mutations.clear()
            editor.deferUndo = deferredUndo
            val immediate = history.undo(connection)
            if (deferredUndo) {
                assertFalse(immediate)
                assertTrue(history.hasPendingUndo)
                editor.flush()
                settle()
            } else assertTrue(immediate)
            assertFalse(history.hasPendingUndo)
            assertEquals(original + "xy", editor.document)
            assertEquals(listOf(original + "xy"), editor.observedDocuments)
            assertTrue(editor.mutations.isEmpty())
            assertEquals(1, editor.undoCalls)
            editor.deferUndo = false
            undoStep(history, connection)
            assertEquals(original + "x", editor.document)
            undoStep(history, connection)
            assertEquals(original, editor.document)
            assertEquals(1, editor.undoCalls)
        }
    }

    @Test fun a_selection_that_changes_after_copy_validation_is_not_cut() {
        val editor = Editor("left right")
        editor.setSelection(editor.rawOffset(0), editor.rawOffset(4))
        var reads = 0
        var cutCalls = 0
        val target = object : InputConnectionWrapper(editor, false) {
            override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? {
                if (++reads == 2) editor.setSelection(editor.rawOffset(5), editor.rawOffset(10))
                return super.getExtractedText(request, flags)
            }
            override fun performContextMenuAction(id: Int): Boolean {
                cutCalls++
                return editor.commitText("", 1)
            }
        }
        val history = history()
        val connection = history.wrap(target)
        assertFalse(history.cutCopiedSelection(connection, "left"))
        assertEquals(0, cutCalls)
        assertEquals("left right", editor.document)
        assertFalse(history.hasUndo)
    }

    @Test fun repeating_native_edit_before_text_does_not_create_an_ambiguous_undo_step() {
        for (reverse in listOf(false, true)) for (deferred in listOf(false, true)) for (removal in listOf("cut", "delete", "forward-delete", "paste")) {
            val editor = Editor("prefix").apply { setSelection(if (reverse) 4 else 2, if (reverse) 2 else 4) }
            val nativeHistory = ArrayDeque<Triple<String, Int, Int>>()
            var nativeUndos = 0
            var restoring = false
            fun remember() { nativeHistory.addLast(Triple(editor.document,
                Selection.getSelectionStart(editor.editable), Selection.getSelectionEnd(editor.editable))) }
            val target = object : InputConnectionWrapper(editor, false) {
                override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? =
                    super.getExtractedText(request, flags)?.apply {
                        val start = minOf(selectionStart, selectionEnd)
                        selectionEnd = maxOf(selectionStart, selectionEnd)
                        selectionStart = start
                    }
                override fun performContextMenuAction(id: Int): Boolean {
                    assertEquals(if (removal == "paste") android.R.id.paste else android.R.id.cut, id)
                    remember()
                    return if (id == android.R.id.paste) editor.commitText("e", 1) else editor.performContextMenuAction(id)
                }
                override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                    assertFalse("Local inverse would collide with the original Cut body", restoring)
                    remember()
                    return super.commitText(text, newCursorPosition)
                }
                override fun sendKeyEvent(event: KeyEvent): Boolean {
                    if (event.keyCode == KeyEvent.KEYCODE_Z && event.isCtrlPressed) {
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            nativeUndos++
                            val saved = nativeHistory.removeLast()
                            val restore = Runnable { editor.hold(saved.first); editor.setSelection(saved.second, saved.third) }
                            if (deferred) Handler(Looper.getMainLooper()).postDelayed(restore, 80) else restore.run()
                        }
                        return true
                    }
                    if (event.action == KeyEvent.ACTION_DOWN && event.keyCode in
                        listOf(KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL)) remember()
                    return super.sendKeyEvent(event)
                }
            }
            val history = history()
            val connection = history.wrap(target)
            if (removal == "cut") assertTrue(history.cutCopiedSelection(connection, "ef"))
            else if (removal == "paste") assertTrue(history.pasteCopiedText(connection, "e"))
            else connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,
                if (removal == "delete") KeyEvent.KEYCODE_DEL else KeyEvent.KEYCODE_FORWARD_DEL))
            assertEquals(if (removal == "paste") "preix" else "prix", editor.document)
            connection.commitText(if (removal == "paste") "f" else "ef", 1)
            assertEquals("prefix", editor.document)
            restoring = true
            undoStep(history, connection, "$removal reinsert $reverse/$deferred")
            assertEquals(if (removal == "paste") "preix" else "prix", editor.document)
            assertEquals(1, nativeUndos)
            assertTrue(history.canUndo(connection))
            undoStep(history, connection, "$removal restore $reverse/$deferred")
            assertEquals("prefix", editor.document)
            assertEquals(2, nativeUndos)
            assertEquals(if (reverse) 4 else 2, Selection.getSelectionStart(editor.editable))
            assertEquals(if (reverse) 2 else 4, Selection.getSelectionEnd(editor.editable))
            assertFalse(history.canUndo(connection))
        }
    }

    @Test fun selected_web_edits_preserve_native_direction_when_input_connection_sorts_endpoints() {
        for (reverse in listOf(false, true)) for (action in listOf("cut", "tab", "delete", "forward-delete")) {
            val editor = Editor("prefix").apply { setSelection(if (reverse) 4 else 2, if (reverse) 2 else 4) }
            val before = editor.raw()
            val anchor = if (reverse) 4 else 2
            val head = if (reverse) 2 else 4
            var nativeUndos = 0
            var restoring = false
            val target = object : InputConnectionWrapper(editor, false) {
                override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? =
                    super.getExtractedText(request, flags)?.apply {
                        val start = minOf(selectionStart, selectionEnd)
                        selectionEnd = maxOf(selectionStart, selectionEnd)
                        selectionStart = start
                    }
                override fun performContextMenuAction(id: Int): Boolean {
                    assertEquals(android.R.id.cut, id)
                    return editor.commitText("", 1)
                }
                override fun sendKeyEvent(event: KeyEvent): Boolean {
                    if (event.keyCode == KeyEvent.KEYCODE_Z && event.isCtrlPressed) {
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            nativeUndos++
                            editor.hold("prefix")
                            editor.setSelection(anchor, head)
                        }
                        return true
                    }
                    return super.sendKeyEvent(event)
                }
                override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                    assertFalse("A selected Web edit must use its native selection history", restoring)
                    return super.commitText(text, newCursorPosition)
                }
            }
            val history = history()
            val connection = history.wrap(target)
            when (action) {
                "cut" -> assertTrue(history.cutCopiedSelection(connection, "ef"))
                "tab" -> assertTrue(connection.commitText("\t", 1))
                else -> connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,
                    if (action == "delete") KeyEvent.KEYCODE_DEL else KeyEvent.KEYCODE_FORWARD_DEL))
            }
            assertTrue(history.canUndo(connection))
            restoring = true
            undoStep(history, connection, "$reverse/$action")
            assertEquals(1, nativeUndos)
            assertEquals(before, editor.raw())
            assertEquals(anchor, Selection.getSelectionStart(editor.editable))
            assertEquals(head, Selection.getSelectionEnd(editor.editable))
            assertFalse(history.canUndo(connection))
        }
    }

    @Test fun copied_tabs_match_their_rendered_columns_and_restore_the_real_tab() {
        for (prefix in listOf("", "a", "ab", "abc")) for (expanded in listOf(false, true)) for (expandedUndo in listOf(false, true)) for (reverse in listOf(false, true)) for (rerender in listOf(false, true)) {
            val editor = Editor()
            var document = prefix + "\t suffix"
            var offsets = intArrayOf()
            var undoing = false
            var nativeUndos = 0
            var savedSelection = 0 to 0
            val writes = mutableListOf<String>()
            fun render(caret: Int = document.length, displayExpanded: Boolean = if (undoing) expandedUndo else expanded) {
                val raw = StringBuilder("1\n")
                offsets = IntArray(document.length + 1)
                offsets[0] = raw.length
                var column = 0
                for ((index, character) in document.withIndex()) {
                    if (character == '\t') {
                        val width = 4 - column % 4
                        raw.append(if (displayExpanded) " ".repeat(width) else "\t")
                        column += width
                    } else { raw.append(character); column++ }
                    offsets[index + 1] = raw.length
                }
                raw.append('\n')
                editor.renderRaw(raw.toString(), offsets[caret])
            }
            render()
            val target = object : InputConnectionWrapper(editor, false) {
                override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                    val anchor = offsets.indexOf(Selection.getSelectionStart(editor.editable))
                    val head = offsets.indexOf(Selection.getSelectionEnd(editor.editable))
                    savedSelection = anchor to head
                    val start = minOf(anchor, head)
                    val end = maxOf(anchor, head)
                    assertTrue(start >= 0 && end >= start)
                    document = document.replaceRange(start, end, text.toString())
                    writes += text.toString()
                    val caret = start + text.toString().length
                    render(caret, if (undoing && rerender) false else if (undoing) expandedUndo else expanded)
                    if (undoing && rerender) Handler(Looper.getMainLooper()).postDelayed({ render(caret) }, 80)
                    return true
                }
                override fun performContextMenuAction(id: Int): Boolean {
                    assertEquals(android.R.id.cut, id)
                    return commitText("", 1)
                }
                override fun sendKeyEvent(event: KeyEvent): Boolean {
                    assertTrue(event.keyCode == KeyEvent.KEYCODE_Z && event.isCtrlPressed)
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        nativeUndos++
                        document = prefix + "\t suffix"
                        fun restore() {
                            render(savedSelection.second)
                            editor.setSelection(offsets[savedSelection.first], offsets[savedSelection.second])
                        }
                        restore()
                        if (rerender) Handler(Looper.getMainLooper()).postDelayed({ restore() }, 80)
                    }
                    return true
                }
            }
            val history = history()
            val connection = history.wrap(target)
            editor.setSelection(offsets[prefix.length + if (reverse) 1 else 0], offsets[prefix.length + if (reverse) 0 else 1])
            assertTrue(history.cutCopiedSelection(connection, "\t"))
            assertEquals(prefix + " suffix", document)
            assertTrue(history.canUndo(connection))
            undoing = true
            undoStep(history, connection)
            settle()
            assertEquals(prefix + "\t suffix", document)
            assertEquals(listOf(""), writes)
            assertEquals(1, nativeUndos)
            assertEquals(offsets[prefix.length + if (reverse) 1 else 0], Selection.getSelectionStart(editor.editable))
            assertEquals(offsets[prefix.length + if (reverse) 0 else 1], Selection.getSelectionEnd(editor.editable))
        }
    }

    @Test fun copying_a_quote_before_cut_preserves_all_content_and_prior_input_history() {
        val prefix = "> quote\n\nbody\n\n"
        val editor = Editor(prefix).apply { mergeInputs = true }
        val target = object : InputConnectionWrapper(editor, false) {
            override fun performContextMenuAction(id: Int): Boolean = editor.commitText("", 1)
        }
        val history = history()
        val connection = history.wrap(target)
        for (letter in listOf("a", "b", "c")) connection.commitText(letter, 1)
        editor.selectAll()
        assertTrue(history.cutCopiedSelection(connection, prefix + "abc"))
        assertEquals("", editor.document)
        undoStep(history, connection)
        assertEquals(prefix + "abc", editor.document)
        undoStep(history, connection)
        assertEquals(prefix + "ab", editor.document)
    }

    @Test fun a_prior_context_paste_does_not_prevent_undoing_the_last_known_commit_locally() {
        val editor = Editor().apply { mergeInputs = true }
        var pasteCalls = 0
        val target = object : InputConnectionWrapper(editor, false) {
            override fun performContextMenuAction(id: Int): Boolean {
                assertEquals(android.R.id.paste, id)
                pasteCalls++
                return editor.commitText("b", 1)
            }
        }
        val history = history()
        val connection = history.wrap(target)
        connection.commitText("a", 1)
        connection.performContextMenuAction(android.R.id.paste)
        connection.commitText("c", 1)
        editor.observedDocuments.clear()
        undoStep(history, connection)
        assertEquals("ab", editor.document)
        assertEquals(listOf("ab"), editor.observedDocuments)
        assertEquals(1, pasteCalls)
        assertEquals(0, editor.undoCalls)
        assertFalse(history.canUndo(connection))
    }

    @Test fun rejected_local_selection_never_changes_the_document() {
        val editor = Editor().apply { mergeInputs = true }
        var rejecting = false
        var selectionCalls = 0
        val target = object : InputConnectionWrapper(editor, false) {
            override fun setSelection(start: Int, end: Int): Boolean {
                selectionCalls++
                return if (rejecting) false else super.setSelection(start, end)
            }
        }
        val history = history()
        val connection = history.wrap(target)
        for (character in "abc") connection.commitText(character.toString(), 1)
        rejecting = true
        editor.observedDocuments.clear()
        editor.mutations.clear()
        assertFalse(history.undo(connection))
        assertEquals("abc", editor.document)
        assertTrue(editor.observedDocuments.isEmpty())
        assertTrue(editor.mutations.isEmpty())
        assertEquals(1, selectionCalls)
        assertEquals(0, editor.undoCalls)
        assertFalse(history.hasPendingUndo)
    }

    @Test fun an_accepted_selection_must_be_observed_before_any_local_replacement() {
        val editor = Editor().apply { mergeInputs = true }
        val target = object : InputConnectionWrapper(editor, false) {
            override fun setSelection(start: Int, end: Int): Boolean = true
        }
        val history = history()
        val connection = history.wrap(target)
        for (character in "abc") connection.commitText(character.toString(), 1)
        editor.observedDocuments.clear()
        editor.mutations.clear()
        var completed: Boolean? = null
        history.onUndoCompleted = { completed = it }
        assertFalse(history.undo(connection))
        assertTrue(history.hasPendingUndo)
        settle()
        assertEquals(false, completed)
        assertEquals("abc", editor.document)
        assertTrue(editor.observedDocuments.isEmpty())
        assertTrue(editor.mutations.isEmpty())
        assertEquals(0, editor.undoCalls)
        assertFalse(history.hasPendingUndo)
    }

    @Test fun an_unobservable_current_snapshot_does_not_start_a_local_or_native_undo() {
        val editor = Editor()
        var readable = true
        val target = object : InputConnectionWrapper(editor, false) {
            override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? =
                if (readable) super.getExtractedText(request, flags) else null

            override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): SurroundingText? =
                if (readable) super.getSurroundingText(beforeLength, afterLength, flags) else null
        }
        val history = history()
        val connection = history.wrap(target)
        connection.commitText("a", 1)
        readable = false
        editor.observedDocuments.clear()
        editor.mutations.clear()
        assertFalse(history.canUndo(connection))
        assertFalse(history.undo(connection))
        assertEquals("a", editor.document)
        assertTrue(editor.observedDocuments.isEmpty())
        assertTrue(editor.mutations.isEmpty())
        assertEquals(0, editor.undoCalls)
    }

    @Test fun replacing_or_deleting_text_in_the_middle_never_touches_its_prefix_or_suffix() {
        val editor = Editor("LoldR")
        val history = history()
        val connection = history.wrap(editor)
        connection.setSelection(1, 4)
        connection.commitText("新", 1)
        editor.observedDocuments.clear()
        editor.mutations.clear()
        undoStep(history, connection)
        assertEquals("LoldR", editor.document)
        assertEquals(listOf("LoldR"), editor.observedDocuments)
        assertTrue(editor.mutations.isEmpty())
        connection.setSelection(4, 4)
        connection.deleteSurroundingTextInCodePoints(1, 0)
        assertEquals("LolR", editor.document)
        editor.observedDocuments.clear()
        editor.mutations.clear()
        undoStep(history, connection)
        assertEquals("LoldR", editor.document)
        assertEquals(listOf("LoldR"), editor.observedDocuments)
        assertEquals(listOf(Mutation(3, 3, "d")), editor.mutations)
        assertEquals(1, editor.undoCalls)
    }

    @Test fun a_rejected_local_replacement_never_clears_or_replays_prior_input() {
        val editor = Editor().apply { mergeInputs = true }
        var rejecting = false
        val target = object : InputConnectionWrapper(editor, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean =
                if (rejecting) false else super.commitText(text, newCursorPosition)
        }
        val history = history()
        val connection = history.wrap(target)
        for (character in "abc") connection.commitText(character.toString(), 1)
        rejecting = true
        editor.observedDocuments.clear()
        assertFalse(history.undo(connection))
        assertEquals("abc", editor.document)
        assertTrue(editor.observedDocuments.isEmpty())
        assertEquals(0, editor.undoCalls)
        assertFalse(history.hasPendingUndo)
    }

    @Test fun more_than_fifty_merged_inputs_are_undone_without_replaying_the_prefix() {
        for (web in listOf(true, false)) {
            val editor = Editor(web = web).apply { mergeInputs = true }
            val history = history()
            val connection = history.wrap(editor)
            repeat(60) { connection.commitText("a", 1) }
            assertEquals("a".repeat(60), editor.document)
            for (length in listOf(59, 58)) {
                val before = editor.document
                editor.observedDocuments.clear()
                undoStep(history, connection)
                assertEquals("a".repeat(length), editor.document)
                assertTrue(editor.observedDocuments.all { it == before || it == editor.document })
            }
            assertEquals(0, editor.undoCalls)
            assertTrue(history.canUndo(connection))
        }
    }

    @Test fun returning_to_a_repeated_prefix_keeps_the_intervening_edit_steps() {
        val editor = Editor("P").apply { mergeInputs = true }
        val history = history()
        val connection = history.wrap(editor)
        connection.commitText("a", 1)
        connection.setSelection(1, 2)
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        connection.commitText("b", 1)
        assertEquals("Pb", editor.document)
        for (expected in listOf("P", "Pa", "P")) {
            undoStep(history, connection)
            assertEquals(expected, editor.document)
        }
        assertFalse(history.canUndo(connection))
    }

    @Test fun separately_batched_preedit_updates_keep_each_committed_candidate_as_one_step() {
        val editor = Editor().apply { mergeInputs = true }
        val history = history()
        val connection = history.wrap(editor)
        for ((preedit, word) in listOf("ni" to "你", "hao" to "好")) {
            for (length in 1..preedit.length) {
                connection.beginBatchEdit()
                connection.setComposingText(preedit.take(length), 1)
                connection.endBatchEdit()
            }
            connection.beginBatchEdit()
            connection.commitText(word, 1)
            connection.endBatchEdit()
        }
        assertEquals("你好", editor.document)
        for (expected in listOf("你", "")) {
            val before = editor.document
            editor.observedDocuments.clear()
            editor.mutations.clear()
            undoStep(history, connection)
            assertEquals(expected, editor.document)
            assertTrue(editor.observedDocuments.all { it == before || it == expected })
            assertEquals(listOf(Mutation(expected.length, before.length, "")), editor.mutations)
        }
        assertEquals(0, editor.undoCalls)
        assertFalse(history.canUndo(connection))
    }

    @Test fun an_older_chain_origin_is_invalidated_by_external_changes_clear_and_field_switch() {
        for (invalidate in listOf("external", "clear", "field")) {
            val editor = Editor().apply { mergeInputs = true }
            val history = history()
            var connection = history.wrap(editor)
            repeat(60) { connection.commitText("a", 1) }
            when (invalidate) {
                "external" -> editor.hold("external")
                "clear" -> history.clear()
                "field" -> connection = history.wrap(Editor("other field"))
            }
            assertFalse(invalidate, history.canUndo(connection))
            assertFalse(invalidate, history.undo(connection))
            assertEquals(invalidate, 0, editor.undoCalls)
        }
    }

    @Test fun restoring_a_quote_then_undoing_input_never_replays_the_earlier_prefix() {
        for (key in listOf(KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL)) {
            val quote = "> quote\nA\u200bB\n\n"
            val editor = Editor(quote).apply { mergeInputs = true }
            val history = history()
            val connection = history.wrap(editor)
            for (letter in "abc") connection.commitText(letter.toString(), 1)
            editor.selectAll()
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
            assertEquals("", editor.document)
            for (suffix in listOf("abc", "ab", "a", "")) {
                val before = editor.document
                editor.observedDocuments.clear()
                undoStep(history, connection)
                assertEquals(quote + suffix, editor.document)
                assertTrue(editor.observedDocuments.all { it == before || it == quote + suffix })
            }
            assertEquals(1, editor.undoCalls)
        }
    }

    @Test fun quoted_replies_and_real_trailing_lines_restore_after_both_delete_keys() {
        for (web in listOf(true, false)) for (key in listOf(KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL)) {
            val originals = listOf("> quoted reply\n\n", "A\u200bB\n\n", "一") + if (web) emptyList() else listOf("\u200b")
            for (original in originals) {
                val editor = Editor(original, web)
                val originalRaw = editor.raw()
                val history = history()
                val connection = history.wrap(editor)
                editor.selectAll()
                connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
                assertEquals("", editor.document)
                assertTrue(history.canUndo(connection))
                undoStep(history, connection)
                assertEquals(original, editor.document)
                assertEquals(originalRaw, editor.raw())
                assertFalse(history.canUndo(connection))
            }
        }
    }

    @Test fun indistinguishable_placeholder_snapshots_do_not_create_unverified_undo() {
        val editor = Editor("\u200b")
        val history = history()
        val connection = history.wrap(editor)
        val beforeRaw = editor.raw()
        editor.selectAll()
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        assertEquals("", editor.document)
        assertEquals(beforeRaw, editor.raw())
        settle()
        assertFalse(history.canUndo(connection))
        assertFalse(history.undo(connection))
        assertEquals("", editor.document)
        assertEquals(0, editor.undoCalls)
    }

    @Test fun composition_undo_uses_the_empty_baseline_after_all_preedit_updates() {
        val editor = Editor()
        val history = history()
        val connection = history.wrap(editor)
        connection.setComposingText("a", 1)
        connection.setComposingText("ab", 1)
        connection.commitText("abc", 1)
        assertTrue(history.canUndo(connection))
        assertTrue(history.undo(connection))
        assertEquals("", editor.document)
        assertFalse(history.canUndo(connection))
    }

    @Test fun batch_commits_keep_previous_history_and_undo_to_empty() {
        val editor = Editor()
        val history = history()
        val connection = history.wrap(editor)
        for (character in "ab") {
            connection.beginBatchEdit()
            connection.commitText(character.toString(), 1)
            connection.endBatchEdit()
        }
        assertTrue(history.undo(connection))
        assertEquals("a", editor.document)
        assertTrue(history.undo(connection))
        assertEquals("", editor.document)
    }

    @Test fun delayed_input_and_native_undo_require_observed_changes() {
        val editor = Editor().apply { deferEdits = true; deferUndo = true }
        val history = history()
        val connection = history.wrap(editor)
        connection.commitText("a", 1)
        assertFalse(history.hasUndo)
        editor.flush()
        settle()
        assertTrue(history.canUndo(connection))
        var completed: Boolean? = null
        history.onUndoCompleted = { completed = it }
        assertFalse(history.undo(connection))
        assertTrue(history.hasPendingUndo)
        assertEquals(null, completed)
        editor.flush()
        settle()
        assertEquals(true, completed)
        assertEquals("", editor.document)
        assertFalse(history.hasPendingUndo)
    }

    @Test fun external_changes_do_not_gain_undo_tickets() {
        val editor = Editor()
        val history = history()
        val connection = history.wrap(editor)
        connection.commitText("a", 1)
        editor.hold("external")
        assertFalse(history.canUndo(connection))
        assertFalse(history.undo(connection))
        assertEquals(0, editor.undoCalls)
    }

    @Test fun unrelated_changes_while_input_is_pending_invalidate_history() {
        val editor = Editor().apply { deferEdits = true }
        val history = history()
        val connection = history.wrap(editor)
        connection.commitText("a", 1)
        editor.hold("unrelated")
        settle()
        assertFalse(history.canUndo(connection))
        assertEquals(0, editor.undoCalls)
    }

    @Test fun accepted_but_ignored_local_undo_is_reported_as_failure() {
        val editor = Editor()
        var ignoring = false
        val target = object : InputConnectionWrapper(editor, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean =
                if (ignoring) true else super.commitText(text, newCursorPosition)
        }
        val history = history()
        val connection = history.wrap(target)
        connection.commitText("a", 1)
        ignoring = true
        var completed: Boolean? = null
        history.onUndoCompleted = { completed = it }
        assertFalse(history.undo(connection))
        settle()
        assertEquals(false, completed)
        assertEquals("a", editor.document)
        assertEquals(0, editor.undoCalls)
        assertFalse(history.hasUndo)
    }

    @Test fun native_clear_restore_that_misses_its_own_before_is_redone_without_replaying() {
        val editor = Editor("> quote\n\n")
        val history = history()
        val connection = history.wrap(editor)
        editor.selectAll()
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        editor.undoOverride = "unrelated"
        var completed: Boolean? = null
        history.onUndoCompleted = { completed = it }
        assertFalse(history.undo(connection))
        settle()
        assertEquals(false, completed)
        assertEquals("", editor.document)
        assertEquals(1, editor.redoCalls)
        assertFalse(history.hasUndo)
    }

}
