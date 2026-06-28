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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * U1 (production blocker): on the 9-key, picking a pinyin reading from the LEFT column collapsed the
 * whole middle candidate grid to the single best sentence (often "one word"), so the user could no
 * longer choose any word. Root cause: [KeyboardController.refreshCandidates]'s locked branch decoded
 * the combined full pinyin through the NARROW `decode()` (best-sentence only) instead of the RICH
 * `decodeCovered()` the unlocked path uses. A long-standing ★E design flaw, NOT a debug.10 regression.
 *
 * This is a REAL [InputView] test (the controller-only FakeHost suites never asserted candidate
 * *richness* after a lock, which is exactly why it slipped). It drives the real strip render and checks
 * the rendered count via [InputView.shownCandidateCount].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LockedReadingCandidatesTest {

    private val ctx = RuntimeEnvironment.getApplication()

    private class RecordingHost : ImeHost {
        val commits = mutableListOf<String>()
        override fun commitText(text: CharSequence) { commits.add(text.toString()) }
        override fun deleteBackward() {}
        override fun performEnter() {}
    }

    /**
     * Stand-in for the real decoder: rich for both the live digit buffer ([candidatesCovered]) AND the
     * locked full-pinyin reading ([candidatesForReadingCovered]). The locked path returns a full
     * sentence (covers all the letters) plus shorter prefix words (你/拟/泥 over the first syllable),
     * mirroring `PinyinDecoder.decodeCovered`.
     */
    private val rich = object : CandidateEngine {
        override fun candidates(composing: String, t9: Boolean) =
            candidatesCovered(composing, t9).map { it.word }
        override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>): List<Cand> =
            if (composing.isEmpty()) emptyList()
            else listOf(Cand("你", 2), Cand("你的", composing.length), Cand("拟", 2))
        override fun candidatesForReadingCovered(letters: String): List<Cand> =
            listOf(Cand("你的", letters.length), Cand("你", 2), Cand("拟", 2), Cand("泥", 2))
    }

    private fun out(s: String) = Key(s, output = s)
    private fun pick(reading: String) = Key(reading, output = reading, action = KeyAction.PICK_READING)

    private fun attached(host: ImeHost = RecordingHost()): Pair<InputView, KeyboardController> {
        val iv = InputView(ctx)
        val c = KeyboardController(host, rich)
        c.attachView(iv)
        return iv to c
    }

    @Test fun locking_a_left_column_reading_keeps_the_full_candidate_grid() {
        val (iv, c) = attached()
        c.onKey(Key("", action = KeyAction.SWITCH_NINE))
        "6433".forEach { c.onKey(out(it.toString())) } // ni'de
        assertTrue("grid populated before any lock", iv.shownCandidateCount() >= 2)

        c.onKey(pick("ni")) // lock syllable 1 from the left column

        // THE U1 BUG: before the fix this dropped to <=1 (narrow decode / empty default). It must stay rich.
        assertTrue(
            "after locking a reading the candidate strip must STAY rich, was ${iv.shownCandidateCount()}",
            iv.shownCandidateCount() >= 3,
        )
        assertTrue("the full-pinyin sentence is among the kept candidates", "你的" in c.candidateWords())
    }

    @Test fun a_prefix_word_picked_after_locking_still_partial_commits() {
        // The coverage bridge (letters→digits): picking 你 (covers only the first syllable's 2 digits)
        // must commit 你 and KEEP the rest ("de"), not swallow or drop the whole buffer.
        val host = RecordingHost()
        val (_, c) = attached(host)
        c.onKey(Key("", action = KeyAction.SWITCH_NINE))
        "6433".forEach { c.onKey(out(it.toString())) } // ni(64) de(33)
        c.onKey(pick("ni"))                            // lock syllable 1 → grid: 你的, 你, 拟, 泥

        val niIndex = c.candidateWords().indexOf("你")
        assertTrue("你 present as a partial candidate", niIndex >= 0)
        c.onPickCandidate(niIndex)                     // commit 你, expect "de" to remain composing
        c.onKey(Key("", action = KeyAction.ENTER))     // flush remainder

        assertEquals(listOf("你", "de"), host.commits)
    }

    @Test fun the_left_column_keeps_offering_the_next_syllable_after_a_lock() {
        // U5 (same region as U1): picking a left-column reading must keep BOTH the middle candidate grid
        // AND the left column populated — the NEXT syllable's combinations must still come out, so the
        // user can keep selecting ("至少要能出来"). leftColumnReadings is real (no engine), so this also
        // proves the column is never empty while an active syllable remains.
        val (_, c) = attached()
        c.onKey(Key("", action = KeyAction.SWITCH_NINE))
        "42633".forEach { c.onKey(out(it.toString())) } // hao(426) de(33)
        assertTrue("hao offered before any lock", "hao" in c.expandedReadings())

        c.onKey(pick("hao")) // lock syllable 1

        assertTrue("after the lock the candidate grid is still rich", c.candidateWords().size >= 3)
        assertTrue(
            "after the lock the left column still offers the next syllable, was ${c.expandedReadings()}",
            c.expandedReadings().isNotEmpty(),
        )
        assertTrue("specifically 'de' is offered for syllable 2", "de" in c.expandedReadings())
    }

    @Test fun picking_the_full_sentence_after_locking_commits_everything() {
        val host = RecordingHost()
        val (_, c) = attached(host)
        c.onKey(Key("", action = KeyAction.SWITCH_NINE))
        "6433".forEach { c.onKey(out(it.toString())) }
        c.onKey(pick("ni"))
        val full = c.candidateWords().indexOf("你的")
        c.onPickCandidate(full)                        // covers the whole buffer → nothing left
        assertEquals(listOf("你的"), host.commits)
        assertTrue("buffer fully consumed", c.candidateWords().isEmpty())
    }
}
