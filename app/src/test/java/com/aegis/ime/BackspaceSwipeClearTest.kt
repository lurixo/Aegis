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

import android.os.Looper
import android.text.InputType
import android.text.Selection
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.widget.FrameLayout
import com.aegis.ime.engine.CandidateEngine
import com.aegis.ime.ime.KeyboardController
import android.view.inputmethod.InputConnection
import com.aegis.ime.ime.ClearedTextRestore
import com.aegis.ime.ime.LargeCommit
import com.aegis.ime.ime.EditorSweep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackspaceSwipeClearTest {

    @Before fun clean() {
        File(RuntimeEnvironment.getApplication().filesDir, "cleared_text.txt").delete()
    }

    private val engine = object : CandidateEngine {
        override fun candidates(composing: String, t9: Boolean): List<String> = emptyList()
    }

    private class FakeEditor(target: View) : BaseInputConnection(target, true) {
        val committedChunks = ArrayList<Int>()
        val readSizes = ArrayList<Int>()
        var extractedWindow = Int.MAX_VALUE
        var walkWindow = Int.MAX_VALUE
        var hidesExtractedText = false
        var hidesWalkedText = false
        var acceptsSelectAll = true
        var acceptsSurroundingDelete = true
        var surroundingDeletesAllowed = Int.MAX_VALUE
        var selectAllCalls = 0

        fun hold(text: CharSequence) {
            val content = requireNotNull(editable)
            content.replace(0, content.length, text)
            Selection.setSelection(content, content.length)
        }

        fun held(): String = requireNotNull(editable).toString()

        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? {
            if (hidesExtractedText) return null
            val content = editable ?: return null
            val window = minOf(content.length, extractedWindow)
            return ExtractedText().apply {
                startOffset = 0
                text = content.subSequence(0, window)
                selectionStart = Selection.getSelectionStart(content).coerceIn(0, window)
                selectionEnd = Selection.getSelectionEnd(content).coerceIn(0, window)
            }
        }

        override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence? =
            if (hidesWalkedText) null else {
                readSizes.add(length)
                super.getTextBeforeCursor(minOf(length, walkWindow), flags)
            }

        override fun getTextAfterCursor(length: Int, flags: Int): CharSequence? =
            if (hidesWalkedText) null else {
                readSizes.add(length)
                super.getTextAfterCursor(minOf(length, walkWindow), flags)
            }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            if (!text.isNullOrEmpty()) committedChunks.add(text.length)
            return super.commitText(text, newCursorPosition)
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            if (!acceptsSurroundingDelete || surroundingDeletesAllowed <= 0) return false
            surroundingDeletesAllowed--
            return super.deleteSurroundingText(beforeLength, afterLength)
        }

        override fun performContextMenuAction(id: Int): Boolean {
            if (id != android.R.id.selectAll) return super.performContextMenuAction(id)
            selectAllCalls++
            if (!acceptsSelectAll) return false
            val content = editable ?: return false
            Selection.setSelection(content, 0, content.length)
            return true
        }
    }

    private class Fixture(val service: AegisInputMethodService, val editor: FakeEditor)

    private fun fixture(inputType: Int = InputType.TYPE_CLASS_TEXT, fieldId: Int = 101): Fixture {
        val service = Robolectric.buildService(AegisInputMethodService::class.java).get()
        service.javaClass.getDeclaredField("controller").apply {
            isAccessible = true
            set(service, KeyboardController(service, engine, null))
        }
        val info = editor(fieldId, inputType)
        service.onStartInput(info, false)
        val editor = FakeEditor(FrameLayout(service))
        val framework = requireNotNull(service.javaClass.superclass)
        for (fieldName in listOf("mInputConnection", "mStartedInputConnection")) {
            framework.getDeclaredField(fieldName).apply {
                isAccessible = true
                set(service, editor)
            }
        }
        return Fixture(service, editor)
    }

    private fun editor(fieldId: Int, inputType: Int) = EditorInfo().apply {
        packageName = "com.example.editor"
        this.fieldId = fieldId
        fieldName = "message"
        this.inputType = inputType
    }

    private fun swipe(service: AegisInputMethodService, up: Boolean) {
        service.javaClass.getDeclaredMethod("backspaceSwipe", Boolean::class.javaPrimitiveType).apply {
            isAccessible = true
            invoke(service, up)
        }
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMinutes(10))
    }

    private fun longText(chars: Int): String = String(CharArray(chars) { '一' + (it % 2048) })

    @Test fun a_field_the_editor_only_shows_a_window_of_still_comes_back_whole() {
        val f = fixture()
        val written = longText(20890)
        f.editor.hold(written)
        f.editor.extractedWindow = 5000

        swipe(f.service, up = true)
        assertEquals("the swipe must clear the whole field", "", f.editor.held())

        swipe(f.service, up = false)
        assertEquals("what the window hid must come back too", written, f.editor.held())
    }

    @Test fun a_field_longer_than_one_read_comes_back_whole_without_the_extracted_text() {
        val f = fixture()
        val written = longText(12345)
        f.editor.hold(written)
        f.editor.hidesExtractedText = true

        swipe(f.service, up = true)
        assertEquals("the swipe must clear the whole field", "", f.editor.held())

        swipe(f.service, up = false)
        assertEquals("walking the field in chunks must reach its end", written, f.editor.held())
    }

    @Test fun a_selection_pending_when_the_swipe_lands_is_part_of_what_comes_back() {
        val f = fixture()
        val written = longText(9000)
        f.editor.hold(written)
        f.editor.hidesExtractedText = true
        Selection.setSelection(requireNotNull(f.editor.editable), 1000, 6000)

        swipe(f.service, up = true)
        assertEquals("the swipe must clear the whole field", "", f.editor.held())

        swipe(f.service, up = false)
        assertEquals("the selected run sits between the two walks", written, f.editor.held())
    }

    @Test fun an_editor_that_refuses_select_all_is_still_emptied() {
        val f = fixture()
        val written = "先写下的内容"
        f.editor.hold(written)
        f.editor.acceptsSelectAll = false

        swipe(f.service, up = true)
        assertEquals("select all is not the only way to empty a field", "", f.editor.held())

        swipe(f.service, up = false)
        assertEquals("what was cleared comes back once, not twice", written, f.editor.held())
    }

    @Test fun an_editor_that_deletes_nothing_at_all_keeps_what_it_holds_undoubled() {
        val f = fixture()
        val written = "删不掉的内容"
        f.editor.hold(written)
        f.editor.acceptsSelectAll = false
        f.editor.acceptsSurroundingDelete = false

        swipe(f.service, up = true)
        assertEquals("nothing was deleted, so nothing was lost", written, f.editor.held())

        swipe(f.service, up = false)
        assertEquals("a clear that never landed must not be restored on top", written, f.editor.held())
    }

    @Test fun a_field_that_reads_back_as_empty_is_cleared_all_the_same() {
        val f = fixture()
        f.editor.hold("读不出来的内容")
        f.editor.hidesExtractedText = true
        f.editor.hidesWalkedText = true

        swipe(f.service, up = true)

        assertEquals("an unreadable field is no reason to leave it filled", "", f.editor.held())
    }

    @Test fun what_a_swipe_cleared_outlives_the_editor_it_came_from() {
        val f = fixture()
        val written = "换个输入框也要回得来"
        f.editor.hold(written)
        swipe(f.service, up = true)

        f.service.onStartInput(editor(fieldId = 202, inputType = InputType.TYPE_CLASS_TEXT), false)

        swipe(f.service, up = false)
        assertEquals("leaving the field must not throw the cleared text away", written, f.editor.held())
    }

    @Test fun what_a_swipe_cleared_outlives_the_process_that_cleared_it() {
        val first = fixture()
        val written = "进程重建也要回得来"
        first.editor.hold(written)
        swipe(first.service, up = true)

        val second = fixture()
        swipe(second.service, up = false)

        assertEquals("a snapshot only in memory dies with the process", written, second.editor.held())
    }

    @Test fun what_a_swipe_cleared_is_put_back_once_and_not_again() {
        val f = fixture()
        val written = "一次"
        f.editor.hold(written)
        swipe(f.service, up = true)

        swipe(f.service, up = false)
        swipe(f.service, up = false)

        assertEquals("a second restore has nothing left to put back", written, f.editor.held())
    }

    @Test fun a_field_bigger_than_one_read_still_comes_back_whole() {
        val f = fixture()
        val written = longText(EditorSweep.CHUNK * 5 + 77)
        f.editor.hold(written)
        f.editor.walkWindow = EditorSweep.CHUNK
        f.editor.extractedWindow = EditorSweep.CHUNK

        swipe(f.service, up = true)
        assertEquals("a field no single read can reach must still be emptied", "", f.editor.held())

        swipe(f.service, up = false)
        assertEquals("what no single read could reach must still come back", written, f.editor.held())
    }

    @Test fun a_swipe_reads_the_field_in_bounded_steps() {
        val f = fixture()
        f.editor.hold(longText(EditorSweep.CHUNK * 3))
        f.editor.walkWindow = EditorSweep.CHUNK

        swipe(f.service, up = true)

        assertTrue(
            "no single read may ask for the whole field, was " + f.editor.readSizes,
            f.editor.readSizes.isNotEmpty() && f.editor.readSizes.all { it <= EditorSweep.CHUNK },
        )
    }

    @Test fun a_field_longer_than_the_walk_can_reach_keeps_what_it_could_not_carry() {
        val f = fixture()
        val reach = 8
        val written = longText(reach * EditorSweep.MAX_ROUNDS + 952)
        f.editor.hold(written)
        f.editor.walkWindow = reach

        swipe(f.service, up = true)

        val left = written.length - reach * EditorSweep.MAX_ROUNDS
        assertEquals(
            "what the walk could not carry has to stay where it is",
            written.substring(0, left),
            f.editor.held(),
        )
        assertEquals(
            "and what it did carry has to be the snapshot",
            written.substring(left),
            File(RuntimeEnvironment.getApplication().filesDir, "cleared_text.txt").readText(),
        )

        swipe(f.service, up = false)
        assertEquals("the two halves have to add back up", written, f.editor.held())
    }

    @Test fun a_delete_the_editor_stops_taking_leaves_the_rest_where_it_is() {
        val f = fixture()
        val reach = 8
        val taken = 3
        val written = longText(reach * (taken + 4))
        f.editor.hold(written)
        f.editor.walkWindow = reach
        f.editor.surroundingDeletesAllowed = taken

        swipe(f.service, up = true)

        val left = written.length - reach * taken
        assertEquals(
            "a walk the editor cut short must not hand the rest to select all",
            0,
            f.editor.selectAllCalls,
        )
        assertEquals(
            "what the editor would not delete has to stay where it is",
            written.substring(0, left),
            f.editor.held(),
        )
        assertEquals(
            "and only what it did delete may be the snapshot",
            written.substring(left),
            File(RuntimeEnvironment.getApplication().filesDir, "cleared_text.txt").readText(),
        )

        swipe(f.service, up = false)
        assertEquals("the two halves have to add back up", written, f.editor.held())
    }

    @Test fun a_field_too_big_for_one_transaction_goes_back_in_chunks() {
        val f = fixture()
        val written = longText(LargeCommit.CHUNK * 2 + 1234)
        f.editor.hold(written)

        swipe(f.service, up = true)
        f.editor.committedChunks.clear()
        swipe(f.service, up = false)

        assertTrue(
            "one commit of this size would not fit through a binder transaction, was " +
                f.editor.committedChunks,
            f.editor.committedChunks.size > 1 && f.editor.committedChunks.all { it <= LargeCommit.CHUNK },
        )
        assertEquals("the chunks must put the field back exactly as it was", written, f.editor.held())
    }

    @Test fun a_first_line_longer_than_one_frame_still_comes_back_in_order() {
        val f = fixture()
        val written = longText(20_000) + "\nBBB\nCCC"
        f.editor.hold(written)

        swipe(f.service, up = true)
        swipe(f.service, up = false)

        assertEquals(
            "a piece handed over across frames must finish before the next one starts",
            written,
            f.editor.held(),
        )
    }

    @Test fun a_restore_reaches_as_far_as_a_clear_can_capture() {
        assertEquals(
            "a clear that keeps more than a restore can put back would lose the difference",
            EditorSweep.CHUNK * EditorSweep.MAX_ROUNDS,
            ClearedTextRestore.MAX_CHARS,
        )
    }

    private class OpeningEditor(target: View, val opens: Int) : BaseInputConnection(target, true) {
        fun held(): String = requireNotNull(editable).toString()

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val done = super.commitText(text, newCursorPosition)
            val content = requireNotNull(editable)
            val at = Selection.getSelectionEnd(content)
            if (opens > 0 && !text.isNullOrEmpty() && at == content.length) {
                content.insert(at, "\n".repeat(opens))
                Selection.setSelection(content, at)
            }
            return done
        }

        override fun sendKeyEvent(event: android.view.KeyEvent?): Boolean {
            if (event?.action != android.view.KeyEvent.ACTION_DOWN ||
                event.keyCode != android.view.KeyEvent.KEYCODE_DPAD_RIGHT
            ) {
                return super.sendKeyEvent(event)
            }
            val content = requireNotNull(editable)
            val at = Selection.getSelectionEnd(content)
            Selection.setSelection(content, minOf(at + 1, content.length))
            return true
        }
    }

    private fun openingFixture(opens: Int): Pair<AegisInputMethodService, OpeningEditor> {
        val service = Robolectric.buildService(AegisInputMethodService::class.java).get()
        service.javaClass.getDeclaredField("controller").apply {
            isAccessible = true
            set(service, KeyboardController(service, engine, null))
        }
        service.onStartInput(editor(101, InputType.TYPE_CLASS_TEXT), false)
        val opened = OpeningEditor(FrameLayout(service), opens)
        val framework = requireNotNull(service.javaClass.superclass)
        for (fieldName in listOf("mInputConnection", "mStartedInputConnection")) {
            framework.getDeclaredField(fieldName).apply {
                isAccessible = true
                set(service, opened)
            }
        }
        return service to opened
    }

    @Test fun empty_lines_the_editor_opens_are_not_left_behind_by_a_restore() {
        val (service, opened) = openingFixture(opens = 2)
        seedCleared("AAA")

        swipe(service, up = false)

        assertEquals("what was put back must end where the snapshot ended", "AAA", opened.held())
    }

    @Test fun empty_lines_the_editor_opens_are_not_left_behind_after_a_restore_with_breaks() {
        val (service, opened) = openingFixture(opens = 2)
        seedCleared("AAA\nBBB")

        swipe(service, up = false)

        assertEquals("what was put back must end where the snapshot ended", "AAA\nBBB", opened.held())
    }

    private class BlockEditor(target: View) : BaseInputConnection(target, true) {
        val paragraphs = ArrayList<String>().apply { add("") }

        fun read(): String =
            if (paragraphs.size == 1 && paragraphs[0].isEmpty()) "" else paragraphs.joinToString("") { it + "\n\n" }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val parts = (text ?: "").toString().split('\n')
            paragraphs[paragraphs.size - 1] = paragraphs.last() + parts.first()
            for (part in parts.drop(1)) paragraphs.add(part)
            return true
        }

        override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence {
            val held = read()
            return held.substring(maxOf(0, held.length - length))
        }

        override fun getTextAfterCursor(length: Int, flags: Int): CharSequence = ""

        override fun getSelectedText(flags: Int): CharSequence? = null

        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText =
            ExtractedText().apply {
                startOffset = 0
                text = read()
                selectionStart = text.length
                selectionEnd = text.length
            }
    }

    private fun blockFixture(): Pair<AegisInputMethodService, BlockEditor> {
        val service = Robolectric.buildService(AegisInputMethodService::class.java).get()
        service.javaClass.getDeclaredField("controller").apply {
            isAccessible = true
            set(service, KeyboardController(service, engine, null))
        }
        service.onStartInput(editor(101, InputType.TYPE_CLASS_TEXT), false)
        val block = BlockEditor(FrameLayout(service))
        val framework = requireNotNull(service.javaClass.superclass)
        for (fieldName in listOf("mInputConnection", "mStartedInputConnection")) {
            framework.getDeclaredField(fieldName).apply {
                isAccessible = true
                set(service, block)
            }
        }
        return service to block
    }

    private fun seedCleared(text: String) {
        File(RuntimeEnvironment.getApplication().filesDir, "cleared_text.txt").writeText(text)
    }

    @Test fun a_block_editor_gets_one_break_for_each_pair_of_newlines_it_reported() {
        val (service, block) = blockFixture()
        seedCleared("AAA\n\nBBB\n\nCCC\n\n")

        swipe(service, up = false)

        assertEquals(
            "each paragraph must come back as one paragraph, not one paragraph and a blank line",
            listOf("AAA", "BBB", "CCC", ""),
            block.paragraphs,
        )
    }

    @Test fun a_block_editor_keeps_the_blank_paragraph_that_was_really_there() {
        val (service, block) = blockFixture()
        seedCleared("AAA\n\n\n\nCCC\n\n")

        swipe(service, up = false)

        assertEquals(
            "a run of four newlines stood for a real empty paragraph",
            listOf("AAA", "", "CCC", ""),
            block.paragraphs,
        )
    }

    @Test fun a_plain_field_still_gets_every_newline_it_reported() {
        val f = fixture()
        val written = "AAA\nBBB\n\nCCC"
        f.editor.hold(written)

        swipe(f.service, up = true)
        swipe(f.service, up = false)

        assertEquals("a field that reads back what it was given must round trip", written, f.editor.held())
    }

    @Test fun an_unmeasurable_target_falls_back_to_writing_the_newlines_verbatim() {
        val put = StringBuilder()

        ClearedTextRestore.restore(
            "AAA\n\nBBB",
            measure = { 0 },
            commit = { part, then -> put.append(part); then() },
            done = {},
        )

        assertEquals("with no signal to go on, nothing may be dropped", "AAA\n\nBBB", put.toString())
    }

    @Test fun a_run_of_newlines_never_comes_back_as_none() {
        val put = StringBuilder()
        var reads = 0

        ClearedTextRestore.restore(
            "AAA\n\nBBB",
            measure = { if (reads++ == 0) 0 else 9000 },
            commit = { part, then -> put.append(part); then() },
            done = {},
        )

        assertEquals("an absurd measurement must still leave the lines apart", "AAA\nBBB", put.toString())
    }
}
