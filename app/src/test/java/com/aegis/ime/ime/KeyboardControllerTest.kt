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
import com.aegis.ime.layout.Lang
import com.aegis.ime.layout.LayoutId
import com.aegis.ime.layout.Layouts
import com.aegis.ime.layout.SymbolCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardControllerTest {

    private class FakeHost : ImeHost {
        val commits = mutableListOf<String>()
        val text = StringBuilder()
        var enters = 0
        var deletes = 0
        override fun commitText(text: CharSequence) { commits.add(text.toString()); this.text.append(text) }
        override fun deleteBackward() {
            deletes++
            if (text.isNotEmpty()) text.delete(text.length - 1, text.length)
        }
        override fun performEnter() { enters++ }
        override fun textBeforeCursor(n: Int): CharSequence = text.substring(maxOf(0, text.length - n))
    }

    private class SymbolPairingHost : ImeHost {
        val commits = mutableListOf<String>()
        val text = StringBuilder()
        override fun commitText(text: CharSequence) {
            commits.add(text.toString())
            this.text.append(text)
        }
        override fun commitSymbol(symbol: CharSequence) {
            for (part in SymbolCatalog.insertionFor(symbol.toString(), hasTextAfterCursor = false)) {
                commitText(part)
            }
        }
        override fun deleteBackward() {
            if (text.isNotEmpty()) text.delete(text.length - 1, text.length)
        }
        override fun performEnter() {}
    }

    private val engine = object : CandidateEngine {
        override fun candidates(composing: String, t9: Boolean): List<String> = emptyList()
    }

    private fun act(a: KeyAction) = Key("", action = a)
    private fun out(s: String) = Key(s, output = s)
    private fun swipe(s: String) = Key(s, output = s, direct = true, preeditLiteral = true)
    private fun clearCandidateUndo(c: KeyboardController) {
        c.onKey(out("2"))
        c.onKey(act(KeyAction.BACKSPACE))
    }

    @Test fun nine_enter_commits_raw_pinyin_not_digits() {
        val h = FakeHost()
        val c = KeyboardController(h, engine)
        c.switchTextLayoutForTest(nine = true)
        "6433".forEach { c.onKey(out(it.toString())) }
        c.onKey(act(KeyAction.ENTER))
        assertEquals(listOf("nide"), h.commits)
    }

    @Test fun clear_composing_drops_buffer_without_committing() {
        val h = FakeHost()
        val c = KeyboardController(h, engine)
        c.switchTextLayoutForTest(nine = true)
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
        c.switchTextLayoutForTest(nine = true)
        "6433".forEach { c.onKey(out(it.toString())) }
        c.onKey(Key("ni", output = "ni", action = KeyAction.PICK_READING))
        c.onKey(act(KeyAction.ENTER))
        assertEquals(listOf("nide"), h.commits)
    }

    @Test fun left_column_advances_to_the_second_syllable_then_enter_commits_both() {
        val h = FakeHost()
        val c = KeyboardController(h, engine)
        c.switchTextLayoutForTest(nine = true)
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
        c.switchTextLayoutForTest(nine = true)
        "64426".forEach { c.onKey(out(it.toString())) }
        c.onPickCandidate(0)
        assertTrue("a partial pick must NOT commit to the editor", h.commits.isEmpty())
        assertEquals("你 is the assembled prefix", "你", c.composingPrefix())
        assertEquals("the prefix renders at the strip's leftmost", "你hao", c.preeditForTest())
        c.onKey(act(KeyAction.ENTER))
        assertEquals(listOf("你hao"), h.commits)
    }

    @Test fun backspace_commits_the_assembled_prefix_when_the_remaining_reading_is_deleted() {
        val h = FakeHost()
        val partial = object : CandidateEngine {
            override fun candidates(composing: String, t9: Boolean) = candidatesCovered(composing, t9).map { it.word }
            override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>, context: CharSequence): List<Cand> =
                if (composing.isEmpty()) emptyList() else listOf(Cand("你", 2))
        }
        val c = KeyboardController(h, partial)
        c.switchTextLayoutForTest(nine = true)
        "64426".forEach { c.onKey(out(it.toString())) }
        c.onPickCandidate(0)
        assertEquals("你", c.composingPrefix())
        clearCandidateUndo(c)
        repeat(3) { c.onKey(act(KeyAction.BACKSPACE)) }
        assertEquals("prefix committed when no reading remains", listOf("你"), h.commits)
        assertEquals("preedit cleared after committing the prefix", "", c.preeditForTest())
        assertEquals("prefix no longer stranded inside the IME", "", c.composingPrefix())
        assertEquals("clearing reading did not delete editor text", 0, h.deletes)
    }

    @Test fun backspace_commits_a_supplementary_prefix_when_the_remaining_reading_is_deleted() {
        val h = FakeHost()
        val supplementaryHan = String(Character.toChars(0x20000))
        assertEquals("test character must occupy a surrogate pair", 2, supplementaryHan.length)
        val partial = object : CandidateEngine {
            override fun candidates(composing: String, t9: Boolean) = candidatesCovered(composing, t9).map { it.word }
            override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>, context: CharSequence): List<Cand> =
                if (composing.isEmpty()) emptyList() else listOf(Cand(supplementaryHan, 2))
        }
        val c = KeyboardController(h, partial)
        c.switchTextLayoutForTest(nine = true)
        "64426".forEach { c.onKey(out(it.toString())) }
        c.onPickCandidate(0)
        assertEquals(supplementaryHan, c.composingPrefix())
        clearCandidateUndo(c)
        repeat(3) { c.onKey(act(KeyAction.BACKSPACE)) }

        assertEquals("supplementary prefix committed as one string", listOf(supplementaryHan), h.commits)
        assertEquals("preedit cleared after committing the prefix", "", c.preeditForTest())
        assertEquals("prefix no longer stranded inside the IME", "", c.composingPrefix())
        assertEquals("clearing reading did not delete editor text", 0, h.deletes)
    }

    @Test fun space_after_deleting_the_remaining_reading_follows_the_committed_prefix() {
        val h = FakeHost()
        val partial = object : CandidateEngine {
            override fun candidates(composing: String, t9: Boolean) = candidatesCovered(composing, t9).map { it.word }
            override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>, context: CharSequence): List<Cand> =
                if (composing.isEmpty()) emptyList() else listOf(Cand("你", 2))
        }
        val c = KeyboardController(h, partial)
        c.switchTextLayoutForTest(nine = true)
        "64426".forEach { c.onKey(out(it.toString())) }
        c.onPickCandidate(0)
        clearCandidateUndo(c)
        repeat(3) { c.onKey(act(KeyAction.BACKSPACE)) }
        c.onKey(act(KeyAction.SPACE))
        assertEquals(listOf("你", " "), h.commits)
    }

    @Test fun field_switch_drops_an_assembled_prefix_no_cross_field_leak() {
        val h = FakeHost()
        val partial = object : CandidateEngine {
            override fun candidates(composing: String, t9: Boolean) = candidatesCovered(composing, t9).map { it.word }
            override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>, context: CharSequence): List<Cand> =
                if (composing.isEmpty()) emptyList() else listOf(Cand("你", 2))
        }
        val c = KeyboardController(h, partial)
        c.switchTextLayoutForTest(nine = true)
        "64426".forEach { c.onKey(out(it.toString())) }
        c.onPickCandidate(0)
        assertEquals("你", c.composingPrefix())
        assertTrue("partial pick committed nothing", h.commits.isEmpty())

        c.reset()
        assertEquals("the pending prefix is dropped on field switch", "", c.composingPrefix())

        c.switchTextLayoutForTest(nine = true)
        "64426".forEach { c.onKey(out(it.toString())) }
        c.onKey(act(KeyAction.ENTER))
        assertEquals("no leaked 你 in the new field", listOf("nihao"), h.commits)
    }

    @Test fun direct_key_after_deleting_the_remaining_reading_follows_the_committed_prefix() {
        val h = FakeHost()
        val partial = object : CandidateEngine {
            override fun candidates(composing: String, t9: Boolean) = candidatesCovered(composing, t9).map { it.word }
            override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>, context: CharSequence): List<Cand> =
                if (composing.isEmpty()) emptyList() else listOf(Cand("你", 2))
        }
        val c = KeyboardController(h, partial)
        c.switchTextLayoutForTest(nine = true)
        "64426".forEach { c.onKey(out(it.toString())) }
        c.onPickCandidate(0)
        clearCandidateUndo(c)
        repeat(3) { c.onKey(act(KeyAction.BACKSPACE)) }
        c.onKey(Key("，", output = "，", direct = true))
        assertEquals(listOf("你", "，"), h.commits)
    }

    @Test fun segment_forces_a_syllable_boundary() {
        val h = FakeHost()
        val c = KeyboardController(h, engine)
        c.switchTextLayoutForTest(nine = true)
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
        c.switchTextLayoutForTest(nine = true)
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
        c.switchTextLayoutForTest(nine = true)
        "64".forEach { c.onKey(out(it.toString())) }
        c.onKey(Key("，", output = "，", direct = true))
        assertEquals(listOf("ni", "，"), h.commits)
    }

    @Test fun direct_pairable_symbol_flushes_pinyin_then_commits_plain_text() {
        val h = SymbolPairingHost()
        val c = KeyboardController(h, engine)
        c.onKey(out("n"))
        c.onKey(Key("\"", output = "\"", direct = true))
        assertEquals(listOf("n", "\""), h.commits)
        assertEquals("n\"", h.text.toString())
    }

    @Test fun direct_pairable_symbols_from_keyboard_commit_plain_text() {
        val h = SymbolPairingHost()
        val c = KeyboardController(h, engine)
        for (symbol in listOf("\"", "[", "(", "`")) {
            c.onKey(Key(symbol, output = symbol, direct = true))
        }
        assertEquals(listOf("\"", "[", "(", "`"), h.commits)
        assertEquals("\"[(`", h.text.toString())
    }

    @Test fun direct_mode_pairable_symbols_commit_plain_text() {
        val h = SymbolPairingHost()
        val c = KeyboardController(h, engine)
        c.onKey(act(KeyAction.TOGGLE_LANG))
        for (symbol in listOf("'", "\"", "[")) {
            c.onKey(Key(symbol, output = symbol))
        }
        assertEquals(listOf("'", "\"", "["), h.commits)
        assertEquals("'\"[", h.text.toString())
    }

    @Test fun shift_is_inert_in_cn_full_pinyin_26_key() {
        val h = FakeHost()
        val c = KeyboardController(h, engine)
        c.switchTextLayoutForTest(nine = false)
        c.onKey(act(KeyAction.SHIFT))
        assertEquals("shift stays OFF in CN pinyin", "OFF", c.shiftStateName())
        c.onKey(act(KeyAction.SHIFT_LOCK))
        assertEquals("double-tap lock is inert in CN pinyin too", "OFF", c.shiftStateName())
    }

    @Test fun shift_lock_resets_on_layout_switch_and_on_lang_toggle() {
        val h = FakeHost()
        val c = KeyboardController(h, engine)
        c.onKey(act(KeyAction.TOGGLE_LANG)); c.onKey(act(KeyAction.SHIFT_LOCK))
        assertEquals("LOCK", c.shiftStateName())
        c.onKey(act(KeyAction.SWITCH_NUMBERS))
        assertEquals("OFF", c.shiftStateName())
        c.switchTextLayoutForTest(nine = false); c.onKey(act(KeyAction.SHIFT_LOCK))
        assertEquals("LOCK", c.shiftStateName())
        c.onKey(act(KeyAction.TOGGLE_LANG))
        assertEquals("OFF", c.shiftStateName())
    }

    @Test fun one_shot_shift_survives_the_case_box_symbol_cell_but_not_its_letter_cells() {
        val h = FakeHost()
        val c = KeyboardController(h, engine)
        c.onKey(act(KeyAction.TOGGLE_LANG))
        c.onKey(act(KeyAction.SHIFT))
        c.onKey(Key("%", output = "%", direct = true, verbatim = true))
        assertEquals("the symbol cell must not consume the pending shift", "ONCE", c.shiftStateName())
        c.onKey(Key("g", output = "g", direct = true, verbatim = true))
        assertEquals("an explicit case choice counts as the next letter", "OFF", c.shiftStateName())
        assertEquals(listOf("%", "g"), h.commits)
    }


    private fun nineColumnFor(digits: String): List<Key> {
        val c = KeyboardController(FakeHost(), engine)
        c.switchTextLayoutForTest(nine = true)
        digits.forEach { c.onKey(out(it.toString())) }
        return c.nineLeftColumn()
    }

    @Test fun nine_left_column_shows_only_real_readings_no_blanks_no_punct() {
        val col = nineColumnFor("23744").filter { it.action == KeyAction.PICK_READING }
        assertEquals(listOf("ce", "a", "b", "c"), col.map { it.label })
        assertTrue("no blank keys", col.none { it.label.isEmpty() })
        assertTrue("no punctuation", col.all { k -> k.label.all { it in 'a'..'z' } })
        assertTrue("all are pick-reading actions", col.all { it.action == KeyAction.PICK_READING })
    }

    @Test fun nine_left_column_is_punctuation_only_when_idle() {
        val c = KeyboardController(FakeHost(), engine)
        c.switchTextLayoutForTest(nine = true)
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
        c.switchTextLayoutForTest(nine = true)
        c.onKey(Key("自定义", action = KeyAction.CUSTOM_SYMBOL))
        assertTrue("自定义 tap opens the custom-symbol panel", opened)
    }


    @Test fun numpad_operator_自定义_entry_opens_the_operator_panel() {
        var opened = false
        val c = KeyboardController(FakeHost(), engine).apply { onShowCustomOperators = { opened = true } }
        c.onKey(act(KeyAction.SWITCH_NUMPAD))
        c.onKey(Key("自定义", action = KeyAction.CUSTOM_OPERATOR))
        assertTrue("自定义 tap opens the custom-operator panel", opened)
    }

    @Test fun numpad_operator_commits_directly_to_the_editor() {
        val h = FakeHost()
        val c = KeyboardController(h, engine)
        c.onKey(act(KeyAction.SWITCH_NUMPAD))
        c.onKey(Key("×", direct = true))
        assertEquals(listOf("×"), h.commits)
    }

    @Test fun nine_left_column_ni_full_scroll_list_matches_reference() {
        val col = nineColumnFor("64744336488").filter { it.action == KeyAction.PICK_READING }.map { it.label }
        assertTrue("ni present, was $col", "ni" in col)
        assertTrue("mi present, was $col", "mi" in col)
        assertTrue("first-key letters m/n/o present, was $col", listOf("m", "n", "o").all { it in col })
        assertTrue("no blank keys, was $col", col.none { it.isEmpty() })
        assertTrue("clean a-z only, was $col", col.all { s -> s.all { it in 'a'..'z' } })
    }


    @Test fun expanded_readings_empty_at_rest_combos_while_composing() {
        val c = KeyboardController(FakeHost(), engine)
        c.switchTextLayoutForTest(nine = true)
        assertTrue("no combos at rest", c.expandedReadings().isEmpty())
        "426".forEach { c.onKey(out(it.toString())) }
        assertTrue("hao among combos while composing, was ${c.expandedReadings()}", "hao" in c.expandedReadings())
    }

    @Test fun no_ghost_suggestion_after_commit() {
        val full = object : CandidateEngine {
            override fun candidates(composing: String, t9: Boolean) = candidatesCovered(composing, t9).map { it.word }
            override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>, context: CharSequence) =
                if (composing.isEmpty()) emptyList() else listOf(Cand("你好", composing.length))
        }
        val c = KeyboardController(FakeHost(), full)
        c.switchTextLayoutForTest(nine = true)
        "426".forEach { c.onKey(out(it.toString())) }
        c.onPickCandidate(0)
        assertTrue("no candidates linger after commit (no ghost)", c.candidateWords().isEmpty())
    }

    @Test fun alpha_composing_offers_no_raw_pinyin_echo_candidate() {
        val full = object : CandidateEngine {
            override fun candidates(composing: String, t9: Boolean) = candidatesCovered(composing, t9).map { it.word }
            override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>, context: CharSequence): List<Cand> =
                if (composing.isEmpty()) emptyList() else listOf(Cand("你好", composing.length))
        }
        val c = KeyboardController(FakeHost(), full)
        c.switchTextLayoutForTest(nine = false)
        "nihao".forEach { c.onKey(out(it.toString())) }
        assertEquals("the typed string is not echoed as a candidate", listOf("你好"), c.candidateWords())

        val h = FakeHost()
        val bare = KeyboardController(h, engine)
        bare.switchTextLayoutForTest(nine = false)
        "nihao".forEach { bare.onKey(out(it.toString())) }
        assertTrue("an empty decode shows no raw echo either", bare.candidateWords().isEmpty())
        bare.onKey(act(KeyAction.ENTER))
        assertEquals("Enter still commits the raw letters", listOf("nihao"), h.commits)
    }

    @Test fun backspace_after_a_full_candidate_pick_deletes_editor_text_without_restoring_preedit() {
        val h = FakeHost()
        val full = object : CandidateEngine {
            override fun candidates(composing: String, t9: Boolean) = candidatesCovered(composing, t9).map { it.word }
            override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>, context: CharSequence): List<Cand> =
                if (composing.isEmpty()) emptyList() else listOf(Cand("你好", composing.length))
        }
        val c = KeyboardController(h, full)
        c.switchTextLayoutForTest(nine = true)
        "64426".forEach { c.onKey(out(it.toString())) }
        c.onPickCandidate(0)
        assertEquals(listOf("你好"), h.commits)
        assertEquals("你好", h.text.toString())
        assertEquals("", c.preeditForTest())

        c.onKey(act(KeyAction.BACKSPACE))

        assertEquals("Backspace deletes one committed editor character", "你", h.text.toString())
        assertEquals("full editor commits must not restore preedit", "", c.preeditForTest())
        assertTrue("full editor commits must not restore candidate grid", c.candidateWords().isEmpty())
        assertEquals("full editor commits use normal raw deleteBackward", 1, h.deletes)
    }

    @Test fun backspace_after_new_composing_input_keeps_committed_candidate_text() {
        val h = FakeHost()
        val full = object : CandidateEngine {
            override fun candidates(composing: String, t9: Boolean) = candidatesCovered(composing, t9).map { it.word }
            override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>, context: CharSequence): List<Cand> =
                if (composing.isEmpty()) emptyList() else listOf(Cand("你好", composing.length))
        }
        val c = KeyboardController(h, full)
        c.switchTextLayoutForTest(nine = true)
        "64426".forEach { c.onKey(out(it.toString())) }
        c.onPickCandidate(0)
        assertEquals("你好", h.text.toString())

        c.onKey(out("6"))
        c.onKey(act(KeyAction.BACKSPACE))

        assertEquals("backspace must keep the already committed candidate", "你好", h.text.toString())
        assertEquals("backspace removes only the new composing input", "", c.preeditForTest())
        assertEquals(listOf("你好"), h.commits)
    }


    @Test fun backspace_steps_back_a_locked_reading_not_the_whole_syllable() {
        val c = KeyboardController(FakeHost(), engine)
        c.switchTextLayoutForTest(nine = true)
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
        c.switchTextLayoutForTest(nine = true)
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
        c.switchTextLayoutForTest(nine = true)
        "426".forEach { c.onKey(out(it.toString())) }
        c.onKey(act(KeyAction.BACKSPACE))
        assertTrue("hao gone (one letter removed)", "hao" !in c.expandedReadings())
        assertTrue("ha still present", "ha" in c.expandedReadings())
    }

    @Test fun lang_round_trip_preserves_a_manual_cn_26_key_choice() {
        val c = KeyboardController(FakeHost(), engine)
        c.reset()
        c.switchTextLayoutForTest(nine = false)
        assertEquals(LayoutId.ALPHA, c.activeLayoutId())
        c.onKey(act(KeyAction.TOGGLE_LANG))
        c.onKey(act(KeyAction.TOGGLE_LANG))
        assertEquals(LayoutId.ALPHA, c.activeLayoutId())
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

    @Test fun nine_page_key_opens_number_then_symbol_and_returns_to_nine() {
        val c = KeyboardController(FakeHost(), engine)
        c.reset()
        val pageKey = Layouts.forId(LayoutId.NINE, Lang.CN).cells!!.first { it.key.label == "@#" }.key
        c.onKey(pageKey)
        assertEquals(LayoutId.NUMBER, c.activeLayoutId())
        val symbols = Layouts.forId(LayoutId.NUMBER, Lang.CN).rows.flatMap { it.keys }
            .first { it.action == KeyAction.SWITCH_SYMBOLS }
        c.onKey(symbols)
        assertEquals(LayoutId.SYMBOL, c.activeLayoutId())
        val text = Layouts.forId(LayoutId.SYMBOL, Lang.CN).rows.flatMap { it.keys }
            .first { it.action == KeyAction.SWITCH_TEXT }
        c.onKey(text)
        assertEquals(LayoutId.NINE, c.activeLayoutId())
    }

    @Test fun qwerty_123_opens_the_nine_key_numpad_and_returns_to_qwerty() {
        val c = KeyboardController(FakeHost(), engine)
        c.reset()
        c.switchTextLayoutForTest(nine = false)
        val alphabet123 = Layouts.forId(LayoutId.ALPHA, Lang.CN).cells!!.first { it.key.label == "123" }.key
        c.onKey(alphabet123)
        assertEquals(LayoutId.NUMPAD, c.activeLayoutId())
        val text = Layouts.forId(LayoutId.NUMPAD, Lang.CN).cells!!.first { it.key.action == KeyAction.SWITCH_TEXT }.key
        c.onKey(text)
        assertEquals(LayoutId.ALPHA, c.activeLayoutId())
    }

    @Test fun alpha_up_swipe_digit_still_commits_directly_when_preedit_is_idle() {
        val h = FakeHost()
        val c = KeyboardController(h, engine)
        c.onKey(swipe("1"))
        assertEquals(listOf("1"), h.commits)
        assertEquals("", c.preeditForTest())
    }

    @Test fun alpha_preedit_automatically_displays_syllable_boundaries() {
        val c = KeyboardController(FakeHost(), engine)
        "nihao".forEach { c.onKey(out(it.toString())) }

        assertEquals("ni'hao", c.preeditForTest())
    }

    @Test fun alpha_segment_key_forces_and_backspace_undoes_the_boundary() {
        val c = KeyboardController(FakeHost(), engine)
        "xi".forEach { c.onKey(out(it.toString())) }
        c.onKey(act(KeyAction.SEGMENT))
        "an".forEach { c.onKey(out(it.toString())) }

        assertEquals("xi'an", c.preeditForTest())
        c.onKey(act(KeyAction.BACKSPACE))
        c.onKey(act(KeyAction.BACKSPACE))
        assertEquals("xi'", c.preeditForTest())
        c.onKey(act(KeyAction.BACKSPACE))
        assertEquals("xi", c.preeditForTest())
    }
}
