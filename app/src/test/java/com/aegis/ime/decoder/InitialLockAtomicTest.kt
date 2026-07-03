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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class InitialLockAtomicTest {

    private val dictFile = FullDictTestAssets.file(FullDictTestAssets.DICT)
    private val lmFile = FullDictTestAssets.file(FullDictTestAssets.LM)
    private val jianpinFile = FullDictTestAssets.file(FullDictTestAssets.JIANPIN)

    private val dict: BinaryDict by lazy { BinaryDict.fromFile(dictFile) }
    private val jianpin: BinaryDict by lazy { BinaryDict.fromFile(jianpinFile) }
    private val lm: CharBigramLM by lazy { CharBigramLM.fromFile(lmFile) }

    private fun assets() = assumeTrue(
        "26-key dictionary, language model and jianpin table present",
        FullDictTestAssets.available(dictFile, lmFile, jianpinFile),
    )

    private fun decoder() = PinyinDecoder(dict, lm, initialsDict = jianpin)

    private fun plainDecoder() = PinyinDecoder(dict, lm)

    private enum class Layout { ALPHA, NINE }

    private data class Lock(val initial: String, val restLetters: String, val restDigits: String)

    private fun letters(layout: Layout, lock: Lock): String {
        val rest = if (layout == Layout.NINE) T9Pinyin.preedit(lock.restDigits).replace("'", "") else lock.restLetters
        if (layout == Layout.NINE) assertEquals("9-key preedit of ${lock.restDigits}", lock.restLetters, rest)
        return lock.initial + rest
    }

    private fun decode(layout: Layout, lock: Lock): List<Cand> {
        val input = letters(layout, lock)
        val cuts = if (lock.restLetters.isEmpty()) emptySet() else setOf(lock.initial.length)
        return decoder().decodeCoveredAtomic(input, 30, cuts)
    }

    private fun isSingle(word: String) = word.codePointCount(0, word.length) == 1

    private fun singles(source: BinaryDict, key: String): Set<String> =
        source.exact(key).filter { isSingle(it.word) }.mapTo(HashSet()) { it.word }

    private fun chars(word: String): List<String> =
        word.codePoints().toArray().map { String(Character.toChars(it)) }

    @Test fun aLockedInitialOffersTheWordsItSpellsWithTheFollowingSyllable() {
        assets()
        val lock = Lock("j", "yi", "94")
        val j = singles(jianpin, "j")
        val yi = singles(dict, "yi")
        for (layout in Layout.values()) {
            val got = decode(layout, lock)
            val words = got.filter { it.coveredLen == 3 }.map { it.word }
            for (w in listOf("记忆", "建议", "交易")) assertTrue("$layout: $w offered, was ${words.take(12)}", w in words)
            for (w in listOf("经验", "具有", "基于")) assertTrue("$layout: $w does not read yi, was ${words.take(12)}", w !in got.map { it.word })
            for (w in words) {
                val cs = chars(w)
                assertTrue("$layout: $w spans the initial and one syllable", cs.size == 2 && cs[0] in j && cs[1] in yi)
            }
            assertTrue("$layout: only whole or first-segment coverage, was ${got.map { it.coveredLen }.toSet()}",
                got.all { it.coveredLen == 1 || it.coveredLen == 3 })
            val firstSingle = got.indexOfFirst { it.coveredLen == 1 }
            assertTrue("$layout: 就 covers the initial alone, was ${got.take(20)}", got.any { it.word == "就" && it.coveredLen == 1 })
            assertTrue("$layout: aligned words lead the single characters", got.indexOfFirst { it.word == "记忆" } in 0 until firstSingle)
            assertTrue("$layout: the initial's characters are its jianpin singles", got.filter { it.coveredLen == 1 }.all { it.word in j })
        }
    }

    @Test fun aLockedInitialAloneOffersItsMostFrequentSingles() {
        assets()
        val table = jianpin.exact("j").filter { isSingle(it.word) }.associate { it.word to it.freq }
        for (layout in Layout.values()) {
            val got = decode(layout, Lock("j", "", ""))
            assertTrue("$layout: j alone offers characters", got.isNotEmpty())
            assertTrue("$layout: j alone offers single characters covering it", got.all { isSingle(it.word) && it.coveredLen == 1 })
            val offered = got.map { it.word }.toSet()
            assertEquals("$layout: the offer is bounded", minOf(table.size, PinyinDecoder.INITIALS_SINGLES), offered.size)
            assertTrue("$layout: the ${table.size} j singles are not all offered", offered.size < table.size)
            assertTrue("$layout: every offer is a j single", offered.all { it in table })
            val lowestOffered = offered.minOf { table.getValue(it) }
            val highestLeftOut = table.filterKeys { it !in offered }.values.maxOrNull() ?: 0
            assertTrue("$layout: the bound keeps the most frequent j singles ($lowestOffered < $highestLeftOut)", lowestOffered >= highestLeftOut)
            assertTrue("$layout: 就 leads the common j singles, was ${got.take(8)}", "就" in got.take(8).map { it.word })
        }
    }

    @Test fun everyInitialAlignsWithItsFollowingSyllable() {
        assets()
        val cases = listOf(
            Lock("b", "yu", "98") to listOf("比喻"),
            Lock("w", "yi", "94") to listOf("唯一", "无疑"),
            Lock("z", "yi", "94") to listOf("注意", "之一", "主意"),
            Lock("x", "yi", "94") to listOf("协议", "心意"),
        )
        for (layout in Layout.values()) for ((lock, expected) in cases) {
            val words = decode(layout, lock).filter { it.coveredLen == 3 }.map { it.word }
            for (w in expected) assertTrue("$layout ${lock.initial}+${lock.restLetters}: $w offered, was ${words.take(12)}", w in words)
        }
    }

    @Test fun anInitialLockedAfterASyllableKeepsTheWordsBothSpell() {
        assets()
        val d = decoder()
        val two = d.decodeCoveredAtomic("nih", 30, setOf(2))
        for (w in listOf("你好", "你会")) assertTrue("ni+h offers $w, was ${two.take(12)}", two.any { it.word == w && it.coveredLen == 3 })
        assertTrue("ni+h keeps the ni singles", two.any { it.word == "你" && it.coveredLen == 2 })
        val three = d.decodeCoveredAtomic("nihao", 30, setOf(2, 3))
        assertTrue("ni+h+ao offers a whole reading, was ${three.take(12)}", three.any { it.coveredLen == 5 })
        assertTrue("ni+h+ao keeps 你好 over ni+h, was ${three.take(12)}", three.any { it.word == "你好" && it.coveredLen == 3 })
        assertEquals(
            "9-key ni+h+ao reads the same letters",
            listOf("ni", "h", "ao"),
            d.syllables("ni" + "h" + T9Pinyin.preedit("26"), setOf(2, 3)).map { it.reading },
        )
    }

    @Test fun theLockedInitialIsItsOwnDrillSyllable() {
        assets()
        val d = decoder()
        assertEquals(
            listOf(Syllable("j", 0, 1), Syllable("yi", 1, 3)),
            d.syllables("jyi", setOf(1)),
        )
        val j = d.homophonesAt("jyi", 0, setOf(1))
        assertTrue("the initial drills its own characters, was ${j.take(12)}", "就" in j && j.all { it in singles(jianpin, "j") })
        assertEquals("the syllable after it drills yi", d.homophonesAt("yi", 0), d.homophonesAt("jyi", 1, setOf(1)))
        assertEquals(listOf(Syllable("j", 0, 1)), d.syllables("j"))
    }

    @Test fun wholeSyllableLocksDecodeIdenticallyWithOrWithoutTheJianpinTable() {
        assets()
        val withTable = decoder()
        val without = plainDecoder()
        val locks = listOf(
            listOf("fang", "an"), listOf("fan", "gan"), listOf("jiu", "yi"), listOf("ji", "yi"), listOf("ni", "hao"),
            listOf("xian", "sheng"), listOf("zhong", "guo"), listOf("yin", "shi", "jian"), listOf("jiu"), listOf("m", "de"),
            listOf("zhu", "yi"), listOf("bi", "yu"), listOf("wei", "yi"), listOf("n", "hao"), listOf("e", "yi"),
        )
        for (layout in Layout.values()) for (parts in locks) for (context in listOf("", "我")) {
            val head = parts.dropLast(1)
            val tail = if (layout == Layout.NINE) T9Pinyin.preedit(T9Pinyin.toT9(parts.last())).replace("'", "") else parts.last()
            val input = head.joinToString("") + tail
            val cuts = head.runningFold(0) { acc, r -> acc + r.length }.drop(1).filter { it < input.length }.toSet()
            val tag = "$layout $parts |$context"
            assertEquals(tag, without.decodeCoveredAtomic(input, 30, cuts, context), withTable.decodeCoveredAtomic(input, 30, cuts, context))
            val syllables = without.syllables(input, cuts)
            assertEquals(tag, syllables, withTable.syllables(input, cuts))
            for (i in syllables.indices) assertEquals(tag, without.homophonesAt(input, i, cuts), withTable.homophonesAt(input, i, cuts))
        }
    }

    @Test fun withoutTheJianpinTableALockedInitialKeepsTheOldEmptyResult() {
        assets()
        val d = plainDecoder()
        assertEquals(emptyList<Cand>(), d.decodeCoveredAtomic("jyi", 30, setOf(1)))
        assertEquals(emptyList<Cand>(), d.decodeCoveredAtomic("j", 30))
        assertEquals(listOf("yi"), d.syllables("jyi", setOf(1)).map { it.reading })
    }
}
