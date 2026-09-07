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
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import com.aegis.ime.engine.CandidateEngine
import com.aegis.ime.ime.KeyboardController
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmailAssociationServiceTest {
    private class Fixture {
        val service = Robolectric.buildService(AegisInputMethodService::class.java).get()
        val engine = object : CandidateEngine {
            override fun candidates(composing: String, t9: Boolean) = emptyList<String>()
        }
        val controller = KeyboardController(service, engine)
        val editor = BaseInputConnection(FrameLayout(service), true)
        init {
            service.javaClass.getDeclaredField("controller").apply { isAccessible = true; set(service, controller) }
            service.onStartInput(EditorInfo().apply {
                packageName = "com.example.editor"
                fieldId = 101
                inputType = InputType.TYPE_CLASS_TEXT
            }, false)
            for (name in listOf("mInputConnection", "mStartedInputConnection")) {
                service.javaClass.superclass!!.getDeclaredField(name).apply { isAccessible = true; set(service, editor) }
            }
            controller.setEmailAssociationsEnabled(true)
        }
        fun call(name: String, text: String) {
            if (name == "commitLargeText") {
                service.javaClass.getDeclaredMethod(name, CharSequence::class.java, Boolean::class.javaPrimitiveType).apply {
                    isAccessible = true; invoke(service, text, true)
                }
            } else {
                service.javaClass.getDeclaredMethod(name, CharSequence::class.java).apply {
                    isAccessible = true; invoke(service, text)
                }
            }
        }
    }

    @Test fun external_text_symbol_and_clipboard_paste_show_the_same_email_domains() {
        for (entry in listOf("commitExternalText", "commitExternalSymbol", "commitLargeText")) {
            val f = Fixture()
            f.editor.commitText("name", 1)
            f.call(entry, "@")
            assertEquals("name@", f.editor.editable.toString())
            assertTrue("$entry", f.controller.candidateWords().contains("qq.com"))
            f.controller.onPickCandidate(f.controller.candidateWords().indexOf("gmail.com"))
            assertEquals("name@gmail.com", f.editor.editable.toString())
            assertTrue(f.controller.candidateWords().isEmpty())
        }
    }

    @Test fun selection_reports_refresh_email_context_and_dismiss_it_when_the_cursor_moves() {
        val f = Fixture()
        f.editor.commitText("name@", 1)
        f.service.onUpdateSelection(0, 0, 5, 5, -1, -1)
        assertTrue(f.controller.candidateWords().contains("qq.com"))
        Selection.setSelection(f.editor.editable, 2)
        f.service.onUpdateSelection(5, 5, 2, 2, -1, -1)
        assertTrue(f.controller.candidateWords().isEmpty())
    }

    @Test fun external_entries_respect_email_hot_off_and_empty_prefix() {
        for (entry in listOf("commitExternalText", "commitExternalSymbol", "commitLargeText")) {
            val f = Fixture()
            f.call(entry, "@")
            assertTrue(f.controller.candidateWords().isEmpty())
            f.controller.setEmailAssociationsEnabled(false)
            f.call(entry, "name@")
            assertTrue(f.controller.candidateWords().isEmpty())
            assertEquals("@name@", f.editor.editable.toString())
        }
    }
}
