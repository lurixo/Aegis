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

    @Test fun newlines_survive_but_other_control_characters_are_removed() {
        assertEquals("a\nb", ClipboardStore.sanitizePhraseText("a\nb"))
        assertEquals("a\nb", ClipboardStore.sanitizePhraseText("a\r\nb"))
        assertEquals("a\nb", ClipboardStore.sanitizePhraseText("a\rb"))
        assertEquals("ab", ClipboardStore.sanitizePhraseText("a\tb"))
        assertEquals("ab", ClipboardStore.sanitizePhraseText("a\u0000b"))
        assertEquals("a\nb", ClipboardStore.sanitizePhraseText("  a\nb \n"))
    }

    @Test fun every_phrase_write_path_applies_the_same_rule() {
        val raw = " head\ttab\r\nbody "
        val cleaned = "headtab\nbody"
        val dir = newDir()
        val s = ClipboardStore(dir).apply { load() }

        s.addCategory(raw)
        assertTrue("addCategory sanitizes", s.categories().contains(cleaned))

        s.addPhrasesTo(cleaned, listOf(raw))
        assertEquals("addPhrasesTo sanitizes", listOf(cleaned), s.phrasesIn(cleaned))

        s.setPhraseNote(cleaned, cleaned, raw)
        assertEquals("setPhraseNote sanitizes", cleaned, s.noteFor(cleaned, cleaned))

        s.editPhrase(cleaned, cleaned, " edited\ttext\r\nline ")
        assertEquals("editPhrase sanitizes", listOf("editedtext\nline"), s.phrasesIn(cleaned))

        s.renameCategory(cleaned, " renamed\tcat\r\nname ")
        assertTrue("renameCategory sanitizes", s.categories().contains("renamedcat\nname"))
    }

    @Test fun an_edit_that_collides_with_a_legacy_phrase_through_the_rule_is_refused() {
        val s = ClipboardStore(newDir()).apply { load() }
        s.importPhrasesText("C\t甲\nP\ta\u0001b\nP\tother\n", merge = false)

        assertFalse(
            "the edited text equals a stored phrase once both pass the rule",
            s.editPhrase("甲", "other", "ab"),
        )
        assertEquals(
            "the refused edit changes nothing",
            listOf("a\u0001b", "other"),
            s.phrasesIn("甲"),
        )
    }

    @Test fun a_raw_category_name_never_splits_the_category_in_two() {
        val raw = "work\r\ntemp"
        val cleaned = "work\ntemp"
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

    @Test fun multiline_phrase_and_note_survive_persist_roundtrip() {
        val dir = newDir()
        ClipboardStore(dir).apply {
            load()
            addCategory("cat")
            addPhrasesTo("cat", listOf("line1\nline2"))
            setPhraseNote("cat", "line1\nline2", "note1\nnote2")
            flushPendingWrites()
        }
        val reloaded = ClipboardStore(dir).apply { load() }
        assertEquals(listOf("line1\nline2"), reloaded.phrasesIn("cat"))
        assertEquals("note1\nnote2", reloaded.noteFor("cat", "line1\nline2"))
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

    @Test fun a_name_stored_before_the_rule_existed_still_reaches_its_own_category() {
        val stored = "work\u0001temp"
        val held = "a\u0001b"
        val s = ClipboardStore(newDir()).apply { load() }
        assertTrue(s.importPhrasesText("C\t" + stored + "\nP\t" + held, merge = false))

        assertEquals("what was already stored is left alone", listOf(stored),
            s.categories().filterNot { it == ClipboardStore.DEFAULT_CATEGORY_ID })
        assertEquals("the stored name still reaches its own category", 1, s.addPhrasesTo(stored, listOf("new")))
        assertEquals("no second, near-identical category appears", 2, s.categories().size)
        assertEquals("a phrase the category already holds is not added again in cleaned form",
            0, s.addPhrasesTo(stored, listOf(held)))
        assertEquals(listOf("new", held), s.phrasesIn(stored))
    }

    @Test fun every_lookup_path_reaches_a_legacy_category_through_the_rule() {
        val stored = "work\u0001temp"
        val s = ClipboardStore(newDir()).apply { load() }
        assertTrue(s.importPhrasesText("C\t" + stored + "\nP\tkeep\n", merge = false))
        val cleaned = ClipboardStore.sanitizePhraseText(stored)

        s.setPhraseNote(cleaned, "keep", "note")
        assertEquals("the note lands on the stored name", "note", s.noteFor(stored, "keep"))

        assertTrue("rename finds the stored name through the rule", s.renameCategory(cleaned, "renamed"))
        assertTrue("renamed" in s.categories())
        assertFalse(stored in s.categories())
    }
}
