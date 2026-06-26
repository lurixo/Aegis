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
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

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

    private fun editText(service: AegisInputMethodService): String =
        (service.javaClass.getDeclaredField("inputView").run {
            isAccessible = true
            get(service) as InputView
        }).editBarForTest().fieldForTest().text.toString()

    private fun fileKey(file: File): Any? = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java).fileKey()

    private fun seedDefaultAndWork(service: AegisInputMethodService) {
        assertTrue(store(service).importPhrasesText("C\tdefault\nP\t你好\nC\t工作\n", merge = false))
    }

    @Test fun renaming_the_default_category_prefills_its_shown_name_not_its_id() {
        val service = started()
        seedDefaultAndWork(service)
        beginRename(service, com.aegis.ime.user.ClipboardStore.DEFAULT_CATEGORY_ID)
        assertEquals(app.getString(R.string.clip_default_category), editText(service))
    }

    @Test
    @Config(sdk = [34], qualifiers = "zh")
    fun renaming_the_default_category_in_chinese_prefills_its_chinese_name() {
        val service = started()
        seedDefaultAndWork(service)
        beginRename(service, com.aegis.ime.user.ClipboardStore.DEFAULT_CATEGORY_ID)
        assertEquals("默认", editText(service))
    }

    @Test fun confirming_the_prefilled_default_name_keeps_the_id_and_writes_nothing() {
        val service = started()
        seedDefaultAndWork(service)
        val file = File(app.filesDir, "phrases.txt")
        val written = file.readText()
        val identity = fileKey(file)
        beginRename(service, com.aegis.ime.user.ClipboardStore.DEFAULT_CATEGORY_ID)
        confirm(service)
        store(service).flushPendingWrites()
        assertEquals(listOf(com.aegis.ime.user.ClipboardStore.DEFAULT_CATEGORY_ID, "工作"), store(service).categories())
        assertEquals(listOf("你好"), store(service).phrasesIn(com.aegis.ime.user.ClipboardStore.DEFAULT_CATEGORY_ID))
        assertEquals(written, file.readText())
        assertEquals("an untouched rename never rewrites the file", identity, fileKey(file))
    }

    @Test fun renaming_the_default_category_lands_under_the_new_name_without_a_twin() {
        val service = started()
        seedDefaultAndWork(service)
        beginRename(service, com.aegis.ime.user.ClipboardStore.DEFAULT_CATEGORY_ID)
        panelInput(service).selectAll()
        service.javaClass.getDeclaredMethod("commitExternalText", CharSequence::class.java)
            .apply { isAccessible = true }.invoke(service, "常用")
        confirm(service)
        store(service).flushPendingWrites()
        assertEquals(listOf("常用", "工作"), store(service).categories())
        assertEquals(listOf("你好"), store(service).phrasesIn("常用"))
        val reloaded = com.aegis.ime.user.ClipboardStore(app.filesDir).apply { load() }
        assertEquals(
            "the new name survives a reload and no default category comes back beside it",
            listOf("常用", "工作"),
            reloaded.categories(),
        )
        assertEquals(listOf("你好"), reloaded.phrasesIn("常用"))
        reloaded.stopSaving()
    }

    private fun editInPanel(service: AegisInputMethodService, action: com.aegis.ime.ime.EditAction) {
        service.javaClass.getDeclaredMethod("handleEditInPanel", com.aegis.ime.ime.EditAction::class.java)
            .apply { isAccessible = true }.invoke(service, action)
    }

    private fun beginAdd(service: AegisInputMethodService) {
        service.javaClass.getDeclaredMethod("beginInlineAddPhrase", String::class.java)
            .apply { isAccessible = true }.invoke(service, "default")
    }

    private fun beginAddCategory(service: AegisInputMethodService) {
        service.javaClass.getDeclaredMethod("beginInlineAddCategory", List::class.java, Pair::class.java)
            .apply { isAccessible = true }.invoke(service, emptyList<String>(), null)
    }

    private fun type(service: AegisInputMethodService, text: String) {
        service.javaClass.getDeclaredMethod("commitExternalText", CharSequence::class.java)
            .apply { isAccessible = true }.invoke(service, text)
    }

    @Test fun enter_confirms_a_new_category_name_instead_of_breaking_the_line() {
        val service = started()
        seedDefaultAndWork(service)
        beginAddCategory(service)
        type(service, "私人")
        service.performEnter()
        assertFalse("Enter closes the name field", panelInput(service).active)
        assertEquals(listOf(com.aegis.ime.user.ClipboardStore.DEFAULT_CATEGORY_ID, "工作", "私人"), store(service).categories())
    }

    @Test fun enter_confirms_a_category_rename() {
        val service = started()
        seedDefaultAndWork(service)
        beginRename(service, "工作")
        panelInput(service).selectAll()
        type(service, "私人")
        service.performEnter()
        assertFalse("Enter closes the name field", panelInput(service).active)
        assertEquals(listOf(com.aegis.ime.user.ClipboardStore.DEFAULT_CATEGORY_ID, "私人"), store(service).categories())
    }

    @Test fun line_breaks_put_into_a_category_name_become_spaces() {
        val service = started()
        seedDefaultAndWork(service)
        beginAddCategory(service)
        type(service, "甲\n乙\r\n丙\n")
        assertEquals("甲 乙 丙", editText(service))
        confirm(service)
        assertTrue("甲 乙 丙" in store(service).categories())
    }

    @Test fun a_stored_multiline_name_is_offered_on_one_line_and_an_untouched_rename_keeps_it() {
        val service = started()
        assertTrue(store(service).importPhrasesText("C\tdefault\nC\t工作\\n客户\nP\tx\n", merge = false))
        val legacy = "工作\n客户"
        assertTrue("precondition: the stored name holds a line break", legacy in store(service).categories())
        beginRename(service, legacy)
        assertEquals("工作 客户", editText(service))
        confirm(service)
        assertTrue("the stored name is left as it was", legacy in store(service).categories())
        assertFalse("no folded twin appears", "工作 客户" in store(service).categories())
    }

    @Test fun enter_still_breaks_the_line_in_a_phrase() {
        val service = started()
        beginAdd(service)
        type(service, "甲")
        service.performEnter()
        type(service, "乙")
        assertTrue("the phrase field stays open", panelInput(service).active)
        assertEquals("甲\n乙", panelInput(service).text())
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
