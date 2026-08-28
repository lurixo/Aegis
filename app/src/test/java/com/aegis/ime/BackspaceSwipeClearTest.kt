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
import com.aegis.ime.ime.EditorSweep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackspaceSwipeClearTest {

    private val engine = object : CandidateEngine {
        override fun candidates(composing: String, t9: Boolean): List<String> = emptyList()
    }

    private class FakeEditor(target: View) : BaseInputConnection(target, true) {
        val readSizes = ArrayList<Int>()
        var extractedWindow = Int.MAX_VALUE
        var walkWindow = Int.MAX_VALUE
        var hidesExtractedText = false
        var hidesWalkedText = false
        var acceptsSelectAll = true
        var acceptsSurroundingDelete = true

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

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
            if (acceptsSurroundingDelete) super.deleteSurroundingText(beforeLength, afterLength) else false

        override fun performContextMenuAction(id: Int): Boolean {
            if (id != android.R.id.selectAll) return super.performContextMenuAction(id)
            if (!acceptsSelectAll) return false
            val content = editable ?: return false
            Selection.setSelection(content, 0, content.length)
            return true
        }
    }

    private class Fixture(val service: AegisInputMethodService, val editor: FakeEditor)

    private fun fixture(inputType: Int = InputType.TYPE_CLASS_TEXT): Fixture {
        val service = Robolectric.buildService(AegisInputMethodService::class.java).get()
        service.javaClass.getDeclaredField("controller").apply {
            isAccessible = true
            set(service, KeyboardController(service, engine, null))
        }
        val info = EditorInfo().apply {
            packageName = "com.example.editor"
            fieldId = 101
            fieldName = "message"
            this.inputType = inputType
        }
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

    private fun swipe(service: AegisInputMethodService, up: Boolean) {
        service.javaClass.getDeclaredMethod("backspaceSwipe", Boolean::class.javaPrimitiveType).apply {
            isAccessible = true
            invoke(service, up)
        }
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
        swipe(f.service, up = false)
        assertEquals("the two halves have to add back up", written, f.editor.held())
    }
}
