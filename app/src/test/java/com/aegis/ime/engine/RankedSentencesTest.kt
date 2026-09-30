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

package com.aegis.ime.engine

import com.aegis.ime.decoder.EngineFixture
import com.aegis.ime.dict.OctagramFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RankedSentencesTest {

    private val letters = EngineFixture.build(
        listOf(
            EngineFixture.Row("xian", "先", 100),
            EngineFixture.Row("xian", "现", 80),
            EngineFixture.Row("xi", "西", 100),
            EngineFixture.Row("an", "安", 100),
        ),
    )
    private val nineKey = EngineFixture.build(
        listOf(
            EngineFixture.Row("9426", "先", 100),
            EngineFixture.Row("9426", "现", 80),
            EngineFixture.Row("94", "西", 100),
            EngineFixture.Row("26", "安", 100),
        ),
    )

    private fun engine(withGrammar: Boolean) = DictEngine(
        letters,
        nineKey,
        null,
        octagram = if (withGrammar) OctagramFixture.reader(mapOf("西安" to 30.0)) else null,
    )

    @Test fun theFirstRankedSentenceIsTheSentenceCandidate() {
        val e = engine(withGrammar = true)
        for ((input, nine) in listOf("xian" to false, "9426" to true)) {
            val ranked = e.rankedSentences(input, nine, "", 10)
            assertTrue("$input: $ranked", ranked.size >= 2)
            assertEquals(e.candidatesCovered(input, nine).first().word, ranked.first().first)
            assertEquals(ranked.map { it.first }.distinct(), ranked.map { it.first })
            assertEquals(ranked.sortedByDescending { it.second }, ranked)
        }
    }

    @Test fun theLimitCapsThePaths() {
        assertEquals(1, engine(withGrammar = true).rankedSentences("xian", false, "", 1).size)
    }

    @Test fun nothingIsRankedWithoutTheGrammarModel() {
        assertTrue(engine(withGrammar = false).rankedSentences("xian", false, "", 10).isEmpty())
    }

    @Test fun separatedInputIsNotRanked() {
        assertTrue(engine(withGrammar = true).rankedSentences("xi'an", false, "", 10).isEmpty())
    }
}
