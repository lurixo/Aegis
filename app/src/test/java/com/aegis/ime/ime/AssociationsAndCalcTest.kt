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

package com.aegis.ime.ime

import com.aegis.ime.decoder.Cand
import com.aegis.ime.engine.CandidateEngine
import com.aegis.ime.layout.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssociationsAndCalcTest {

    private class EditorHost : ImeHost {
        val sb = StringBuilder()
        var cursor = 0
        var selectionActive = false
        val learned = mutableListOf<String>()
        override fun hasSelection(): Boolean = selectionActive
        override fun commitText(text: CharSequence) { sb.insert(cursor, text); cursor += text.length }
        override fun deleteBackward() { if (cursor > 0) { sb.deleteCharAt(cursor - 1); cursor-- } }
        override fun performEnter() {}
        override fun textBeforeCursor(n: Int): CharSequence = sb.substring(maxOf(0, cursor - n), cursor)
        override fun replaceBeforeCursor(length: Int, text: CharSequence) {
            val from = maxOf(0, cursor - length)
            sb.delete(from, cursor); cursor = from
            sb.insert(cursor, text); cursor += text.length
        }
        fun preset(s: String) { sb.setLength(0); sb.append(s); cursor = s.length }
        fun moveCursorTo(pos: Int) { cursor = pos }
        val text get() = sb.toString()
    }

    private fun spyEngine(learned: MutableList<String>) = object : CandidateEngine {
        override fun candidates(composing: String, t9: Boolean) = candidatesCovered(composing, t9).map { it.word }
        override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>, context: CharSequence) =
            if (composing.isEmpty()) emptyList() else listOf(Cand("好的", composing.length))
        override fun learn(prevWord: String?, word: String) { learned.add(word) }
    }

    private val emptyEngine = object : CandidateEngine {
        override fun candidates(composing: String, t9: Boolean): List<String> = emptyList()
    }

    private fun out(s: String) = Key(s, output = s)
    private fun digit(s: String) = Key(s, output = s, direct = true)


    @Test fun u23_associated_emoji_is_offered_after_the_top_candidate() {
        val h = EditorHost()
        val c = KeyboardController(h, spyEngine(h.learned))
        "haode".forEach { c.onKey(out(it.toString())) }
        assertEquals("好的 stays the top candidate", "好的", c.candidateWords().first())
        assertTrue("👌 is offered for haode", "👌" in c.candidateWords())
    }

    @Test fun u23_picking_the_emoji_commits_it_directly_and_does_not_learn_it() {
        val h = EditorHost()
        val c = KeyboardController(h, spyEngine(h.learned))
        "haode".forEach { c.onKey(out(it.toString())) }
        c.onPickCandidate(c.candidateWords().indexOf("👌"))
        assertEquals("emoji committed to the editor", "👌", h.text)
        assertFalse("an emoji must not be learned as a pinyin word", "👌" in h.learned)
        assertTrue("buffer cleared after the emoji commit", c.candidateWords().isEmpty())
    }

    @Test fun u23_symbol_association_jia_offers_plus() {
        val h = EditorHost()
        val c = KeyboardController(h, spyEngine(h.learned))
        "jia".forEach { c.onKey(out(it.toString())) }
        assertTrue("jia → +", "+" in c.candidateWords())
    }


    @Test fun u25_a_trailing_expression_shows_its_result() {
        val h = EditorHost()
        val c = KeyboardController(h, emptyEngine)
        "12+34*2".forEach { c.onKey(digit(it.toString())) }
        assertEquals("the calculator offers =80", listOf("=80"), c.candidateWords())
    }

    @Test fun u25_picking_the_result_appends_it_after_the_expression() {
        val h = EditorHost()
        val c = KeyboardController(h, emptyEngine)
        "12+34*2".forEach { c.onKey(digit(it.toString())) }
        c.onPickCandidate(0)
        assertEquals("=80 is appended, the expression is kept", "12+34*2=80", h.text)
        assertTrue("no candidate lingers after committing the result", c.candidateWords().isEmpty())
    }

    @Test fun u25_plain_numbers_are_not_treated_as_a_calculation() {
        val h = EditorHost()
        val c = KeyboardController(h, emptyEngine)
        "123456".forEach { c.onKey(digit(it.toString())) }
        assertTrue("a phone-number-like digit run shows no calc result", c.candidateWords().isEmpty())
    }

    @Test fun u25_does_not_fire_while_composing_pinyin() {
        val h = EditorHost()
        h.preset("2+2")
        val c = KeyboardController(h, spyEngine(h.learned))
        "ni".forEach { c.onKey(out(it.toString())) }
        assertFalse("the calc result must not appear while a pinyin buffer is active", "4" in c.candidateWords())
        assertEquals("好的", c.candidateWords().first())
    }

    @Test fun u25_m3_moving_the_caret_before_picking_does_not_delete_unrelated_text() {
        val h = EditorHost()
        val c = KeyboardController(h, emptyEngine)
        "买了3个5*2".forEach { c.onKey(digit(it.toString())) }
        assertEquals("trailing 5*2 is offered as =10", listOf("=10"), c.candidateWords())

        h.moveCursorTo(4)

        c.onPickCandidate(c.candidateWords().indexOf("=10"))
        assertEquals("nothing is appended at the stale caret; text intact", "买了3个5*2", h.text)
    }

    @Test fun u25_m3_picking_a_still_valid_result_after_an_unrelated_edit_still_replaces() {
        val h = EditorHost()
        val c = KeyboardController(h, emptyEngine)
        "5*2".forEach { c.onKey(digit(it.toString())) }
        assertEquals(listOf("=10"), c.candidateWords())
        c.onPickCandidate(0)
        assertEquals("the result is appended after the live expression", "5*2=10", h.text)
    }

    @Test fun u25_m3_picking_with_an_active_selection_skips_the_replace() {
        val h = EditorHost()
        val c = KeyboardController(h, emptyEngine)
        "5*2".forEach { c.onKey(digit(it.toString())) }
        assertEquals(listOf("=10"), c.candidateWords())
        h.selectionActive = true
        c.onPickCandidate(c.candidateWords().indexOf("=10"))
        assertEquals("with a selection active the calc append is skipped (no data loss)", "5*2", h.text)
    }
}
