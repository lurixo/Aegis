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
import android.view.inputmethod.InputConnectionWrapper
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

    private class Editor(initial: String = "", val web: Boolean = true, val numbered: Boolean = false) :
        BaseInputConnection(View(RuntimeEnvironment.getApplication()), true) {
        var document = initial
            private set
        var mergeInputs = false
        var deferEdits = false
        var deferUndo = false
        var ignoreUndo = false
        var undoOverride: String? = null
        var selectionDelayMs = 0L
        var caretEchoMs = 0L
        private val handler = Handler(Looper.getMainLooper())
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
            if (caretEchoMs > 0) {
                val echoed = rawOffset(caret)
                handler.postDelayed({ Selection.setSelection(editable, minOf(echoed, raw().length)) }, caretEchoMs)
            }
        }

        override fun setSelection(start: Int, end: Int): Boolean {
            if (selectionDelayMs <= 0) return super.setSelection(start, end)
            handler.postDelayed({ Selection.setSelection(editable, start, end) }, selectionDelayMs)
            return true
        }

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

    private fun settle() { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600)) }

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

}
