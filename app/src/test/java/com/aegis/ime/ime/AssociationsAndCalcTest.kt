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

    @Test fun u25_an_expression_longer_than_the_scan_window_is_not_answered_from_its_tail() {
        val h = EditorHost()
        val c = KeyboardController(h, emptyEngine)
        h.preset("1".repeat(40))
        "+1".forEach { c.onKey(digit(it.toString())) }
        assertTrue(
            "an operand cut off by the window must not be answered as if it were whole, was ${c.candidateWords()}",
            c.candidateWords().isEmpty(),
        )
    }

    @Test fun u25_an_expression_that_exactly_fills_the_scan_window_is_still_answered() {
        val h = EditorHost()
        val c = KeyboardController(h, emptyEngine)
        h.preset("11" + "+1".repeat(14))
        "+1".forEach { c.onKey(digit(it.toString())) }
        assertEquals("thirty-two characters still fit", 32, h.text.length)
        assertEquals(listOf("=26"), c.candidateWords())
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

    @Test fun f3_typing_a_trailing_equals_completes_the_equation_in_place() {
        val h = EditorHost()
        val c = KeyboardController(h, emptyEngine)
        "1+1=".forEach { c.onKey(digit(it.toString())) }
        assertEquals("the bare result is offered after the typed '='", listOf("2"), c.candidateWords())
        c.onPickCandidate(0)
        assertEquals("1+1=2", h.text)
        assertTrue("no candidate lingers once the equation is complete", c.candidateWords().isEmpty())
    }

    @Test fun f3_a_percentage_expression_computes_and_appends() {
        val h = EditorHost()
        val c = KeyboardController(h, emptyEngine)
        "200×15%".forEach { c.onKey(digit(it.toString())) }
        assertEquals(listOf("=30"), c.candidateWords())
        c.onPickCandidate(0)
        assertEquals("200×15%=30", h.text)
    }

    @Test fun no_calc_candidate_appears_while_learning_is_blocked() {
        val h = EditorHost()
        val c = KeyboardController(h, emptyEngine)
        c.setLearningBlocked(true)
        "12+3".forEach { c.onKey(digit(it.toString())) }
        assertTrue("a field that opted out of personalization must not surface a calc result", c.candidateWords().isEmpty())
    }

    @Test fun picking_a_calc_candidate_after_learning_becomes_blocked_commits_nothing() {
        val h = EditorHost()
        val c = KeyboardController(h, emptyEngine)
        "5*2".forEach { c.onKey(digit(it.toString())) }
        assertEquals(listOf("=10"), c.candidateWords())
        c.setLearningBlocked(true)
        c.onPickCandidate(0)
        assertEquals("the calc append is skipped once learning is blocked", "5*2", h.text)
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

    @Test fun clearing_composing_dismisses_the_calc_result_and_keeps_the_expression() {
        val h = EditorHost()
        val c = KeyboardController(h, emptyEngine)
        "12+34*2".forEach { c.onKey(digit(it.toString())) }
        assertEquals(listOf("=80"), c.candidateWords())
        c.onKey(Key("", action = KeyAction.CLEAR_COMPOSING))
        assertTrue("重输 clears the calc result from the toolbar", c.candidateWords().isEmpty())
        assertEquals("重输 leaves the typed expression untouched", "12+34*2", h.text)
    }

    @Test fun the_first_backspace_dismisses_the_calc_result_and_leaves_the_expression_untouched() {
        val h = EditorHost()
        val c = KeyboardController(h, emptyEngine)
        "12+345".forEach { c.onKey(digit(it.toString())) }
        assertEquals(listOf("=357"), c.candidateWords())
        c.onKey(Key("", action = KeyAction.BACKSPACE))
        assertTrue("退格 clears the calc result instead of surfacing a new one", c.candidateWords().isEmpty())
        assertEquals("the first 退格 consumes only the calc step; the expression stays intact", "12+345", h.text)
    }

    @Test fun a_dismissed_calc_result_returns_once_new_input_extends_the_expression() {
        val h = EditorHost()
        val c = KeyboardController(h, emptyEngine)
        "5*2".forEach { c.onKey(digit(it.toString())) }
        assertEquals(listOf("=10"), c.candidateWords())
        c.onKey(Key("", action = KeyAction.CLEAR_COMPOSING))
        assertTrue("dismissed after 重输", c.candidateWords().isEmpty())
        c.onKey(digit("3"))
        assertEquals("typing again re-derives the result for the new expression", listOf("=115"), c.candidateWords())
        assertEquals("5*23", h.text)
    }

    @Test fun repeated_backspace_dismisses_the_result_then_deletes_one_char_per_press() {
        val h = EditorHost()
        val c = KeyboardController(h, emptyEngine)
        "100+200".forEach { c.onKey(digit(it.toString())) }
        assertEquals(listOf("=300"), c.candidateWords())
        c.onKey(Key("", action = KeyAction.BACKSPACE))
        assertEquals("the first press keeps the full expression", "100+200", h.text)
        c.onKey(Key("", action = KeyAction.BACKSPACE))
        assertTrue("no result lingers across successive backspaces", c.candidateWords().isEmpty())
        assertEquals("the second press deletes exactly one trailing char", "100+20", h.text)
    }

    @Test fun backspace_with_an_active_selection_deletes_the_selection_not_the_calc_step() {
        val h = EditorHost()
        val c = KeyboardController(h, emptyEngine)
        "5*2".forEach { c.onKey(digit(it.toString())) }
        assertEquals(listOf("=10"), c.candidateWords())
        h.selectionActive = true
        c.onKey(Key("", action = KeyAction.BACKSPACE))
        assertTrue("退格 deletes the active selection instead of being swallowed by the calc step", h.deletedSelection)
        assertEquals("no expression char is removed when a selection takes the backspace", "5*2", h.text)
        assertTrue("the calc result clears alongside the selection delete", c.candidateWords().isEmpty())
    }
}
