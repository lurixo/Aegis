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
import android.view.inputmethod.EditorInfo
import com.aegis.ime.engine.CandidateEngine
import com.aegis.ime.ime.CustomSymbolPanel
import com.aegis.ime.ime.InputView
import com.aegis.ime.ime.KeyboardController
import com.aegis.ime.ime.KeyboardView
import com.aegis.ime.ime.SymbolsView
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.layout.Layouts
import com.aegis.ime.layout.ScrollColumn
import com.aegis.ime.layout.SymbolCatalog
import com.aegis.ime.user.CustomSymbolStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
class MathOperatorAvailabilityTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val density = ctx.resources.displayMetrics.density
    private val forms = listOf("×", "÷", "*", "/")

    private val engine = object : CandidateEngine {
        override fun candidates(composing: String, t9: Boolean): List<String> = emptyList()
    }

    @Before
    @After
    fun clearStores() {
        ctx.getSharedPreferences("aegis", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun mathTabIndex(): Int = SymbolCatalog.categories.indexOfFirst { it.id == "math" } + 1

    private fun startedService(): Triple<AegisInputMethodService, KeyboardController, InputView> {
        val service = Robolectric.buildService(AegisInputMethodService::class.java).get()
        val controller = KeyboardController(service, engine, null)
        service.javaClass.getDeclaredField("controller").apply {
            isAccessible = true
            set(service, controller)
        }
        val info = EditorInfo().apply {
            packageName = "com.example.editor"
            fieldId = 11
            inputType = InputType.TYPE_CLASS_TEXT
        }
        service.onStartInput(info, false)
        val view = service.onCreateInputView() as InputView
        service.onStartInputView(info, false)
        return Triple(service, controller, view)
    }

    private fun openOperatorPanel(service: AegisInputMethodService): CustomSymbolPanel {
        service.javaClass.getDeclaredMethod("showCustomOperatorPanel").run {
            isAccessible = true
            invoke(service)
        }
        val panel = service.javaClass.getDeclaredField("customOperatorView").run {
            isAccessible = true
            get(service) as CustomSymbolPanel
        }
        panel.measure(
            View.MeasureSpec.makeMeasureSpec((411 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((700 * density).toInt(), View.MeasureSpec.EXACTLY),
        )
        return panel
    }

    private fun operatorStore(service: AegisInputMethodService): CustomSymbolStore =
        service.javaClass.getDeclaredField("customOperatorStore\$delegate").run {
            isAccessible = true
            (get(service) as Lazy<*>).value as CustomSymbolStore
        }

    private fun renderedNumpadOperators(view: InputView): List<String> {
        val keyboard = view.javaClass.getDeclaredField("keyboardView").run {
            isAccessible = true
            get(view) as KeyboardView
        }
        val column = keyboard.javaClass.getDeclaredField("scrollColumn").run {
            isAccessible = true
            get(keyboard) as ScrollColumn
        }
        return column.items.map { it.label }
    }

    @Test fun the_math_tab_shows_and_commits_all_four_multiplication_and_division_forms() {
        var picked: Pair<String, String?>? = null
        val panel = SymbolsView(ctx).apply {
            onSymbol = { symbol, origin -> picked = symbol to origin }
            applyPalette(ImePalette.STATIC_LIGHT)
            openCategoryForTest(mathTabIndex())
        }
        val cells = panel.gridCellTextsForTest()
        for (sign in forms) {
            assertEquals("数学分类里 $sign 只出现一次", 1, cells.count { it == sign })
            assertNotNull("$sign 必须可见", panel.gridGlyphForTest(sign))
            assertTrue("$sign 必须可上屏", panel.tapCellForTest(sign))
            assertEquals(sign, picked?.first)
        }
    }

    @Test fun the_custom_operator_palette_offers_the_multiplication_and_division_signs() {
        val (service, _, _) = startedService()
        val panel = openOperatorPanel(service)

        for (sign in forms) {
            assertNotNull("$sign 必须出现在自定义运算符池", panel.paletteChipForTest(sign))
        }
        for (hidden in listOf("+", "-", "=", "(", ")", "%", ".")) {
            assertNull("默认小键盘运算符 $hidden 仍不进入自定义池", panel.paletteChipForTest(hidden))
        }

        val store = operatorStore(service)
        requireNotNull(panel.paletteChipForTest("×")).performClick()
        requireNotNull(panel.paletteChipForTest("÷")).performClick()
        assertEquals(listOf("×", "÷"), store.list())
        panel.refresh()
        assertNotNull("× 添加后进入已添加区", panel.addedChipForTest("×"))
        assertNotNull("÷ 添加后进入已添加区", panel.addedChipForTest("÷"))

        val keys = Layouts.numpadOperators(store.list())
        for (builtIn in Layouts.defaultNumpadOperators) {
            assertEquals("$builtIn 在小键盘上只出现一次", 1, keys.count { it.label == builtIn })
        }
    }

    @Test fun both_keyboards_reach_a_numpad_that_keeps_one_key_per_default_operator() {
        for (base in listOf(KeyAction.SWITCH_NINE, KeyAction.SWITCH_ALPHA)) {
            clearStores()
            val (service, controller, view) = startedService()
            val panel = openOperatorPanel(service)
            requireNotNull(panel.paletteChipForTest("≠")).performClick()
            panel.refresh()
            requireNotNull(panel.paletteChipForTest("×")).performClick()

            controller.onKey(Key("", action = base))
            controller.onKey(Key("", action = KeyAction.SWITCH_NUMPAD))
            assertEquals(LayoutId.NUMPAD, controller.activeLayoutId())

            val rendered = renderedNumpadOperators(view)
            assertTrue("$base 必须把自定义运算符接到小键盘: $rendered", "≠" in rendered)
            for (builtIn in Layouts.defaultNumpadOperators) {
                assertEquals("$base 下 $builtIn 只出现一次: $rendered", 1, rendered.count { it == builtIn })
            }
        }
    }

    @Test fun existing_custom_operators_keep_their_order_and_content() {
        val store = CustomSymbolStore(ctx.getSharedPreferences("math-operator-availability", 0), "custom_operators")
        val existing = listOf("≠", "≈", "∓")
        existing.forEach { assertTrue(store.add(it)) }

        assertEquals(existing, store.list())
        assertTrue(store.add("×"))
        assertEquals(existing + "×", store.list())
        val keys = Layouts.numpadOperators(store.list()).map { it.label }
        assertEquals("既有自定义项顺序不变", existing, keys.filter { it in existing })
        assertFalse("× 不会在小键盘上重复", keys.count { it == "×" } > 1)
    }
}
