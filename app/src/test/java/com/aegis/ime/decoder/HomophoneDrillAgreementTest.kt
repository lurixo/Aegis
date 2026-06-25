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
import com.aegis.ime.dict.TghGrading
import java.io.File
import kotlin.math.exp
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

class HomophoneDrillAgreementTest {

    private val dictFile = File("src/main/assets/aegis_dict.bin")
    private val t9File = File("src/main/assets/aegis_t9.bin")
    private val lmFile = File("src/main/assets/aegis_lm.bin")

    private val dict: BinaryDict by lazy { BinaryDict.fromFile(dictFile) }
    private val model: CharBigramLM by lazy { CharBigramLM.fromFile(lmFile) }
    private val grading: TghGrading = TghGrading.bundled
    private val letter: PinyinDecoder by lazy { PinyinDecoder(dict, model) }

    private fun isSingle(word: String) = word.codePointCount(0, word.length) == 1

    private fun tieRank(word: String) =
        if (isSingle(word) && Character.isSupplementaryCodePoint(word.codePointAt(0))) 1 else 0

    private fun preferred(entries: List<BinaryDict.WordFreq>) =
        entries.sortedWith(compareByDescending<BinaryDict.WordFreq> { it.freq }.thenBy { tieRank(it.word) })

    private fun aliasesFor(key: String): List<String> = when (key) {
        "en", T9Pinyin.toT9("en") -> listOf("ng")
        else -> emptyList()
    }

    private fun expectedLayer(word: String, value: Double): Int {
        if (value <= INJECTED_FREQ) return INJECTED_LAYER
        if (!isSingle(word)) return UNCOMMON_LAYER
        val codePoint = word.codePointAt(0)
        val band = when (grading.level(codePoint)) {
            1 -> COMMON_LAYER
            2 -> UNCOMMON_LAYER
            3 -> SPECIALIZED_LAYER
            else -> if (codePoint >= EXTENSION_B_FLOOR) EXTENSION_LAYER else RARE_LAYER
        }
        if (band < RARE_LAYER) return band
        return if (model.unigramRank(codePoint) <= GENERAL_USE_CARDINALITY) band - 1 else band
    }

    private fun expectedInLayerKey(word: String): Int {
        if (!isSingle(word)) return CORPUS_BAND_OUT
        val rank = model.unigramRank(word.codePointAt(0))
        return when {
            rank <= LEVEL1_CARDINALITY -> 0
            rank <= GENERAL_USE_CARDINALITY -> 1
            rank <= TABLE_CARDINALITY -> 2
            else -> CORPUS_BAND_OUT
        }
    }

    private fun expectation(source: BinaryDict, key: String): List<String> {
        val seen = HashSet<String>()
        val supply = ArrayList<Pair<String, Double>>()
        for (wf in preferred(source.exact(key))) {
            if (isSingle(wf.word) && seen.add(wf.word)) supply.add(wf.word to wf.freq.toDouble())
        }
        val aliasHits = ArrayList<BinaryDict.WordFreq>()
        val aliasSeen = HashSet<String>()
        for (alias in aliasesFor(key)) for (wf in dict.exact(alias)) if (aliasSeen.add(wf.word)) aliasHits.add(wf)
        for (wf in preferred(aliasHits)) {
            if (isSingle(wf.word) && seen.add(wf.word)) supply.add(wf.word to wf.freq * ALIAS_DISCOUNT)
        }
        return supply
            .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { tieRank(it.first) })
            .sortedWith(
                compareBy<Pair<String, Double>> { expectedLayer(it.first, it.second) }
                    .thenBy { expectedInLayerKey(it.first) },
            )
            .map { it.first }
    }

    private fun letterExpectation(key: String) = expectation(dict, key)

    private fun assumeAssets() =
        assumeTrue("full dict assets present", dictFile.exists() && t9File.exists() && lmFile.exists())

    @Test fun cutAwareDrillReachesSpansTheFreeSegmentationMerges() {
        assumeAssets()
        assertEquals(listOf("xian"), letter.syllables("xian").map { it.reading })
        assertEquals(emptyList<String>(), letter.homophonesAt("xian", 1))
        val spans = letter.syllables("xian", setOf(2))
        assertEquals(listOf("xi", "an"), spans.map { it.reading })
        assertEquals(letterExpectation("xi"), letter.homophonesAt("xian", 0, setOf(2)))
        assertEquals(letterExpectation("an"), letter.homophonesAt("xian", 1, setOf(2)))
    }

    @Test fun midRemnantDoesNotShiftTheDisplayedDrillIndex() {
        assumeAssets()
        val cuts = setOf(3)
        assertEquals(listOf("ni", "hao"), letter.syllables("niihao", cuts).map { it.reading })
        assertEquals(
            letterExpectation("hao"),
            letter.homophonesAt("niihao", 1, cuts),
        )
        assertEquals(emptyList<String>(), letter.homophonesAt("niihao", 2, cuts))
    }

    private companion object {
        const val INJECTED_FREQ = 1.0
        const val COMMON_LAYER = 0
        const val UNCOMMON_LAYER = 1
        const val SPECIALIZED_LAYER = 2
        const val RARE_LAYER = 3
        const val EXTENSION_LAYER = 4
        const val INJECTED_LAYER = 5
        const val CORPUS_BAND_OUT = 3
        const val EXTENSION_B_FLOOR = 0x20000
        const val LEVEL1_CARDINALITY = 3500
        const val GENERAL_USE_CARDINALITY = 6500
        const val TABLE_CARDINALITY = 8105
        val ALIAS_DISCOUNT = exp(-3.5)
    }
}
