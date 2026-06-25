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

import com.aegis.ime.translate.TranslateClient
import java.io.File
import java.net.URL
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslateDisclosureTest {

    private val host = URL(TranslateClient.ENDPOINT).host

    @Test fun both_readmes_scope_the_no_network_claim_to_the_translate_bar() {
        val en = File("../README.md").readText()
        assertTrue(en.contains("Outside the translate bar, no network request is\n  ever made while you type, and nothing you type is ever sent."))
        assertTrue("README.md names the translation host", en.contains("`$host`"))
        assertTrue(en.contains("and the text you type into the translate bar, which goes to Google Translate and nowhere else."))
        val zh = File("../README.zh-CN.md").readText()
        assertTrue(zh.contains("**翻译栏之外，输入时绝不会发起网络请求，你输入的内容也绝不会被发送。**"))
        assertTrue("README.zh-CN.md names the translation host", zh.contains("`$host`"))
        assertTrue(zh.contains("以及你输入到翻译栏里的文字（只发给 Google 翻译）。"))
    }

    @Test fun the_privacy_statement_lists_translation_as_the_third_network_use() {
        val privacy = File("../PRIVACY.md").readText()
        assertTrue(privacy.contains("apart from the translate bar, nothing you type is ever sent anywhere."))
        assertTrue(privacy.contains("goes online for three kinds of thing only"))
        assertTrue(privacy.contains("- **The translate bar.**"))
        assertTrue("PRIVACY.md names the translation host", privacy.contains("`$host`"))
        assertTrue(privacy.contains("Nothing else — no keystrokes outside the translate bar, no candidates, no learned words, no\nclipboard — is ever sent."))
    }
}
