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
import com.aegis.ime.ime.BarFunction
import com.aegis.ime.ime.InputView
import com.aegis.ime.ime.KeyboardController
import com.aegis.ime.ime.PanelTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranslateBarSessionTest {

    private class Session(val service: AegisInputMethodService, val controller: KeyboardController, var view: InputView)

    private fun editor(fieldId: Int = 11, packageName: String = "com.example.editor") = EditorInfo().apply {
        this.packageName = packageName
        this.fieldId = fieldId
        inputType = InputType.TYPE_CLASS_TEXT
    }

    private fun started(info: EditorInfo = editor()): Session {
        val service = Robolectric.buildService(AegisInputMethodService::class.java).get()
        val engine = object : CandidateEngine {
            override fun candidates(composing: String, t9: Boolean): List<String> = emptyList()
        }
        val controller = KeyboardController(service, engine, null)
        service.javaClass.getDeclaredField("controller").apply {
            isAccessible = true
            set(service, controller)
        }
        controller.onShowTranslate = { call(service, "toggleTranslateBar") }
        service.onStartInput(info, false)
        val view = service.onCreateInputView() as InputView
        service.onStartInputView(info, false)
        return Session(service, controller, view)
    }

    private fun call(service: AegisInputMethodService, name: String) {
        service.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(service)
    }

    private fun panelInput(service: AegisInputMethodService): PanelTextInput =
        service.javaClass.getDeclaredField("panelInput").run { isAccessible = true; get(service) as PanelTextInput }

    private fun type(service: AegisInputMethodService, text: String) {
        service.javaClass.getDeclaredMethod("commitExternalText", CharSequence::class.java)
            .apply { isAccessible = true }
            .invoke(service, text)
    }

    private fun beginAddPhrase(service: AegisInputMethodService) {
        service.javaClass.getDeclaredMethod("beginInlineAddPhrase", String::class.java)
            .apply { isAccessible = true }
            .invoke(service, "默认")
    }

    private fun open(s: Session) = s.controller.onBarFunction(BarFunction.TRANSLATE)

    @Test fun the_toolbar_entry_opens_the_bar_and_routes_typing_into_its_field() {
        val s = started()
        open(s)
        assertTrue(s.service.translateBarOpenForTest())
        assertTrue(s.view.isTranslateBarShowing())
        type(s.service, "你好")
        assertEquals("你好", s.view.translateText())
        assertEquals("你好", panelInput(s.service).text())

        open(s)
        assertFalse(s.service.translateBarOpenForTest())
        assertFalse(s.view.isTranslateBarShowing())
        assertFalse(panelInput(s.service).active)
        assertEquals("", s.view.translateText())
    }

    @Test fun the_close_key_closes_the_bar() {
        val s = started()
        open(s)
        type(s.service, "abc")
        s.view.translateBarForTest().closeButtonForTest().performClick()
        assertFalse(s.service.translateBarOpenForTest())
        assertFalse(s.view.isTranslateBarShowing())
        assertFalse(panelInput(s.service).active)
    }

    @Test fun an_inline_edit_covers_the_bar_and_hands_typing_back_afterwards() {
        val s = started()
        open(s)
        type(s.service, "keep me")

        beginAddPhrase(s.service)
        assertTrue(s.view.isEditBarShowing())
        assertFalse(s.view.isTranslateBarShowing())
        assertTrue(s.service.translateBarOpenForTest())
        type(s.service, "新短语")
        assertEquals("新短语", panelInput(s.service).text())
        assertEquals("keep me", s.view.translateText())

        call(s.service, "confirmInlineInput")
        assertFalse(s.view.isEditBarShowing())
        assertTrue(s.view.isTranslateBarShowing())
        type(s.service, "!")
        assertEquals("keep me!", s.view.translateText())
        assertEquals("keep me!", panelInput(s.service).text())
    }

    @Test fun the_toggle_is_inert_while_an_inline_edit_is_active() {
        val s = started()
        beginAddPhrase(s.service)
        type(s.service, "短语")
        open(s)
        assertFalse("the toggle must not arm a hidden translate bar", s.service.translateBarOpenForTest())
        assertFalse(s.view.isTranslateBarShowing())
        assertTrue(s.view.isEditBarShowing())
        assertEquals("短语", panelInput(s.service).text())

        call(s.service, "confirmInlineInput")
        assertFalse(s.view.isTranslateBarShowing())
        assertFalse(panelInput(s.service).active)
    }

    @Test fun switching_editors_keeps_the_bar_open_with_an_empty_field() {
        val s = started()
        open(s)
        type(s.service, "stale")

        val next = editor(fieldId = 22)
        s.service.onFinishInput()
        s.service.onStartInput(next, false)
        s.service.onStartInputView(next, false)

        assertTrue(s.service.translateBarOpenForTest())
        assertTrue(s.view.isTranslateBarShowing())
        assertEquals("", s.view.translateText())
        type(s.service, "fresh")
        assertEquals("fresh", s.view.translateText())
    }

    @Test fun hiding_the_window_keeps_the_bar_for_the_next_show() {
        val s = started()
        open(s)
        type(s.service, "gone")
        s.service.onWindowHidden()
        assertTrue(s.service.translateBarOpenForTest())
        assertTrue(s.view.isTranslateBarShowing())
        assertEquals("", s.view.translateText())
        type(s.service, "back")
        assertEquals("back", s.view.translateText())
    }

    @Test fun a_recreated_input_view_shows_the_open_bar_again() {
        val s = started()
        open(s)
        val info = editor()
        val recreated = s.service.onCreateInputView() as InputView
        s.service.onStartInputView(info, true)
        assertTrue(recreated.isTranslateBarShowing())
        type(s.service, "again")
        assertEquals("again", recreated.translateText())
    }
}
