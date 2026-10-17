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
import com.aegis.ime.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class DecodeInstantTest {

    private val dictFile = FullDictTestAssets.file(FullDictTestAssets.DICT)
    private val t9File = FullDictTestAssets.file(FullDictTestAssets.T9)
    private val lmFile = FullDictTestAssets.file(FullDictTestAssets.LM)
    private val gramFile = System.getenv("AEGIS_GRAM")?.takeIf { it.isNotEmpty() }?.let { File(it) }

    private val bucketStart = 1_700_000_000_000L

    private val taughtAt = bucketStart - 26L * 60L * 60L * 1000L

    private var now = bucketStart

    private val model = UserModel { now }

    private val learning = UserLearning { now }

    private val readings = listOf("wojintianhengaoxing", "tamenzaijialikandianshi")

    private val contexts = listOf("", "昨天开会的时候他说")

    private fun assetsPresent(): Boolean =
        dictFile.exists() && t9File.exists() && lmFile.exists() && gramFile?.exists() == true

    private fun teach() {
        repeat(3) {
            learning.observeCommit(null, "我", "wo", taughtAt)
            learning.observeCommit("我", "今天", "jintian", taughtAt)
        }
        model.recordWord("gaoxing", "高兴", taughtAt, true)
        model.recordWord("kandianshi", "看电视", taughtAt, true)
    }

    private fun decoder(nine: Boolean, gram: OctagramReader): PinyinDecoder =
        if (nine) {
            PinyinDecoder(
                BinaryDict.fromFile(t9File),
                CharBigramLM.fromFile(lmFile),
                userModel = model,
                octagram = gram,
                aliasDict = BinaryDict.fromFile(dictFile),
                userLearning = learning,
                fuzzyVariants = { s, rules -> T9Pinyin.fuzzyVariants(s, rules) },
                fuzzyPenalty = T9_FUZZY_PENALTY,
                grammarCharPenalty = T9_GRAMMAR_CHAR_PENALTY,
            )
        } else {
            PinyinDecoder(
                BinaryDict.fromFile(dictFile),
                CharBigramLM.fromFile(lmFile),
                userModel = model,
                octagram = gram,
                userLearning = learning,
            )
        }

    private fun decodes(decoder: PinyinDecoder, nine: Boolean): List<String> {
        val out = ArrayList<String>()
        for (reading in readings) for (context in contexts) {
            val input = if (nine) T9Pinyin.toT9(reading) else reading
            val sink = PinyinDecoder.PathSink(20)
            val candidates = decoder.decodeCovered(input, 30, context, sink)
            out.add("$input|$context|${sink.paths}|$candidates|" + decoder.rankedSentences(input, context, 20))
        }
        return out
    }

    @Test fun userScoresAreReadAtTheStartOfTheirTenSecondBucket() {
        teach()
        now = bucketStart + 9_999L
        assertEquals(bucketStart, model.scoreInstant())
        assertEquals(bucketStart, learning.scoreInstant())
        val atEnd = listOf(model.wordBoost("高兴", model.scoreInstant()), learning.followBoost("我", "今天", learning.scoreInstant()))
        now = bucketStart
        assertEquals(listOf(model.wordBoost("高兴"), learning.followBoost("我", "今天")), atEnd)
        now = bucketStart + 10_000L
        assertEquals(bucketStart + 10_000L, model.scoreInstant())
        assertNotEquals(atEnd, listOf(model.wordBoost("高兴"), learning.followBoost("我", "今天")))
    }

    @Test fun decodesInsideOneBucketAgreeBitForBit() {
        assumeTrue("full dictionaries and the grammar are present", assetsPresent())
        val gram = OctagramReader.fromFile(gramFile!!)
        teach()
        for (nine in listOf(false, true)) {
            now = bucketStart + 1_000L
            val warm = decoder(nine, gram)
            val first = decodes(warm, nine)
            now = bucketStart + 9_999L
            assertEquals("nine=$nine", first, decodes(warm, nine))
            now = bucketStart
            assertEquals("nine=$nine", first, decodes(decoder(nine, gram), nine))
        }
    }

    @Test fun aNewBucketScoresAtItsOwnInstant() {
        assumeTrue("full dictionaries and the grammar are present", assetsPresent())
        val gram = OctagramReader.fromFile(gramFile!!)
        teach()
        for (nine in listOf(false, true)) {
            now = bucketStart + 9_999L
            val warm = decoder(nine, gram)
            val before = decodes(warm, nine)
            now = bucketStart + 10_000L
            val after = decodes(warm, nine)
            assertNotEquals("nine=$nine", before, after)
            now = bucketStart + 19_999L
            assertEquals("nine=$nine", after, decodes(decoder(nine, gram), nine))
        }
    }
}
