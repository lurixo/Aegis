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

package com.aegis.ime.ime

import com.aegis.ime.decoder.Cand
import com.aegis.ime.decoder.FullDictTestAssets
import com.aegis.ime.decoder.PinyinDecoder
import com.aegis.ime.dict.BinaryDict
import com.aegis.ime.dict.CharBigramLM
import com.aegis.ime.engine.CandidateEngine
import com.aegis.ime.engine.DictEngine
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyboardLossMatrixTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val assets = FullDictTestAssets.directory
    private fun assetsPresent() = FullDictTestAssets.available(
        File(assets, FullDictTestAssets.DICT),
        File(assets, FullDictTestAssets.T9),
        File(assets, FullDictTestAssets.LM),
        File(assets, FullDictTestAssets.JIANPIN),
    )

    private class Host : ImeHost {
        override fun commitText(text: CharSequence) {}
        override fun deleteBackward() {}
        override fun performEnter() {}
    }

    private fun realEngine() = DictEngine(
        BinaryDict.fromFile(File(assets, FullDictTestAssets.DICT)),
        BinaryDict.fromFile(File(assets, FullDictTestAssets.T9)),
        CharBigramLM.fromFile(File(assets, FullDictTestAssets.LM)),
        initialsDict = BinaryDict.fromFile(File(assets, FullDictTestAssets.JIANPIN)),
    )

    private fun controller(e: CandidateEngine): KeyboardController =
        KeyboardController(Host(), e).apply { attachView(InputView(ctx)) }

    private fun type(c: KeyboardController, s: String) = s.forEach { c.onKey(Key(it.toString(), output = it.toString())) }
    private fun pick(c: KeyboardController, reading: String) =
        c.onKey(Key(reading, output = reading, action = KeyAction.PICK_READING))
    private fun isSingleChar(word: String): Boolean = word.codePointCount(0, word.length) == 1

    private fun chaiSingles() =
        BinaryDict.fromFile(File(assets, FullDictTestAssets.DICT)).exact("chai")
            .filter { isSingleChar(it.word) }
            .map { it.word }

    private fun assertChaiReachableAndRanked(words: List<String>) {
        assertTrue("拆 must be reachable (was buried/lost)", "拆" in words)
        val firstSingle = words.indexOfFirst { isSingleChar(it) }
        assertTrue(
            "a chai single reaches the first screen, was $firstSingle in ${words.take(12)}",
            firstSingle in 0..PinyinDecoder.STAGED_REAL_WORD_SLOTS,
        )
        val singles = chaiSingles()
        val got = words.filter { it in singles }
        assertTrue("chai homophones present in the grid", got.isNotEmpty())
        assertEquals("the highest-freq chai homophone 拆 leads them (common before rare)", "拆", got.first())
        val glued = words.indexOf("拆次")
        assertTrue("拆次 (chai|ci best sentence) stays reachable", glued >= 0)
        assertTrue("拆次 leads the chai singles once the boundary is confirmed, was at $glued", glued < firstSingle)
        assertFalse("no stray non-pinyin junk leading the grid", words.first().any { it.code < 128 })
    }


    @Test fun fullPinyin26Key_chaiApostropheCi_surfacesChaiAndChaici() {
        assumeTrue(assetsPresent())
        val c = controller(realEngine())
        c.switchTextLayoutForTest(nine = false)
        type(c, "chai"); c.onKey(Key("'", output = "'")); type(c, "ci")
        assertEquals("preedit keeps the 隔音符 the user typed", "chai'ci", c.preeditForTest())
        assertChaiReachableAndRanked(c.candidateWords())
    }


    @Test fun nineKey_lockChaiThenCi_surfacesChaiAndChaici() {
        assumeTrue(assetsPresent())
        val c = controller(realEngine())
        c.switchTextLayoutForTest(nine = true)
        type(c, "2424"); pick(c, "chai")
        type(c, "24"); pick(c, "ci")
        assertEquals("preedit shows the locked chai'ci", "chai'ci", c.preeditForTest())
        assertChaiReachableAndRanked(c.candidateWords())
    }

    @Test fun nineKey_singleSyllableStaysFine_control() {
        assumeTrue(assetsPresent())
        val c = controller(realEngine())
        c.switchTextLayoutForTest(nine = true)
        type(c, "2424"); pick(c, "chai")
        assertTrue("拆 reachable for a single locked chai", "拆" in c.candidateWords())
        val injected = com.aegis.ime.engine.InputAssociations.lookup("chai")
        assertEquals(
            "拆 leads its homophones (freq order)",
            "拆",
            c.candidateWords().first { isSingleChar(it) && it !in injected },
        )
    }

    @Test fun nineKey_lockedBoundariesAreForwardedAsDecodeCuts() {
        var seenLetters = ""
        var seenCuts: Set<Int> = setOf(-999)
        val recorder = object : CandidateEngine {
            override fun candidates(composing: String, t9: Boolean) = listOf("拆")
            override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>, context: CharSequence) =
                listOf(Cand("拆", composing.length))
            override fun candidatesForLockedReadingCovered(letters: String, cuts: Set<Int>, context: CharSequence): List<Cand> {
                seenLetters = letters; seenCuts = cuts
                return listOf(Cand("拆次", letters.length), Cand("拆", 4))
            }
        }
        val c = controller(recorder)
        c.switchTextLayoutForTest(nine = true)
        type(c, "2424"); pick(c, "chai")
        type(c, "24"); pick(c, "ci")
        assertEquals("decode runs over the combined full pinyin", "chaici", seenLetters)
        assertTrue("the chai|ci lock boundary (letter 4) is forwarded as a decode cut", 4 in seenCuts)
    }

    @Test fun nineKey_partialLockForwardsLockedBoundaryToLockedDecode() {
        var seenCuts: Set<Int>? = null
        val recorder = object : CandidateEngine {
            override fun candidates(composing: String, t9: Boolean) = listOf("拆")
            override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>, context: CharSequence) =
                listOf(Cand("拆", composing.length))
            override fun candidatesForLockedReadingCovered(letters: String, cuts: Set<Int>, context: CharSequence): List<Cand> {
                seenCuts = cuts; return listOf(Cand("拆", 4))
            }
        }
        val c = controller(recorder)
        c.switchTextLayoutForTest(nine = true)
        type(c, "2424"); pick(c, "chai")
        type(c, "24")
        assertTrue("the locked-path decode ran", seenCuts != null)
        assertTrue("a partial lock must forward the selected chai boundary as a cut", 4 in seenCuts!!)
    }

    @Test fun aDeclaredBoundaryCutExcludesCrossBoundaryCompletions() {
        assumeTrue(assetsPresent())
        val dec = PinyinDecoder(
            BinaryDict.fromFile(File(assets, FullDictTestAssets.DICT)),
            CharBigramLM.fromFile(File(assets, FullDictTestAssets.LM)),
            initialsDict = BinaryDict.fromFile(File(assets, FullDictTestAssets.JIANPIN)),
        )
        val noCut = dec.decodeCovered("nihao", 30).map { it.word }
        val withCut = dec.decodeCovered("nihao", 30, setOf(2)).map { it.word }
        assertTrue("no-cut decode floods with cross-boundary 你好X completions", noCut.any { it.length >= 3 && it.startsWith("你好") })
        assertTrue("the boundary cut excludes EVERY 你好X cross-boundary completion", withCut.none { it.length >= 3 && it.startsWith("你好") })
        assertTrue("…but keeps the in-boundary 你好", "你好" in withCut)
        assertTrue("…and keeps the leading single 你", "你" in withCut)
    }
}
