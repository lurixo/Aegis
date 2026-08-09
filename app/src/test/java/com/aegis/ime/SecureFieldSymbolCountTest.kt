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
class SecureFieldSymbolCountTest {

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

    private fun startedIn(
        info: EditorInfo,
        secure: Boolean,
        blocked: Boolean = secure,
    ): AegisInputMethodService {
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
            "precondition: the service must read this field's secrecy as $secure",
            secure == flag(service, "secureField"),
        )
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

    @Test fun a_password_field_counts_neither_the_symbols_nor_the_emoji_you_pick() {
        val service = startedIn(password(), secure = true)

        pickASymbolAndAnEmoji(service)

        assertFalse(
            "a symbol picked in a password field may be part of the password, so it must not be counted",
            "€" in store(service, "symbolUsageStore").recent(),
        )
        assertFalse(
            "an emoji picked in a password field may be part of the password, so it must not be counted",
            "😀" in store(service, "emojiUsageStore").recent(),
        )
    }

    @Test fun a_field_that_asks_for_no_personalized_learning_counts_neither_either() {
        val service = startedIn(noPersonalizedLearning(), secure = false, blocked = true)

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
        val service = startedIn(ordinary(), secure = false)

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

    @Test fun no_document_claims_a_password_field_learns_nothing_at_all() {
        for (name in docs) {
            val text = File("../$name").readText()
            assertFalse(
                "$name still makes an absolute claim only an audit of every store could carry",
                text.contains("Nothing at all is learned") || text.contains("一律不学习任何内容"),
            )
        }
    }

    @Test fun the_privacy_statement_stops_the_count_wherever_the_learning_stops() {
        val privacy = File("../PRIVACY.md").readText()
        assertFalse(
            "PRIVACY.md must no longer disclose a count that a password field survives",
            privacy.contains("a password field included"),
        )
        assertTrue(
            "PRIVACY.md must say the symbol and emoji count is not kept in a password field",
            privacy.contains("Nothing at all is counted in"),
        )
        assertTrue(
            "PRIVACY.md must say the count stops in an opted-out field too",
            privacy.contains("or in any field whose app"),
        )
        assertTrue(
            "PRIVACY.md must say the count stops in an opted-out field too",
            privacy.contains("asks for no personalized learning; elsewhere it records"),
        )
        assertTrue(
            "PRIVACY.md must keep saying no word is learned there either",
            privacy.contains("No word is learned while you are in a"),
        )
    }

    @Test fun both_readmes_stop_the_count_wherever_the_learning_stops() {
        val en = File("../README.md").readText()
        assertTrue(
            "README.md must say the panels do not count what you pick",
            en.contains("emoji panels do not count what you pick either"),
        )
        assertTrue(
            "README.md must extend that to every field the learning stops in",
            en.contains("no personalized learning; in those fields the symbol and"),
        )
        val zh = File("../README.zh-CN.md").readText()
        assertTrue(
            "README.zh-CN.md must say the panels do not count what you pick",
            zh.contains("符号与 emoji 面板也不会记录你选了什么"),
        )
        assertTrue(
            "README.zh-CN.md must say the same thing README.md says",
            zh.contains("不学习任何词；在这些输入框里，"),
        )
        assertFalse(
            "README.zh-CN.md must no longer narrow it back to a password field",
            zh.contains("在密码输入框里，"),
        )
    }
}
