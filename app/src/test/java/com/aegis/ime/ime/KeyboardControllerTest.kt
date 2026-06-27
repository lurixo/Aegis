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

/** Behaviour for the correction cases — no view attached, so render() is a no-op. */
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

    @Test fun picking_a_partial_candidate_commits_it_and_keeps_the_rest() {
        // ★E: a candidate whose reading covers only part of the buffer commits that part, continues the rest.
        val h = FakeHost()
        val partial = object : CandidateEngine {
            override fun candidates(composing: String, t9: Boolean) = candidatesCovered(composing, t9).map { it.word }
            override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>): List<Cand> =
                if (composing.isEmpty()) emptyList() else listOf(Cand("你", 2)) // 你 covers the first 2 digits "64"
        }
        val c = KeyboardController(h, partial)
        c.onKey(act(KeyAction.SWITCH_NINE))
        "64426".forEach { c.onKey(out(it.toString())) } // ni(64) hao(426)
        c.onPickCandidate(0) // pick 你 → commits 你, drops "64", keeps "426"
        c.onKey(act(KeyAction.ENTER)) // flush the remaining hao as raw pinyin
        assertEquals(listOf("你", "hao"), h.commits)
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
}
