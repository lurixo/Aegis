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
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import com.aegis.ime.engine.CandidateEngine
import com.aegis.ime.ime.ClipboardView
import com.aegis.ime.ime.InputView
import com.aegis.ime.ime.KeyboardController
import com.aegis.ime.ime.PanelTextInput
import com.aegis.ime.user.ClipboardStore
import com.aegis.ime.user.LiveUserData
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class CategoryPageInlineReturnTest {

    private val app = RuntimeEnvironment.getApplication()
    private val phraseFile = File(app.filesDir, "phrases.txt")
    private val historyFile = File(app.filesDir, "clipboard.txt")

    @Before
    @After
    fun clean() {
        LiveUserData.clipboardHost = null
        phraseFile.delete()
        historyFile.delete()
    }

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

    private fun clipboard(service: AegisInputMethodService): ClipboardView {
        service.javaClass.getDeclaredMethod("showClipboardPanel").apply { isAccessible = true }.invoke(service)
        return service.javaClass.getDeclaredField("clipboardView").run {
            isAccessible = true
            get(service) as ClipboardView
        }
    }

    private fun inputView(service: AegisInputMethodService): InputView =
        service.javaClass.getDeclaredField("inputView").run {
            isAccessible = true
            get(service) as InputView
        }

    private fun store(service: AegisInputMethodService): ClipboardStore {
        val delegate = service.javaClass.getDeclaredField("clipboardStore\$delegate").run {
            isAccessible = true
            get(service) as Lazy<*>
        }
        return delegate.value as ClipboardStore
    }

    private fun panelInput(service: AegisInputMethodService): PanelTextInput =
        service.javaClass.getDeclaredField("panelInput").run {
            isAccessible = true
            get(service) as PanelTextInput
        }

    private fun type(service: AegisInputMethodService, text: String) {
        panelInput(service).selectAll()
        service.javaClass.getDeclaredMethod("commitExternalText", CharSequence::class.java)
            .apply { isAccessible = true }
            .invoke(service, text)
    }

    private fun settle(service: AegisInputMethodService) {
        store(service).flushPendingWrites()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun layout(v: View, height: Int = 700) {
        v.measure(
            View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
    }

    private fun allViews(root: View): List<View> =
        if (root is ViewGroup) listOf(root) + (0 until root.childCount).flatMap { allViews(root.getChildAt(it)) }
        else listOf(root)

    private fun openCategoryPage(service: AegisInputMethodService): ClipboardView {
        val panel = clipboard(service)
        panel.switchTabForTest(toClipboard = false)
        layout(panel)
        val manage = allViews(panel).single {
            it.contentDescription?.toString() == app.getString(R.string.clip_manage_categories)
        }
        assertTrue(manage.performClick())
        layout(panel)
        assertTrue("precondition: the category page is open", panel.isCategorySortModeForTest())
        return panel
    }

    private fun rows(panel: ClipboardView): List<ViewGroup> =
        (0 until panel.listRowCountForTest()).map { requireNotNull(panel.listRowViewForTest(it)) as ViewGroup }

    private fun row(panel: ClipboardView, category: String): ViewGroup =
        rows(panel).single { ((it.getChildAt(0) as ViewGroup).getChildAt(0) as TextView).text.toString() == category }

    @Test fun a_rename_confirmed_from_the_category_page_returns_to_the_refreshed_page() {
        val service = started()
        assertTrue(store(service).addCategory("工作"))
        settle(service)
        val panel = openCategoryPage(service)
        val iv = inputView(service)

        assertTrue(row(panel, "工作").getChildAt(1).performClick())
        assertTrue("the rename runs in the edit bar", iv.isEditBarShowing())
        assertFalse(iv.isPanelShowing(panel))
        assertEquals("工作", iv.editBarForTest().fieldForTest().text.toString())
        type(service, "办公")
        iv.onEditConfirm()
        settle(service)

        assertFalse(iv.isEditBarShowing())
        assertTrue("confirming returns to the panel", iv.isPanelShowing(panel))
        assertTrue("…on the category page", panel.isCategorySortModeForTest())
        layout(panel)
        val names = rows(panel).map { ((it.getChildAt(0) as ViewGroup).getChildAt(0) as TextView).text.toString() }
        assertTrue("the page lists the new name", "办公" in names)
        assertFalse("the old name is gone from the page", "工作" in names)
        assertTrue("办公" in store(service).categories())
    }

    @Test fun backing_out_of_a_rename_from_the_category_page_returns_to_the_page() {
        val service = started()
        assertTrue(store(service).addCategory("工作"))
        settle(service)
        val panel = openCategoryPage(service)
        val iv = inputView(service)

        assertTrue(row(panel, "工作").getChildAt(1).performClick())
        assertTrue(iv.isEditBarShowing())
        type(service, "不要了")
        iv.onEditCancel()
        settle(service)

        assertFalse(iv.isEditBarShowing())
        assertTrue(iv.isPanelShowing(panel))
        assertTrue("backing out returns to the category page", panel.isCategorySortModeForTest())
        layout(panel)
        assertTrue("工作" in rows(panel).map { ((it.getChildAt(0) as ViewGroup).getChildAt(0) as TextView).text.toString() })
        assertFalse("不要了" in store(service).categories())
    }

    @Test fun a_new_category_made_on_the_category_page_returns_to_the_page_scrolled_to_it() {
        val service = started()
        repeat(14) { assertTrue(store(service).addCategory("分类" + it.toString().padStart(2, '0'))) }
        settle(service)
        val panel = openCategoryPage(service)
        val iv = inputView(service)

        val create = allViews(panel).filterIsInstance<TextView>()
            .single { it.text?.toString() == app.getString(R.string.clip_add_category) && it.hasOnClickListeners() }
        assertTrue(create.performClick())
        assertTrue(iv.isEditBarShowing())
        type(service, "新分类")
        iv.onEditConfirm()
        settle(service)

        assertTrue(iv.isPanelShowing(panel))
        assertTrue("making a category returns to the category page", panel.isCategorySortModeForTest())
        layout(panel, height = 400)
        val fresh = row(panel, "新分类")
        val viewport = panel.listViewportForTest()
        assertTrue("the list had to scroll to reach the new category", viewport.scrollY > 0)
        assertTrue(
            "the new category is scrolled into view: top=${fresh.top} bottom=${fresh.bottom} scroll=${viewport.scrollY} height=${viewport.height}",
            fresh.top >= viewport.scrollY && fresh.bottom <= viewport.scrollY + viewport.height,
        )
        assertTrue("新分类" in store(service).categories())
    }

    @Test fun a_new_category_from_the_move_chooser_comes_back_scrolled_into_the_rail() {
        val service = started()
        repeat(11) { assertTrue(store(service).addCategory("分类" + it.toString().padStart(2, '0'))) }
        assertEquals(1, store(service).addPhrasesTo(ClipboardStore.DEFAULT_CATEGORY_ID, listOf("你好")))
        settle(service)
        val panel = clipboard(service)
        panel.switchTabForTest(toClipboard = false)
        layout(panel)
        val iv = inputView(service)

        panel.enterSelectForTest(listOf("你好"))
        val move = allViews(panel).filterIsInstance<TextView>()
            .single { it.text?.toString() == app.getString(R.string.clip_move_to_category) && it.hasOnClickListeners() }
        assertTrue(move.performClick())
        val create = allViews(panel).filterIsInstance<TextView>()
            .single { it.text?.toString() == app.getString(R.string.clip_new_category) && it.hasOnClickListeners() }
        assertTrue(create.performClick())
        assertTrue(iv.isEditBarShowing())
        type(service, "新分类")
        iv.onEditConfirm()
        settle(service)

        assertTrue(iv.isPanelShowing(panel))
        assertEquals("新分类", panel.phraseCatForTest())
        assertEquals(listOf("你好"), store(service).phrasesIn("新分类"))
        layout(panel)
        val scroll = allViews(panel).filterIsInstance<android.widget.HorizontalScrollView>()
            .single { s -> allViews(s).filterIsInstance<TextView>().any { it.text?.toString() == "新分类" } }
        val strip = scroll.getChildAt(0)
        val tab = allViews(scroll).filterIsInstance<TextView>().single { it.text?.toString() == "新分类" }
        assertTrue("the rail had to scroll to reach the new category", scroll.scrollX > 0)
        assertTrue(
            "the new category is scrolled into the rail",
            strip.left + tab.left >= scroll.scrollX + scroll.paddingLeft &&
                strip.left + tab.right <= scroll.scrollX + scroll.width - scroll.paddingRight,
        )
    }

    @Test fun a_rename_started_from_a_category_tab_still_returns_to_the_phrase_list() {
        val service = started()
        assertTrue(store(service).addCategory("工作"))
        settle(service)
        val panel = clipboard(service)
        panel.switchTabForTest(toClipboard = false)
        layout(panel)
        val iv = inputView(service)

        val tab = allViews(panel).filterIsInstance<TextView>().first { it.text?.toString() == "工作" && it.hasOnClickListeners() }
        assertTrue(tab.performLongClick())
        val rename = allViews(panel).first {
            it.contentDescription?.toString() == app.getString(R.string.clip_rename_named, "工作") && it.hasOnClickListeners()
        }
        assertTrue(rename.performClick())
        assertTrue(iv.isEditBarShowing())
        type(service, "办公")
        iv.onEditConfirm()
        settle(service)

        assertTrue(iv.isPanelShowing(panel))
        assertFalse("a tab rename goes back to the list, not the category page", panel.isCategorySortModeForTest())
        assertEquals("办公", panel.phraseCatForTest())
    }
}
