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

/**
 * debug.13 — 连续拼音「单字丢字」 fix (②分层预算 + ①按音节全量补单字), now over the FULL (freq≥1, 14-table)
 * dictionary.
 *
 * Contract proven here: for EVERY syllable position the single-char layer lists the syllable's COMPLETE
 * homophone set — exactly what `dict.exact(syllable)` holds — independent of total buffer length and never
 * crowded out by word/phrase candidates. The bug: 「he」 alone listed all its 单字 but 「heshui」 truncated
 * them (PREFIX_PER_LEN cap).
 *
 * Assertions are **dict-derived** (compare against `BinaryDict.exact`), not hard-coded counts, so they
 * stay correct across dictionary rebuilds while still being a strict losslessness guarantee: a re-imposed
 * cap makes the decoder's set a proper subset of the dict's → `containsAll` fails (mutation guard).
 */
class LossFixTest {

    private val dictFile = File("src/main/assets/aegis_dict.bin")
    private val t9File = File("src/main/assets/aegis_t9.bin")
    private val lmFile = File("src/main/assets/aegis_lm.bin")
    private val jianpinFile = File("src/main/assets/aegis_jianpin.bin")

    /** Production-faithful 26-key decoder: letter dict + char-bigram LM + 简拼 initials (fuzzy off). */
    private fun letterDecoder(): PinyinDecoder {
        assumeTrue("26-key dict + LM assets present", dictFile.exists() && lmFile.exists())
        val initials = if (jianpinFile.exists()) BinaryDict.fromFile(jianpinFile) else null
        return PinyinDecoder(BinaryDict.fromFile(dictFile), CharBigramLM.fromFile(lmFile), initialsDict = initials)
    }

    private fun t9Decoder(): PinyinDecoder {
        assumeTrue("T9 dict + LM assets present", t9File.exists() && lmFile.exists())
        return PinyinDecoder(BinaryDict.fromFile(t9File), CharBigramLM.fromFile(lmFile))
    }

    /** The dictionary's COMPLETE single-char homophone set for an exact syllable key. */
    private fun dictSingles(dict: BinaryDict, key: String): Set<String> =
        dict.exact(key).filter { it.word.length == 1 }.map { it.word }.toSet()

    /** All single-char candidate words in [cands] (any coverage). */
    private fun allSingles(cands: List<Cand>): Set<String> =
        cands.filter { it.word.length == 1 }.map { it.word }.toSet()

    /** Single-char candidates of [cands] covering exactly [coveredLen] input units (one syllable). */
    private fun singlesAt(cands: List<Cand>, coveredLen: Int): List<String> =
        cands.filter { it.word.length == 1 && it.coveredLen == coveredLen }.map { it.word }.distinct()

    // ---- ① 首音节单字完整（与整串长度无关）----

    @Test fun firstSyllableHomophonesCompleteWhenAlone() {
        val dict = BinaryDict.fromFile(dictFile)
        val he = dictSingles(dict, "he")
        assumeTrue("dict present with he homophones", he.size >= 8) // seed≈15, full≈257 — both run
        val got = allSingles(letterDecoder().decodeCovered("he", 30))
        assertTrue("he alone must list EVERY 同音字 the dict holds", got.containsAll(he))
        assertTrue("和 present", "和" in got)
    }

    @Test fun firstSyllableHomophonesStayCompleteInLongerBuffer() {
        // ★ THE FIX: "he" stays COMPLETE inside "heshui" — was truncated to 8 by PREFIX_PER_LEN.
        val dict = BinaryDict.fromFile(dictFile)
        val he = dictSingles(dict, "he")
        assumeTrue("dict present with he homophones", he.size >= 8)
        val got = allSingles(letterDecoder().decodeCovered("heshui", 30))
        // containsAll covers EVERY he 同音字 the dict holds — including the lower-freq ranks the old
        // PREFIX_PER_LEN=8 cap used to drop — whatever the seed/full floor keeps (floor-independent).
        assertTrue("every he 同音字 must stay reachable in a longer buffer (no cap)", got.containsAll(he))
    }

    @Test fun firstSyllableHomophonesCompleteInThreeSyllableBuffer() {
        // 3+ syllables「丢得更狠」path: gan|xie|nin — gan's complete 单字 set must survive, uncrowded.
        val dict = BinaryDict.fromFile(dictFile)
        val gan = dictSingles(dict, "gan")
        assumeTrue("dict present with gan homophones", gan.size >= 8)
        val got = allSingles(letterDecoder().decodeCovered("ganxienin", 30))
        assertTrue("gan-position 单字 complete in a 3-syllable buffer", got.containsAll(gan))
        assertTrue("感 present", "感" in got); assertTrue("赶 present", "赶" in got)
    }

