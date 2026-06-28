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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * debug.12 fix-a — two locked-9-key data-integrity bugs (reproduced below)
 * with a deterministic stand-in engine (no dict assets, real [T9Pinyin] coverage maths):
 *
 *  F1 (data loss): after locking a reading, a candidate whose coverage is NOT on a syllable boundary used
 *      to be mislabelled as covering the WHOLE buffer — picking it committed the partial word AND cleared
 *      the rest of the still-typed digits (越界候选过度上屏丢数据). It must partial-commit only what it covers.
 *  F6: a forced 分词 boundary in the active tail was dropped the moment a reading was locked, because the
 *      locked-path decode was called without the cuts the unlocked path always forwarded.
 */
class Debug12FixATest {

    private class RecordingHost : ImeHost {
        val commits = mutableListOf<String>()
        override fun commitText(text: CharSequence) { commits.add(text.toString()) }
        override fun deleteBackward() {}
        override fun performEnter() {}
    }

    /**
     * Stand-in decoder for the LOCKED path. Records the cuts it is handed (F6) and returns a full-cover
     * sentence plus "X" (covers 4 letters — OFF the syllable boundaries, exercises the F1 remap). When a
     * forced cut is present it suppresses the spanning sentence, mirroring `PinyinDecoder.decodeCovered`
     * dropping whole-input completions across a forced boundary.
     */
    private class ProbeEngine : CandidateEngine {
        var lastReadingCuts: Set<Int> = setOf(-1) // sentinel: not "called with the empty set"
        override fun candidates(composing: String, t9: Boolean) =
            candidatesCovered(composing, t9).map { it.word }
        override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>, context: CharSequence): List<Cand> =
            if (composing.isEmpty()) emptyList() else listOf(Cand("好", composing.length))
        override fun candidatesForReadingCovered(letters: String, cuts: Set<Int>, context: CharSequence): List<Cand> {
            lastReadingCuts = cuts
            val spanning = if (cuts.isEmpty()) listOf(Cand("好的", letters.length)) else emptyList()
            return spanning + listOf(Cand("X", 4))
        }
    }

    private fun digit(d: Char) = Key(d.toString(), output = d.toString())
    private fun pick(reading: String) = Key(reading, output = reading, action = KeyAction.PICK_READING)
    private fun nine() = Key("", action = KeyAction.SWITCH_NINE)

    // ---------------- F1 ----------------

    @Test fun f1_an_off_boundary_candidate_does_not_over_commit_and_wipe_the_buffer() {
        val host = RecordingHost()
        val c = KeyboardController(host, ProbeEngine())
        c.onKey(nine())
        "42633".forEach { c.onKey(digit(it)) }   // hao(426) de(33)
        c.onKey(pick("hao"))                      // lock syllable 1 → fullLetters "haode", boundaries {3,5}

        // "X" covers 4 letters — OFF the {3,5} boundaries. The bug mapped any off-boundary coverage to the
        // full buffer length, so a partial pick was misread as a complete word.
        val xi = c.candidateWords().indexOf("X")
        assertTrue("the off-boundary candidate is offered, was ${c.candidateWords()}", xi >= 0)
        c.onPickCandidate(xi)

        // It is a PARTIAL pick: it must build the assembled prefix, NOT commit + clear the buffer (the typed
        // tail must survive). The bug committed "X" and wiped everything.
        assertTrue("an off-boundary pick must not over-commit, commits=${host.commits}", host.commits.isEmpty())
        assertEquals("the pick builds the assembled prefix", "X", c.composingPrefix())
    }

    // ---------------- F6 ----------------

    @Test fun f6_a_forced_cut_in_the_active_tail_is_forwarded_to_the_locked_decode() {
        val probe = ProbeEngine()
        val c = KeyboardController(RecordingHost(), probe)
        c.onKey(nine())
        "6433".forEach { c.onKey(digit(it)) }        // ni(64) de(33)
        c.onKey(Key("", action = KeyAction.SEGMENT)) // 分词 → forced boundary at digit 4 (between "de" and "hao")
        "426".forEach { c.onKey(digit(it)) }         // hao(426)
        c.onKey(pick("ni"))                          // lock syllable 1 (activeStart → 2)

        assertEquals(
            "the forced 分词 boundary inside the active tail is forwarded to the locked-path decode",
            setOf(4), probe.lastReadingCuts,
        )
    }

    @Test fun f6_a_forced_cut_after_a_lock_suppresses_a_word_spanning_it() {
        // Behavioural contrast: with NO forced cut the spanning sentence survives the lock; with one it is
        // dropped (the engine honours the forwarded boundary). Proves the cut actually reaches the decode.
        val noCut = KeyboardController(RecordingHost(), ProbeEngine())
        noCut.onKey(nine())
        "6433426".forEach { noCut.onKey(digit(it)) }
        noCut.onKey(pick("ni"))
        assertTrue("without a forced cut the spanning sentence is offered", "好的" in noCut.candidateWords())

        val withCut = KeyboardController(RecordingHost(), ProbeEngine())
        withCut.onKey(nine())
        "6433".forEach { withCut.onKey(digit(it)) }
        withCut.onKey(Key("", action = KeyAction.SEGMENT))
        "426".forEach { withCut.onKey(digit(it)) }
        withCut.onKey(pick("ni"))
        assertFalse("with a forced cut a word spanning it is suppressed", "好的" in withCut.candidateWords())
    }

    // ---------------- F7 ----------------

    /** Records the (prevWord, word) pairs the controller learns, to assert bigram context is preserved. */
    private class LearnSpyEngine : CandidateEngine {
        val learns = mutableListOf<Pair<String?, String>>()
        override fun candidates(composing: String, t9: Boolean) = candidatesCovered(composing, t9).map { it.word }
        override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>, context: CharSequence): List<Cand> =
            if (composing.isEmpty()) emptyList() else listOf(Cand("好", composing.length))
        override fun learn(prevWord: String?, word: String) { learns.add(prevWord to word) }
    }

    @Test fun f7_toolbar_entries_defensively_flush_a_pending_buffer_before_opening() {
        // Defensive controller contract (F7): IF a toolbar entry is invoked with a pending buffer it must land
        // it BEFORE opening the panel. NOTE: today's candidate bar exposes these icons ONLY while idle
        // (CandidateView draws + hit-tests them only when there are no candidates AND no preedit), so this
        // state is not reachable through the live UI — the test drives the controller method directly to lock
        // the guard, keeping the entries consistent with the ✎ SHOW_SYMBOLS key.
        for (f in BarFunction.entries) {
            val host = RecordingHost()
            val c = KeyboardController(host, ProbeEngine())
            var commitsWhenOpened: List<String>? = null
            val recordOpen = { commitsWhenOpened = host.commits.toList() }
            c.onShowEmoji = recordOpen
            c.onShowClipboard = recordOpen
            c.onShowEdit = recordOpen
            c.onShowSettings = recordOpen

            c.onKey(nine())
            "42633".forEach { c.onKey(digit(it)) }   // a live 9-key pinyin buffer (hao de)
            assertEquals("precondition: buffer is live for $f", false, c.preeditForTest().isEmpty())

            c.onBarFunction(f)

            assertTrue("$f committed the in-progress buffer, commits=${host.commits}", host.commits.isNotEmpty())
            assertEquals("$f flushed BEFORE opening its panel", host.commits, commitsWhenOpened)
            assertEquals("no dangling preedit after $f", "", c.preeditForTest())
            assertEquals("no dangling assembled prefix after $f", "", c.composingPrefix())
        }
    }

    @Test fun f7_an_idle_toolbar_tap_does_not_disturb_bigram_context() {
        // The reachable path: tapping a toolbar entry while the strip is idle (no buffer) must NOT flush, so it
        // must NOT clear the learned predecessor (lastWord). An unconditional flush would learn (null, word2)
        // instead of (word1, word2) on the next commit — this locks the guard against that regression.
        val eng = LearnSpyEngine()
        val c = KeyboardController(RecordingHost(), eng) // default ALPHA + CN = pinyin buffer
        "hao".forEach { c.onKey(Key(it.toString(), output = it.toString())) }
        c.onPickCandidate(c.candidateWords().indexOf("好")) // commit 好 → learn(null,好); lastWord=好
        assertTrue("precondition: nothing pending after the commit", c.preeditForTest().isEmpty())

        c.onBarFunction(BarFunction.EMOJI)                 // idle tap → must be a no-op for the buffer/lastWord

        "hao".forEach { c.onKey(Key(it.toString(), output = it.toString())) }
        c.onPickCandidate(c.candidateWords().indexOf("好")) // commit 好 again → must learn(好,好), not (null,好)
        assertEquals(
            "an idle toolbar tap preserves the bigram predecessor",
            listOf<Pair<String?, String>>(null to "好", "好" to "好"), eng.learns,
        )
    }
}
