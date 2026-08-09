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
class SecureFieldSymbolCountDisclosureTest {

    private val docs = listOf("PRIVACY.md", "README.md", "README.zh-CN.md")

    private fun password() = EditorInfo().apply {
        packageName = "com.example.editor"
        fieldId = 7
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }

    private fun startedInAPasswordField(): AegisInputMethodService {
        val service = Robolectric.buildService(AegisInputMethodService::class.java).get()
        val engine = object : CandidateEngine {
            override fun candidates(composing: String, t9: Boolean): List<String> = emptyList()
        }
        service.javaClass.getDeclaredField("controller").apply {
            isAccessible = true
            set(service, KeyboardController(service, engine, null))
        }
        val info = password()
        service.onStartInput(info, false)
        service.onCreateInputView() as InputView
        service.onStartInputView(info, false)
        assertTrue(
            "precondition: the service must consider this a secure field",
            service.javaClass.getDeclaredField("secureField").run {
                isAccessible = true
                getBoolean(service)
            },
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

    @Test fun a_password_field_still_counts_the_symbols_and_emoji_you_pick() {
        val service = startedInAPasswordField()

        open(service, "showSymbolsPanel")
        (panel(service, "symbolsView") as SymbolsView).onSymbol("€", null)
        open(service, "showEmojiPanel")
        (panel(service, "emojiView") as EmojiView).onEmoji("😀")

        assertTrue(
            "the symbol count survives a password field, so the docs must not deny it",
            "€" in store(service, "symbolUsageStore").recent(),
        )
        assertTrue(
            "the emoji count survives a password field, so the docs must not deny it",
            "😀" in store(service, "emojiUsageStore").recent(),
        )
    }

    @Test fun no_document_claims_a_password_field_learns_nothing_at_all() {
        for (name in docs) {
            val text = File("../$name").readText()
            assertFalse(
                "$name still makes an absolute claim the symbol and emoji counts break",
                text.contains("Nothing at all is learned") || text.contains("一律不学习任何内容"),
            )
        }
    }

    @Test fun the_privacy_statement_says_the_symbol_count_survives_a_password_field() {
        val privacy = File("../PRIVACY.md").readText()
        assertTrue(
            "PRIVACY.md must say the symbol and emoji count is kept in a password field too",
            privacy.contains("a password field included"),
        )
        assertTrue(
            "PRIVACY.md must keep the word-learning claim scoped to words",
            privacy.contains("No word is learned while you are in a"),
        )
    }
}
