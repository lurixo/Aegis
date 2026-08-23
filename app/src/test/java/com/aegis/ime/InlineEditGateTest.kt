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
import com.aegis.ime.ime.PanelTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InlineEditGateTest {

    private val app = RuntimeEnvironment.getApplication()

    private fun started(): AegisInputMethodService {
        val service = Robolectric.buildService(AegisInputMethodService::class.java).get()
        val engine = object : CandidateEngine {
            override fun candidates(composing: String, t9: Boolean): List<String> = emptyList()
        }
        service.javaClass.getDeclaredField("controller").apply {
            isAccessible = true
            set(service, KeyboardController(service, engine, null))
        }
        val info = EditorInfo().apply {
            packageName = "com.example.editor"
            fieldId = 11
            inputType = InputType.TYPE_CLASS_TEXT
        }
        service.onStartInput(info, false)
        service.onCreateInputView() as InputView
        service.onStartInputView(info, false)
        return service
    }

    private fun panelInput(service: AegisInputMethodService): PanelTextInput =
        service.javaClass.getDeclaredField("panelInput").run {
            isAccessible = true
            get(service) as PanelTextInput
        }

    private fun beginEdit(service: AegisInputMethodService, category: String, phrase: String) {
        service.javaClass
            .getDeclaredMethod("beginInlineEdit", String::class.java, String::class.java)
            .apply { isAccessible = true }
            .invoke(service, category, phrase)
    }

    @Test fun a_phrase_too_long_to_edit_reports_it_and_keeps_the_editor_closed() {
        val service = started()
        beginEdit(service, "default", "长".repeat(4097))
        assertEquals(
            app.getString(R.string.phrase_edit_too_long),
            service.toastTextForTest(),
        )
        assertFalse("the inline editor must not open on a refused phrase", panelInput(service).active)
    }

    @Test fun a_phrase_at_the_limit_still_opens_the_editor() {
        val service = started()
        beginEdit(service, "default", "长".repeat(4096))
        assertTrue("a phrase at the limit is editable", panelInput(service).active)
        assertNull("an accepted edit needs no notice", service.toastTextForTest())
    }
}
