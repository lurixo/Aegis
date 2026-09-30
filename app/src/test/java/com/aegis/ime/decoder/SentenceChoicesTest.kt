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
import com.aegis.ime.engine.DictEngine
import com.aegis.ime.ime.ImeHost
import com.aegis.ime.ime.KeyboardController
import com.aegis.ime.layout.Key
import com.aegis.ime.user.UserModel
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

    @Test fun aPickedSentenceChoiceLeadsTheSameInputNextTime() {
        val commits = ArrayList<String>()
        val host = object : ImeHost {
            override fun commitText(text: CharSequence) { commits.add(text.toString()) }
            override fun deleteBackward() {}
            override fun performEnter() {}
        }
        val collocations = OctagramFixture.reader(mapOf("买书" to 30.0, "卖书" to 30.0))
        val controller = KeyboardController(host, DictEngine(dict(50), null, null, userModel = UserModel(), octagram = collocations))
        fun type() = "maishu".forEach { controller.onKey(Key(it.toString(), output = it.toString())) }
        type()
        assertEquals("卖书", controller.candidateWords()[1])
        controller.onPickCandidate(1)
        assertEquals(listOf("卖书"), commits)
        type()
        assertEquals("卖书", controller.candidateWords().first())
    }

    @Test fun aSentenceChoiceAlreadyLearnedAsAWordStillFollowsTheBestOne() {
        val userModel = UserModel().apply { recordWord("maishu", "卖书", 0L, incrementCount = false) }
        val rows = listOf(
            EngineFixture.Row("mai", "买", 100),
            EngineFixture.Row("mai", "卖", 7),
            EngineFixture.Row("shu", "书", 100),
            EngineFixture.Row("shu", "树", 50),
        )
        val collocations = OctagramFixture.reader(mapOf("买书" to 30.0, "卖书" to 30.0, "买树" to 30.0))
        val decoder = PinyinDecoder(EngineFixture.build(rows), userModel = userModel, octagram = collocations)
        assertEquals(listOf("买书", "卖书", "买树"), words(decoder).take(3))
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
