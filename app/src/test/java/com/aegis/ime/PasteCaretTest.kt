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
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import com.aegis.ime.engine.CandidateEngine
import com.aegis.ime.ime.KeyboardController
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PasteCaretTest {

    private val engine = object : CandidateEngine {
        override fun candidates(composing: String, t9: Boolean): List<String> = emptyList()
    }

    private class BlockEditor(target: View, val lagsPerBreak: Int) : BaseInputConnection(target, true) {
        var arrows = 0

        fun hold(text: CharSequence) {
            val content = requireNotNull(editable)
            content.replace(0, content.length, text)
            Selection.setSelection(content, content.length)
        }

        fun held(): String = requireNotNull(editable).toString()

        fun caret(): Int = Selection.getSelectionEnd(requireNotNull(editable))

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val done = super.commitText(text, newCursorPosition)
            val lag = lagsPerBreak * (text?.count { it == '\n' } ?: 0)
            if (lag > 0) {
                val content = requireNotNull(editable)
                Selection.setSelection(content, (caret() - lag).coerceAtLeast(0))
            }
            return done
        }

        override fun sendKeyEvent(event: KeyEvent?): Boolean {
            if (event?.action != KeyEvent.ACTION_DOWN || event.keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) {
                return super.sendKeyEvent(event)
            }
            arrows++
            val content = requireNotNull(editable)
            Selection.setSelection(content, (caret() + 1).coerceAtMost(content.length))
            return true
        }
    }

    private class Fixture(val service: AegisInputMethodService, val editor: BlockEditor)

    private fun fixture(lagsPerBreak: Int): Fixture {
        val service = Robolectric.buildService(AegisInputMethodService::class.java).get()
        service.javaClass.getDeclaredField("controller").apply {
            isAccessible = true
            set(service, KeyboardController(service, engine, null))
        }
        service.onStartInput(
            EditorInfo().apply {
                packageName = "com.example.editor"
                fieldId = 101
                inputType = InputType.TYPE_CLASS_TEXT
            },
            false,
        )
        val editor = BlockEditor(FrameLayout(service), lagsPerBreak)
        val framework = requireNotNull(service.javaClass.superclass)
        for (fieldName in listOf("mInputConnection", "mStartedInputConnection")) {
            framework.getDeclaredField(fieldName).apply {
                isAccessible = true
                set(service, editor)
            }
        }
        return Fixture(service, editor)
    }

    private fun paste(service: AegisInputMethodService, text: CharSequence) {
        service.javaClass.getDeclaredMethod(
            "commitLargeText",
            CharSequence::class.java,
            Boolean::class.javaPrimitiveType,
        ).apply {
            isAccessible = true
            invoke(service, text, true)
        }
    }

    @Test fun text_pasted_into_a_block_editor_leaves_the_caret_at_its_end() {
        val f = fixture(lagsPerBreak = 1)
        val service = f.service
        val editor = f.editor
        editor.hold("")
        val pasted = "第一行\n第二行\n第三行\n第四行"

        paste(service, pasted)

        assertEquals("the paste must land whole", pasted, editor.held())
        assertEquals("the caret must sit at the end of what was pasted", pasted.length, editor.caret())
        assertEquals("one step for each break the editor swallowed", 3, editor.arrows)
    }

    @Test fun a_paste_with_more_breaks_than_a_fixed_step_budget_still_lands_at_the_end() {
        val f = fixture(lagsPerBreak = 1)
        val service = f.service
        val editor = f.editor
        editor.hold("")
        val lines = 4_000
        val pasted = (0 until lines).joinToString("\n") { "行" }

        paste(service, pasted)

        assertEquals("the paste must land whole", pasted, editor.held())
        assertEquals("the caret must sit at the end of what was pasted", pasted.length, editor.caret())
        assertEquals("no step budget may cut the catch-up short", lines - 1, editor.arrows)
    }

    @Test fun text_pasted_into_an_editor_that_keeps_up_is_left_alone() {
        val f = fixture(lagsPerBreak = 0)
        val service = f.service
        val editor = f.editor
        editor.hold("")
        val pasted = "第一行\n第二行\n第三行"

        paste(service, pasted)

        assertEquals("the paste must land whole", pasted, editor.held())
        assertEquals("the caret must sit at the end of what was pasted", pasted.length, editor.caret())
        assertEquals("an editor that keeps up must not be nudged", 0, editor.arrows)
    }

    @Test fun a_paste_without_line_breaks_never_reaches_for_the_editor() {
        val f = fixture(lagsPerBreak = 1)
        val service = f.service
        val editor = f.editor
        editor.hold("已有内容")
        val pasted = "一段没有换行的内容"

        paste(service, pasted)

        assertEquals("the paste must land whole", "已有内容" + pasted, editor.held())
        assertEquals("nothing to catch up on", 0, editor.arrows)
    }

    @Test fun a_paste_in_the_middle_of_a_block_editor_still_ends_where_it_was_written() {
        val f = fixture(lagsPerBreak = 1)
        val service = f.service
        val editor = f.editor
        editor.hold("开头结尾")
        Selection.setSelection(requireNotNull(editor.editable), 2)
        val pasted = "甲\n乙\n丙"

        paste(service, pasted)

        assertEquals("the paste must land where the caret was", "开头" + pasted + "结尾", editor.held())
        assertEquals("the caret must follow what was pasted", 2 + pasted.length, editor.caret())
    }
}
