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

/** U23 (emoji/symbol associations) + U25 (inline calculator): both inject special candidates. */
class AssociationsAndCalcTest {

    /** A FakeHost modelling the editor WITH a caret, so a manual cursor move (M-3) can be simulated:
     *  edits act at [cursor], and textBeforeCursor / replaceBeforeCursor are caret-relative like a real IC. */
    private class EditorHost : ImeHost {
        val sb = StringBuilder()
        var cursor = 0
        var selectionActive = false // M-3: simulate a non-empty selection made with no keystroke
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
        /** Pre-fill the editor with [s], caret at the end (as if the user had typed it). */
        fun preset(s: String) { sb.setLength(0); sb.append(s); cursor = s.length }
        /** Simulate a manual caret move to [pos] with no keystroke (the M-3 trigger). */
        fun moveCursorTo(pos: Int) { cursor = pos }
        val text get() = sb.toString()
    }

    /** Chinese IME behavior note. */
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

    // ---------- U23 ----------

    @Test fun u23_associated_emoji_is_offered_after_the_top_candidate() {
        val h = EditorHost()
        val c = KeyboardController(h, spyEngine(h.learned)) // default ALPHA + CN = pinyin buffer
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

    // ---------- U23 data expansion: units / currency / emoji index ----------

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

    /** An engine with ZERO dictionary candidates that still records learn() — the ⑥ edge where a glyph is first. */
    private fun emptySpyEngine(learned: MutableList<String>) = object : CandidateEngine {
        override fun candidates(composing: String, t9: Boolean): List<String> = emptyList()
        override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>, context: CharSequence): List<Cand> = emptyList()
        override fun learn(prevWord: String?, word: String) { learned.add(word) }
    }

    @Test fun space_on_a_first_position_injected_glyph_commits_it_without_learning() {
        // ⑥ edge (the spec's "9-key locked reading with zero dictionary candidates"): on the 9-key, lock the reading "jia" while the engine
        // returns ZERO candidates for it. injectAssociations then makes the glyph "+" the WHOLE list, so
        // candidates[0] is a directCommit glyph. Space must commit it directly (not via the pinyin learn path),
        // so adaptation is never polluted. (On the 26-key the raw-letters fallback keeps a word first, so this
        // edge only surfaces on the 9-key locked path.)
        val h = EditorHost()
        val c = KeyboardController(h, emptySpyEngine(h.learned))
        c.onKey(Key("", action = KeyAction.SWITCH_NINE))
        "542".forEach { c.onKey(out(it.toString())) } // T9 digits for j-i-a
        c.onKey(Key("jia", output = "jia", action = KeyAction.PICK_READING)) // lock the reading → rawComposing = "jia"
        assertEquals("precondition: the glyph is the first (only) candidate", "+", c.candidateWords().firstOrNull())
        c.onKey(Key("空格", output = " ", action = KeyAction.SPACE))
        assertEquals("the glyph is committed to the editor", "+", h.text)
        assertFalse("the injected glyph must NOT be learned as a pinyin word", "+" in h.learned)
        assertTrue("buffer cleared after committing the glyph", c.candidateWords().isEmpty())
    }

    // ---------- U25 ----------

    @Test fun u25_a_trailing_expression_shows_its_result() {
        val h = EditorHost()
        val c = KeyboardController(h, emptyEngine)
        "12+34*2".forEach { c.onKey(digit(it.toString())) } // committed directly to the editor
        assertEquals("the calculator offers =80", listOf("=80"), c.candidateWords())
    }

    @Test fun u25_picking_the_result_appends_it_after_the_expression() {
        // I1: picking the calc candidate APPENDS "=result" after the expression (1+1 → 1+1=2), it does
        // NOT replace the expression with the bare result.
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
        h.preset("2+2") // editor already holds an expression
        val c = KeyboardController(h, spyEngine(h.learned))
        "ni".forEach { c.onKey(out(it.toString())) } // now composing pinyin
        assertFalse("the calc result must not appear while a pinyin buffer is active", "4" in c.candidateWords())
        assertEquals("好的", c.candidateWords().first())
    }

    @Test fun u25_m3_moving_the_caret_before_picking_does_not_delete_unrelated_text() {
        // M-3 (data loss): type an expression so its result is offered, then move the caret elsewhere with
        // NO keystroke (so the stale candidate lingers), then tap it. The blind old replace deleted
        // Chinese IME behavior note.
        // the live text, finds the expression is no longer there, and leaves every character intact.
        val h = EditorHost()
        val c = KeyboardController(h, emptyEngine)
        "买了3个5*2".forEach { c.onKey(digit(it.toString())) } // Chinese IME behavior note.
        assertEquals("trailing 5*2 is offered as =10", listOf("=10"), c.candidateWords())

        h.moveCursorTo(4) // Chinese IME behavior note.

        c.onPickCandidate(c.candidateWords().indexOf("=10"))
        assertEquals("nothing is appended at the stale caret; text intact", "买了3个5*2", h.text)
    }

    @Test fun u25_m3_picking_a_still_valid_result_after_an_unrelated_edit_still_replaces() {
        // The guard must not over-fire: if the SAME expression still sits before the caret at pick time,
        // the replace proceeds normally (regression guard for the happy path).
        val h = EditorHost()
        val c = KeyboardController(h, emptyEngine)
        "5*2".forEach { c.onKey(digit(it.toString())) }
        assertEquals(listOf("=10"), c.candidateWords())
        c.onPickCandidate(0)
        assertEquals("the result is appended after the live expression", "5*2=10", h.text)
    }

    @Test fun f3_typing_a_trailing_equals_completes_the_equation_in_place() {
        // F3: the numpad '=' commits directly; the calculator then offers just the bare result so a pick
        // lands "1+1=2" — never a doubled "1+1==2".
        val h = EditorHost()
        val c = KeyboardController(h, emptyEngine)
        "1+1=".forEach { c.onKey(digit(it.toString())) }
        assertEquals("the bare result is offered after the typed '='", listOf("2"), c.candidateWords())
        c.onPickCandidate(0)
        assertEquals("1+1=2", h.text)
        assertTrue("no candidate lingers once the equation is complete", c.candidateWords().isEmpty())
    }

    @Test fun f3_a_percentage_expression_computes_and_appends() {
        // F3: '%' parses as a postfix ÷100, so a numpad-built "200×15%" offers and appends "=30".
        val h = EditorHost()
        val c = KeyboardController(h, emptyEngine)
        "200×15%".forEach { c.onKey(digit(it.toString())) }
        assertEquals(listOf("=30"), c.candidateWords())
        c.onPickCandidate(0)
        assertEquals("200×15%=30", h.text)
    }

    @Test fun u25_m3_picking_with_an_active_selection_skips_the_replace() {
        // Same M-3 family: the expression still precedes the caret, but a selection is
        // active. deleteSurroundingText is selection-start-relative while commitText replaces the selection,
        // so a blind replace would delete the expression AND destroy the selected text. The guard skips it.
        val h = EditorHost()
        val c = KeyboardController(h, emptyEngine)
        "5*2".forEach { c.onKey(digit(it.toString())) }
        assertEquals(listOf("=10"), c.candidateWords())
        h.selectionActive = true // user selected unrelated text with no keystroke → stale calc cand
        c.onPickCandidate(c.candidateWords().indexOf("=10"))
        assertEquals("with a selection active the calc append is skipped (no data loss)", "5*2", h.text)
    }
}
