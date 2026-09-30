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
import org.junit.Assert.assertFalse
import org.junit.Test

class SentenceChoicesTest {

    private val grammar = OctagramFixture.reader(mapOf("无关" to 10.0))

    private fun dict(treeFreq: Int, extra: List<EngineFixture.Row> = emptyList()) = EngineFixture.build(
        listOf(
            EngineFixture.Row("mai", "买", 100),
            EngineFixture.Row("mai", "卖", 90),
            EngineFixture.Row("shu", "书", 100),
            EngineFixture.Row("shu", "树", treeFreq),
        ) + extra,
    )

    private fun words(decoder: PinyinDecoder, atomic: Boolean = false) =
        (if (atomic) decoder.decodeCoveredAtomic("maishu", 30) else decoder.decodeCovered("maishu", 30)).map { it.word }

    @Test fun closeSentencesFollowTheBestOne() {
        val decoder = PinyinDecoder(dict(50), octagram = grammar)
        assertEquals(listOf("买书", "卖书", "买树"), words(decoder).take(3))
        assertEquals(listOf("买书", "卖书", "买树"), words(decoder, atomic = true).take(3))
    }

    @Test fun distantSentencesStayOut() {
        val decoder = PinyinDecoder(dict(1), octagram = grammar)
        assertEquals(listOf("买书", "卖书"), words(decoder).take(2))
        assertFalse("买树" in words(decoder))
    }

    @Test fun aDictionaryWordLeadingTheInputBringsNoSentenceChoices() {
        val decoder = PinyinDecoder(
            dict(50, listOf(EngineFixture.Row("maishu", "卖书", 5_000))),
            octagram = OctagramFixture.reader(mapOf("买书" to 30.0)),
        )
        val got = words(decoder)
        assertEquals("卖书", got.first())
        assertFalse("买书" in got)
    }

    @Test fun aDictionaryWordAmongTheRunnersUpStillOutranksTheComposedSentence() {
        val decoder = PinyinDecoder(
            dict(50, listOf(EngineFixture.Row("maishu", "卖书", 500))),
            octagram = OctagramFixture.reader(mapOf("买书" to 30.0)),
        )
        assertEquals(listOf("卖书", "买书"), words(decoder).take(2))
    }

    @Test fun noGrammarNoSentenceChoices() {
        assertFalse("卖书" in words(PinyinDecoder(dict(50))))
    }
}
