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
import com.aegis.ime.ime.EmojiView
import com.aegis.ime.ime.InputView
import com.aegis.ime.ime.KeyboardController
import com.aegis.ime.ime.SymbolsView
import com.aegis.ime.user.SymbolUsageStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SymbolCountPersonalizationTest {

    private fun password() = EditorInfo().apply {
        packageName = "com.example.editor"
        fieldId = 7
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }

    private fun ordinary() = EditorInfo().apply {
        packageName = "com.example.editor"
        fieldId = 8
        inputType = InputType.TYPE_CLASS_TEXT
    }

    private fun noPersonalizedLearning() = EditorInfo().apply {
        packageName = "com.example.editor"
        fieldId = 9
        inputType = InputType.TYPE_CLASS_TEXT
        imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
    }

    private fun flag(service: AegisInputMethodService, name: String): Boolean =
        service.javaClass.getDeclaredField(name).run {
            isAccessible = true
            getBoolean(service)
        }

    private fun startedIn(info: EditorInfo, blocked: Boolean): AegisInputMethodService {
        val service = Robolectric.buildService(AegisInputMethodService::class.java).get()
        val engine = object : CandidateEngine {
            override fun candidates(composing: String, t9: Boolean): List<String> = emptyList()
        }
        service.javaClass.getDeclaredField("controller").apply {
            isAccessible = true
            set(service, KeyboardController(service, engine, null))
        }
        service.onStartInput(info, false)
        service.onCreateInputView() as InputView
        service.onStartInputView(info, false)
        assertTrue(
            "precondition: the service must read this field's personalization as blocked=$blocked",
            blocked == flag(service, "personalizationBlocked"),
        )
        return service
    }

    private fun open(service: AegisInputMethodService, method: String) {
        service.javaClass.getDeclaredMethod(method).apply { isAccessible = true }.invoke(service)
    }

    private fun panel(service: AegisInputMethodService, field: String): Any =
        service.javaClass.getDeclaredField(field).run {
            isAccessible = true
            get(service)!!
        }

    private fun store(service: AegisInputMethodService, field: String): SymbolUsageStore {
        val lazy = service.javaClass.getDeclaredField("$field\$delegate").run {
            isAccessible = true
            get(service) as Lazy<*>
        }
        return lazy.value as SymbolUsageStore
    }

    private fun pickASymbolAndAnEmoji(service: AegisInputMethodService) {
        open(service, "showSymbolsPanel")
        (panel(service, "symbolsView") as SymbolsView).onSymbol("€", null)
        open(service, "showEmojiPanel")
        (panel(service, "emojiView") as EmojiView).onEmoji("😀")
    }

    @Test fun a_password_field_counts_the_symbols_and_emoji_you_pick_like_any_other_field() {
        val service = startedIn(password(), blocked = false)

        pickASymbolAndAnEmoji(service)

        assertTrue(
            "a password field is an ordinary field, so the symbol it took must reach the Common tab",
            "€" in store(service, "symbolUsageStore").recent(),
        )
        assertTrue(
            "a password field is an ordinary field, so the emoji it took must reach the Common tab",
            "😀" in store(service, "emojiUsageStore").recent(),
        )
    }

    @Test fun a_field_that_asks_for_no_personalized_learning_counts_neither_either() {
        val service = startedIn(noPersonalizedLearning(), blocked = true)

        pickASymbolAndAnEmoji(service)

        assertFalse(
            "which symbol you reach for is a personal signal, so a field that opted out must not build it",
            "€" in store(service, "symbolUsageStore").recent(),
        )
        assertFalse(
            "which emoji you reach for is a personal signal, so a field that opted out must not build it",
            "😀" in store(service, "emojiUsageStore").recent(),
        )
    }

    @Test fun an_ordinary_field_still_counts_the_symbols_and_emoji_you_pick() {
        val service = startedIn(ordinary(), blocked = false)

        pickASymbolAndAnEmoji(service)

        assertTrue(
            "the Common tab is what the count is for, so an ordinary field must still fill it",
            "€" in store(service, "symbolUsageStore").recent(),
        )
        assertTrue(
            "the Common tab is what the count is for, so an ordinary field must still fill it",
            "😀" in store(service, "emojiUsageStore").recent(),
        )
    }
}
