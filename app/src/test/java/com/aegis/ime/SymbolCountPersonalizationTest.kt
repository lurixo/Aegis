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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SymbolCountPersonalizationTest {

    private val docs = listOf("PRIVACY.md", "README.md", "README.zh-CN.md")

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

    @Test fun no_document_singles_out_a_password_field() {
        for (name in docs) {
            val text = File("../$name").readText()
            assertFalse(
                "$name still describes a password field as a case of its own",
                text.contains("password field") || text.contains("密码输入框"),
            )
            assertFalse(
                "$name still makes an absolute claim only an audit of every store could carry",
                text.contains("Nothing at all is learned") || text.contains("一律不学习任何内容"),
            )
        }
    }

    @Test fun the_privacy_statement_ties_both_gates_to_the_app_opting_out() {
        val privacy = File("../PRIVACY.md").readText()
        assertTrue(
            "PRIVACY.md must tie the learning gate to the app asking for no personalized learning",
            privacy.contains("No word is learned in a field whose\n  app asks for no personalized learning."),
        )
        assertTrue(
            "PRIVACY.md must tie the symbol and emoji count to that same gate",
            privacy.contains("Nothing at all is counted in\n  a field whose app asks for no personalized learning;"),
        )
    }

    @Test fun both_readmes_tie_both_gates_to_the_app_opting_out() {
        val en = File("../README.md").readText()
        assertTrue(
            "README.md must tie the learning gate to the app asking for no personalized learning",
            en.contains("No word is learned in a field that\n  asks for no personalized learning;"),
        )
        assertTrue(
            "README.md must say the panels stop counting in those same fields",
            en.contains("in those fields the symbol and emoji panels do not count what\n  you pick either."),
        )
        val zh = File("../README.zh-CN.md").readText()
        assertTrue(
            "README.zh-CN.md must say the same thing README.md says",
            zh.contains("在任何声明不参与个性化学习的输入框里，不学习任何词；在这些输入框里，符号与 emoji 面板也不会记录你选了什么。"),
        )
    }
}
