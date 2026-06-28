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
import com.aegis.ime.layout.KeyAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssociationsAndCalcTest {

    private class EditorHost : ImeHost {
        val sb = StringBuilder()
        var cursor = 0
        var selectionActive = false
        var deletedSelection = false
        val learned = mutableListOf<String>()
        override fun hasSelection(): Boolean = selectionActive
        override fun commitText(text: CharSequence) { sb.insert(cursor, text); cursor += text.length }
        override fun deleteBackward() { if (cursor > 0) { sb.deleteCharAt(cursor - 1); cursor-- } }
        override fun deleteSelection() { deletedSelection = true; selectionActive = false }
        override fun performEnter() {}
        override fun textBeforeCursor(n: Int): CharSequence = sb.substring(maxOf(0, cursor - n), cursor)
        val text get() = sb.toString()
    }

    private fun spyEngine(learned: MutableList<String>) = object : CandidateEngine {
        override fun candidates(composing: String, t9: Boolean) = candidatesCovered(composing, t9).map { it.word }
        override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>, context: CharSequence) =
            if (composing.isEmpty()) emptyList() else listOf(Cand("好的", composing.length))
        override fun learn(prevWord: String?, word: String) { learned.add(word) }
    }

    private fun out(s: String) = Key(s, output = s)

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


    @Test fun u23_sheshidu_offers_celsius_right_after_the_word() {
        val h = EditorHost()
        val c = KeyboardController(h, spyEngine(h.learned))
        "sheshidu".forEach { c.onKey(out(it.toString())) }
        assertEquals("the word stays on top", "好的", c.candidateWords().first())
        assertEquals("℃ is spliced in right after it", "℃", c.candidateWords()[1])
    }

    @Test fun u23_meijin_offers_the_dollar_sign() {
        val h = EditorHost()
        val c = KeyboardController(h, spyEngine(h.learned))
        "meijin".forEach { c.onKey(out(it.toString())) }
        assertTrue("meijin → \$", "\$" in c.candidateWords())
    }

    @Test fun u23_weixiao_offers_the_smile_emoji_from_the_index() {
        val h = EditorHost()
        val c = KeyboardController(h, spyEngine(h.learned))
        "weixiao".forEach { c.onKey(out(it.toString())) }
        assertTrue("weixiao → 🙂", "🙂" in c.candidateWords())
    }

    @Test fun u23_picking_a_unit_symbol_commits_directly_and_does_not_learn_it() {
        val h = EditorHost()
        val c = KeyboardController(h, spyEngine(h.learned))
        "sheshidu".forEach { c.onKey(out(it.toString())) }
        c.onPickCandidate(c.candidateWords().indexOf("℃"))
        assertEquals("℃ committed to the editor", "℃", h.text)
        assertFalse("a symbol must not be learned as a pinyin word", "℃" in h.learned)
        assertTrue("buffer cleared after the symbol commit", c.candidateWords().isEmpty())
    }

    private fun emptySpyEngine(learned: MutableList<String>) = object : CandidateEngine {
        override fun candidates(composing: String, t9: Boolean): List<String> = emptyList()
        override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>, context: CharSequence): List<Cand> = emptyList()
        override fun learn(prevWord: String?, word: String) { learned.add(word) }
    }

    @Test fun space_on_a_first_position_injected_glyph_commits_it_without_learning() {
        val h = EditorHost()
        val c = KeyboardController(h, emptySpyEngine(h.learned))
        c.switchTextLayoutForTest(nine = true)
        "542".forEach { c.onKey(out(it.toString())) }
        c.onKey(Key("jia", output = "jia", action = KeyAction.PICK_READING))
        assertEquals("precondition: the glyph is the first (only) candidate", "+", c.candidateWords().firstOrNull())
        c.onKey(Key("空格", output = " ", action = KeyAction.SPACE))
        assertEquals("the glyph is committed to the editor", "+", h.text)
        assertFalse("the injected glyph must NOT be learned as a pinyin word", "+" in h.learned)
        assertTrue("buffer cleared after committing the glyph", c.candidateWords().isEmpty())
    }
}
