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
  * Chinese IME behavior note.
 *
  * Chinese IME behavior note.
 * key on 26-key, so the long-press ' is appended as a buffer char). The decoder's core invariant is "the
 * buffer is pure a-z" — a stray ' poisons segmentation, dict lookup AND the lattice, so the entire post-'
 * tail was silently dropped: `decode("chai'ci")` returned [] and `syllables("chai'ci")` lost the "ci"
  * Chinese IME behavior note.
 *
 * The fix treats ' as a hard syllable boundary throughout the decoder (strip → forced cut, coverage remapped
 * back to the original ' -inclusive index). These lock the WHOLE class so it can never silently regress:
  * Chinese IME behavior note.
  * Chinese IME behavior note.
 *
 * Assertions are dict-derived (compare to BinaryDict.exact) so they survive dictionary rebuilds.
 */
class SeparatorLossTest {

    private val dictFile = File("src/main/assets/aegis_dict.bin")
    private val t9File = File("src/main/assets/aegis_t9.bin")
    private val lmFile = File("src/main/assets/aegis_lm.bin")
    private val jianpinFile = File("src/main/assets/aegis_jianpin.bin")

    private fun decoder(): PinyinDecoder {
        assumeTrue("26-key dict + LM assets present", dictFile.exists() && lmFile.exists())
        val initials = if (jianpinFile.exists()) BinaryDict.fromFile(jianpinFile) else null
        return PinyinDecoder(BinaryDict.fromFile(dictFile), CharBigramLM.fromFile(lmFile), initialsDict = initials)
    }

    private fun t9Decoder(): PinyinDecoder {
        assumeTrue("T9 dict + LM assets present", t9File.exists() && lmFile.exists())
        return PinyinDecoder(BinaryDict.fromFile(t9File), CharBigramLM.fromFile(lmFile))
    }

    private fun isSingleChar(word: String): Boolean = word.codePointCount(0, word.length) == 1

    private fun dictSingles(key: String): Set<String> =
        BinaryDict.fromFile(dictFile).exact(key).filter { isSingleChar(it.word) }.map { it.word }.toSet()

    private fun singles(cands: List<Cand>): List<String> =
        cands.filter { isSingleChar(it.word) }.map { it.word }

    // ---- ① the chai'ci case: chai'ci must behave like chaici, the ci syllable must NOT vanish ----

    @Test fun apostropheInputNeverDropsTheTailSyllable() {
        val d = decoder()
        // THE BUG: decode("chai'ci") used to be [] and the "ci" syllable was silently lost.
        val decoded = d.decode("chai'ci", 20)
        assertFalse("decode(chai'ci) must NOT be empty (the 丢字 regression)", decoded.isEmpty())
        assertTrue("拆次 (chai|ci best sentence, honouring the boundary) surfaces", "拆次" in decoded)

        val cov = d.decodeCovered("chai'ci", 30)
        assertTrue("单字「拆」reachable for chai'ci", "拆" in cov.map { it.word })
        // Chinese IME behavior note.
        assertEquals("ci syllable's homophones intact at position 1", dictSingles("ci"), d.homophonesAt("chai'ci", 1).toSet())
        assertTrue("词 reachable as the 2nd syllable (拆+词 buildable)", "词" in d.homophonesAt("chai'ci", 1))
    }

    @Test fun apostropheSegmentationMatchesTheCleanSplit() {
        val d = decoder()
        assertEquals("chai'ci segments into chai|ci (ci was being dropped)", listOf("chai", "ci"), d.syllables("chai'ci").map { it.reading })
        assertEquals("plain chaici segments the same", listOf("chai", "ci"), d.syllables("chaici").map { it.reading })
    }

    @Test fun apostropheAndPlainAreEquivalentForLeadingSingles() {
        val d = decoder()
        // The leading-syllable single-char layer is identical with or without the (redundant) chai|ci boundary.
        val withSep = singles(d.decodeCovered("chai'ci", 30)).filter { it in dictSingles("chai") }
        val plain = singles(d.decodeCovered("chaici", 30)).filter { it in dictSingles("chai") }
        assertEquals("chai homophones identical with/without '", plain, withSep)
        assertTrue("and complete vs the dict", withSep.toSet().containsAll(dictSingles("chai")))
    }

    // Chinese IME behavior note.

