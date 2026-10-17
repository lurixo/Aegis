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
import com.aegis.ime.dict.OctagramReader
import com.aegis.ime.engine.T9_FUZZY_PENALTY
import com.aegis.ime.engine.T9_GRAMMAR_CHAR_PENALTY
import com.aegis.ime.user.UserLearning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class LatticeLearningTest {

    private val dictFile = FullDictTestAssets.file(FullDictTestAssets.DICT)
    private val t9File = FullDictTestAssets.file(FullDictTestAssets.T9)
    private val lmFile = FullDictTestAssets.file(FullDictTestAssets.LM)
    private val gramFile = System.getenv("AEGIS_GRAM")?.takeIf { it.isNotEmpty() }?.let { File(it) }

    private val clock = 1_700_000_000_000L

    private val readings = listOf("wojintianhengaoxing", "tamenzaijialikandianshi", "mingtianwomenyiqiquchifan")

    private val contexts = listOf("", "昨天开会的时候他说")

    private fun assetsPresent(): Boolean =
        dictFile.exists() && t9File.exists() && lmFile.exists() && gramFile?.exists() == true

    private fun decoder(nine: Boolean, learning: UserLearning, gram: OctagramReader): PinyinDecoder =
        if (nine) {
            PinyinDecoder(
                BinaryDict.fromFile(t9File),
                CharBigramLM.fromFile(lmFile),
                octagram = gram,
                aliasDict = BinaryDict.fromFile(dictFile),
                userLearning = learning,
                fuzzyVariants = { s, rules -> T9Pinyin.fuzzyVariants(s, rules) },
                fuzzyPenalty = T9_FUZZY_PENALTY,
                grammarCharPenalty = T9_GRAMMAR_CHAR_PENALTY,
            )
        } else {
            PinyinDecoder(BinaryDict.fromFile(dictFile), CharBigramLM.fromFile(lmFile), octagram = gram, userLearning = learning)
        }

    private fun sentences(decoder: PinyinDecoder, nine: Boolean): List<String> {
        val out = ArrayList<String>()
        for (reading in readings) for (context in contexts) {
            val input = if (nine) T9Pinyin.toT9(reading) else reading
            out.add("$input|$context|" + decoder.rankedSentences(input, context, 20))
        }
        return out
    }

    private fun candidates(decoder: PinyinDecoder, nine: Boolean): List<String> {
        val out = ArrayList<String>()
        for (reading in readings) for (context in contexts) {
            val input = if (nine) T9Pinyin.toT9(reading) else reading
            out.add("$input|$context|" + decoder.decodeCovered(input, 30, context = context))
        }
        return out
    }

    private fun teach(learning: UserLearning) {
        repeat(3) {
            learning.observeCommit(null, "我", "wo", clock)
            learning.observeCommit("我", "今天", "jintian", clock)
        }
    }

    @Test fun anEmptyLearningStoreScoresSentencesLikeADisabledOne() {
        assumeTrue("full dictionaries and the grammar are present", assetsPresent())
        val gram = OctagramReader.fromFile(gramFile!!)
        for (nine in listOf(false, true)) {
            val empty = decoder(nine, UserLearning { clock }, gram)
            val disabled = decoder(nine, UserLearning { clock }.apply { enabled = false }, gram)
            assertEquals("sentences, nine=$nine", sentences(disabled, nine), sentences(empty, nine))
            assertEquals("candidates, nine=$nine", candidates(disabled, nine), candidates(empty, nine))
        }
    }

    @Test fun wordsLearnedAfterADecodeCountInTheNextOne() {
        assumeTrue("full dictionaries and the grammar are present", assetsPresent())
        val gram = OctagramReader.fromFile(gramFile!!)
        for (nine in listOf(false, true)) {
            val late = UserLearning { clock }
            val warm = decoder(nine, late, gram)
            val before = sentences(warm, nine)
            teach(late)
            val after = sentences(warm, nine)
            val taught = UserLearning { clock }.also { teach(it) }
            assertEquals("nine=$nine", sentences(decoder(nine, taught, gram), nine), after)
            assertNotEquals("nine=$nine", before, after)
        }
    }
}