    // ---- ② 结构性：每个音节位都能全量取到（含非首音节）----

    @Test fun everySyllablePositionExposesAllHomophones() {
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

    // ---- 26-key 音节切分（UI-2 依赖；与词库大小无关）----

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

    // ---- 切分歧义无关：每个合法首音节读法的同音字都可达 ----

    @Test fun ambiguousLeadingSyllablesAllReachable() {
        // segmentation-INDEPENDENT: when a leading prefix is ambiguous (xian ⊃ xi, fangan ⊃ fang/fan/fa)
        // EVERY leading syllable's 同音字 must surface — else 西 (西安) / 反 (反感) would be unreachable.
        val dict = BinaryDict.fromFile(dictFile)
        val d = letterDecoder()

        val xian = allSingles(d.decodeCovered("xian", 30))
        assertTrue("all xian 同音字 reachable", xian.containsAll(dictSingles(dict, "xian")))
        assertTrue("all xi 同音字 reachable (the xi'an reading)", xian.containsAll(dictSingles(dict, "xi")))
        assertTrue("现 reachable", "现" in xian); assertTrue("西 reachable — lost if keyed only off the split", "西" in xian)
        // 西 (xi-only) commits 2 letters; 现 (xian-only) commits 4 → partial-commit coverage is right.
        assertTrue("西 tagged coveredLen=2", d.decodeCovered("xian", 30).any { it.word == "西" && it.coveredLen == 2 })

        val fangan = allSingles(d.decodeCovered("fangan", 30))
        assertTrue("all fang 同音字 reachable", fangan.containsAll(dictSingles(dict, "fang")))
        assertTrue("all fan 同音字 reachable (反感)", fangan.containsAll(dictSingles(dict, "fan")))
        assertTrue("all fa 同音字 reachable", fangan.containsAll(dictSingles(dict, "fa")))
        for (c in listOf("方", "反", "发")) assertTrue("$c reachable", c in fangan)
    }

    // ---- 变异守卫：恢复任何 cap 都让此测试挂 ----

    @Test fun singleCharLayerIsUncapped_mutationGuard() {
        val dict = BinaryDict.fromFile(dictFile)
        val he = dictSingles(dict, "he")
        assumeTrue("full dict present", he.size > 8)
        val got = allSingles(letterDecoder().decodeCovered("heshui", 30))
        assertTrue("must exceed the old PREFIX_PER_LEN=8 cap (mutation guard)", got.count { it in he } > 8)
        assertTrue("must contain the dict's ENTIRE he set — a re-imposed cap fails here", got.containsAll(he))
    }

    // ---- 词组/整句质量不回归 ----

    @Test fun wordLayerQualityUnchanged() {
        val d = letterDecoder()
        assertEquals("best sentence still leads", "你好", d.decodeCovered("nihao", 30).firstOrNull()?.word)
        assertTrue("multi-char prefix word 喝水 still surfaces (★G word layer intact)",
            d.decodeCovered("heshui", 30).any { it.word == "喝水" })
        // single chars never crowd out the leading word: the word candidate precedes the appended 单字 layer.
        val cands = d.decodeCovered("heshui", 30)
        val firstSingleIdx = cands.indexOfFirst { it.word.length == 1 }
        val heShuiWordIdx = cands.indexOfFirst { it.word == "喝水" }
        assertTrue("word candidates precede the appended 单字 layer", heShuiWordIdx in 0 until firstSingleIdx)
    }

    // ---- 9键路径同样无损 ----

    @Test fun t9PathAlsoLossless() {
        val t9 = BinaryDict.fromFile(t9File)
        val d = t9Decoder()
        val digits = "437484" // heshui on T9 → leading group "43" (= he + ge readings)
        val group43 = dictSingles(t9, "43")
        val got = allSingles(d.decodeCovered(digits, 30))
        assertTrue("和 present on the T9 single-char layer", "和" in got)
        assertTrue("T9 first-syllable 单字 uncapped (>8)", got.count { it in group43 } > 8)
        assertTrue("the WHOLE 43-group homophone set is reachable on T9", got.containsAll(group43))
        assertEquals("homophonesAt = the dict's full 43-group set", group43, d.homophonesAt(digits, 0).toSet())
    }

    // ---- 健壮性 ----

    @Test fun robustOnEmptyAndNonPinyin() {
        val d = letterDecoder()
        assertTrue(d.syllables("").isEmpty())
        assertTrue(d.homophonesAt("", 0).isEmpty())
        assertTrue(d.homophonesAt("he", 5).isEmpty())
        assertTrue("a non-pinyin run has no syllables", d.syllables("zzz").isEmpty())
        assertFalse("decoding a non-pinyin run must not throw", d.decodeCovered("zzz", 30).any { it.word == "他" })
    }
}
