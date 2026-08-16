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
import java.io.File
import kotlin.math.exp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class HomophoneDrillAgreementTest {

    private val dictFile = File("src/main/assets/aegis_dict.bin")
    private val t9File = File("src/main/assets/aegis_t9.bin")
    private val lmFile = File("src/main/assets/aegis_lm.bin")

    private val dict: BinaryDict by lazy { BinaryDict.fromFile(dictFile) }
    private val t9Dict: BinaryDict by lazy { BinaryDict.fromFile(t9File) }
    private val model: CharBigramLM by lazy { CharBigramLM.fromFile(lmFile) }
    private val letter: PinyinDecoder by lazy { PinyinDecoder(dict, model) }
    private val t9: PinyinDecoder by lazy { PinyinDecoder(t9Dict, model, aliasDict = dict) }

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
        val counted = model.unigramCount(word.codePointAt(0))
        val commonness = if (counted > 0L) counted.toDouble() else value
        val band = when {
            commonness >= COMMON_FREQ -> COMMON_LAYER
            commonness <= RARE_FREQ -> RARE_LAYER
            else -> UNCOMMON_LAYER
        }
        val core = isSingle(word) && word.codePointAt(0) in 0x4E00..0x9FFF
        return if (core) band else maxOf(band, UNCOMMON_LAYER)
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
            .sortedBy { expectedLayer(it.first, it.second) }
            .map { it.first }
    }

    private fun letterExpectation(key: String) = expectation(dict, key)

    private fun t9Expectation(key: String) = expectation(t9Dict, key)

    @Suppress("UNCHECKED_CAST")
    private fun runtimeSyllables(): List<String> {
        val f = T9Pinyin::class.java.getDeclaredField("SYLLABLES")
        f.isAccessible = true
        val syls = (f.get(T9Pinyin) as Set<String>).toList().sorted()
        assertTrue("runtime SYLLABLES ~415 (drift guard): ${syls.size}", syls.size in 400..430)
        return syls
    }

    private fun assumeAssets() =
        assumeTrue("full dict assets present", dictFile.exists() && t9File.exists() && lmFile.exists())

    @Test fun drillMatchesTheWholeSyllableKeyOnBothDictionaries() {
        assumeAssets()
        val mismatches = ArrayList<String>()
        for (s in runtimeSyllables()) {
            val expected26 = letterExpectation(s)
            if (letter.homophonesAt(s, 0) != expected26) mismatches.add("26-key $s")
            val digits = T9Pinyin.toT9(s)
            val expected9 = t9Expectation(digits)
            if (t9.homophonesAt(digits, 0) != expected9) mismatches.add("9-key $s($digits)")
        }
        assertEquals("drill must match the independently ranked syllable key: $mismatches", emptyList<String>(), mismatches)
    }

    @Test fun drillHonoursLockedCutsOnBothLayouts() {
        assumeAssets()
        val mismatches = ArrayList<String>()
        for (s in runtimeSyllables()) {
            val letters = s + "hao"
            val letterCuts = setOf(s.length)
            if (letter.homophonesAt(letters, 0, letterCuts) != letterExpectation(s)) {
                mismatches.add("26-key $letters span0")
            }
            if (letter.homophonesAt(letters, 1, letterCuts) != letterExpectation("hao")) {
                mismatches.add("26-key $letters span1")
            }
            val d0 = T9Pinyin.toT9(s)
            val digits = d0 + T9Pinyin.toT9("hao")
            val digitCuts = setOf(d0.length)
            if (t9.homophonesAt(digits, 0, digitCuts) != t9Expectation(d0)) {
                mismatches.add("9-key $digits span0")
            }
            if (t9.homophonesAt(digits, 1, digitCuts) != t9Expectation(T9Pinyin.toT9("hao"))) {
                mismatches.add("9-key $digits span1")
            }
        }
        assertEquals("drill must return the locked segment's homophones: ${mismatches.take(8)}", emptyList<String>(), mismatches)
    }

    @Test fun drillSpansAgreeWithTheAtomicDecodeOnEverySyllableKey() {
        assumeAssets()
        val mismatches = ArrayList<String>()
        for (s in runtimeSyllables()) {
            val digits = T9Pinyin.toT9(s)
            for ((decoder, input, tag) in listOf(
                Triple(letter, s, "26-key"),
                Triple(t9, digits, "9-key"),
            )) {
                val spans = decoder.syllables(input)
                if (spans.size != 1 || spans[0].start != 0 || spans[0].end != input.length) {
                    mismatches.add("$tag $input spans=$spans")
                    continue
                }
                val drill = decoder.homophonesAt(input, 0).toSet()
                val emitted = decoder.decodeCoveredAtomic(input, 30)
                val emittedSingles = emitted
                    .filter { it.word.codePointCount(0, it.word.length) == 1 && it.coveredLen == input.length }
                    .map { it.word }
                    .toSet()
                if (emittedSingles != drill) {
                    mismatches.add("$tag $input decode/drill differ: ${(drill - emittedSingles).take(4)} / ${(emittedSingles - drill).take(4)}")
                }
            }
        }
        assertEquals("atomic decode and drill must share one segmentation: ${mismatches.take(8)}", emptyList<String>(), mismatches)
    }

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
        const val COMMON_FREQ = 1000.0
        const val RARE_FREQ = 100.0
        const val INJECTED_FREQ = 1.0
        const val COMMON_LAYER = 0
        const val UNCOMMON_LAYER = 1
        const val RARE_LAYER = 2
        const val INJECTED_LAYER = 3
        val ALIAS_DISCOUNT = exp(-3.5)
    }
}
