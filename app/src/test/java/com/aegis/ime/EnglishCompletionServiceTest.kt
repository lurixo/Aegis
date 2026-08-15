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
import android.view.inputmethod.EditorInfo
import com.aegis.ime.engine.CandidateEngine
import com.aegis.ime.ime.InputView
import com.aegis.ime.ime.KeyboardController
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w853dp-h388dp-land-hdpi")
class EnglishCompletionServiceTest {

    private val words = listOf("orange", "order")

    private val engine = object : CandidateEngine {
        override fun candidates(composing: String, t9: Boolean): List<String> = emptyList()
        override fun englishCompletions(typed: String): List<String> =
            words.filter { it.length > typed.length && it.startsWith(typed, ignoreCase = true) }
    }

    private fun editor() = EditorInfo().apply {
        packageName = "com.example.editor"
        fieldId = 101
        fieldName = "message"
        inputType = InputType.TYPE_CLASS_TEXT
    }

    private class Fixture(
        val service: AegisInputMethodService,
        val controller: KeyboardController,
        val info: EditorInfo,
    )

    private fun typingOr(): Fixture {
        val service = Robolectric.buildService(AegisInputMethodService::class.java).get()
        val controller = KeyboardController(service, engine)
        service.javaClass.getDeclaredField("controller").apply {
            isAccessible = true
            set(service, controller)
        }
        val info = editor()
        service.onStartInput(info, false)
        service.onCreateInputView() as InputView
        service.onStartInputView(info, false)
        controller.setAssociationsEnabled(true)
        controller.onKey(Key("", action = KeyAction.TOGGLE_LANG))
        "or".forEach { controller.onKey(Key(it.toString(), output = it.toString())) }
        assertEquals("or", controller.englishWordForTest())
        assertEquals(listOf("or", "orange", "order"), controller.candidateWords())
        return Fixture(service, controller, info)
    }

    @Test
    fun selection_reports_from_the_editor_leave_the_composing_word_alone() {
        val f = typingOr()
        f.service.onUpdateSelection(0, 0, 2, 2, -1, -1)
        f.service.onUpdateSelection(2, 2, 12, 12, -1, -1)
        assertEquals("or", f.controller.englishWordForTest())
        assertEquals(listOf("or", "orange", "order"), f.controller.candidateWords())
    }

    @Test
    fun a_restart_on_the_same_field_keeps_the_composing_word() {
        val f = typingOr()
        f.service.onStartInput(f.info, true)
        assertEquals("or", f.controller.englishWordForTest())
        assertEquals(listOf("or", "orange", "order"), f.controller.candidateWords())
    }

    @Test
    fun the_end_of_the_input_session_drops_the_composing_word() {
        val f = typingOr()
        f.service.onFinishInput()
        assertEquals("", f.controller.englishWordForTest())
        assertEquals(emptyList<String>(), f.controller.candidateWords())
    }
}
