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
import com.aegis.ime.layout.LayoutId
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

    @Test fun picking_a_partial_candidate_builds_a_prefix_and_defers_the_commit() {
        val h = FakeHost()
        val partial = object : CandidateEngine {
            override fun candidates(composing: String, t9: Boolean) = candidatesCovered(composing, t9).map { it.word }
            override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>, context: CharSequence): List<Cand> =
                if (composing.isEmpty()) emptyList() else listOf(Cand("你", 2))
        }
        val c = KeyboardController(h, partial)
        c.onKey(act(KeyAction.SWITCH_NINE))
        "64426".forEach { c.onKey(out(it.toString())) }
        c.onPickCandidate(0)
        assertTrue("a partial pick must NOT commit to the editor", h.commits.isEmpty())
        assertEquals("你 is the assembled prefix", "你", c.composingPrefix())
        assertEquals("the prefix renders at the strip's leftmost", "你hao", c.preeditForTest())
        c.onKey(act(KeyAction.ENTER))
        assertEquals(listOf("你hao"), h.commits)
    }

    @Test fun backspace_peels_the_assembled_prefix_before_touching_the_editor() {
        val h = FakeHost()
        val partial = object : CandidateEngine {
            override fun candidates(composing: String, t9: Boolean) = candidatesCovered(composing, t9).map { it.word }
            override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>, context: CharSequence): List<Cand> =
                if (composing.isEmpty()) emptyList() else listOf(Cand("你", 2))
        }
        val c = KeyboardController(h, partial)
        c.onKey(act(KeyAction.SWITCH_NINE))
        "64426".forEach { c.onKey(out(it.toString())) }
        c.onPickCandidate(0)
        assertEquals("你", c.composingPrefix())
        repeat(3) { c.onKey(act(KeyAction.BACKSPACE)) }
        assertEquals("prefix intact while the remainder peels", "你", c.composingPrefix())
        c.onKey(act(KeyAction.BACKSPACE))
        assertEquals("prefix peeled away", "", c.composingPrefix())
        assertEquals("never deleted committed editor text", 0, h.deletes)
        assertTrue("nothing was ever committed", h.commits.isEmpty())
    }

    @Test fun space_on_a_bare_assembled_prefix_commits_it_once_without_a_literal_space() {
        val h = FakeHost()
        val partial = object : CandidateEngine {
            override fun candidates(composing: String, t9: Boolean) = candidatesCovered(composing, t9).map { it.word }
            override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>, context: CharSequence): List<Cand> =
                if (composing.isEmpty()) emptyList() else listOf(Cand("你", 2))
        }
        val c = KeyboardController(h, partial)
        c.onKey(act(KeyAction.SWITCH_NINE))
        "64426".forEach { c.onKey(out(it.toString())) }
        c.onPickCandidate(0)
        repeat(3) { c.onKey(act(KeyAction.BACKSPACE)) }
        c.onKey(act(KeyAction.SPACE))
        assertEquals(listOf("你"), h.commits)
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

    @Test fun custom_symbol_key_opens_the_panel() {
        var opened = false
        val c = KeyboardController(FakeHost(), engine).apply { onShowCustomSymbols = { opened = true } }
        c.onKey(act(KeyAction.SWITCH_NINE))
        c.onKey(Key("自定义", action = KeyAction.CUSTOM_SYMBOL))
        assertTrue("自定义 tap opens the custom-symbol panel", opened)
    }

    @Test fun set_custom_symbols_surfaces_them_in_the_idle_column_before_自定义() {
        val c = KeyboardController(FakeHost(), engine)
        c.onKey(act(KeyAction.SWITCH_NINE))
        c.setCustomSymbols(listOf("、", "《"))
        val labels = c.nineLeftColumn().map { it.label }
        assertTrue("、 present", "、" in labels)
        assertTrue("《 present", "《" in labels)
        assertEquals("自定义 stays last", "自定义", labels.last())
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

    @Test fun no_ghost_suggestion_after_commit() {
        val full = object : CandidateEngine {
            override fun candidates(composing: String, t9: Boolean) = candidatesCovered(composing, t9).map { it.word }
            override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>, context: CharSequence) =
                if (composing.isEmpty()) emptyList() else listOf(Cand("你好", composing.length))
        }
        val c = KeyboardController(FakeHost(), full)
        c.onKey(act(KeyAction.SWITCH_NINE))
        "426".forEach { c.onKey(out(it.toString())) }
        c.onPickCandidate(0)
        assertTrue("no candidates linger after commit (no ghost)", c.candidateWords().isEmpty())
    }


    private fun learnSpyEngine(learned: MutableList<String>) = object : CandidateEngine {
        override fun candidates(composing: String, t9: Boolean) = candidatesCovered(composing, t9).map { it.word }
        override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>, context: CharSequence) =
            if (composing.isEmpty()) emptyList() else listOf(Cand("密码", composing.length))
        override fun learn(prevWord: String?, word: String) { learned.add(word) }
    }

    @Test fun sensitive_field_commit_is_not_learned() {
        val learned = mutableListOf<String>()
        val c = KeyboardController(FakeHost(), learnSpyEngine(learned))
        c.setLearningBlocked(true)
        c.onKey(act(KeyAction.SWITCH_NINE))
        "426".forEach { c.onKey(out(it.toString())) }
        c.onPickCandidate(0)
        assertTrue("a blocked field must never learn the committed word", learned.isEmpty())
    }

    @Test fun ordinary_field_commit_is_learned_no_regression() {
        val learned = mutableListOf<String>()
        val c = KeyboardController(FakeHost(), learnSpyEngine(learned))
        c.onKey(act(KeyAction.SWITCH_NINE))
        "426".forEach { c.onKey(out(it.toString())) }
        c.onPickCandidate(0)
        assertEquals(listOf("密码"), learned)
    }


    @Test fun backspace_steps_back_a_locked_reading_not_the_whole_syllable() {
        val c = KeyboardController(FakeHost(), engine)
        c.onKey(act(KeyAction.SWITCH_NINE))
        "426".forEach { c.onKey(out(it.toString())) }
        c.onKey(Key("hao", output = "hao", action = KeyAction.PICK_READING))
        c.onKey(act(KeyAction.BACKSPACE))
        assertTrue("pick undone → hao offered again, was ${c.expandedReadings()}", "hao" in c.expandedReadings())
        c.onKey(act(KeyAction.BACKSPACE))
        assertTrue("one letter removed → hao gone", "hao" !in c.expandedReadings())
        assertTrue("…but the 2-digit syllable remains", "ha" in c.expandedReadings())
    }

    @Test fun backspace_after_two_locks_steps_back_each_pick_then_keeps_digits() {
        val h = FakeHost()
        val c = KeyboardController(h, engine)
        c.onKey(act(KeyAction.SWITCH_NINE))
        "42633".forEach { c.onKey(out(it.toString())) }
        c.onKey(Key("hao", output = "hao", action = KeyAction.PICK_READING))
        c.onKey(Key("de", output = "de", action = KeyAction.PICK_READING))
        c.onKey(act(KeyAction.BACKSPACE))
        assertTrue("de offered again", "de" in c.expandedReadings())
        c.onKey(act(KeyAction.BACKSPACE))
        assertTrue("hao offered again", "hao" in c.expandedReadings())
        c.onKey(act(KeyAction.ENTER))
        assertEquals(listOf("haode"), h.commits)
    }

    @Test fun backspace_without_a_pick_deletes_one_letter_only() {
        val c = KeyboardController(FakeHost(), engine)
        c.onKey(act(KeyAction.SWITCH_NINE))
        "426".forEach { c.onKey(out(it.toString())) }
        c.onKey(act(KeyAction.BACKSPACE))
        assertTrue("hao gone (one letter removed)", "hao" !in c.expandedReadings())
        assertTrue("ha still present", "ha" in c.expandedReadings())
    }


    @Test fun reset_opens_cn_on_the_chosen_default_keyboard() {
        val c = KeyboardController(FakeHost(), engine)
        c.reset()
        assertEquals("CN defaults to 9-key (B5)", LayoutId.NINE, c.activeLayoutId())
        c.setCnDefaultLayout(LayoutId.ALPHA)
        c.reset()
        assertEquals("CN honours the 26-key choice (B5)", LayoutId.ALPHA, c.activeLayoutId())
    }

    @Test fun reset_keeps_en_on_26_key_even_with_a_nine_default() {
        val c = KeyboardController(FakeHost(), engine)
        c.onKey(act(KeyAction.TOGGLE_LANG))
        c.setCnDefaultLayout(LayoutId.NINE)
        c.reset()
        assertEquals("EN is always 26-key", LayoutId.ALPHA, c.activeLayoutId())
    }

    @Test fun lang_round_trip_returns_to_the_cn_default_keyboard() {
        val c = KeyboardController(FakeHost(), engine)
        c.reset()
        assertEquals(LayoutId.NINE, c.activeLayoutId())
        c.onKey(act(KeyAction.TOGGLE_LANG))
        assertEquals(LayoutId.ALPHA, c.activeLayoutId())
        c.onKey(act(KeyAction.TOGGLE_LANG))
        assertEquals(LayoutId.NINE, c.activeLayoutId())
    }

    @Test fun lang_round_trip_preserves_a_manual_cn_26_key_choice() {
        val c = KeyboardController(FakeHost(), engine)
        c.reset()
        c.onKey(act(KeyAction.SWITCH_ALPHA))
        assertEquals(LayoutId.ALPHA, c.activeLayoutId())
        c.onKey(act(KeyAction.TOGGLE_LANG))
        c.onKey(act(KeyAction.TOGGLE_LANG))
        assertEquals(LayoutId.ALPHA, c.activeLayoutId())
    }


    @Test fun nine_key_default_user_can_return_from_the_numpad() {
        val c = KeyboardController(FakeHost(), engine)
        c.reset()
        assertEquals(LayoutId.NINE, c.activeLayoutId())
        c.onKey(act(KeyAction.SWITCH_NUMPAD))
        assertEquals(LayoutId.NUMPAD, c.activeLayoutId())
        c.onKey(act(KeyAction.SWITCH_TEXT))
        assertEquals("返回 lands back on the 9-key default, not 26-key (H-1)", LayoutId.NINE, c.activeLayoutId())
    }

    @Test fun nine_key_default_user_can_return_from_the_symbol_page() {
        val c = KeyboardController(FakeHost(), engine)
        c.reset()
        c.onKey(act(KeyAction.SWITCH_SYMBOLS))
        assertEquals(LayoutId.SYMBOL, c.activeLayoutId())
        c.onKey(act(KeyAction.SWITCH_TEXT))
        assertEquals(LayoutId.NINE, c.activeLayoutId())
    }

    @Test fun en_user_returns_from_the_number_page_to_26_key() {
        val c = KeyboardController(FakeHost(), engine)
        c.reset()
        c.onKey(act(KeyAction.TOGGLE_LANG))
        c.onKey(act(KeyAction.SWITCH_NUMBERS))
        c.onKey(act(KeyAction.SWITCH_TEXT))
        assertEquals(LayoutId.ALPHA, c.activeLayoutId())
    }


    @Test fun b2_up_swipe_symbol_commits_directly_even_mid_pinyin() {
        val h = FakeHost()
        val c = KeyboardController(h, engine)
        c.onKey(Key("n", output = "n"))
        c.onKey(Key("@", output = "@", direct = true))
        assertEquals(listOf("n", "@"), h.commits)
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
