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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Decoder sanity on the JVM against the committed demo dict. */
class PinyinDecoderTest {

    private val dictFile = File("src/main/assets/aegis_dict.bin")

    private fun decoder(): PinyinDecoder {
        assumeTrue("demo dict asset present", dictFile.exists())
        return PinyinDecoder(BinaryDict.fromFile(dictFile))
    }

    private fun dictSingles(key: String): Set<String> =
        BinaryDict.fromFile(dictFile).exact(key)
            .filter { it.word.codePointCount(0, it.word.length) == 1 }
            .map { it.word }
            .toSet()

    @Test
    fun decodesSentences() {
        val d = decoder()
        fun top(s: String) = d.decode(s, 30).firstOrNull()
        assertEquals("测试", top("ceshi"))
        assertEquals("你好世界", top("nihaoshijie"))
        assertEquals("我是中国人", top("woshizhongguoren"))
        assertEquals("北京大学", top("beijingdaxue"))
        assertEquals("输入法", top("shurufa"))
    }

    @Test
    fun enCompatibilityAliasSurfacesNasalInterjection() {
        val d = decoder()

        assertTrue("en should offer 嗯 through the ng compatibility alias", "嗯" in d.decode("en", 30))
        assertTrue(
            "covered en candidates should include 嗯 covering the whole input",
            d.decodeCovered("en", 30).any { it.word == "嗯" && it.coveredLen == 2 },
        )
        assertEquals(listOf("en"), d.syllables("en").map { it.reading })
        assertTrue("en homophone drill should include the compatibility alias 嗯", "嗯" in d.homophonesAt("en", 0))
    }

    @Test
    fun syllabicNasalReadingsStayCompleteSyllables() {
        val d = decoder()

        assertEquals(listOf("ng"), d.syllables("ng").map { it.reading })
        assertTrue(
            "ng should offer 嗯 and cover the complete reading",
            d.decodeCovered("ng", 30).any { it.word == "嗯" && it.coveredLen == 2 },
        )
        assertTrue("ng homophones should include 嗯", "嗯" in d.homophonesAt("ng", 0))

        assertEquals(listOf("n"), d.syllables("n").map { it.reading })
        assertTrue(
            "n should remain a source-backed syllabic nasal reading for 嗯",
            d.decodeCovered("n", 30).any { it.word == "嗯" && it.coveredLen == 1 },
        )
    }

    @Test
    fun jiangzhiAndSeparatedJiangZhiShareTheSameSyllablePath() {
        val d = decoder()

        assertEquals(listOf("jiang", "zhi"), d.syllables("jiangzhi").map { it.reading })
        assertEquals(listOf("jiang", "zhi"), d.syllables("jiang'zhi").map { it.reading })

        val jiang = dictSingles("jiang")
        val zhi = dictSingles("zhi")
        assumeTrue("dict has jiang homophones", jiang.isNotEmpty())
        assumeTrue("dict has zhi homophones", zhi.isNotEmpty())

        assertEquals("plain input drills the jiang syllable, not a shorter prefix", jiang, d.homophonesAt("jiangzhi", 0).toSet())
        assertEquals("separated input drills the same jiang syllable", jiang, d.homophonesAt("jiang'zhi", 0).toSet())
        assertEquals("plain input keeps the zhi tail navigable", zhi, d.homophonesAt("jiangzhi", 1).toSet())
        assertEquals("separated input keeps the zhi tail navigable", zhi, d.homophonesAt("jiang'zhi", 1).toSet())
    }

    @Test
    fun selectedXiangUsesOnlyTheChosenReadingInTheAssetDict() {
        val d = decoder()
        val words = d.decodeCoveredAtomic("xiang", 30).map { it.word }

        assumeTrue("asset has common xiang homophones", words.containsAll(listOf("向", "想", "相")))
        assertTrue("common xiang homophones stay prominent", words.take(8).containsAll(listOf("向", "想", "相")))
        assertTrue("selected xiang must not leak xi prefix singles", "西" !in words)
        assertTrue("selected xiang must not leak xian words", "西安" !in words)
    }
}
