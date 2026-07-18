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
import android.view.View
import android.view.inputmethod.EditorInfo
import com.aegis.ime.engine.CandidateEngine
import com.aegis.ime.ime.InputView
import com.aegis.ime.ime.KeyboardController
import com.aegis.ime.ime.KeyboardView
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.layout.Layouts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w853dp-h388dp-land-hdpi")
class SecureLayoutPersistenceTest {

    private val engine = object : CandidateEngine {
        override fun candidates(composing: String, t9: Boolean): List<String> =
            if (composing.isEmpty()) emptyList() else listOf("候选")
    }

    private data class Fixture(
        val service: AegisInputMethodService,
        val controller: KeyboardController,
        val view: InputView,
    )

    private fun editor(
        packageName: String = "com.bank.app",
        fieldId: Int = 1,
        fieldName: String? = null,
        inputType: Int = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
    ) = EditorInfo().apply {
        this.packageName = packageName
        this.fieldId = fieldId
        this.fieldName = fieldName
        this.inputType = inputType
    }

    private fun fixture(info: EditorInfo = editor()): Fixture {
        val service = Robolectric.buildService(AegisInputMethodService::class.java).get()
        val controller = KeyboardController(service, engine, null)
        service.javaClass.getDeclaredField("controller").apply {
            isAccessible = true
            set(service, controller)
        }
        service.onStartInput(info, false)
        val view = service.onCreateInputView() as InputView
        service.onStartInputView(info, false)
        return Fixture(service, controller, view)
    }

    private fun keyboardView(view: InputView): KeyboardView =
        view.javaClass.getDeclaredField("keyboardView").run {
            isAccessible = true
            get(view) as KeyboardView
        }

    @Test fun numpad_survives_a_secure_restart_storm_with_field_and_kind_churn() {
        val f = fixture(editor(fieldId = 1))
        f.controller.onKey(Key("", action = KeyAction.SWITCH_NUMPAD))
        assertEquals(LayoutId.NUMPAD, f.controller.activeLayoutId())

        val storm = listOf(
            editor(fieldId = 2),
            editor(fieldId = View.NO_ID, fieldName = "pin2", inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD),
            editor(fieldId = 1, inputType = InputType.TYPE_CLASS_NUMBER),
            editor(fieldId = 2, fieldName = "otp"),
        )
        for (info in storm) {
            f.service.onStartInput(info, true)
            f.service.onStartInputView(info, true)
            assertEquals(LayoutId.NUMPAD, f.controller.activeLayoutId())
        }
    }

    @Test fun numpad_survives_per_digit_finish_start_cycles_in_the_same_package() {
        val f = fixture(editor(fieldId = 1))
        f.controller.onKey(Key("", action = KeyAction.SWITCH_NUMPAD))
        assertEquals(LayoutId.NUMPAD, f.controller.activeLayoutId())

        for (fieldId in 2..4) {
            f.service.onFinishInputView(true)
            f.service.onFinishInput()
            val next = editor(fieldId = fieldId)
            f.service.onStartInput(next, false)
            f.service.onStartInputView(next, false)
            assertEquals(LayoutId.NUMPAD, f.controller.activeLayoutId())
        }
    }

    @Test fun secure_restart_clears_composition_and_shift_but_keeps_the_manual_layout() {
        val password = editor(fieldId = 5, inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val f = fixture(password)
        f.controller.onKey(Key("", action = KeyAction.SWITCH_ALPHA))
        f.controller.onKey(Key("n", output = "n"))
        f.controller.onKey(Key("i", output = "i"))
        assertEquals(LayoutId.ALPHA, f.controller.activeLayoutId())
        assertTrue(f.controller.preeditForTest().isNotEmpty())

        f.service.onStartInput(password, true)
        f.service.onStartInputView(password, true)

        assertEquals("", f.controller.preeditForTest())
        assertEquals("OFF", f.controller.shiftStateName())
        assertEquals(LayoutId.ALPHA, f.controller.activeLayoutId())
    }

    @Test fun a_different_package_restores_the_default_layout_immediately() {
        val f = fixture(editor(fieldId = 1))
        f.controller.onKey(Key("", action = KeyAction.SWITCH_NUMPAD))
        assertEquals(LayoutId.NUMPAD, f.controller.activeLayoutId())

        val other = editor(packageName = "com.other.app", fieldId = 7)
        f.service.onStartInput(other, true)
        f.service.onStartInputView(other, true)
        assertEquals(LayoutId.NINE, f.controller.activeLayoutId())
    }

    @Test fun window_hidden_ends_the_layout_session_for_the_same_package() {
        val f = fixture(editor(fieldId = 1))
        f.controller.onKey(Key("", action = KeyAction.SWITCH_NUMPAD))
        assertEquals(LayoutId.NUMPAD, f.controller.activeLayoutId())
        f.service.onFinishInputView(false)
        f.service.onWindowHidden()
        val next = editor(fieldId = 9)
        f.service.onStartInput(next, false)
        f.service.onStartInputView(next, false)
        assertEquals(LayoutId.NINE, f.controller.activeLayoutId())

        f.controller.onKey(Key("", action = KeyAction.SWITCH_ALPHA))
        assertEquals(LayoutId.ALPHA, f.controller.activeLayoutId())
        f.service.onFinishInputView(false)
        f.service.onWindowHidden()
        val again = editor(fieldId = 10)
        f.service.onStartInput(again, false)
        f.service.onStartInputView(again, false)
        assertEquals(LayoutId.NINE, f.controller.activeLayoutId())
    }

    @Test fun a_secure_restart_storm_does_not_rebuild_an_unchanged_keyboard() {
        val f = fixture(editor(fieldId = 1))
        f.controller.onKey(Key("", action = KeyAction.SWITCH_NUMPAD))
        val kv = keyboardView(f.view)
        val applies = kv.layoutAppliesForTest()
        val modes = kv.modeSwitchesForTest()

        for (fieldId in 2..4) {
            val info = editor(fieldId = fieldId)
            f.service.onStartInput(info, true)
            f.service.onStartInputView(info, true)
        }

        assertEquals(LayoutId.NUMPAD, f.controller.activeLayoutId())
        assertEquals(applies, kv.layoutAppliesForTest())
        assertEquals(modes, kv.modeSwitchesForTest())
    }

    @Test fun keyboard_view_skips_structurally_equal_layouts_and_applies_real_changes() {
        val kv = KeyboardView(RuntimeEnvironment.getApplication())
        kv.setLayout(Layouts.numpad(), false, false, Lang.CN)
        val applies = kv.layoutAppliesForTest()
        kv.setLayout(Layouts.numpad(), false, false, Lang.CN)
        assertEquals(applies, kv.layoutAppliesForTest())
        kv.setLayout(Layouts.forId(LayoutId.SYMBOL, Lang.CN), false, false, Lang.CN)
        assertEquals(applies + 1, kv.layoutAppliesForTest())
    }
}
