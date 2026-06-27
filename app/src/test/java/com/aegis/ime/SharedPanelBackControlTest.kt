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

import android.content.Context
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.view.inputmethod.EditorInfo
import com.aegis.ime.ime.CustomSymbolPanel
import com.aegis.ime.ime.InputView
import com.aegis.ime.ime.KeyboardController
import com.aegis.ime.ime.EditPanelView
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.engine.CandidateEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SharedPanelBackControlTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val density = ctx.resources.displayMetrics.density

    private val engine = object : CandidateEngine {
        override fun candidates(composing: String, t9: Boolean): List<String> = emptyList()
    }

    private fun dp(v: Int) = (v * density).toInt()

    @Before
    @After
    fun clearStores() {
        ctx.getSharedPreferences("aegis", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun startedService(): Pair<AegisInputMethodService, InputView> {
        val service = Robolectric.buildService(AegisInputMethodService::class.java).get()
        service.javaClass.getDeclaredField("controller").apply {
            isAccessible = true
            set(service, KeyboardController(service, engine, null))
        }
        val info = EditorInfo().apply {
            packageName = "com.example.editor"
            fieldId = 7
            inputType = InputType.TYPE_CLASS_TEXT
        }
        service.onStartInput(info, false)
        val view = service.onCreateInputView() as InputView
        service.onStartInputView(info, false)
        return service to view
    }

    private fun open(service: AegisInputMethodService, method: String) {
        service.javaClass.getDeclaredMethod(method).run {
            isAccessible = true
            invoke(service)
        }
    }

    private fun cached(service: AegisInputMethodService, field: String): Any? =
        service.javaClass.getDeclaredField(field).run {
            isAccessible = true
            get(service)
        }

    private fun layout(view: View, width: Int = dp(411), height: Int = dp(700)) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun editPanelBack(): TextView {
        val panel = EditPanelView(ctx).also { it.applyPalette(ImePalette.STATIC_LIGHT) }
        return (panel.getChildAt(0) as ViewGroup).getChildAt(0) as TextView
    }

    @Test fun the_custom_symbol_family_is_exactly_the_punctuation_and_operator_pages() {
        val declared = AegisInputMethodService::class.java.declaredFields
            .filter { it.type == CustomSymbolPanel::class.java }
            .map { it.name }
            .toSet()
        assertEquals(
            "a new custom-X page must be added to this enumeration and given its own titles",
            setOf("customSymbolView", "customOperatorView"),
            declared,
        )
    }

    @Test fun every_custom_symbol_entry_shares_the_back_control_and_names_its_own_object() {
        val (service, view) = startedService()
        val entries = listOf(
            Triple(
                "showCustomSymbolPanel" to "customSymbolView",
                R.string.csp_punctuation_title,
                R.string.csp_section_all_punctuation,
            ),
            Triple(
                "showCustomOperatorPanel" to "customOperatorView",
                R.string.csp_operators_title,
                R.string.csp_section_all_operators,
            ),
        )
        val titles = ArrayList<String>()
        for ((entry, title, paletteTitle) in entries) {
            val (method, field) = entry
            open(service, method)
            val panel = cached(service, field) as CustomSymbolPanel
            assertTrue("$field must be the panel on screen", view.isPanelShowing(panel))
            layout(view)

            val button = panel.backButtonForTest()
            assertTrue("$field uses the edit panel title control", button is TextView)
            assertTrue("$field back hit height", button.height >= dp(48))
            assertTrue("$field back hit width", button.width >= dp(48))
            val editBack = editPanelBack()
            assertEquals("$field back text scale", editBack.textSize, (button as TextView).textSize, 0.01f)
            assertEquals(
                "$field back icon box",
                editBack.compoundDrawables[0]!!.intrinsicWidth,
                button.compoundDrawables[0]!!.intrinsicWidth,
            )
            assertEquals("$field back icon gap", editBack.compoundDrawablePadding, button.compoundDrawablePadding)
            assertEquals("$field title", ctx.getString(title), panel.titleForTest().text.toString())
            assertEquals(
                "$field added section",
                ctx.getString(R.string.csp_section_added),
                panel.addedSectionLabelForTest().text.toString(),
            )
            assertEquals(
                "$field palette section",
                ctx.getString(paletteTitle),
                panel.paletteSectionLabelForTest().text.toString(),
            )
            titles.add(panel.titleForTest().text.toString())
        }
        assertNotEquals("each custom page names its own object", titles[0], titles[1])
    }
}
