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

package com.aegis.ime.decoder

import com.aegis.ime.dict.BinaryDict
import com.aegis.ime.dict.CharBigramLM
import com.aegis.ime.user.UserLearning
import com.aegis.ime.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class AssembledCandidateOrderTest {

    private val dictFile = FullDictTestAssets.file(FullDictTestAssets.DICT)
    private val t9File = FullDictTestAssets.file(FullDictTestAssets.T9)
    private val lmFile = FullDictTestAssets.file(FullDictTestAssets.LM)
    private val jianpinFile = FullDictTestAssets.file(FullDictTestAssets.JIANPIN)

    private val clock = 1_700_000_000_000L

    private fun assets() = assumeTrue(
        "production dictionary, T9 table, language model and jianpin table present",
        FullDictTestAssets.available(dictFile, t9File, lmFile, jianpinFile),
    )

    private fun letters(um: UserModel? = null, ul: UserLearning? = null) = PinyinDecoder(
        BinaryDict.fromFile(dictFile),
        CharBigramLM.fromFile(lmFile),
        userModel = um,
        initialsDict = BinaryDict.fromFile(jianpinFile),
        userLearning = ul,
    )

    private fun digits(um: UserModel? = null, ul: UserLearning? = null) = PinyinDecoder(
        BinaryDict.fromFile(t9File),
        CharBigramLM.fromFile(lmFile),
        userModel = um,
        aliasDict = BinaryDict.fromFile(dictFile),
        userLearning = ul,
    )

    private fun chain(vararg steps: Pair<String, String>): UserLearning {
        val learning = UserLearning { clock }
        repeat(8) {
            var prev: String? = null
            for ((word, reading) in steps) {
                learning.observeCommit(prev, word, reading, clock)
                prev = word
            }
            learning.observeBreak()
        }
        return learning
    }

    private fun taught(reading: String, word: String): UserModel =
        UserModel { clock }.apply { repeat(500) { recordWord(reading, word, clock, incrementCount = true) } }

    private fun words(cands: List<Cand>) = cands.map { it.word }

    private fun paths(d: PinyinDecoder, key: String, cuts: Set<Int>): List<Pair<String, List<String>>> = listOf(
        "free" to words(d.decodeCovered(key, 80)),
        "cut" to words(d.decodeCovered(key, 80, cuts)),
        "locked" to words(d.decodeCoveredAtomic(key, 80, cuts)),
    )

    private fun assertLeads(arm: String, lead: String, assembled: String, runs: List<Pair<String, List<String>>>) {
        for ((path, got) in runs) {
            assertEquals("$arm/$path: the dictionary word of this reading leads, was ${got.take(6)}", lead, got.first())
            val at = got.indexOf(assembled)
            assertTrue("$arm/$path: the assembled word may not lead, was at $at", at != 0)
        }
    }

    @Test fun aGluedWordNeverLeadsTheDictionaryWordOfTheSameReadingOnBothKeyboards() {
        assets()
        val learning = chain("你" to "ni", "呢" to "ne", "嗯" to "n")
        assumeTrue("the chain forms the glued word", "你呢嗯" in learning.formedWordsFor("ninen"))
        assertLeads("26-key", "你们", "你呢嗯", paths(letters(ul = learning), "nimen", setOf(2)))
        assertLeads("9-key", "你们", "你呢嗯", paths(digits(ul = learning), "64636", setOf(2)))
    }

    @Test fun theGluedWordStaysReachableRightBehindTheDictionaryWord() {
        assets()
        val learning = chain("我" to "wo", "呢" to "ne", "嗯" to "n", "的" to "de")
        assumeTrue("the chain forms the glued word", "我呢嗯的" in learning.formedWordsFor("wonende"))
        for ((path, got) in paths(digits(ul = learning), "9663633", setOf(2, 5))) {
            assertEquals("9-key/$path: 我们的 leads, was ${got.take(6)}", "我们的", got.first())
            val at = got.indexOf("我呢嗯的")
            assertTrue("9-key/$path: the glued word stays on the first screen, was at $at", at in 1..8)
        }
    }

    @Test fun aTaughtWordTheDictionaryLacksStillYieldsTheLeadToTheDictionaryWord() {
        assets()
        val um = taught("nimen", "拟门")
        for ((path, got) in paths(letters(um = um), "nimen", setOf(2))) {
            assertEquals("26-key/$path: 你们 leads, was ${got.take(6)}", "你们", got.first())
            val at = got.indexOf("拟门")
            assertTrue("26-key/$path: the taught word stays reachable, was at $at", at > 0)
        }
    }

    @Test fun aGluedSentenceNeverLeadsTheDictionaryWordOfTheSameReading() {
        val rows = listOf(
            EngineFixture.Row("diu", "丢", 900),
            EngineFixture.Row("zi", "字", 900),
            EngineFixture.Row("zi", "子", 800),
            EngineFixture.Row("diuzi", "丢子", 40),
        )
        val decoder = PinyinDecoder(EngineFixture.build(rows))
        for ((path, got) in listOf(
            "free" to words(decoder.decodeCovered("diuzi", 30)),
            "cut" to words(decoder.decodeCovered("diuzi", 30, setOf(3))),
            "locked" to words(decoder.decodeCoveredAtomic("diuzi", 30, setOf(3))),
        )) {
            assertEquals("$path: the dictionary word of the reading leads, was $got", "丢子", got.first())
            val at = got.indexOf("丢字")
            assertTrue("$path: the glued sentence stays reachable behind it, was at $at", at > 0)
        }
    }

    @Test fun theSentenceStillLeadsWhenTheDictionaryHasNoWordForTheReading() {
        assets()
        assertTrue("the reading has no dictionary word", BinaryDict.fromFile(dictFile).exact("nidepingguo").none { it.word.length > 1 })
        val staged = letters().decodeCoveredAtomic("nidepingguo", 80, setOf(2, 4, 8))
        assertEquals("the sentence covering every reading still leads", "你的苹果", staged.first().word)
    }
}
