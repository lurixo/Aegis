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
            override fun candidatesCovered(composing: String, t9: Boolean): List<Cand> =
                if (composing.isEmpty()) emptyList() else listOf(Cand("你", 2)) // 你 covers the first 2 digits "64"
        }
        val c = KeyboardController(h, partial)
        c.onKey(act(KeyAction.SWITCH_NINE))
        "64426".forEach { c.onKey(out(it.toString())) } // ni(64) hao(426)
        c.onPickCandidate(0) // pick 你 → commits 你, drops "64", keeps "426"
        c.onKey(act(KeyAction.ENTER)) // flush the remaining hao as raw pinyin
        assertEquals(listOf("你", "hao"), h.commits)
    }

    @Test fun direct_punctuation_flushes_pinyin_then_commits_directly() {
        val h = FakeHost()
        val c = KeyboardController(h, engine)
        c.onKey(act(KeyAction.SWITCH_NINE))
        "64".forEach { c.onKey(out(it.toString())) } // ni
        c.onKey(Key("，", output = "，", direct = true)) // ★D: punctuation never buffers as pinyin
        assertEquals(listOf("ni", "，"), h.commits)
    }

    @Test fun toggling_to_english_leaves_the_nine_key() {
        val h = FakeHost()
        val c = KeyboardController(h, engine)
        c.onKey(act(KeyAction.SWITCH_NINE))
        c.onKey(act(KeyAction.TOGGLE_LANG)) // CN -> EN; must drop to the 26-key
        c.onKey(out("a"))
        c.onKey(act(KeyAction.SPACE))
        // English buffered mode commits "a " as one chunk; DIRECT (still-9-key) would commit "a" then " ".
        assertEquals(listOf("a "), h.commits)
    }
}
