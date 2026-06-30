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

/** Behaviour for the correction cases — no view attached, so render() is a no-op. */
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
        override fun replaceBeforeCursor(length: Int, text: CharSequence) {
            val start = maxOf(0, this.text.length - length)
            this.text.delete(start, this.text.length)
            this.text.append(text)
        }
    }

    private val engine = object : CandidateEngine {
        override fun candidates(composing: String, t9: Boolean): List<String> = emptyList()
    }

    private fun act(a: KeyAction) = Key("", action = a)
    private fun out(s: String) = Key(s, output = s)
    private fun clearCandidateUndo(c: KeyboardController) {
        c.onKey(out("2"))
        c.onKey(act(KeyAction.BACKSPACE))
    }

    /** E4 hot-toggle (debug.16): a fake engine that records the fuzzy rule set pushed to it. */
    private class FuzzyRecordingEngine : CandidateEngine {
        var rules: Set<String>? = null
        override fun candidates(composing: String, t9: Boolean): List<String> = emptyList()
        override fun setFuzzyRules(rules: Set<String>) { this.rules = rules }
    }

    @Test fun setEngine_reapplies_last_pushed_fuzzy_rules_across_a_hot_reload_swap() {
        // The lost-update guard: fuzzy rules live inside the engine, so a hot-reload swap must NOT revert them.
        val c = KeyboardController(FakeHost(), FuzzyRecordingEngine())
        c.setFuzzyRules(setOf("zh")) // service pushes the user's 模糊音 choice (mirrors onStartInputView)
        val swapped = FuzzyRecordingEngine() // a freshly hot-reloaded engine that carries no rules of its own
        c.setEngine(swapped)
        assertEquals("engine swap must preserve the live fuzzy rules", setOf("zh"), swapped.rules)
    }

    @Test fun setEngine_does_not_force_fuzzy_rules_before_the_service_has_pushed_any() {
        // Before any push, a swap must keep the new engine's OWN build-time rules (don't stomp with a default).
        val c = KeyboardController(FakeHost(), engine)
        val swapped = FuzzyRecordingEngine()
        c.setEngine(swapped)
        assertEquals("no push yet → swap must not override build-time rules", null, swapped.rules)
    }

    @Test fun nine_enter_commits_raw_pinyin_not_digits() {
        val h = FakeHost()
        val c = KeyboardController(h, engine)
        c.onKey(act(KeyAction.SWITCH_NINE))
        "6433".forEach { c.onKey(out(it.toString())) } // n-i-d-e
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
        c.onKey(act(KeyAction.ENTER)) // buffer empty → editor action, not a commit
        assertEquals(1, h.enters)
        assertTrue(h.commits.isEmpty())
    }

    @Test fun picking_a_reading_then_enter_commits_the_full_pinyin() {
        val h = FakeHost()
        val c = KeyboardController(h, engine)
        c.onKey(act(KeyAction.SWITCH_NINE))
        "6433".forEach { c.onKey(out(it.toString())) } // ni'de
        c.onKey(Key("ni", output = "ni", action = KeyAction.PICK_READING))
        c.onKey(act(KeyAction.ENTER))
        // #12b: locking "ni" must keep "de" — commit "nide", not "ni".
        assertEquals(listOf("nide"), h.commits)
    }

    @Test fun left_column_advances_to_the_second_syllable_then_enter_commits_both() {
        // ★E: the actual bug — after locking syllable 1 you must be able to pick syllable 2.
        val h = FakeHost()
        val c = KeyboardController(h, engine)
        c.onKey(act(KeyAction.SWITCH_NINE))
        "42633".forEach { c.onKey(out(it.toString())) } // hao(426) + de(33)
        c.onKey(Key("hao", output = "hao", action = KeyAction.PICK_READING)) // lock syllable 1
        c.onKey(Key("de", output = "de", action = KeyAction.PICK_READING))   // syllable 2 now selectable
        c.onKey(act(KeyAction.ENTER))
        assertEquals(listOf("haode"), h.commits)
    }

    @Test fun picking_a_partial_candidate_builds_a_prefix_and_defers_the_commit() {
        // S1(c) (debug.12): a candidate whose reading covers only part of the buffer must NOT dribble into
        // the editor ("选一个就上屏一个"). It joins the assembled prefix (shown at the strip's leftmost) and
        // decoding continues; the whole word lands in ONE commit only when it completes (here: ENTER flush).
        val h = FakeHost()
        val partial = object : CandidateEngine {
            override fun candidates(composing: String, t9: Boolean) = candidatesCovered(composing, t9).map { it.word }
            override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>, context: CharSequence): List<Cand> =
                if (composing.isEmpty()) emptyList() else listOf(Cand("你", 2)) // 你 covers the first 2 digits "64"
        }
        val c = KeyboardController(h, partial)
        c.onKey(act(KeyAction.SWITCH_NINE))
        "64426".forEach { c.onKey(out(it.toString())) } // ni(64) hao(426)
        c.onPickCandidate(0) // pick 你 → builds prefix "你", drops "64", keeps "426" — NOTHING committed yet
        assertTrue("a partial pick must NOT commit to the editor", h.commits.isEmpty())
        assertEquals("你 is the assembled prefix", "你", c.composingPrefix())
        assertEquals("the prefix renders at the strip's leftmost", "你hao", c.preeditForTest())
        c.onKey(act(KeyAction.ENTER)) // complete + flush → ONE commit
        assertEquals(listOf("你hao"), h.commits)
    }

    @Test fun backspace_peels_the_assembled_prefix_before_touching_the_editor() {
        // S1(c): after a partial pick the confirmed prefix lives in the IME, not the editor — 退格 peels the
        // remainder digits then the prefix char, never calling deleteBackward on committed text.
        val h = FakeHost()
        val partial = object : CandidateEngine {
            override fun candidates(composing: String, t9: Boolean) = candidatesCovered(composing, t9).map { it.word }
            override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>, context: CharSequence): List<Cand> =
                if (composing.isEmpty()) emptyList() else listOf(Cand("你", 2))
        }
        val c = KeyboardController(h, partial)
        c.onKey(act(KeyAction.SWITCH_NINE))
        "64426".forEach { c.onKey(out(it.toString())) } // ni(64) hao(426)
        c.onPickCandidate(0) // prefix "你", remainder "426"
        assertEquals("你", c.composingPrefix())
        clearCandidateUndo(c)
        repeat(3) { c.onKey(act(KeyAction.BACKSPACE)) }   // peel 426 -> empty
        assertEquals("prefix intact while the remainder peels", "你", c.composingPrefix())
        c.onKey(act(KeyAction.BACKSPACE))                 // now peel the prefix char itself
        assertEquals("prefix peeled away", "", c.composingPrefix())
        assertEquals("never deleted committed editor text", 0, h.deletes)
        assertTrue("nothing was ever committed", h.commits.isEmpty())
    }

    @Test fun backspace_peels_a_supplementary_committed_prefix_as_one_code_point() {
        val h = FakeHost()
        val supplementaryHan = String(Character.toChars(0x20000))
        assertEquals("test character must occupy a surrogate pair", 2, supplementaryHan.length)
        val partial = object : CandidateEngine {
            override fun candidates(composing: String, t9: Boolean) = candidatesCovered(composing, t9).map { it.word }
            override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>, context: CharSequence): List<Cand> =
                if (composing.isEmpty()) emptyList() else listOf(Cand(supplementaryHan, 2))
        }
        val c = KeyboardController(h, partial)
        c.onKey(act(KeyAction.SWITCH_NINE))
        "64426".forEach { c.onKey(out(it.toString())) }
        c.onPickCandidate(0)
        assertEquals(supplementaryHan, c.composingPrefix())
        clearCandidateUndo(c)
        repeat(3) { c.onKey(act(KeyAction.BACKSPACE)) }

        c.onKey(act(KeyAction.BACKSPACE))

        assertEquals("one backspace must remove the full supplementary code point", "", c.composingPrefix())
        assertEquals("never deleted committed editor text", 0, h.deletes)
        assertTrue("nothing was ever committed", h.commits.isEmpty())
    }

    @Test fun space_on_a_bare_assembled_prefix_commits_it_once_without_a_literal_space() {
        // S1(c): the remainder may be backspaced away leaving only the prefix — space commits that pending
        // word in ONE commit and is consumed (no stray " " inserted).
        val h = FakeHost()
        val partial = object : CandidateEngine {
            override fun candidates(composing: String, t9: Boolean) = candidatesCovered(composing, t9).map { it.word }
            override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>, context: CharSequence): List<Cand> =
                if (composing.isEmpty()) emptyList() else listOf(Cand("你", 2))
        }
        val c = KeyboardController(h, partial)
        c.onKey(act(KeyAction.SWITCH_NINE))
        "64426".forEach { c.onKey(out(it.toString())) }
        c.onPickCandidate(0)                              // prefix "你", remainder "426"
        clearCandidateUndo(c)
        repeat(3) { c.onKey(act(KeyAction.BACKSPACE)) }   // remainder gone, only the prefix remains
        c.onKey(act(KeyAction.SPACE))
        assertEquals(listOf("你"), h.commits)
    }

    @Test fun field_switch_drops_an_assembled_prefix_no_cross_field_leak() {
        // D1 (debug.12, blocker): reset() (onStartInputView / rotation) must DROP a pending prefix so it
        // cannot leak into the next field. Build "你", reset → the prefix is gone and a fresh commit in the
        // new field never carries it.
        val h = FakeHost()
        val partial = object : CandidateEngine {
            override fun candidates(composing: String, t9: Boolean) = candidatesCovered(composing, t9).map { it.word }
            override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>, context: CharSequence): List<Cand> =
                if (composing.isEmpty()) emptyList() else listOf(Cand("你", 2))
        }
        val c = KeyboardController(h, partial)
        c.onKey(act(KeyAction.SWITCH_NINE))
        "64426".forEach { c.onKey(out(it.toString())) }
        c.onPickCandidate(0)                              // prefix "你" pending, nothing committed
        assertEquals("你", c.composingPrefix())
        assertTrue("partial pick committed nothing", h.commits.isEmpty())

        c.reset()                                         // simulate onStartInputView (new field) / rotation
        assertEquals("the pending prefix is dropped on field switch", "", c.composingPrefix())

        // In the "new field": flush a fresh buffer — the dropped 你 must NOT reappear prepended.
        c.onKey(act(KeyAction.SWITCH_NINE))
        "64426".forEach { c.onKey(out(it.toString())) }
        c.onKey(act(KeyAction.ENTER))
        assertEquals("no leaked 你 in the new field", listOf("nihao"), h.commits)
    }

    @Test fun direct_key_on_a_bare_prefix_flushes_the_word_first_then_the_symbol() {
        // D2 (debug.12): a punctuation/number (direct) tapped when only the prefix remains must commit the
        // word THEN the symbol — "你" then "，", never "，你" and never a stranded prefix.
        val h = FakeHost()
        val partial = object : CandidateEngine {
            override fun candidates(composing: String, t9: Boolean) = candidatesCovered(composing, t9).map { it.word }
            override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>, context: CharSequence): List<Cand> =
                if (composing.isEmpty()) emptyList() else listOf(Cand("你", 2))
        }
        val c = KeyboardController(h, partial)
        c.onKey(act(KeyAction.SWITCH_NINE))
        "64426".forEach { c.onKey(out(it.toString())) }
        c.onPickCandidate(0)                              // prefix "你", remainder "426"
        clearCandidateUndo(c)
        repeat(3) { c.onKey(act(KeyAction.BACKSPACE)) }   // remainder gone, only prefix "你" remains
        c.onKey(Key("，", output = "，", direct = true))   // idle-column punctuation (direct)
        assertEquals(listOf("你", "，"), h.commits)
    }

    @Test fun segment_forces_a_syllable_boundary() {
        // 9426 decodes as ONE syllable (xi.., x-initial) by default; a forced cut after "94" makes the
        // first chunk decode on its own as "yi" (rank-2), so the commit starts "yi" not "xi".
        val h = FakeHost()
        val c = KeyboardController(h, engine)
        c.onKey(act(KeyAction.SWITCH_NINE))
        "94".forEach { c.onKey(out(it.toString())) }
        c.onKey(act(KeyAction.SEGMENT)) // force a boundary here
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
        c.onKey(act(KeyAction.SEGMENT))   // cut after "94"
        c.onKey(act(KeyAction.BACKSPACE)) // undoes the cut, keeps the digits
        "26".forEach { c.onKey(out(it.toString())) }
        c.onKey(act(KeyAction.ENTER))
        assertTrue("cut gone -> single syllable xi.., was ${h.commits[0]}", h.commits[0].startsWith("xi"))
    }

    @Test fun direct_punctuation_flushes_pinyin_then_commits_directly() {
        val h = FakeHost()
        val c = KeyboardController(h, engine)
        c.onKey(act(KeyAction.SWITCH_NINE))
        "64".forEach { c.onKey(out(it.toString())) } // ni
        c.onKey(Key("，", output = "，", direct = true)) // ★D: punctuation never buffers as pinyin
        assertEquals(listOf("ni", "，"), h.commits)
    }

    // ---- I4: shift one-shot (single tap) vs caps lock (double tap → SHIFT_LOCK), reset on switch ----

    @Test fun single_shift_tap_is_one_shot_uppercase_then_back_to_lowercase() {
        val h = FakeHost()
        val c = KeyboardController(h, engine)
        c.onKey(act(KeyAction.TOGGLE_LANG)) // CN → EN (letters commit directly, case applies)
        c.onKey(act(KeyAction.SHIFT))       // one-shot
        assertEquals("ONCE", c.shiftStateName())
        c.onKey(out("a"))                   // first letter uppercased…
        c.onKey(out("b"))                   // …then shift is spent, second stays lowercase
        assertEquals(listOf("A", "b"), h.commits)
        assertEquals("OFF", c.shiftStateName())
    }

    @Test fun double_tap_shift_lock_keeps_uppercasing_until_toggled() {
        val h = FakeHost()
        val c = KeyboardController(h, engine)
        c.onKey(act(KeyAction.TOGGLE_LANG))   // EN
        c.onKey(act(KeyAction.SHIFT_LOCK))    // double tap → caps lock
        assertEquals("LOCK", c.shiftStateName())
        c.onKey(out("a")); c.onKey(out("b"))  // both uppercase, lock persists
        assertEquals(listOf("A", "B"), h.commits)
        assertEquals("LOCK", c.shiftStateName())
        c.onKey(act(KeyAction.SHIFT))         // a tap clears the lock
        assertEquals("OFF", c.shiftStateName())
    }

    @Test fun shift_is_inert_in_cn_full_pinyin_26_key() {
        // I4: ⇧ sits on the shared 26-key, but shift is meaningless for full-pinyin — tapping it in
        // CN 全拼26键 must NOT arm (which would stick the keycaps uppercase while the pinyin stays lowercase).
        val h = FakeHost()
        val c = KeyboardController(h, engine)
        c.onKey(act(KeyAction.SWITCH_ALPHA)) // CN 全拼26键 → mode = PINYIN
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
        c.onKey(act(KeyAction.SWITCH_NUMBERS)) // a layout switch clears it
        assertEquals("OFF", c.shiftStateName())
        c.onKey(act(KeyAction.SWITCH_ALPHA)); c.onKey(act(KeyAction.SHIFT_LOCK))
        assertEquals("LOCK", c.shiftStateName())
        c.onKey(act(KeyAction.TOGGLE_LANG))    // 中英 toggle clears it too
        assertEquals("OFF", c.shiftStateName())
    }

    @Test fun english_letters_commit_directly_not_buffered() {
        // D: EN letters go straight to the editor (no candidate bar). Toggling to EN also drops off the 9-key.
        val h = FakeHost()
        val c = KeyboardController(h, engine)
        c.onKey(act(KeyAction.SWITCH_NINE))
        c.onKey(act(KeyAction.TOGGLE_LANG)) // CN -> EN
        c.onKey(out("a"))                   // committed straight away, not buffered as "a "
        c.onKey(act(KeyAction.SPACE))
        assertEquals(listOf("a", " "), h.commits)
    }

    // ---- 9-key left column subsystem (no blanks / no punctuation / consistent) ----

    private fun nineColumnFor(digits: String): List<Key> {
        val c = KeyboardController(FakeHost(), engine)
        c.onKey(act(KeyAction.SWITCH_NINE))
        digits.forEach { c.onKey(out(it.toString())) }
        return c.nineLeftColumn()
    }

    @Test fun nine_left_column_shows_only_real_readings_no_blanks_no_punct() {
        val col = nineColumnFor("23744") // ce'shi
        assertEquals(listOf("ce", "a", "b", "c"), col.map { it.label })
        assertTrue("no blank keys", col.none { it.label.isEmpty() })
        assertTrue("no punctuation", col.all { k -> k.label.all { it in 'a'..'z' } })
        assertTrue("all are pick-reading actions", col.all { it.action == KeyAction.PICK_READING })
    }

    @Test fun nine_left_column_is_punctuation_only_when_idle() {
        val c = KeyboardController(FakeHost(), engine)
        c.onKey(act(KeyAction.SWITCH_NINE))
        assertEquals(
            com.aegis.ime.layout.Layouts.ninePunctuation().map { it.label }, // ，。？！…：；~.-@自定义
            c.nineLeftColumn().map { it.label },
        )
    }

    @Test fun nine_left_column_consistent_for_same_input() {
        // Guards the debug.8 A-vs-B "same input ce'shi, two different columns" inconsistency.
        assertEquals(nineColumnFor("23744").map { it.label }, nineColumnFor("23744").map { it.label })
    }

    @Test fun custom_symbol_key_opens_the_panel() {
        var opened = false
        val c = KeyboardController(FakeHost(), engine).apply { onShowCustomSymbols = { opened = true } }
        c.onKey(act(KeyAction.SWITCH_NINE))
        c.onKey(Key("自定义", action = KeyAction.CUSTOM_SYMBOL))
        assertTrue("自定义 tap opens the custom-symbol panel", opened)
    }

    // ---- I2: numpad operator column ----

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
        c.onKey(Key("×", direct = true)) // an operator tapped in the scroll column
        assertEquals(listOf("×"), h.commits)
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
        // A3: the scrollable column now carries the FULL combo list for ni'shuo'de'bu'dui — real readings
        // ni & mi PLUS the first-key letters m/n/o (expected candidate layout "mi ni o m n"), clean, no blanks.
        val col = nineColumnFor("64744336488").map { it.label }
        assertTrue("ni present, was $col", "ni" in col)
        assertTrue("mi present, was $col", "mi" in col)
        assertTrue("first-key letters m/n/o present, was $col", listOf("m", "n", "o").all { it in col })
        assertTrue("no blank keys, was $col", col.none { it.isEmpty() })
        assertTrue("clean a-z only, was $col", col.all { s -> s.all { it in 'a'..'z' } })
    }

    // ---- A2 expanded selection screen (combo selector + 退格 / 重输) ----

    @Test fun expanded_readings_empty_at_rest_combos_while_composing() {
        val c = KeyboardController(FakeHost(), engine)
        c.onKey(act(KeyAction.SWITCH_NINE))
        assertTrue("no combos at rest", c.expandedReadings().isEmpty())
        "426".forEach { c.onKey(out(it.toString())) } // hao
        assertTrue("hao among combos while composing, was ${c.expandedReadings()}", "hao" in c.expandedReadings())
    }

    @Test fun panel_pick_reading_advances_syllables_and_commits_both() {
        // A2: picking a combination in the expanded left column locks that syllable and advances, exactly
        // like the 9-key left column — pick hao then de → commit haode.
        val h = FakeHost()
        val c = KeyboardController(h, engine)
        c.onKey(act(KeyAction.SWITCH_NINE))
        "42633".forEach { c.onKey(out(it.toString())) } // hao(426) de(33)
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
        c.onPanelBackspace() // 426 -> 42
        assertTrue("hao gone after one backspace", "hao" !in c.expandedReadings())
    }

    @Test fun panel_clear_drops_composing() {
        val h = FakeHost()
        val c = KeyboardController(h, engine)
        c.onKey(act(KeyAction.SWITCH_NINE))
        "426".forEach { c.onKey(out(it.toString())) }
        c.onPanelClear()
        assertTrue("combos gone after 重输", c.expandedReadings().isEmpty())
        c.onKey(act(KeyAction.ENTER)) // buffer empty → editor action, nothing committed
        assertEquals(1, h.enters)
        assertTrue(h.commits.isEmpty())
    }

    @Test fun no_ghost_suggestion_after_commit() {
        // ★A5/★S regression: committing a word must NOT auto-resurface a prediction in the bar (the old
        // un-clearable "ghost"). An empty buffer always yields zero candidates.
        val full = object : CandidateEngine {
            override fun candidates(composing: String, t9: Boolean) = candidatesCovered(composing, t9).map { it.word }
            override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>, context: CharSequence) =
                if (composing.isEmpty()) emptyList() else listOf(Cand("你好", composing.length))
        }
        val c = KeyboardController(FakeHost(), full)
        c.onKey(act(KeyAction.SWITCH_NINE))
        "426".forEach { c.onKey(out(it.toString())) }
        c.onPickCandidate(0) // commit 你好 → buffer empties
        assertTrue("no candidates linger after commit (no ghost)", c.candidateWords().isEmpty())
    }

    @Test fun backspace_after_a_partial_candidate_pick_restores_the_previous_preedit() {
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
        assertEquals("你hao", c.preeditForTest())
        assertTrue("partial pick has not reached the editor", h.commits.isEmpty())

        c.onKey(act(KeyAction.BACKSPACE))

        assertEquals("ni'hao", c.preeditForTest())
        assertEquals("", c.composingPrefix())
        assertTrue("undoing the pick must not touch editor text", h.commits.isEmpty())
    }

    @Test fun backspace_after_a_full_candidate_pick_deletes_the_commit_and_restores_preedit() {
        val h = FakeHost()
        val full = object : CandidateEngine {
            override fun candidates(composing: String, t9: Boolean) = candidatesCovered(composing, t9).map { it.word }
            override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>, context: CharSequence): List<Cand> =
                if (composing.isEmpty()) emptyList() else listOf(Cand("你好", composing.length))
        }
        val c = KeyboardController(h, full)
        c.onKey(act(KeyAction.SWITCH_NINE))
        "64426".forEach { c.onKey(out(it.toString())) }
        c.onPickCandidate(0)
        assertEquals(listOf("你好"), h.commits)
        assertEquals("你好", h.text.toString())
        assertEquals("", c.preeditForTest())

        c.onKey(act(KeyAction.BACKSPACE))

        assertEquals("the committed candidate is removed from the editor", "", h.text.toString())
        assertEquals("ni'hao", c.preeditForTest())
        assertEquals(listOf("你好"), c.candidateWords())
        assertEquals("candidate undo must not call raw deleteBackward", 0, h.deletes)
    }

    // ---- M-3/L-3: password / NO_PERSONALIZED_LEARNING fields must not learn committed words ----

    private fun learnSpyEngine(learned: MutableList<String>) = object : CandidateEngine {
        override fun candidates(composing: String, t9: Boolean) = candidatesCovered(composing, t9).map { it.word }
        override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>, context: CharSequence) =
            if (composing.isEmpty()) emptyList() else listOf(Cand("密码", composing.length))
        override fun learn(prevWord: String?, word: String) { learned.add(word) }
    }

    @Test fun sensitive_field_commit_is_not_learned() {
        val learned = mutableListOf<String>()
        val c = KeyboardController(FakeHost(), learnSpyEngine(learned))
        c.setLearningBlocked(true) // password / NO_PERSONALIZED_LEARNING field
        c.onKey(act(KeyAction.SWITCH_NINE))
        "426".forEach { c.onKey(out(it.toString())) }
        c.onPickCandidate(0) // commit 密码
        assertTrue("a blocked field must never learn the committed word", learned.isEmpty())
    }

    @Test fun ordinary_field_commit_is_learned_no_regression() {
        val learned = mutableListOf<String>()
        val c = KeyboardController(FakeHost(), learnSpyEngine(learned)) // learningBlocked defaults false
        c.onKey(act(KeyAction.SWITCH_NINE))
        "426".forEach { c.onKey(out(it.toString())) }
        c.onPickCandidate(0)
        assertEquals(listOf("密码"), learned)
    }

    // ---- ★A9 退格 = 退回上一步 (a left-column pick is a step; never drop a whole syllable) ----

    @Test fun backspace_steps_back_a_locked_reading_not_the_whole_syllable() {
        val c = KeyboardController(FakeHost(), engine)
        c.onKey(act(KeyAction.SWITCH_NINE))
        "426".forEach { c.onKey(out(it.toString())) } // hao
        c.onKey(Key("hao", output = "hao", action = KeyAction.PICK_READING)) // lock = one step
        c.onKey(act(KeyAction.BACKSPACE)) // must UNDO THE PICK (426 stays, unlocked) — not delete 426
        assertTrue("pick undone → hao offered again, was ${c.expandedReadings()}", "hao" in c.expandedReadings())
        c.onKey(act(KeyAction.BACKSPACE)) // now delete ONE letter (426 → 42)
        assertTrue("one letter removed → hao gone", "hao" !in c.expandedReadings())
        assertTrue("…but the 2-digit syllable remains", "ha" in c.expandedReadings())
    }

    @Test fun backspace_after_two_locks_steps_back_each_pick_then_keeps_digits() {
        val h = FakeHost()
        val c = KeyboardController(h, engine)
        c.onKey(act(KeyAction.SWITCH_NINE))
        "42633".forEach { c.onKey(out(it.toString())) } // hao de
        c.onKey(Key("hao", output = "hao", action = KeyAction.PICK_READING))
        c.onKey(Key("de", output = "de", action = KeyAction.PICK_READING))
        c.onKey(act(KeyAction.BACKSPACE)) // undo pick de
        assertTrue("de offered again", "de" in c.expandedReadings())
        c.onKey(act(KeyAction.BACKSPACE)) // undo pick hao
        assertTrue("hao offered again", "hao" in c.expandedReadings())
        c.onKey(act(KeyAction.ENTER)) // all 42633 digits intact → commits haode
        assertEquals(listOf("haode"), h.commits)
    }

    @Test fun backspace_without_a_pick_deletes_one_letter_only() {
        val c = KeyboardController(FakeHost(), engine)
        c.onKey(act(KeyAction.SWITCH_NINE))
        "426".forEach { c.onKey(out(it.toString())) }
        c.onKey(act(KeyAction.BACKSPACE)) // 426 → 42, never a whole syllable
        assertTrue("hao gone (one letter removed)", "hao" !in c.expandedReadings())
        assertTrue("ha still present", "ha" in c.expandedReadings())
    }

    // ---- B5: CN default keyboard (9-key unless the user chose 26-key); EN is 26-key only ----

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
        c.onKey(act(KeyAction.TOGGLE_LANG)) // CN -> EN
        c.setCnDefaultLayout(LayoutId.NINE)
        c.reset()
        assertEquals("EN is always 26-key", LayoutId.ALPHA, c.activeLayoutId())
    }

    @Test fun lang_round_trip_returns_to_the_cn_default_keyboard() {
        // B5 regression: 中英 there-and-back must NOT demote a 9-key user to 26-key.
        val c = KeyboardController(FakeHost(), engine)
        c.reset() // CN, 9-key default
        assertEquals(LayoutId.NINE, c.activeLayoutId())
        c.onKey(act(KeyAction.TOGGLE_LANG)) // CN -> EN (26-key only)
        assertEquals(LayoutId.ALPHA, c.activeLayoutId())
        c.onKey(act(KeyAction.TOGGLE_LANG)) // EN -> CN: restores 9-key
        assertEquals(LayoutId.NINE, c.activeLayoutId())
    }

    @Test fun lang_round_trip_preserves_a_manual_cn_26_key_choice() {
        // If the user manually switched CN to 26-key, a 中英 round-trip keeps 26-key (captured on leave).
        val c = KeyboardController(FakeHost(), engine)
        c.reset() // CN 9-key
        c.onKey(act(KeyAction.SWITCH_ALPHA)) // manual CN -> 26-key
        assertEquals(LayoutId.ALPHA, c.activeLayoutId())
        c.onKey(act(KeyAction.TOGGLE_LANG)) // -> EN (26-key)
        c.onKey(act(KeyAction.TOGGLE_LANG)) // -> CN: restores the manual 26-key, not the 9-key default
        assertEquals(LayoutId.ALPHA, c.activeLayoutId())
    }

    // ---- ③ debug.18: changing the CN default keyboard takes effect IMMEDIATELY (no IME re-launch) ----

    @Test fun changing_the_cn_default_keyboard_hot_applies_without_a_relaunch() {
        // The bug: flipping the 9键/26键 setting only took effect on the next reset()/onStartInputView, so it
        // needed re-launching the IME. Now the live layout switches the instant the pref is pushed.
        val c = KeyboardController(FakeHost(), engine)
        c.reset() // CN, 9-key default
        assertEquals(LayoutId.NINE, c.activeLayoutId())
        c.setCnDefaultLayout(LayoutId.ALPHA) // user picks 26-key in settings — NOTE: no reset() after
        assertEquals("switches to 26-key in place", LayoutId.ALPHA, c.activeLayoutId())
        c.setCnDefaultLayout(LayoutId.NINE) // …and back
        assertEquals("switches back to 9-key in place", LayoutId.NINE, c.activeLayoutId())
    }

    @Test fun changing_the_cn_default_does_not_yank_en_off_26_key() {
        // The hot-apply is gated to CN — changing the CN default while typing English must NOT disturb EN. The
        // CN default must REALLY change here (ALPHA→NINE) while in EN, so the lang gate is actually exercised
        // (a no-op same-value call would be short-circuited by the cnDefaultLayout==id guard and prove nothing).
        val c = KeyboardController(FakeHost(), engine)
        c.reset()
        c.setCnDefaultLayout(LayoutId.ALPHA)  // CN: cnDefaultLayout becomes ALPHA (and CN hot-switches to 26-key)
        c.onKey(act(KeyAction.TOGGLE_LANG))   // CN -> EN (26-key only)
        assertEquals(LayoutId.ALPHA, c.activeLayoutId())
        c.setCnDefaultLayout(LayoutId.NINE)   // cnDefaultLayout really flips ALPHA→NINE WHILE in EN
        assertEquals("EN stays 26-key regardless of the CN default flip", LayoutId.ALPHA, c.activeLayoutId())
    }

    // ---- H-1: a default-9-key user must be able to return to the 9-key from the number/symbol pages ----

    @Test fun nine_key_default_user_can_return_from_the_numpad() {
        val c = KeyboardController(FakeHost(), engine)
        c.reset() // CN, 9-key default
        assertEquals(LayoutId.NINE, c.activeLayoutId())
        c.onKey(act(KeyAction.SWITCH_NUMPAD)) // 123 → numpad
        assertEquals(LayoutId.NUMPAD, c.activeLayoutId())
        c.onKey(act(KeyAction.SWITCH_TEXT))   // 返回
        assertEquals("返回 lands back on the 9-key default, not 26-key (H-1)", LayoutId.NINE, c.activeLayoutId())
    }

    @Test fun nine_key_default_user_can_return_from_the_symbol_page() {
        val c = KeyboardController(FakeHost(), engine)
        c.reset()
        c.onKey(act(KeyAction.SWITCH_SYMBOLS)) // @# → symbols
        assertEquals(LayoutId.SYMBOL, c.activeLayoutId())
        c.onKey(act(KeyAction.SWITCH_TEXT))    // 返回
        assertEquals(LayoutId.NINE, c.activeLayoutId())
    }

    @Test fun en_user_returns_from_the_number_page_to_26_key() {
        // SWITCH_TEXT in EN returns to the 26-key (EN has no 9-key).
        val c = KeyboardController(FakeHost(), engine)
        c.reset()
        c.onKey(act(KeyAction.TOGGLE_LANG))    // → EN (26-key)
        c.onKey(act(KeyAction.SWITCH_NUMBERS)) // 123 → number page
        c.onKey(act(KeyAction.SWITCH_TEXT))    // 返回
        assertEquals(LayoutId.ALPHA, c.activeLayoutId())
    }

    // ---- B2: the 26-key up-flick emits the super-script symbol as a direct commit ----

    @Test fun b2_up_swipe_symbol_commits_directly_even_mid_pinyin() {
        // KeyboardView turns an up-flick on a letter into a direct symbol key; the controller must flush any
        // pending CN pinyin first, then commit the symbol straight to the editor.
        val h = FakeHost()
        val c = KeyboardController(h, engine) // CN 26-key (ALPHA) → letters buffer as pinyin
        c.onKey(Key("n", output = "n"))
        c.onKey(Key("@", output = "@", direct = true)) // up-flick symbol
        assertEquals(listOf("n", "@"), h.commits)
    }

    @Test fun backspace_up_swipe_clears_pending_pinyin_in_any_layout() {
        // C: up-swipe on backspace clears the 任务栏 (pending pinyin) and is consumed before the field clear.
        val h = FakeHost()
        val c = KeyboardController(h, engine)
        c.onKey(act(KeyAction.SWITCH_NINE))
        "6433".forEach { c.onKey(out(it.toString())) } // ni'de pending
        assertTrue("up-swipe must consume + clear the buffer", c.onBackspaceSwipe(true))
        c.onKey(act(KeyAction.ENTER)) // buffer gone → editor action, no text committed
        assertEquals(1, h.enters)
        assertTrue(h.commits.isEmpty())
        // nothing pending → not consumed, so the service does its field-level clear/restore
        assertEquals(false, c.onBackspaceSwipe(true))
    }

    @Test fun up_swipe_on_a_bare_assembled_prefix_clears_it_and_consumes_the_gesture() {
        // D3 (debug.12): an up-swipe with ONLY the prefix pending (remainder backspaced away) must 重输
        // (drop the prefix) and be CONSUMED — never fall through to the service's whole-field clear, which
        // would wipe the editor AND strand the prefix.
        val h = FakeHost()
        val partial = object : CandidateEngine {
            override fun candidates(composing: String, t9: Boolean) = candidatesCovered(composing, t9).map { it.word }
            override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>, context: CharSequence): List<Cand> =
                if (composing.isEmpty()) emptyList() else listOf(Cand("你", 2))
        }
        val c = KeyboardController(h, partial)
        c.onKey(act(KeyAction.SWITCH_NINE))
        "64426".forEach { c.onKey(out(it.toString())) }
        c.onPickCandidate(0)                              // prefix "你", remainder "426"
        clearCandidateUndo(c)
        repeat(3) { c.onKey(act(KeyAction.BACKSPACE)) }   // remainder gone, only prefix "你" remains
        assertEquals("你", c.composingPrefix())
        assertTrue("up-swipe must consume the gesture (重输), not fall through to the field wipe", c.onBackspaceSwipe(true))
        assertEquals("the pending prefix is dropped", "", c.composingPrefix())
        assertTrue("nothing committed, field untouched", h.commits.isEmpty())
        assertEquals("never deleted committed editor text", 0, h.deletes)
    }
}
