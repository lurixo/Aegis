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

import com.aegis.ime.decoder.FullDictTestAssets
import com.aegis.ime.decoder.PinyinCorrection
import com.aegis.ime.dict.BinaryDict
import com.aegis.ime.dict.CharBigramLM
import com.aegis.ime.engine.DictEngine
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TypoGuessCandidatesTest {

    private class RecordingHost : ImeHost {
        val commits = mutableListOf<String>()
        val text = StringBuilder()
        override fun commitText(text: CharSequence) { commits.add(text.toString()); this.text.append(text) }
        override fun deleteBackward() { if (text.isNotEmpty()) text.setLength(text.length - 1) }
        override fun performEnter() {}
        override fun textBeforeCursor(n: Int): CharSequence = text.substring(maxOf(0, text.length - n))
    }

    private data class Case(val word: String, val letters: String, val digits: String)

    private val corpus = listOf(
        Case("中国", "zhonguo", "94664476"),
        Case("你好", "nihoa", "64462"),
        Case("我爱你", "wsoaini", "9562464"),
        Case("北京", "beijign", "2354464"),
    )

    private data class LockedCase(val word: String, val lockReading: String, val lockLetters: String, val lockDigits: String, val restLetters: String, val restDigits: String)

    private val lockedCorpus = listOf(
        LockedCase("你好", "ni", "ni", "64", "hoa", "462"),
    )

    private fun out(s: String) = Key(s, output = s)
    private fun act(a: KeyAction) = Key("", action = a)

    private fun engine(): DictEngine {
        val dict = FullDictTestAssets.file(FullDictTestAssets.DICT)
        val t9 = FullDictTestAssets.file(FullDictTestAssets.T9)
        val lm = FullDictTestAssets.file(FullDictTestAssets.LM)
        val jianpin = FullDictTestAssets.file(FullDictTestAssets.JIANPIN)
        assumeTrue(FullDictTestAssets.available(dict, t9, lm, jianpin))
        return DictEngine(BinaryDict.fromFile(dict), BinaryDict.fromFile(t9), CharBigramLM.fromFile(lm), initialsDict = BinaryDict.fromFile(jianpin))
    }

    private fun controller(engine: DictEngine, nine: Boolean, input: String): Pair<RecordingHost, KeyboardController> {
        val host = RecordingHost()
        val c = KeyboardController(host, engine)
        c.switchTextLayoutForTest(nine)
        input.forEach { c.onKey(out(it.toString())) }
        return host to c
    }

    private fun Case.input(nine: Boolean) = if (nine) digits else letters

    @Test fun broken_input_keeps_a_guessed_word_in_the_candidate_row_on_both_layouts() {
        val engine = engine()
        for (nine in listOf(false, true)) for (case in corpus) {
            val input = case.input(nine)
            assertFalse(PinyinCorrection.fullySegmentable(input))
            val (host, c) = controller(engine, nine, input)
            val words = c.candidateWords()
            assertTrue("$input nine=$nine should keep candidates", words.isNotEmpty())
            val at = words.indexOf(case.word)
            assertEquals("$input nine=$nine should put ${case.word} first, was ${words.take(8)}", 0, at)
            c.onPickCandidate(at)
            assertEquals(listOf(case.word), host.commits)
            assertEquals("", c.preeditForTest())
        }
    }

    @Test fun broken_active_segment_after_a_lock_still_guesses_the_whole_word() {
        val engine = engine()
        for (nine in listOf(false, true)) for (case in lockedCorpus) {
            val (host, c) = controller(engine, nine, if (nine) case.lockDigits else case.lockLetters)
            c.onPickReadingIndex(c.expandedReadings().indexOf(case.lockReading))
            assertEquals(case.lockReading, c.preeditForTest())
            val rest = if (nine) case.restDigits else case.restLetters
            assertFalse(PinyinCorrection.fullySegmentable(rest))
            rest.forEach { c.onKey(out(it.toString())) }
            val words = c.candidateWords()
            assertTrue("${case.lockReading}+$rest nine=$nine should guess ${case.word}, was ${words.take(8)}", words.indexOf(case.word) in 0 until 6)
            assertEquals("lock survives", case.lockReading + "'", c.preeditForTest().take(case.lockReading.length + 1))
            c.onPickCandidate(words.indexOf(case.word))
            assertEquals(listOf(case.word), host.commits)
            assertEquals("", c.preeditForTest())
        }
    }


}
