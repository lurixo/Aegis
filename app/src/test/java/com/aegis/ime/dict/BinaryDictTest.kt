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

package com.aegis.ime.dict

import com.aegis.ime.decoder.EngineFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class BinaryDictTest {

    private val dictFile = File("src/main/assets/aegis_dict.bin")
    private val t9File = File("src/main/assets/aegis_t9.bin")

    @Test
    fun looksUpCommonWords() {
        assumeTrue("demo dict asset present", dictFile.exists())
        val dict = BinaryDict.fromFile(dictFile)

        assertTrue("nihao -> 你好", dict.query("nihao", 30).contains("你好"))
        assertTrue("zhongguo -> 中国", dict.query("zhongguo", 30).contains("中国"))
        assertTrue("ceshi -> 测试", dict.query("ceshi", 30).contains("测试"))
        assertTrue("shuru -> 输入", dict.query("shuru", 30).contains("输入"))

        val a = dict.query("a", 30)
        assertTrue("a -> 啊", a.contains("啊"))
        assertTrue("啊 before 阿", a.indexOf("啊") < a.indexOf("阿"))

        assertTrue("prefix bei non-empty", dict.query("bei", 30).isNotEmpty())

        assertTrue("limit honored", dict.query("a", 2).size <= 2)
        assertEquals("empty -> none", emptyList<String>(), dict.query("", 30))
    }

    @Test
    fun exact_lookup_can_be_bounded_and_membership_checked_without_materializing_callers() {
        assumeTrue("demo dict asset present", dictFile.exists())
        val dict = BinaryDict.fromFile(dictFile)

        val all = dict.exact("nihao")
        assertTrue("fixture has nihao candidates", all.isNotEmpty())
        assertEquals("bounded exact returns the frequency-leading prefix", all.take(1), dict.exact("nihao", limit = 1))
        assertEquals("zero limit returns no rows", emptyList<BinaryDict.WordFreq>(), dict.exact("nihao", limit = 0))
        assertTrue("contains existing exact word", dict.containsExactWord("nihao", "你好"))
        assertTrue("does not claim the word under the wrong key", !dict.containsExactWord("ceshi", "你好"))
    }

    @Test
    fun prefixByFreqSpendsEverySupplySlotOnADistinctWord() {
        assumeTrue("full dict assets present", dictFile.exists() && t9File.exists())
        val letter = BinaryDict.fromFile(dictFile)
        val digit = BinaryDict.fromFile(t9File)
        val arms = listOf(
            Triple("26-key", letter, "d"),
            Triple("26-key", letter, "n"),
            Triple("9-key", digit, "6"),
        )
        for ((layout, dict, key) in arms) {
            val hits = dict.prefixByFreq(key, 20)
            assertEquals("$layout '$key' fills all 20 supply slots", 20, hits.size)
            assertEquals(
                "$layout '$key' spends every slot on a distinct word: ${hits.map { it.word }}",
                20,
                hits.map { it.word }.distinct().size,
            )
            for (k in 1 until hits.size) {
                assertTrue(
                    "$layout '$key' stays ordered by frequency at slot $k",
                    hits[k - 1].freq >= hits[k].freq,
                )
            }
        }
    }

    @Test
    fun prefixByFreqKeepsTheStrongestReadingOfADuplicatedWord() {
        val rows = listOf(
            EngineFixture.Row("shga", "同词", 900),
            EngineFixture.Row("shgb", "同词", 700),
            EngineFixture.Row("shgc", "另词", 800),
            EngineFixture.Row("shgd", "三词", 600),
            EngineFixture.Row("shge", "四词", 500),
        )
        val dict = EngineFixture.build(rows)

        val top3 = dict.prefixByFreq("shg", 3)
        assertEquals(listOf("同词" to 900, "另词" to 800, "三词" to 600), top3.map { it.word to it.freq })

        val all = dict.prefixByFreq("shg", 10)
        assertEquals(listOf("同词", "另词", "三词", "四词"), all.map { it.word })
        assertEquals(900, all.first { it.word == "同词" }.freq)
    }

    @Test
    fun oneUnitPrefixIndexDeduplicatesByWordForLettersAndDigits() {
        val letterRows = listOf(
            EngineFixture.Row("de", "地", 900),
            EngineFixture.Row("di", "地", 700),
            EngineFixture.Row("da", "大", 800),
            EngineFixture.Row("du", "读", 600),
        )
        val letterDict = EngineFixture.build(letterRows)
        assertEquals(
            listOf("地" to 900, "大" to 800, "读" to 600),
            letterDict.prefixByFreq("d", 3).map { it.word to it.freq },
        )

        val digitRows = listOf(
            EngineFixture.Row("33", "地", 900),
            EngineFixture.Row("34", "地", 700),
            EngineFixture.Row("32", "大", 800),
            EngineFixture.Row("38", "读", 600),
        )
        val digitDict = EngineFixture.build(digitRows)
        assertEquals(
            listOf("地" to 900, "大" to 800, "读" to 600),
            digitDict.prefixByFreq("3", 3).map { it.word to it.freq },
        )
    }

    @Test
    fun prefixByFreqKeepsTheEarlierEntryOfAnEqualFrequencyDuplicate() {
        val rows = listOf(
            EngineFixture.Row("shga", "同词", 500),
            EngineFixture.Row("shgb", "同词", 500),
            EngineFixture.Row("shgc", "另词", 400),
        )
        val dict = EngineFixture.build(rows)
        assertEquals(
            listOf("同词" to 500, "另词" to 400),
            dict.prefixByFreq("shg", 2).map { it.word to it.freq },
        )
    }

    @Test
    fun prefixByFreqServesALimitLargerThanAnyAllocatableHeap() {
        val rows = (0 until 40).map { EngineFixture.Row("sh" + ('a' + it % 4), "词$it", 1000 - it) }
        val dict = EngineFixture.build(rows)

        val everyHit = dict.prefixByFreq("sh", rows.size)
        assertEquals("multi-character prefix reaches every fixture row", rows.size, everyHit.size)
        assertEquals(
            "a limit past every allocatable size returns the same hits in the same order",
            everyHit,
            dict.prefixByFreq("sh", Int.MAX_VALUE),
        )
        assertEquals("a small limit still returns the leading slice", everyHit.take(3), dict.prefixByFreq("sh", 3))
    }
}
