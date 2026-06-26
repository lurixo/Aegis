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

package com.aegis.ime.user

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PhraseTextSanitizeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newDir(): File = tmp.newFolder()

    @Test fun format_whitespace_survives_but_other_control_characters_are_removed() {
        assertEquals("a\nb", ClipboardStore.sanitizePhraseText("a\nb"))
        assertEquals("a\r\nb", ClipboardStore.sanitizePhraseText("a\r\nb"))
        assertEquals("a\tb", ClipboardStore.sanitizePhraseText("a\tb"))
        assertEquals("ab", ClipboardStore.sanitizePhraseText("a\u0000b"))
        assertEquals("ab", ClipboardStore.sanitizePhraseText("a\u0001b"))
        assertEquals("  a\nb \n", ClipboardStore.sanitizePhraseText("  a\nb \n"))
    }

    @Test fun a_category_name_that_sanitizes_to_nothing_creates_no_category() {
        val s = ClipboardStore(newDir()).apply { load() }
        val before = s.categories()
        assertEquals(0, s.addPhrasesTo("\t\u0000 ", listOf("x")))
        assertEquals("a blank name never conjures a category", before, s.categories())
    }
}
