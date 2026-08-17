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

    private val rich = object : CandidateEngine {
        override fun candidates(composing: String, t9: Boolean) =
            candidatesCovered(composing, t9).map { it.word }
        override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>, context: CharSequence): List<Cand> =
            if (composing.isEmpty()) emptyList()
            else listOf(Cand("你", 2), Cand("你的", composing.length), Cand("拟", 2))
        override fun candidatesForLockedReadingCovered(letters: String, cuts: Set<Int>, context: CharSequence): List<Cand> =
            listOf(Cand("你的", letters.length), Cand("你", 2), Cand("拟", 2), Cand("泥", 2))
    }

    private val rowRich = object : CandidateEngine {
        private fun rowCandidates(covered: Int): List<Cand> =
            List(22) { Cand("词${('A'.code + it).toChar()}", covered) } +
                List(24) { Cand((0x4e00 + it).toChar().toString(), 2) } +
                listOf(Cand("𰻞", 2), Cand("❤️", 2))

        override fun candidates(composing: String, t9: Boolean): List<String> =
            candidatesCovered(composing, t9).map { it.word }

        override fun candidatesCovered(
            composing: String,
            t9: Boolean,
            cuts: Set<Int>,
            context: CharSequence,
        ): List<Cand> = if (composing.isEmpty()) emptyList() else rowCandidates(composing.length)

        override fun candidatesForLockedReadingCovered(
            letters: String,
            cuts: Set<Int>,
            context: CharSequence,
        ): List<Cand> = rowCandidates(letters.length)
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
        c.switchTextLayoutForTest(nine = true)
        "6433".forEach { c.onKey(out(it.toString())) }
        assertTrue("grid populated before any lock", iv.shownCandidateCount() >= 2)

        c.onKey(pick("ni"))

        assertTrue(
            "after locking a reading the candidate strip must STAY rich, was ${iv.shownCandidateCount()}",
            iv.shownCandidateCount() >= 3,
        )
        assertTrue("the full-pinyin sentence is among the kept candidates", "你的" in c.candidateWords())
    }

    @Test fun a_prefix_word_picked_after_locking_builds_a_prefix_not_a_per_syllable_commit() {
        val host = RecordingHost()
        val (_, c) = attached(host)
        c.switchTextLayoutForTest(nine = true)
        "6433".forEach { c.onKey(out(it.toString())) }
        c.onKey(pick("ni"))

        val niIndex = c.candidateWords().indexOf("你")
        assertTrue("你 present as a partial candidate", niIndex >= 0)
        c.onPickCandidate(niIndex)
        assertTrue("a partial pick must not commit to the editor", host.commits.isEmpty())
        assertEquals("你", c.composingPrefix())
        c.onKey(Key("", action = KeyAction.ENTER))

        assertEquals(listOf("你de"), host.commits)
    }

    @Test fun the_left_column_keeps_offering_the_next_syllable_after_a_lock() {
        val (_, c) = attached()
        c.switchTextLayoutForTest(nine = true)
        "42633".forEach { c.onKey(out(it.toString())) }
        assertTrue("hao offered before any lock", "hao" in c.expandedReadings())

        c.onKey(pick("hao"))

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
        c.switchTextLayoutForTest(nine = true)
        "6433".forEach { c.onKey(out(it.toString())) }
        c.onKey(pick("ni"))
        val full = c.candidateWords().indexOf("你的")
        c.onPickCandidate(full)
        assertEquals(listOf("你的"), host.commits)
        assertTrue("buffer fully consumed", c.candidateWords().isEmpty())
    }

    @Test fun ni_shuo_de_dui_uses_the_three_row_phrase_policy_in_every_layout_and_lock_state() {
        data class Case(val nine: Boolean, val input: String, val lockFirstReading: Boolean)
        val cases = listOf(
            Case(nine = true, "64748633384", lockFirstReading = false),
            Case(nine = true, "64748633384", lockFirstReading = true),
            Case(nine = false, "nishuodedui", lockFirstReading = false),
            Case(nine = false, "nishuodedui", lockFirstReading = true),
        )
        for ((nine, input, lockFirstReading) in cases) {
            val iv = InputView(ctx)
            val c = KeyboardController(RecordingHost(), rowRich)
            c.attachView(iv)
            c.switchTextLayoutForTest(nine)
            input.forEach { c.onKey(out(it.toString())) }
            if (lockFirstReading) c.onKey(pick("ni"))
            iv.showExpandedCandidates()

            val source = c.candidateWords()
            val grid = iv.expandedGridForTest()
            val rendered = grid.renderedCandidateTextsForTest()
            val caseLabel = "${if (nine) "9-key" else "26-key"} lock=$lockFirstReading"
            assertTrue("the controller keeps the complete list for $caseLabel", source.size > rendered.size)
            assertEquals(
                "every single item stays reachable for $caseLabel",
                source.filter { GraphemeText.clusterCount(it) == 1 },
                rendered.filter { GraphemeText.clusterCount(it) == 1 },
            )
            assertTrue(
                "rows after the third contain only single items for $caseLabel",
                grid.rowTextsForTest().drop(CandidateProjectionPolicy.PINYIN.maxPhraseRows).flatten()
                    .all { GraphemeText.clusterCount(it) == 1 },
            )
            assertTrue(
                "phrases precede single items for $caseLabel",
                rendered.dropWhile { GraphemeText.clusterCount(it) > 1 }
                    .all { GraphemeText.clusterCount(it) == 1 },
            )
        }
    }
}
