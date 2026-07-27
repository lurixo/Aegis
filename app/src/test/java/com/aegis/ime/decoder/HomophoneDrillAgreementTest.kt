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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class HomophoneDrillAgreementTest {

    private val dictFile = File("src/main/assets/aegis_dict.bin")
    private val t9File = File("src/main/assets/aegis_t9.bin")
    private val lmFile = File("src/main/assets/aegis_lm.bin")

    private val dict: BinaryDict by lazy { BinaryDict.fromFile(dictFile) }
    private val letter: PinyinDecoder by lazy { PinyinDecoder(dict, CharBigramLM.fromFile(lmFile)) }
    private val t9: PinyinDecoder by lazy {
        PinyinDecoder(BinaryDict.fromFile(t9File), CharBigramLM.fromFile(lmFile), aliasDict = dict)
    }

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
            val expected26 = letter.homophoneFreqs(s).map { it.first }
            if (letter.homophonesAt(s, 0) != expected26) mismatches.add("26-key $s")
            val digits = T9Pinyin.toT9(s)
            val expected9 = t9.homophoneFreqs(digits).map { it.first }
            if (t9.homophonesAt(digits, 0) != expected9) mismatches.add("9-key $s($digits)")
        }
        assertEquals("drill must agree with homophoneFreqs on every syllable key: $mismatches", emptyList<String>(), mismatches)
    }

    @Test fun drillHonoursLockedCutsOnBothLayouts() {
        assumeAssets()
        val mismatches = ArrayList<String>()
        for (s in runtimeSyllables()) {
            val letters = s + "hao"
            val letterCuts = setOf(s.length)
            if (letter.homophonesAt(letters, 0, letterCuts) != letter.homophoneFreqs(s).map { it.first }) {
                mismatches.add("26-key $letters span0")
            }
            if (letter.homophonesAt(letters, 1, letterCuts) != letter.homophoneFreqs("hao").map { it.first }) {
                mismatches.add("26-key $letters span1")
            }
            val d0 = T9Pinyin.toT9(s)
            val digits = d0 + T9Pinyin.toT9("hao")
            val digitCuts = setOf(d0.length)
            if (t9.homophonesAt(digits, 0, digitCuts) != t9.homophoneFreqs(d0).map { it.first }) {
                mismatches.add("9-key $digits span0")
            }
            if (t9.homophonesAt(digits, 1, digitCuts) != t9.homophoneFreqs(T9Pinyin.toT9("hao")).map { it.first }) {
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
        assertEquals(letter.homophoneFreqs("xi").map { it.first }, letter.homophonesAt("xian", 0, setOf(2)))
        assertEquals(letter.homophoneFreqs("an").map { it.first }, letter.homophonesAt("xian", 1, setOf(2)))
    }

    @Test fun midRemnantDoesNotShiftTheDisplayedDrillIndex() {
        assumeAssets()
        val cuts = setOf(3)
        assertEquals(listOf("ni", "hao"), letter.syllables("niihao", cuts).map { it.reading })
        assertEquals(
            letter.homophoneFreqs("hao").map { it.first },
            letter.homophonesAt("niihao", 1, cuts),
        )
        assertEquals(emptyList<String>(), letter.homophonesAt("niihao", 2, cuts))
    }
}
