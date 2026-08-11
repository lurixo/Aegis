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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnglishCompletionTest {

    private open class FakeHost : ImeHost {
        val commits = mutableListOf<String>()
        val text = StringBuilder()
        var selection = false
        override fun commitText(text: CharSequence) { commits.add(text.toString()); this.text.append(text) }
        override fun deleteBackward() {
            if (text.isNotEmpty()) text.delete(text.length - 1, text.length)
        }
        override fun performEnter() {}
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
    fun typing_or_offers_orange_from_the_english_table() {
        val h = FakeHost()
        val c = english(h)
        type(c, "or")
        assertTrue("orange" in c.candidateWords())
        assertEquals(listOf("orange", "order", "organ", "ordinary"), c.candidateWords())
    }

    @Test
    fun the_letters_still_reach_the_editor_one_by_one() {
        val h = FakeHost()
        val c = english(h)
        type(c, "or")
        assertEquals(listOf("o", "r"), h.commits)
        assertEquals("or", h.text.toString())
        assertEquals("", c.preeditForTest())
    }

    @Test
    fun completions_grow_narrower_as_more_letters_arrive() {
        val h = FakeHost()
        val c = english(h)
        type(c, "ora")
        assertEquals(listOf("orange"), c.candidateWords())
    }

    @Test
    fun turning_associations_off_leaves_english_exactly_as_it_was() {
        val h = FakeHost()
        val c = english(h, associations = false)
        type(c, "or")
        assertEquals(emptyList<String>(), c.candidateWords())
        assertEquals(listOf("o", "r"), h.commits)
        assertEquals("or", h.text.toString())
    }

    @Test
    fun the_toggle_takes_effect_without_retyping() {
        val h = FakeHost()
        val c = english(h, associations = false)
        type(c, "or")
        assertEquals(emptyList<String>(), c.candidateWords())
        c.setAssociationsEnabled(true)
        assertEquals(listOf("orange", "order", "organ", "ordinary"), c.candidateWords())
        c.setAssociationsEnabled(false)
        assertEquals(emptyList<String>(), c.candidateWords())
    }

    @Test
    fun picking_a_completion_commits_only_the_missing_suffix() {
        val h = FakeHost()
        val c = english(h)
        type(c, "or")
        c.onPickCandidate(c.candidateWords().indexOf("orange"))
        assertEquals(listOf("o", "r", "ange"), h.commits)
        assertEquals("orange", h.text.toString())
    }

    @Test
    fun picking_a_completion_keeps_the_case_the_user_typed() {
        val h = FakeHost()
        val c = english(h)
        c.onKey(act(KeyAction.SHIFT))
        type(c, "or")
        assertEquals("Or", h.text.toString())
        c.onPickCandidate(c.candidateWords().indexOf("orange"))
        assertEquals(listOf("O", "r", "ange"), h.commits)
        assertEquals("Orange", h.text.toString())
    }

    @Test
    fun a_picked_completion_becomes_the_word_that_further_letters_extend() {
        val h = FakeHost()
        val c = english(h)
        type(c, "or")
        c.onPickCandidate(c.candidateWords().indexOf("order"))
        assertEquals("order", c.englishWordForTest())
        assertEquals(emptyList<String>(), c.candidateWords())
    }

    @Test
    fun a_space_ends_the_tracked_word() {
        val h = FakeHost()
        val c = english(h)
        type(c, "or")
        c.onKey(act(KeyAction.SPACE))
        assertEquals("", c.englishWordForTest())
        assertEquals(emptyList<String>(), c.candidateWords())
        assertEquals("or ", h.text.toString())
    }

    @Test
    fun a_non_letter_ends_the_tracked_word() {
        val h = FakeHost()
        val c = english(h)
        type(c, "or")
        c.onKey(Key("-", output = "-", direct = true))
        assertEquals("", c.englishWordForTest())
        assertEquals(emptyList<String>(), c.candidateWords())
    }

    @Test
    fun enter_ends_the_tracked_word() {
        val h = FakeHost()
        val c = english(h)
        type(c, "or")
        c.onKey(act(KeyAction.ENTER))
        assertEquals("", c.englishWordForTest())
        assertEquals(emptyList<String>(), c.candidateWords())
    }

    @Test
    fun backspace_shortens_the_tracked_word_and_reopens_the_wider_list() {
        val h = FakeHost()
        val c = english(h)
        type(c, "ora")
        assertEquals(listOf("orange"), c.candidateWords())
        c.onKey(act(KeyAction.BACKSPACE))
        assertEquals("or", c.englishWordForTest())
        assertEquals(listOf("orange", "order", "organ", "ordinary"), c.candidateWords())
    }

    @Test
    fun backspacing_the_word_away_stops_the_completions() {
        val h = FakeHost()
        val c = english(h)
        type(c, "or")
        c.onKey(act(KeyAction.BACKSPACE))
        c.onKey(act(KeyAction.BACKSPACE))
        assertEquals("", c.englishWordForTest())
        assertEquals(emptyList<String>(), c.candidateWords())
        c.onKey(act(KeyAction.BACKSPACE))
        assertEquals("", c.englishWordForTest())
    }

    @Test
    fun deleting_a_selection_ends_the_tracked_word() {
        val h = FakeHost()
        val c = english(h)
        type(c, "or")
        h.selection = true
        c.onKey(act(KeyAction.BACKSPACE))
        assertEquals("", c.englishWordForTest())
    }

    @Test
    fun switching_to_chinese_ends_the_tracked_word() {
        val h = FakeHost()
        val c = english(h)
        type(c, "or")
        c.onKey(act(KeyAction.TOGGLE_LANG))
        assertEquals("", c.englishWordForTest())
        assertEquals(emptyList<String>(), c.candidateWords())
    }

    @Test
    fun leaving_the_letter_layout_ends_the_tracked_word() {
        val h = FakeHost()
        val c = english(h)
        type(c, "or")
        c.onKey(act(KeyAction.SWITCH_SYMBOLS))
        assertEquals("", c.englishWordForTest())
        c.onKey(act(KeyAction.SWITCH_ALPHA))
        assertEquals(emptyList<String>(), c.candidateWords())
    }

    @Test
    fun choosing_the_english_layout_from_the_panel_ends_the_tracked_word() {
        val h = FakeHost()
        val c = english(h)
        type(c, "or")
        c.applyLayoutChoice(LayoutChoice.EN_ALPHA)
        assertEquals("", c.englishWordForTest())
    }

    @Test
    fun a_new_editor_session_ends_the_tracked_word() {
        val h = FakeHost()
        val c = english(h)
        type(c, "or")
        c.reset()
        assertEquals("", c.englishWordForTest())
    }

    @Test
    fun the_service_can_end_the_tracked_word_on_its_own() {
        val h = FakeHost()
        val c = english(h)
        type(c, "or")
        c.clearEnglishWord()
        assertEquals("", c.englishWordForTest())
        assertEquals(emptyList<String>(), c.candidateWords())
    }

    @Test
    fun ending_the_tracked_word_also_forgets_the_moves_it_was_waiting_for() {
        val h = FakeHost()
        val c = english(h)
        type(c, "or")
        c.clearEnglishWord()

        type(c, "o")
        assertEquals("o", c.englishWordForTest())
        assertTrue("the first letter after a field change still tracks", c.candidateWords().isNotEmpty())
    }

    @Test
    fun a_panel_edit_action_ends_the_tracked_word() {
        val h = FakeHost()
        val c = english(h)
        type(c, "or")
        c.expireCandidateChoiceUndo()
        assertEquals("", c.englishWordForTest())
        assertEquals(emptyList<String>(), c.candidateWords())
    }

    @Test
    fun a_cursor_move_we_did_not_cause_ends_the_tracked_word() {
        val h = FakeHost()
        val c = english(h)
        type(c, "or")
        c.onSelectionUpdate(2, 2, 2, 2)
        c.onSelectionUpdate(2, 2, 9, 9)
        assertEquals("", c.englishWordForTest())
        assertEquals(emptyList<String>(), c.candidateWords())
    }

    @Test
    fun the_cursor_move_our_own_typing_causes_keeps_the_tracked_word() {
        val h = FakeHost()
        val c = english(h)
        c.onKey(out("o"))
        c.onSelectionUpdate(0, 0, 1, 1)
        c.onKey(out("r"))
        c.onSelectionUpdate(1, 1, 2, 2)
        assertEquals("or", c.englishWordForTest())
        assertEquals(listOf("orange", "order", "organ", "ordinary"), c.candidateWords())
    }

    @Test
    fun a_batched_report_of_our_own_typing_keeps_the_tracked_word() {
        val h = FakeHost()
        val c = english(h)
        type(c, "or")
        c.onSelectionUpdate(0, 0, 2, 2)
        assertEquals("or", c.englishWordForTest())
    }

    @Test
    fun a_selection_appearing_ends_the_tracked_word() {
        val h = FakeHost()
        val c = english(h)
        type(c, "or")
        c.onSelectionUpdate(0, 0, 0, 2)
        assertEquals("", c.englishWordForTest())
    }

    @Test
    fun the_cursor_move_a_picked_completion_causes_keeps_the_word() {
        val h = FakeHost()
        val c = english(h)
        type(c, "or")
        c.onSelectionUpdate(0, 0, 2, 2)
        c.onPickCandidate(c.candidateWords().indexOf("orange"))
        c.onSelectionUpdate(2, 2, 6, 6)
        assertEquals("orange", c.englishWordForTest())
    }

    @Test
    fun without_an_english_table_nothing_is_offered_and_nothing_breaks() {
        val h = FakeHost()
        val c = english(h, engine = noDictionary)
        type(c, "or")
        assertEquals(emptyList<String>(), c.candidateWords())
        assertEquals(listOf("o", "r"), h.commits)
        assertEquals("or", h.text.toString())
    }

    @Test
    fun a_field_that_refuses_personalised_learning_still_gets_completions() {
        val h = FakeHost()
        val c = english(h)
        c.setLearningBlocked(true)
        type(c, "or")
        assertEquals(listOf("orange", "order", "organ", "ordinary"), c.candidateWords())
    }

    @Test
    fun taking_a_calculator_result_ends_the_tracked_word() {
        val h = object : FakeHost() {
            override fun textBeforeCursor(n: Int): CharSequence = "1+2"
        }
        val c = english(h)
        type(c, "or")
        assertEquals(listOf("=3"), c.candidateWords())
        c.onPickCandidate(0)
        assertEquals("", c.englishWordForTest())
    }

    @Test
    fun chinese_typing_is_untouched_by_the_english_ledger() {
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
