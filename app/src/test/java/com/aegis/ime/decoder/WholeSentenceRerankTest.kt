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

import com.aegis.ime.dict.OctagramFixture
import org.junit.Assert.assertEquals
import org.junit.Test

class WholeSentenceRerankTest {

    private val dict = EngineFixture.build(listOf(EngineFixture.Row("yi", "一", 1)))

    @Test fun first128PathsRerankByTheBestWholeSentenceSuffix() {
        val grammar = OctagramFixture.reader(
            mapOf(
                "超市买东西" to 700.0,
                "候选128" to 900.0,
            ),
        )
        val decoder = PinyinDecoder(dict, octagram = grammar)
        val paths = (0 until 140).map {
            val text = if (it == 65) "去超市买东西" else "候选$it"
            PinyinDecoder.SentencePath(text, 200.0 - it)
        }
        val reranked = decoder.rerankSentencePaths(paths, "")
        assertEquals(140, reranked.size)
        assertEquals("去超市买东西", reranked.first().text)
        assertEquals(paths.subList(128, paths.size), reranked.subList(128, reranked.size))
    }

    @Test fun equalScoresKeepTheOriginalHeadOrder() {
        val grammar = OctagramFixture.reader(mapOf("无关" to 10.0))
        val decoder = PinyinDecoder(dict, octagram = grammar)
        val paths = (0 until 128).map { PinyinDecoder.SentencePath("候选$it", 1.0) }
        assertEquals(paths, decoder.rerankSentencePaths(paths, ""))
    }

    @Test fun wholeSentenceArmChecksContextSuffixes() {
        val grammar = OctagramFixture.reader(mapOf("超市买东西" to 22.3))
        val decoder = PinyinDecoder(dict, octagram = grammar)
        assertEquals(22.3, decoder.wholeSentenceArm("去超市", "买东西"), 1e-4)
    }

    @Test fun absentGrammarLeavesEveryPathInPlace() {
        val decoder = PinyinDecoder(dict)
        val paths = (0 until 140).map { PinyinDecoder.SentencePath("候选$it", 140.0 - it) }
        assertEquals(paths, decoder.rerankSentencePaths(paths, "上下文"))
    }

    @Test fun atomicSentenceRerankUsesEdgesLongerThanTheRollingTail() {
        val phraseDict = EngineFixture.build(
            listOf(
                EngineFixture.Row("qu", "去", 100),
                EngineFixture.Row("chao", "超", 100),
                EngineFixture.Row("shi", "事", 200),
                EngineFixture.Row("shi", "市", 100),
                EngineFixture.Row("mai", "买", 100),
                EngineFixture.Row("dong", "东", 100),
                EngineFixture.Row("xi", "西", 100),
            ),
        )
        val input = "quchaoshimaidongxi"
        assertEquals("去超事买东西", PinyinDecoder(phraseDict).decodeCoveredAtomic(input, 30).first().word)
        val grammar = OctagramFixture.reader(mapOf("去超市买东西" to 30.0))
        assertEquals(
            "去超市买东西",
            PinyinDecoder(phraseDict, octagram = grammar).decodeCoveredAtomic(input, 30).first().word,
        )
    }
}
