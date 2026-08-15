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

import com.aegis.ime.engine.CandidateEngine
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.LayoutId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnglishCompletionTest {

    private open class FakeHost : ImeHost {
        val commits = mutableListOf<String>()
        val text = StringBuilder()
        var selection = false
        var enters = 0
        var deletes = 0
        override fun commitText(text: CharSequence) { commits.add(text.toString()); this.text.append(text) }
        override fun deleteBackward() {
            deletes++
            if (text.isNotEmpty()) text.delete(text.length - 1, text.length)
        }
        override fun performEnter() { enters++ }
        override fun hasSelection(): Boolean = selection
        override fun deleteSelection() { selection = false; text.setLength(0) }
        override fun textBeforeCursor(n: Int): CharSequence = text.substring(maxOf(0, text.length - n))
    }

    private val words = listOf("orange", "order", "organ", "ordinary")

    private val dictionary = object : CandidateEngine {
        override fun candidates(composing: String, t9: Boolean): List<String> = emptyList()
        override fun englishCompletions(typed: String): List<String> =
            words.filter { it.length > typed.length && it.startsWith(typed, ignoreCase = true) }
    }

    private val noDictionary = object : CandidateEngine {
        override fun candidates(composing: String, t9: Boolean): List<String> = emptyList()
    }

    private fun act(a: KeyAction) = Key("", action = a)
    private fun out(s: String) = Key(s, output = s)

    private fun english(
        host: FakeHost,
        engine: CandidateEngine = dictionary,
        associations: Boolean = true,
    ): KeyboardController {
        val c = KeyboardController(host, engine)
        c.setAssociationsEnabled(associations)
        c.onKey(act(KeyAction.TOGGLE_LANG))
        return c
    }

    private fun type(c: KeyboardController, letters: String) {
        letters.forEach { c.onKey(out(it.toString())) }
    }

    @Test
    fun typed_letters_compose_a_preedit_instead_of_reaching_the_editor() {
        val h = FakeHost()
        val c = english(h)
        type(c, "or")
        assertEquals(emptyList<String>(), h.commits)
        assertEquals("", h.text.toString())
        assertEquals("or", c.englishWordForTest())
        assertEquals("or", c.preeditForTest())
    }

    @Test
    fun the_typed_word_leads_the_candidates_before_the_completions() {
        val h = FakeHost()
        val c = english(h)
        type(c, "or")
        assertEquals(listOf("or", "orange", "order", "organ", "ordinary"), c.candidateWords())
    }

    @Test
    fun completions_grow_narrower_as_more_letters_arrive() {
        val h = FakeHost()
        val c = english(h)
        type(c, "ora")
        assertEquals(listOf("ora", "orange"), c.candidateWords())
    }

    @Test
    fun turning_associations_off_leaves_english_committing_directly() {
        val h = FakeHost()
        val c = english(h, associations = false)
        type(c, "or")
        assertEquals(emptyList<String>(), c.candidateWords())
        assertEquals(listOf("o", "r"), h.commits)
        assertEquals("or", h.text.toString())
        assertEquals("", c.englishWordForTest())
        assertEquals("", c.preeditForTest())
    }

    @Test
    fun disabling_associations_mid_word_flushes_the_preedit_first() {
        val h = FakeHost()
        val c = english(h)
        type(c, "or")
        c.setAssociationsEnabled(false)
        assertEquals(listOf("or"), h.commits)
        assertEquals("", c.englishWordForTest())
        type(c, "d")
        assertEquals(listOf("or", "d"), h.commits)
    }

    @Test
    fun enabling_associations_starts_composing_from_the_next_letter() {
        val h = FakeHost()
        val c = english(h, associations = false)
        type(c, "or")
        c.setAssociationsEnabled(true)
        type(c, "d")
        assertEquals(listOf("o", "r"), h.commits)
        assertEquals("d", c.englishWordForTest())
    }

    @Test
    fun picking_a_completion_commits_exactly_the_word_it_shows() {
        val h = FakeHost()
        val c = english(h)
        type(c, "or")
        c.onPickCandidate(c.candidateWords().indexOf("orange"))
        assertEquals(listOf("orange"), h.commits)
        assertEquals("orange", h.text.toString())
        assertEquals("", c.englishWordForTest())
    }

    @Test
    fun picking_the_typed_word_commits_it_verbatim() {
        val h = FakeHost()
        val c = english(h)
        type(c, "or")
        c.onPickCandidate(0)
        assertEquals(listOf("or"), h.commits)
        assertEquals("or", h.text.toString())
        assertEquals("", c.englishWordForTest())
    }

    @Test
    fun shifted_letters_compose_in_their_typed_case_and_lead_the_candidates() {
        val h = FakeHost()
        val c = english(h)
        c.onKey(act(KeyAction.SHIFT))
        type(c, "or")
        assertEquals("Or", c.englishWordForTest())
        assertEquals("Or", c.candidateWords().first())
        assertTrue("orange" in c.candidateWords())
        c.onPickCandidate(c.candidateWords().indexOf("orange"))
        assertEquals(listOf("orange"), h.commits)
    }

    @Test
    fun a_space_commits_the_word_and_its_trailing_space_in_one_piece() {
        val h = FakeHost()
        val c = english(h)
        type(c, "or")
        c.onKey(act(KeyAction.SPACE))
        assertEquals(listOf("or "), h.commits)
        assertEquals("or ", h.text.toString())
        assertEquals("", c.englishWordForTest())
    }

    @Test
    fun a_space_with_nothing_composed_is_just_a_space() {
        val h = FakeHost()
        val c = english(h)
        c.onKey(act(KeyAction.SPACE))
        assertEquals(listOf(" "), h.commits)
    }

    @Test
    fun enter_commits_the_word_and_swallows_the_line_break() {
        val h = FakeHost()
        val c = english(h)
        type(c, "or")
        c.onKey(act(KeyAction.ENTER))
        assertEquals(listOf("or"), h.commits)
        assertEquals(0, h.enters)
        assertEquals("", c.englishWordForTest())
    }

    @Test
    fun enter_with_nothing_composed_sends_the_line_break() {
        val h = FakeHost()
        val c = english(h)
        c.onKey(act(KeyAction.ENTER))
        assertEquals(1, h.enters)
        assertEquals(emptyList<String>(), h.commits)
    }

    @Test
    fun backspace_shortens_the_preedit_without_touching_the_editor() {
        val h = FakeHost()
        val c = english(h)
        type(c, "ora")
        assertEquals(listOf("ora", "orange"), c.candidateWords())
        c.onKey(act(KeyAction.BACKSPACE))
        assertEquals("or", c.englishWordForTest())
        assertEquals(0, h.deletes)
        assertEquals(listOf("or", "orange", "order", "organ", "ordinary"), c.candidateWords())
    }

    @Test
    fun backspacing_the_word_away_returns_backspace_to_the_editor() {
        val h = FakeHost()
        val c = english(h)
        type(c, "or")
        c.onKey(act(KeyAction.BACKSPACE))
        c.onKey(act(KeyAction.BACKSPACE))
        assertEquals("", c.englishWordForTest())
        assertEquals(emptyList<String>(), c.candidateWords())
        assertEquals(0, h.deletes)
        c.onKey(act(KeyAction.BACKSPACE))
        assertEquals(1, h.deletes)
    }

    @Test
    fun backspace_during_composition_ignores_an_editor_selection() {
        val h = FakeHost()
        val c = english(h)
        type(c, "or")
        h.selection = true
        c.onKey(act(KeyAction.BACKSPACE))
        assertEquals("o", c.englishWordForTest())
        assertTrue(h.selection)
    }

    @Test
    fun swiping_up_on_backspace_discards_the_word_silently() {
        val h = FakeHost()
        val c = english(h)
        type(c, "or")
        assertTrue(c.onBackspaceSwipe(true))
        assertEquals("", c.englishWordForTest())
        assertEquals(emptyList<String>(), h.commits)
        assertEquals(emptyList<String>(), c.candidateWords())
    }

    @Test
    fun a_direct_symbol_flushes_the_word_and_lands_after_it() {
        val h = FakeHost()
        val c = english(h)
        type(c, "don")
        c.onKey(Key("'", output = "'", direct = true))
        assertEquals(listOf("don", "'"), h.commits)
        assertEquals("don'", h.text.toString())
        assertEquals("", c.englishWordForTest())
        type(c, "t")
        assertEquals("t", c.englishWordForTest())
    }

    @Test
    fun a_non_letter_output_flushes_the_word_then_commits_itself() {
        val h = FakeHost()
        val c = english(h)
        type(c, "or")
        c.onKey(out("1"))
        assertEquals(listOf("or", "1"), h.commits)
        assertEquals("", c.englishWordForTest())
    }

    @Test
    fun switching_language_flushes_the_word_to_the_editor() {
        val h = FakeHost()
        val c = english(h)
        type(c, "or")
        c.onKey(act(KeyAction.TOGGLE_LANG))
        assertEquals(listOf("or"), h.commits)
        assertEquals("", c.englishWordForTest())
        assertEquals(emptyList<String>(), c.candidateWords())
    }

    @Test
    fun leaving_the_letter_layout_flushes_the_word() {
        val h = FakeHost()
        val c = english(h)
        type(c, "or")
        c.onKey(act(KeyAction.SWITCH_SYMBOLS))
        assertEquals(listOf("or"), h.commits)
        assertEquals("", c.englishWordForTest())
        c.onKey(act(KeyAction.SWITCH_ALPHA))
        assertEquals(emptyList<String>(), c.candidateWords())
    }

    @Test
    fun reapplying_the_layout_choice_flushes_the_word() {
        val h = FakeHost()
        val c = english(h)
        type(c, "or")
        c.applyLayoutChoice(LayoutChoice.EN_ALPHA)
        assertEquals(listOf("or"), h.commits)
        assertEquals("", c.englishWordForTest())
    }

    @Test
    fun a_bar_function_flushes_the_word_before_its_panel_opens() {
        val h = FakeHost()
        val c = english(h)
        type(c, "or")
        c.onBarFunction(BarFunction.EMOJI)
        assertEquals(listOf("or"), h.commits)
        assertEquals("", c.englishWordForTest())
    }

    @Test
    fun an_external_commit_flushes_the_word_first() {
        val h = FakeHost()
        val c = english(h)
        type(c, "or")
        c.expireCandidateChoiceUndo()
        assertEquals(listOf("or"), h.commits)
        assertEquals("", c.englishWordForTest())
        assertEquals(emptyList<String>(), c.candidateWords())
    }

    @Test
    fun changing_the_default_language_away_flushes_the_word() {
        val h = FakeHost()
        val c = english(h)
        c.setDefaultLang(Lang.EN)
        type(c, "or")
        c.setDefaultLang(Lang.CN)
        assertEquals(listOf("or"), h.commits)
        assertEquals("", c.englishWordForTest())
        assertEquals(LayoutId.NINE, c.activeLayoutId())
    }

    @Test
    fun a_new_editor_session_discards_the_word_without_committing() {
        val h = FakeHost()
        val c = english(h)
        type(c, "or")
        c.reset()
        assertEquals("", c.englishWordForTest())
        assertEquals(emptyList<String>(), h.commits)
        assertEquals("", h.text.toString())
    }

    @Test
    fun without_an_english_table_the_typed_word_still_leads_and_nothing_breaks() {
        val h = FakeHost()
        val c = english(h, engine = noDictionary)
        type(c, "or")
        assertEquals(listOf("or"), c.candidateWords())
        assertEquals(emptyList<String>(), h.commits)
        c.onPickCandidate(0)
        assertEquals(listOf("or"), h.commits)
        assertEquals("or", h.text.toString())
    }

    @Test
    fun a_field_that_refuses_personalised_learning_still_gets_completions() {
        val h = FakeHost()
        val c = english(h)
        c.setLearningBlocked(true)
        type(c, "or")
        assertEquals(listOf("or", "orange", "order", "organ", "ordinary"), c.candidateWords())
    }

    @Test
    fun a_composing_word_outranks_the_calculator() {
        val h = object : FakeHost() {
            override fun textBeforeCursor(n: Int): CharSequence = "1+2"
        }
        val c = english(h)
        assertEquals(listOf("=3"), c.candidateWords())
        type(c, "or")
        assertEquals(listOf("or", "orange", "order", "organ", "ordinary"), c.candidateWords())
        c.onKey(act(KeyAction.BACKSPACE))
        c.onKey(act(KeyAction.BACKSPACE))
        assertEquals("", c.englishWordForTest())
        assertEquals(listOf("=3"), c.candidateWords())
    }

    @Test
    fun picking_a_word_leaves_no_stale_predictions_behind() {
        val h = FakeHost()
        val c = english(h)
        type(c, "or")
        c.onPickCandidate(c.candidateWords().indexOf("order"))
        assertEquals(emptyList<String>(), c.candidateWords())
        type(c, "s")
        assertEquals("s", c.englishWordForTest())
        assertEquals(listOf("s"), c.candidateWords())
    }

    @Test
    fun chinese_typing_is_untouched_by_the_english_preedit() {
        val h = FakeHost()
        val c = KeyboardController(h, dictionary)
        c.setAssociationsEnabled(true)
        c.onKey(act(KeyAction.SWITCH_ALPHA))
        type(c, "or")
        assertEquals(emptyList<String>(), h.commits)
        assertEquals("o'r", c.preeditForTest())
        assertEquals("", c.englishWordForTest())
    }
}