    @Test fun apostropheForcesABoundaryThatPlainInputWouldNot() {
        val d = decoder()
        assertEquals("xian alone is ONE syllable", listOf("xian"), d.syllables("xian").map { it.reading })
        assertEquals("xi'an is forced to xi|an", listOf("xi", "an"), d.syllables("xi'an").map { it.reading })
        // Chinese IME behavior note.
        assertTrue("西 reachable under the forced xi|an split", "西" in singles(d.decodeCovered("xi'an", 30)))
        assertEquals("an syllable homophones intact at position 1", dictSingles("an"), d.homophonesAt("xi'an", 1).toSet())
    }

    // Chinese IME behavior note.

    @Test fun everySyllablePositionStaysLosslessWithSeparators() {
        val d = decoder()
        val input = "ni'hao'ma"
        assertEquals(listOf("ni", "hao", "ma"), d.syllables(input).map { it.reading })
        assertEquals(dictSingles("ni"), d.homophonesAt(input, 0).toSet())
        assertEquals(dictSingles("hao"), d.homophonesAt(input, 1).toSet())
        assertEquals(dictSingles("ma"), d.homophonesAt(input, 2).toSet())
        assertTrue("你 reachable", "你" in d.homophonesAt(input, 0))
        assertTrue("好 reachable", "好" in d.homophonesAt(input, 1))
    }

    // Chinese IME behavior note.

    @Test fun commonCharsOutrankRareOnesInTheSingleCharLayer() {
        val d = decoder()
        for (input in listOf("chaici", "chai'ci")) {
            val s = singles(d.decodeCovered(input, 30))
            val chai = s.filter { it in dictSingles("chai") }
            assertTrue("[$input] 拆 present", "拆" in chai)
            // Chinese IME behavior note.
            assertTrue("[$input] 拆 before 钗", chai.indexOf("拆") < chai.indexOf("钗"))
            assertTrue("[$input] 钗 before 豺", chai.indexOf("钗") < chai.indexOf("豺"))
            assertTrue("[$input] 拆 in the visible head (top 8)", "拆" in s.take(8))
        }
    }

    @Test fun pickingTheLeadingCharConsumesThroughTheSeparator() {
        val d = decoder()
        // Chinese IME behavior note.
        val chaiCand = d.decodeCovered("chai'ci", 30).firstOrNull { it.word == "拆" }
        assertTrue("拆 candidate present", chaiCand != null)
        assertEquals("拆 covers chai + the separator (5 of chai'ci)", 5, chaiCand!!.coveredLen)
    }

    @Test fun pickingLeadingCharLeavesACleanTailThatDecodes() {
        // Chinese IME behavior note.
        // Chinese IME behavior note.
        val d = decoder()
        val chai = d.decodeCovered("chai'ci", 30).first { it.word == "拆" }
        val remaining = "chai'ci".substring(chai.coveredLen)
        assertEquals("the tail after committing 拆 is a clean ci, no stray '", "ci", remaining)
        val tail = singles(d.decodeCovered(remaining, 30))
        assertTrue("词 reachable in the tail (拆词 buildable)", "词" in tail)
        assertTrue("次 reachable in the tail", "次" in tail)
    }

    @Test fun t9SharedPathAlsoHonoursSeparators() {
        // 9-key encodes a boundary as a forced cut (a digit buffer never holds a literal '), but the SHARED
        // decode path must still never collapse/lose a tail if a ' ever reaches it. ni=64, hao=426 on T9.
        val d = t9Decoder()
        val syl = d.syllables("64'426").map { it.reading }
        assertTrue("a ' in a T9 buffer keeps BOTH chunks (tail not dropped)", syl.size >= 2)
        assertTrue("no empty syllable", syl.none { it.isEmpty() })
        assertTrue("tail syllable navigable (homophones non-empty)", d.homophonesAt("64'426", syl.size - 1).isNotEmpty())
        assertFalse("decode of a separated T9 buffer is non-empty, no crash", d.decode("64'426", 20).isEmpty())
    }

    // ---- ⑤ robustness: leading / trailing / doubled separators never crash or drop a real syllable ----

    @Test fun robustOnEdgeSeparators() {
        val d = decoder()
        assertEquals("leading ' is ignored", d.syllables("nihao").map { it.reading }, d.syllables("'nihao").map { it.reading })
        assertEquals("trailing ' is ignored", listOf("ni", "hao"), d.syllables("nihao'").map { it.reading })
        assertEquals("doubled '' collapses to one boundary", listOf("chai", "ci"), d.syllables("chai''ci").map { it.reading })
        assertTrue("an all-separator buffer is empty, no crash", d.syllables("'''").isEmpty())
        assertTrue("decode of an all-separator buffer is empty, no crash", d.decode("'''", 20).isEmpty())
        assertTrue("拆 still reachable with a trailing '", "拆" in singles(d.decodeCovered("chai'", 30)))
    }
}
