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

    private fun store(service: AegisInputMethodService): com.aegis.ime.user.ClipboardStore {
        val delegate = service.javaClass.getDeclaredField("clipboardStore\$delegate").run {
            isAccessible = true
            get(service) as Lazy<*>
        }
        return delegate.value as com.aegis.ime.user.ClipboardStore
    }

    private fun beginRename(service: AegisInputMethodService, old: String) {
        service.javaClass.getDeclaredMethod("beginInlineRenameCategory", String::class.java)
            .apply { isAccessible = true }.invoke(service, old)
    }

    private fun confirm(service: AegisInputMethodService) {
        service.javaClass.getDeclaredMethod("confirmInlineInput")
            .apply { isAccessible = true }.invoke(service)
    }

    @Test fun confirming_an_untouched_rename_rewrites_nothing() {
        val service = started()
        val legacy = "a\u0001b"
        store(service).importPhrasesText("C\t" + legacy + "\nP\tx\n", merge = false)
        beginRename(service, legacy)
        confirm(service)
        assertTrue("the legacy name stays exactly as it was", legacy in store(service).categories())
        assertFalse("no cleaned twin appears", "ab" in store(service).categories())
    }

    @Test fun an_actual_rename_still_cleans_the_new_name() {
        val service = started()
        val legacy = "a\u0001b"
        store(service).importPhrasesText("C\t" + legacy + "\nP\tx\n", merge = false)
        beginRename(service, legacy)
        panelInput(service).selectAll()
        service.javaClass.getDeclaredMethod("commitExternalText", CharSequence::class.java)
            .apply { isAccessible = true }.invoke(service, " new\u0000name ")
        confirm(service)
        assertTrue("the new name passes the rule", " newname " in store(service).categories())
        assertFalse("the old name is gone", legacy in store(service).categories())
    }

    private fun editInPanel(service: AegisInputMethodService, action: com.aegis.ime.ime.EditAction) {
        service.javaClass.getDeclaredMethod("handleEditInPanel", com.aegis.ime.ime.EditAction::class.java)
            .apply { isAccessible = true }.invoke(service, action)
    }

    private fun beginAdd(service: AegisInputMethodService) {
        service.javaClass.getDeclaredMethod("beginInlineAddPhrase", String::class.java)
            .apply { isAccessible = true }.invoke(service, "default")
    }

    @Test fun select_all_on_an_empty_field_reports_nothing_to_select() {
        val service = started()
        beginAdd(service)
        editInPanel(service, com.aegis.ime.ime.EditAction.SELECT_ALL)
        assertEquals(app.getString(R.string.edit_no_selection), service.toastTextForTest())
    }

    @Test fun select_all_with_text_still_reports_it_selected() {
        val service = started()
        beginAdd(service)
        service.javaClass.getDeclaredMethod("commitExternalText", CharSequence::class.java)
            .apply { isAccessible = true }.invoke(service, "abc")
        editInPanel(service, com.aegis.ime.ime.EditAction.SELECT_ALL)
        assertEquals(app.getString(R.string.edit_select_all_done), service.toastTextForTest())
        assertTrue("the field really is selected", panelInput(service).hasSelection())
    }
}
