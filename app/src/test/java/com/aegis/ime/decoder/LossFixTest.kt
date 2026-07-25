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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class LossFixTest {

    private val dictFile = File("src/main/assets/aegis_dict.bin")
    private val t9File = File("src/main/assets/aegis_t9.bin")
    private val lmFile = File("src/main/assets/aegis_lm.bin")
    private val jianpinFile = File("src/main/assets/aegis_jianpin.bin")

    private fun letterDecoder(): PinyinDecoder {
        assumeTrue("26-key dict + LM assets present", dictFile.exists() && lmFile.exists())
        val initials = if (jianpinFile.exists()) BinaryDict.fromFile(jianpinFile) else null
        return PinyinDecoder(BinaryDict.fromFile(dictFile), CharBigramLM.fromFile(lmFile), initialsDict = initials)
    }

    private fun t9Decoder(): PinyinDecoder {
        assumeTrue("T9 dict + LM assets present", t9File.exists() && lmFile.exists())
        return PinyinDecoder(BinaryDict.fromFile(t9File), CharBigramLM.fromFile(lmFile))
    }

    private fun isSingleChar(word: String): Boolean = word.codePointCount(0, word.length) == 1

    private fun dictSingles(dict: BinaryDict, key: String): Set<String> =
        dict.exact(key).filter { isSingleChar(it.word) }.map { it.word }.toSet()

    private fun allSingles(cands: List<Cand>): Set<String> =
        cands.filter { isSingleChar(it.word) }.map { it.word }.toSet()

    private fun singlesAt(cands: List<Cand>, coveredLen: Int): List<String> =
        cands.filter { isSingleChar(it.word) && it.coveredLen == coveredLen }.map { it.word }.distinct()


    @Test fun firstSyllableHomophonesCompleteWhenAlone() {
        assumeTrue("dict asset present", dictFile.exists())
        val dict = BinaryDict.fromFile(dictFile)
        val he = dictSingles(dict, "he")
        assumeTrue("dict present with he homophones", he.size >= 8)
        val d = letterDecoder()
        val primary = d.decodeCovered("he", 30)
        assertTrue("primary list respects its requested bound", primary.size <= 30)
        assertEquals("he drill lists every 同音字 the dict holds", he, d.homophonesAt("he", 0).toSet())
        assertTrue("和 present in the primary list", "和" in allSingles(primary))
    }

    @Test fun firstSyllableHomophonesStayCompleteInLongerBuffer() {
        assumeTrue("dict asset present", dictFile.exists())
        val dict = BinaryDict.fromFile(dictFile)
        val he = dictSingles(dict, "he")
        assumeTrue("dict present with he homophones", he.size >= 8)
        val d = letterDecoder()
        assertTrue("primary list respects its requested bound", d.decodeCovered("heshui", 30).size <= 30)
        assertEquals("every he 同音字 stays reachable in the drill", he, d.homophonesAt("heshui", 0).toSet())
    }

    @Test fun firstSyllableHomophonesCompleteInThreeSyllableBuffer() {
        assumeTrue("dict asset present", dictFile.exists())
        val dict = BinaryDict.fromFile(dictFile)
        val gan = dictSingles(dict, "gan")
        assumeTrue("dict present with gan homophones", gan.size >= 8)
        val d = letterDecoder()
        val got = d.homophonesAt("ganxienin", 0).toSet()
        assertEquals("gan-position 单字 complete in a 3-syllable drill", gan, got)
        assertTrue("感 present", "感" in got); assertTrue("赶 present", "赶" in got)
    }


    @Test fun everySyllablePositionExposesAllHomophones() {
        assumeTrue("dict asset present", dictFile.exists())
        val dict = BinaryDict.fromFile(dictFile)
        val d = letterDecoder()
        assertEquals("syllable 0 = he → the dict's full he set", dictSingles(dict, "he"), d.homophonesAt("heshui", 0).toSet())
        assertEquals("syllable 1 = shui → the dict's full shui set", dictSingles(dict, "shui"), d.homophonesAt("heshui", 1).toSet())
        assertTrue("水 reachable at the 2nd syllable (the structurally-missing case)", "水" in d.homophonesAt("heshui", 1))

        val input = "ganxieninxuanzejiayichanpin"
        val syls = d.syllables(input)
        assertEquals(9, syls.size)
        assertEquals("gan", syls.first().reading)
        assertEquals("pin", syls.last().reading)
        assertEquals("first syllable gan → full set", dictSingles(dict, "gan"), d.homophonesAt(input, 0).toSet())
        assertEquals("last syllable pin → full set", dictSingles(dict, "pin"), d.homophonesAt(input, syls.size - 1).toSet())
        assertTrue("品 reachable at the LAST syllable position", "品" in d.homophonesAt(input, syls.size - 1))
        assertTrue("out-of-range index → empty, no crash", d.homophonesAt(input, 99).isEmpty())
    }


    @Test fun letterSegmenterSplitsCorrectly() {
        val d = letterDecoder()
        assertEquals(listOf("he", "shui"), d.syllables("heshui").map { it.reading })
        assertEquals(listOf("ni", "hao"), d.syllables("nihao").map { it.reading })
        assertEquals("must not split into xi'an", listOf("xian"), d.syllables("xian").map { it.reading })
        val long = d.syllables("ganxieninxuanzejiayichanpin").map { it.reading }
        assertEquals(9, long.size)
        assertEquals("gan", long.first()); assertEquals("pin", long.last())
        assertTrue(long.containsAll(listOf("xie", "nin", "xuan", "ze", "jia", "yi", "chan")))
        val syl = d.syllables("heshui")
        assertEquals(0, syl[0].start); assertEquals(2, syl[0].end)
        assertEquals(2, syl[1].start); assertEquals(6, syl[1].end)
    }


    @Test fun ambiguousLeadingSyllablesAllReachable() {
        assumeTrue("dict asset present", dictFile.exists())
        val dict = BinaryDict.fromFile(dictFile)
        val d = letterDecoder()

        assertEquals("all xian 同音字 reachable", dictSingles(dict, "xian"), d.homophonesAt("xian", 0).toSet())
        assertEquals("all xi 同音字 reachable after an explicit split", dictSingles(dict, "xi"), d.homophonesAt("xi'an", 0).toSet())
        assertTrue("现 reachable", "现" in d.homophonesAt("xian", 0))
        assertTrue("西 reachable", "西" in d.homophonesAt("xi'an", 0))

        assertEquals("all fang 同音字 reachable", dictSingles(dict, "fang"), d.homophonesAt("fang'an", 0).toSet())
        assertEquals("all fan 同音字 reachable", dictSingles(dict, "fan"), d.homophonesAt("fan'gan", 0).toSet())
        assertEquals("all fa 同音字 reachable", dictSingles(dict, "fa"), d.homophonesAt("fa", 0).toSet())
        assertTrue("方 reachable", "方" in d.homophonesAt("fang'an", 0))
        assertTrue("反 reachable", "反" in d.homophonesAt("fan'gan", 0))
        assertTrue("发 reachable", "发" in d.homophonesAt("fa", 0))
    }


    @Test fun homophoneDrillRemainsCompleteWhenPrimaryIsCapped() {
        assumeTrue("dict asset present", dictFile.exists())
        val dict = BinaryDict.fromFile(dictFile)
        val he = dictSingles(dict, "he")
        assumeTrue("full dict present", he.size > 8)
        val d = letterDecoder()
        val primary = d.decodeCovered("heshui", 30)
        assertTrue("primary list respects its requested bound", primary.size <= 30)
        assertTrue("primary still carries a useful leading single layer", allSingles(primary).count { it in he } > 8)
        assertEquals("drill contains the dict's entire he set", he, d.homophonesAt("heshui", 0).toSet())
    }


    @Test fun wordLayerQualityUnchanged() {
        val d = letterDecoder()
        assertEquals("best sentence still leads", "你好", d.decodeCovered("nihao", 30).firstOrNull()?.word)
        assertTrue("multi-char prefix word 喝水 still surfaces (★G word layer intact)",
            d.decodeCovered("heshui", 30).any { it.word == "喝水" })
        val cands = d.decodeCovered("heshui", 30)
        val firstSingleIdx = cands.indexOfFirst { isSingleChar(it.word) }
        val heShuiWordIdx = cands.indexOfFirst { it.word == "喝水" }
        assertTrue("word candidates precede the appended 单字 layer", heShuiWordIdx in 0 until firstSingleIdx)
    }


    @Test fun t9PathAlsoLossless() {
        assumeTrue("t9 asset present", t9File.exists())
        val t9 = BinaryDict.fromFile(t9File)
        val d = t9Decoder()
        val digits = "437484"
        val group43 = dictSingles(t9, "43")
        val primary = d.decodeCovered(digits, 30)
        val got = allSingles(primary)
        assertTrue("T9 primary list respects its requested bound", primary.size <= 30)
        assertTrue("和 present on the T9 single-char layer", "和" in got)
        assertTrue("T9 first-syllable primary layer remains useful (>8)", got.count { it in group43 } > 8)
        assertEquals("homophonesAt = the dict's full 43-group set", group43, d.homophonesAt(digits, 0).toSet())
    }


    @Test fun robustOnEmptyAndNonPinyin() {
        val d = letterDecoder()
        assertTrue(d.syllables("").isEmpty())
        assertTrue(d.homophonesAt("", 0).isEmpty())
        assertTrue(d.homophonesAt("he", 5).isEmpty())
        assertTrue("a non-pinyin run has no syllables", d.syllables("zzz").isEmpty())
        assertFalse("decoding a non-pinyin run must not throw", d.decodeCovered("zzz", 30).any { it.word == "他" })
    }
}
