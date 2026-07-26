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
        val got = allSingles(letterDecoder().decodeCovered("he", 30))
        assertTrue("he alone must list EVERY 同音字 the dict holds", got.containsAll(he))
        assertTrue("和 present", "和" in got)
    }

    @Test fun firstSyllableHomophonesStayCompleteInLongerBuffer() {
        assumeTrue("dict asset present", dictFile.exists())
        val dict = BinaryDict.fromFile(dictFile)
        val he = dictSingles(dict, "he")
        assumeTrue("dict present with he homophones", he.size >= 8)
        val got = allSingles(letterDecoder().decodeCovered("heshui", 30))
        assertTrue("every he 同音字 must stay reachable in a longer buffer (no cap)", got.containsAll(he))
    }

    @Test fun firstSyllableHomophonesCompleteInThreeSyllableBuffer() {
        assumeTrue("dict asset present", dictFile.exists())
        val dict = BinaryDict.fromFile(dictFile)
        val gan = dictSingles(dict, "gan")
        assumeTrue("dict present with gan homophones", gan.size >= 8)
        val got = allSingles(letterDecoder().decodeCovered("ganxienin", 30))
        assertTrue("gan-position 单字 complete in a 3-syllable buffer", got.containsAll(gan))
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

        val xian = allSingles(d.decodeCovered("xian", 30))
        assertTrue("all xian 同音字 reachable", xian.containsAll(dictSingles(dict, "xian")))
        assertTrue("all xi 同音字 reachable (the xi'an reading)", xian.containsAll(dictSingles(dict, "xi")))
        assertTrue("现 reachable", "现" in xian); assertTrue("西 reachable — lost if keyed only off the split", "西" in xian)
        assertTrue("西 tagged coveredLen=2", d.decodeCovered("xian", 30).any { it.word == "西" && it.coveredLen == 2 })

        val fangan = allSingles(d.decodeCovered("fangan", 30))
        assertTrue("all fang 同音字 reachable", fangan.containsAll(dictSingles(dict, "fang")))
        assertTrue("all fan 同音字 reachable (反感)", fangan.containsAll(dictSingles(dict, "fan")))
        assertTrue("all fa 同音字 reachable", fangan.containsAll(dictSingles(dict, "fa")))
        for (c in listOf("方", "反", "发")) assertTrue("$c reachable", c in fangan)
    }


    @Test fun singleCharLayerIsUncapped_mutationGuard() {
        assumeTrue("dict asset present", dictFile.exists())
        val dict = BinaryDict.fromFile(dictFile)
        val he = dictSingles(dict, "he")
        assumeTrue("full dict present", he.size > 8)
        val got = allSingles(letterDecoder().decodeCovered("heshui", 30))
        assertTrue("must exceed the old PREFIX_PER_LEN=8 cap (mutation guard)", got.count { it in he } > 8)
        assertTrue("must contain the dict's ENTIRE he set — a re-imposed cap fails here", got.containsAll(he))
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
        val got = allSingles(d.decodeCovered(digits, 30))
        assertTrue("和 present on the T9 single-char layer", "和" in got)
        assertTrue("T9 first-syllable 单字 uncapped (>8)", got.count { it in group43 } > 8)
        assertTrue("the WHOLE 43-group homophone set is reachable on T9", got.containsAll(group43))
        assertEquals("homophonesAt = the dict's full 43-group set", group43, d.homophonesAt(digits, 0).toSet())
    }


    @Test fun t9WordLayerIsUncapped_mutationGuard() {
        assumeTrue("t9 asset present", t9File.exists())
        val t9 = BinaryDict.fromFile(t9File)
        val d = t9Decoder()
        val digits = "943943"
        val words = t9.exact(digits).filterNot { isSingleChar(it.word) }.map { it.word }.toSet()
        assumeTrue("full dict present", words.size > 8)
        val shown = d.decodeCovered(digits, 30).map { it.word }
        val missing = words - shown.toSet()
        assertTrue("every word the dict holds for '$digits' is reachable; missing $missing", missing.isEmpty())
        assertTrue("写者 reachable", "写者" in shown)
        val firstSingleIdx = shown.indexOfFirst { isSingleChar(it) }
        for (w in words) assertTrue(
            "$w precedes the appended 单字 layer (at ${shown.indexOf(w)}, singles start at $firstSingleIdx)",
            shown.indexOf(w) in 0 until firstSingleIdx,
        )
    }

    @Test fun letterWordLayerIsUncapped_mutationGuard() {
        assumeTrue("dict asset present", dictFile.exists())
        val dict = BinaryDict.fromFile(dictFile)
        val d = letterDecoder()
        val key = "jishi"
        val words = dict.exact(key).filterNot { isSingleChar(it.word) }.map { it.word }.toSet()
        assumeTrue("full dict present", words.size > 8)
        val shown = d.decodeCovered(key, 30).map { it.word }
        val missing = words - shown.toSet()
        assertTrue("every word the dict holds for '$key' is reachable; missing $missing", missing.isEmpty())
        val firstSingleIdx = shown.indexOfFirst { isSingleChar(it) }
        for (w in words) assertTrue(
            "$w precedes the appended 单字 layer (at ${shown.indexOf(w)}, singles start at $firstSingleIdx)",
            shown.indexOf(w) in 0 until firstSingleIdx,
        )
    }

    private fun assertWordLayerPrecedesTheSingleCharLayer(source: BinaryDict, d: PinyinDecoder, key: String) {
        val limit = 30
        val words = source.exact(key).filterNot { isSingleChar(it.word) }.map { it.word }.toSet()
        val singles = dictSingles(source, key)
        assumeTrue("full dict present", words.isNotEmpty() && singles.size > 8)
        val (cands, remainderStart) = d.decodeCoveredLayered(key, limit)
        val shown = cands.map { it.word }
        val missing = words - shown.toSet()
        assertTrue("every word the dict holds for '$key' is reachable; missing $missing", missing.isEmpty())
        for (w in words) assertTrue(
            "$w precedes the appended 单字 layer (at ${shown.indexOf(w)}, the layer starts at $remainderStart)",
            shown.indexOf(w) in 0 until remainderStart,
        )
        val ahead = shown.take(shown.indexOfLast { it in words }).filterNot { it in words }
        assertTrue(
            "only the completion budget may precede the word layer, not the ${singles.size} 单字 of '$key': " +
                "${ahead.size} candidates ahead of the last word, ${ahead.count { it in singles }} of them 单字",
            ahead.size <= PinyinDecoder.completionCap(limit),
        )
    }

    @Test fun t9WordLayerPrecedesTheSingleCharLayer_mutationGuard() {
        assumeTrue("t9 asset present", t9File.exists())
        assertWordLayerPrecedesTheSingleCharLayer(BinaryDict.fromFile(t9File), t9Decoder(), "2264")
    }

    @Test fun letterWordLayerPrecedesTheSingleCharLayer_mutationGuard() {
        assumeTrue("dict asset present", dictFile.exists())
        assertWordLayerPrecedesTheSingleCharLayer(BinaryDict.fromFile(dictFile), letterDecoder(), "xian")
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
