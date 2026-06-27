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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test fun category_names_fold_line_breaks_into_single_spaces() {
        assertEquals("工作 客户", ClipboardStore.categoryName("工作\n客户"))
        assertEquals("a b", ClipboardStore.categoryName("a\r\n\r\nb"))
        assertEquals("a b c", ClipboardStore.categoryName("a b c"))
        assertEquals("工作", ClipboardStore.categoryName("\n工作\r\n"))
        assertEquals("typed spaces and tabs stay", " a\tb ", ClipboardStore.categoryName(" a\tb\u0000 "))

        val s = ClipboardStore(newDir()).apply { load() }
        assertTrue(s.addCategory("工作\n客户"))
        assertTrue("addCategory folds the break", "工作 客户" in s.categories())
        assertFalse("the folded name is the same category", s.addCategory("工作\r\n客户"))
        assertFalse("a name of line breaks alone is blank", s.addCategory("\n\r\n"))
        assertTrue(s.renameCategory("工作 客户", "私人\n事务\n"))
        assertTrue("renameCategory folds the break", "私人 事务" in s.categories())
        assertTrue("no stored name keeps a line break", s.categories().none { name -> name.any { it in "\n\r  " } })
    }

    @Test fun a_raw_category_name_never_splits_the_category_in_two() {
        val raw = "work\u0001temp"
        val cleaned = "worktemp"
        val s = ClipboardStore(newDir()).apply { load() }

        s.addCategory(raw)
        assertEquals("the raw name lands the phrases in the category that exists", 1, s.addPhrasesTo(raw, listOf("x")))
        assertEquals("no second, near-identical category appears", 1, s.categories().count { it == cleaned })
        assertTrue("the raw form is not a category of its own", !s.categories().contains(raw))
        assertEquals(listOf("x"), s.phrasesIn(cleaned))
    }

    @Test fun a_category_name_that_sanitizes_to_nothing_creates_no_category() {
        val s = ClipboardStore(newDir()).apply { load() }
        val before = s.categories()
        assertEquals(0, s.addPhrasesTo("\t\u0000 ", listOf("x")))
        assertEquals("a blank name never conjures a category", before, s.categories())
    }

    @Test fun multiline_category_name_survives_persist_roundtrip() {
        val dir = newDir()
        ClipboardStore(dir).apply {
            load()
            addCategory("work\ntemp")
            addPhrasesTo("work\ntemp", listOf("x"))
            flushPendingWrites()
        }
        val reloaded = ClipboardStore(dir).apply { load() }
        assertTrue(reloaded.categories().contains("work\ntemp"))
        assertEquals(listOf("x"), reloaded.phrasesIn("work\ntemp"))
    }
}
