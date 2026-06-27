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
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardControllerTest {

    private class FakeHost : ImeHost {
        val commits = mutableListOf<String>()
        var enters = 0
        var deletes = 0
        override fun commitText(text: CharSequence) { commits.add(text.toString()) }
        override fun deleteBackward() { deletes++ }
        override fun performEnter() { enters++ }
    }

    private val engine = object : CandidateEngine {
        override fun candidates(composing: String, t9: Boolean): List<String> = emptyList()
    }

    private fun act(a: KeyAction) = Key("", action = a)
    private fun out(s: String) = Key(s, output = s)

    @Test fun nine_enter_commits_raw_pinyin_not_digits() {
        val h = FakeHost()
        val c = KeyboardController(h, engine)
        c.onKey(act(KeyAction.SWITCH_NINE))
        "6433".forEach { c.onKey(out(it.toString())) }
        c.onKey(act(KeyAction.ENTER))
        assertEquals(listOf("nide"), h.commits)
    }

    @Test fun clear_composing_drops_buffer_without_committing() {
        val h = FakeHost()
        val c = KeyboardController(h, engine)
        c.onKey(act(KeyAction.SWITCH_NINE))
        "6433".forEach { c.onKey(out(it.toString())) }
        c.onKey(act(KeyAction.CLEAR_COMPOSING))
        assertTrue("重输 must not commit text", h.commits.isEmpty())
        c.onKey(act(KeyAction.ENTER))
        assertEquals(1, h.enters)
        assertTrue(h.commits.isEmpty())
    }

    @Test fun picking_a_reading_then_enter_commits_the_full_pinyin() {
        val h = FakeHost()
        val c = KeyboardController(h, engine)
        c.onKey(act(KeyAction.SWITCH_NINE))
        "6433".forEach { c.onKey(out(it.toString())) }
        c.onKey(Key("ni", output = "ni", action = KeyAction.PICK_READING))
        c.onKey(act(KeyAction.ENTER))
        assertEquals(listOf("nide"), h.commits)
    }

    @Test fun left_column_advances_to_the_second_syllable_then_enter_commits_both() {
        val h = FakeHost()
        val c = KeyboardController(h, engine)
        c.onKey(act(KeyAction.SWITCH_NINE))
        "42633".forEach { c.onKey(out(it.toString())) }
        c.onKey(Key("hao", output = "hao", action = KeyAction.PICK_READING))
        c.onKey(Key("de", output = "de", action = KeyAction.PICK_READING))
        c.onKey(act(KeyAction.ENTER))
        assertEquals(listOf("haode"), h.commits)
    }

    @Test fun picking_a_partial_candidate_commits_it_and_keeps_the_rest() {
        val h = FakeHost()
        val partial = object : CandidateEngine {
            override fun candidates(composing: String, t9: Boolean) = candidatesCovered(composing, t9).map { it.word }
            override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>): List<Cand> =
                if (composing.isEmpty()) emptyList() else listOf(Cand("你", 2))
        }
        val c = KeyboardController(h, partial)
        c.onKey(act(KeyAction.SWITCH_NINE))
        "64426".forEach { c.onKey(out(it.toString())) }
        c.onPickCandidate(0)
        c.onKey(act(KeyAction.ENTER))
        assertEquals(listOf("你", "hao"), h.commits)
    }

    @Test fun segment_forces_a_syllable_boundary() {
        val h = FakeHost()
        val c = KeyboardController(h, engine)
        c.onKey(act(KeyAction.SWITCH_NINE))
        "94".forEach { c.onKey(out(it.toString())) }
        c.onKey(act(KeyAction.SEGMENT))
        "26".forEach { c.onKey(out(it.toString())) }
        c.onKey(act(KeyAction.ENTER))
        assertEquals(1, h.commits.size)
        assertTrue("forced 94|26 split should start yi, was ${h.commits[0]}", h.commits[0].startsWith("yi"))
    }

    @Test fun backspace_undoes_a_forced_cut_before_deleting_a_digit() {
        val h = FakeHost()
        val c = KeyboardController(h, engine)
        c.onKey(act(KeyAction.SWITCH_NINE))
        "94".forEach { c.onKey(out(it.toString())) }
        c.onKey(act(KeyAction.SEGMENT))
        c.onKey(act(KeyAction.BACKSPACE))
        "26".forEach { c.onKey(out(it.toString())) }
        c.onKey(act(KeyAction.ENTER))
        assertTrue("cut gone -> single syllable xi.., was ${h.commits[0]}", h.commits[0].startsWith("xi"))
    }

    @Test fun direct_punctuation_flushes_pinyin_then_commits_directly() {
        val h = FakeHost()
        val c = KeyboardController(h, engine)
        c.onKey(act(KeyAction.SWITCH_NINE))
        "64".forEach { c.onKey(out(it.toString())) }
        c.onKey(Key("，", output = "，", direct = true))
        assertEquals(listOf("ni", "，"), h.commits)
    }

    @Test fun english_letters_commit_directly_not_buffered() {
        val h = FakeHost()
        val c = KeyboardController(h, engine)
        c.onKey(act(KeyAction.SWITCH_NINE))
        c.onKey(act(KeyAction.TOGGLE_LANG))
        c.onKey(out("a"))
        c.onKey(act(KeyAction.SPACE))
        assertEquals(listOf("a", " "), h.commits)
    }


    private fun nineColumnFor(digits: String): List<Key> {
        val c = KeyboardController(FakeHost(), engine)
        c.onKey(act(KeyAction.SWITCH_NINE))
        digits.forEach { c.onKey(out(it.toString())) }
        return c.nineLeftColumn()
    }

    @Test fun nine_left_column_shows_only_real_readings_no_blanks_no_punct() {
        val col = nineColumnFor("23744")
        assertEquals(listOf("ce", "a", "b", "c"), col.map { it.label })
        assertTrue("no blank keys", col.none { it.label.isEmpty() })
        assertTrue("no punctuation", col.all { k -> k.label.all { it in 'a'..'z' } })
        assertTrue("all are pick-reading actions", col.all { it.action == KeyAction.PICK_READING })
    }

    @Test fun nine_left_column_is_punctuation_only_when_idle() {
        val c = KeyboardController(FakeHost(), engine)
        c.onKey(act(KeyAction.SWITCH_NINE))
        assertEquals(
            com.aegis.ime.layout.Layouts.ninePunctuation().map { it.label },
            c.nineLeftColumn().map { it.label },
        )
    }

    @Test fun nine_left_column_consistent_for_same_input() {
        assertEquals(nineColumnFor("23744").map { it.label }, nineColumnFor("23744").map { it.label })
    }

    @Test fun nine_left_column_ni_full_scroll_list_matches_reference() {
        val col = nineColumnFor("64744336488").map { it.label }
        assertTrue("ni present, was $col", "ni" in col)
        assertTrue("mi present, was $col", "mi" in col)
        assertTrue("first-key letters m/n/o present, was $col", listOf("m", "n", "o").all { it in col })
        assertTrue("no blank keys, was $col", col.none { it.isEmpty() })
        assertTrue("clean a-z only, was $col", col.all { s -> s.all { it in 'a'..'z' } })
    }


    @Test fun expanded_readings_empty_at_rest_combos_while_composing() {
        val c = KeyboardController(FakeHost(), engine)
        c.onKey(act(KeyAction.SWITCH_NINE))
        assertTrue("no combos at rest", c.expandedReadings().isEmpty())
        "426".forEach { c.onKey(out(it.toString())) }
        assertTrue("hao among combos while composing, was ${c.expandedReadings()}", "hao" in c.expandedReadings())
    }

    @Test fun panel_pick_reading_advances_syllables_and_commits_both() {
        val h = FakeHost()
        val c = KeyboardController(h, engine)
        c.onKey(act(KeyAction.SWITCH_NINE))
        "42633".forEach { c.onKey(out(it.toString())) }
        c.onPickReadingIndex(c.expandedReadings().indexOf("hao"))
        c.onPickReadingIndex(c.expandedReadings().indexOf("de"))
        c.onKey(act(KeyAction.ENTER))
        assertEquals(listOf("haode"), h.commits)
    }

    @Test fun panel_backspace_removes_one_unit() {
        val c = KeyboardController(FakeHost(), engine)
        c.onKey(act(KeyAction.SWITCH_NINE))
        "426".forEach { c.onKey(out(it.toString())) }
        assertTrue("hao present before backspace", "hao" in c.expandedReadings())
        c.onPanelBackspace()
        assertTrue("hao gone after one backspace", "hao" !in c.expandedReadings())
    }

    @Test fun panel_clear_drops_composing() {
        val h = FakeHost()
        val c = KeyboardController(h, engine)
        c.onKey(act(KeyAction.SWITCH_NINE))
        "426".forEach { c.onKey(out(it.toString())) }
        c.onPanelClear()
        assertTrue("combos gone after 重输", c.expandedReadings().isEmpty())
        c.onKey(act(KeyAction.ENTER))
        assertEquals(1, h.enters)
        assertTrue(h.commits.isEmpty())
    }

    @Test fun backspace_up_swipe_clears_pending_pinyin_in_any_layout() {
        val h = FakeHost()
        val c = KeyboardController(h, engine)
        c.onKey(act(KeyAction.SWITCH_NINE))
        "6433".forEach { c.onKey(out(it.toString())) }
        assertTrue("up-swipe must consume + clear the buffer", c.onBackspaceSwipe(true))
        c.onKey(act(KeyAction.ENTER))
        assertEquals(1, h.enters)
        assertTrue(h.commits.isEmpty())
        assertEquals(false, c.onBackspaceSwipe(true))
    }
}
