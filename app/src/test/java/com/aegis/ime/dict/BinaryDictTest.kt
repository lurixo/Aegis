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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BinaryDictTest {

    private val dictFile = File("src/main/assets/aegis_dict.bin")
    private val t9File = File("src/main/assets/aegis_t9.bin")

    @get:Rule
    val tmp = TemporaryFolder()

    private fun dictBytes(
        version: Int = 2,
        numKeys: Int = 0,
        numEntries: Int = 0,
        keyBlob: ByteArray = ByteArray(0),
        wordBlob: ByteArray = ByteArray(0),
        keyBlobLen: Int = keyBlob.size,
        wordBlobLen: Int = wordBlob.size,
        keyRecords: IntArray = IntArray(0),
        entryRecords: IntArray = IntArray(0),
    ): File {
        val out = java.io.ByteArrayOutputStream()
        fun le(v: Int) { out.write(v); out.write(v ushr 8); out.write(v ushr 16); out.write(v ushr 24) }
        out.write("AEGD".toByteArray(Charsets.US_ASCII))
        le(version); le(numKeys); le(numEntries)
        for (shift in 0 until 64 step 8) out.write(((1L ushr shift).toInt()) and 0xFF)
        le(keyBlobLen); out.write(keyBlob)
        le(wordBlobLen); out.write(wordBlob)
        for (v in keyRecords) le(v)
        for (v in entryRecords) le(v)
        return File(tmp.newFolder(), "dict.bin").apply { writeBytes(out.toByteArray()) }
    }

    @Test
    fun anEmptyButWellFormedDictionaryLoads() {
        val dict = BinaryDict.fromFile(dictBytes())
        assertEquals(emptyList<String>(), dict.query("a", 5))
    }

    @Test
    fun aDictionaryFromAnotherFormatIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { BinaryDict.fromFile(dictBytes(version = 3)) }
    }

    @Test
    fun lengthsThatRunPastTheFileAreRefused() {
        assertThrows("a key blob longer than the file", IllegalArgumentException::class.java) {
            BinaryDict.fromFile(dictBytes(keyBlobLen = 1 shl 20))
        }
        assertThrows("a negative key blob length", IllegalArgumentException::class.java) {
            BinaryDict.fromFile(dictBytes(keyBlobLen = -1))
        }
        assertThrows("a word blob longer than the file", IllegalArgumentException::class.java) {
            BinaryDict.fromFile(dictBytes(wordBlobLen = 1 shl 20))
        }
    }

    @Test
    fun aKeyRecordThatLeavesItsBlobIsRefused() {
        assertThrows("a key length past the blob", IllegalArgumentException::class.java) {
            BinaryDict.fromFile(
                dictBytes(
                    numKeys = 1,
                    keyBlob = byteArrayOf('a'.code.toByte()),
                    keyRecords = intArrayOf(0, 1 shl 20, 0),
                ),
            )
        }
        assertThrows("a negative key offset", IllegalArgumentException::class.java) {
            BinaryDict.fromFile(
                dictBytes(
                    numKeys = 1,
                    keyBlob = byteArrayOf('a'.code.toByte()),
                    keyRecords = intArrayOf(-8, 1, 0),
                ),
            )
        }
    }

    @Test
    fun aKeyPointingOutsideTheEntryTableIsRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            BinaryDict.fromFile(
                dictBytes(
                    numKeys = 1,
                    numEntries = 1,
                    keyBlob = byteArrayOf('a'.code.toByte()),
                    wordBlob = "啊".toByteArray(Charsets.UTF_8),
                    keyRecords = intArrayOf(0, 1, 5),
                    entryRecords = intArrayOf(0, 3, 100),
                ),
            )
        }
    }

    @Test
    fun anEntryThatLeavesTheWordBlobIsRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            BinaryDict.fromFile(
                dictBytes(
                    numKeys = 1,
                    numEntries = 1,
                    keyBlob = byteArrayOf('a'.code.toByte()),
                    wordBlob = "啊".toByteArray(Charsets.UTF_8),
                    keyRecords = intArrayOf(0, 1, 0),
                    entryRecords = intArrayOf(0, 1 shl 20, 100),
                ),
            )
        }
    }

    @Test
    fun aDictionaryEndingBeforeItsEntryTableIsRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            BinaryDict.fromFile(
                dictBytes(
                    numKeys = 1,
                    numEntries = 1,
                    keyBlob = byteArrayOf('a'.code.toByte()),
                    keyRecords = intArrayOf(0, 1, 0),
                ),
            )
        }
    }

    @Test
    fun aFullyValidatedSingleWordDictionaryStillAnswers() {
        val word = "啊".toByteArray(Charsets.UTF_8)
        val dict = BinaryDict.fromFile(
            dictBytes(
                numKeys = 1,
                numEntries = 1,
                keyBlob = byteArrayOf('a'.code.toByte()),
                wordBlob = word,
                keyRecords = intArrayOf(0, 1, 0),
                entryRecords = intArrayOf(0, word.size, 7),
            ),
        )
        assertEquals(listOf("啊"), dict.query("a", 5))
        assertEquals(7, requireNotNull(dict.exactWordFreq("a", "啊")))
    }

    @Test
    fun aKeyCountThatOverflowsItsTableIsRefused() {
        assertThrows(
            "numKeys * 12 must not wrap into a negative offset",
            IllegalArgumentException::class.java,
        ) { BinaryDict.fromFile(dictBytes(numKeys = 200_000_000)) }
    }

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

    private fun spelled(index: Int, radix: Int, first: Char, width: Int): String {
        val out = StringBuilder()
        var v = index
        repeat(width) {
            out.append(first + v % radix)
            v /= radix
        }
        return out.toString()
    }

    private fun heavyRows(stem: String, radix: Int, first: Char, width: Int): List<EngineFixture.Row> {
        val rows = ArrayList<EngineFixture.Row>()
        for (i in 0 until 4500) {
            val key = stem + spelled(i, radix, first, width)
            val word = when {
                i % 7 == 0 -> EngineFixture.supplementary(i % 300)
                i % 5 == 0 -> "单$i"
                else -> "词${i % 3000}"
            }
            rows.add(EngineFixture.Row(key, word, 5000 - i % 40))
            if (i % 3 == 0) rows.add(EngineFixture.Row(key, "次${i % 900}", 4990 - i % 60))
        }
        return rows
    }

    private fun assertServedWithoutAScan(dict: BinaryDict, prefix: String) {
        var polls = 0
        val served = DecodeCancellation.attempt({ polls++; false }) { dict.prefixByFreq(prefix, 20) }
        assertEquals("'$prefix' is answered", dict.scanByFreq(prefix, 20), served?.getOrNull())
        assertEquals("'$prefix' is answered without walking its keys", 0, polls)
    }

    @Test
    fun prebuiltTwoUnitAndThreeDigitPrefixesMatchTheKeyScan() {
        val letterDict = EngineFixture.build(
            heavyRows("sh", 26, 'a', 3) + heavyRows("zh", 26, 'a', 3).take(600) + listOf(EngineFixture.Row("s", "是", 9000)),
        )
        val digitDict = EngineFixture.build(heavyRows("942", 8, '2', 5) + heavyRows("64", 8, '2', 5) + heavyRows("843", 8, '2', 4).take(900))
        val limits = listOf(1, 2, 5, 20, 127, 128, 129, 5000)
        for ((dict, prefixes) in listOf(letterDict to listOf("sh", "zh", "sa", "sha", "shb"), digitDict to listOf("94", "942", "64", "642", "649", "84", "843"))) {
            for (prefix in prefixes) for (limit in limits) {
                assertEquals("'$prefix' limit $limit", dict.scanByFreq(prefix, limit), dict.prefixByFreq(prefix, limit))
            }
        }
        assertServedWithoutAScan(letterDict, "sh")
        assertServedWithoutAScan(digitDict, "94")
        assertServedWithoutAScan(digitDict, "942")
        assertServedWithoutAScan(digitDict, "64")
    }

    @Test
    fun prebuiltPrefixesOfTheFullDictionariesMatchTheKeyScan() {
        val jianpinFile = File("src/main/assets/aegis_jianpin.bin")
        assumeTrue("full dict assets present", dictFile.exists() && t9File.exists() && jianpinFile.exists())
        val units = ('a'..'z') + ('2'..'9')
        val prefixes = ArrayList<String>()
        for (a in units) for (b in units) prefixes.add("$a$b")
        for (a in '2'..'9') for (b in '2'..'9') for (c in '2'..'9') prefixes.add("$a$b$c")
        for (file in listOf(dictFile, t9File, jianpinFile)) {
            val dict = BinaryDict.fromFile(file)
            for (prefix in prefixes) for (limit in listOf(1, 13, 20, 128)) {
                assertEquals("${file.name} '$prefix' limit $limit", dict.scanByFreq(prefix, limit), dict.prefixByFreq(prefix, limit))
            }
        }
        assertServedWithoutAScan(BinaryDict.fromFile(dictFile), "zh")
        assertServedWithoutAScan(BinaryDict.fromFile(t9File), "94")
        assertServedWithoutAScan(BinaryDict.fromFile(t9File), "942")
    }

    private val initialRows = listOf(
        EngineFixture.Row("w", "我", 990), EngineFixture.Row("wx", "无限", 610),
        EngineFixture.Row("wx", "微信", 900), EngineFixture.Row("wxh", "无限好", 300),
        EngineFixture.Row("wz", "位置", 700), EngineFixture.Row("wz", "文字", 640),
        EngineFixture.Row("xw", "希望", 880), EngineFixture.Row("xx", "学习", 950),
        EngineFixture.Row("xx", "谢谢", 940), EngineFixture.Row("xxw", "新希望", 200),
        EngineFixture.Row("xy", "需要", 930), EngineFixture.Row("yw", "因为", 960),
        EngineFixture.Row("yx", "游戏", 870), EngineFixture.Row("yx", "无限", 955),
        EngineFixture.Row("zw", "作为", 820), EngineFixture.Row("zz", "正在", 910),
        EngineFixture.Row("zzz", "做作者", 100), EngineFixture.Row("gh", "更好", 800),
        EngineFixture.Row("tg", "通过", 850), EngineFixture.Row("tgs", "通过式", 50),
        EngineFixture.Row("wxhn", "我喜欢你", 400), EngineFixture.Row("ydbw", "原地不我", 10),
    )

    private fun exhaustive(dict: BinaryDict, choices: List<String>, limit: Int): List<Pair<String, Int>> {
        val keys = choices.fold(listOf("")) { heads, letters -> heads.flatMap { head -> letters.map { head + it } } }
        val seen = HashSet<String>()
        return keys.flatMap { dict.exact(it) }.sortedByDescending { it.freq }
            .filter { seen.add(it.word) }.take(limit).map { it.word to it.freq }
    }

    @Test
    fun exactAmongMergesEveryKeySpelledFromTheChoicesByFrequency() {
        val dict = EngineFixture.build(initialRows)
        assertEquals(
            listOf("因为" to 960, "无限" to 955, "学习" to 950, "谢谢" to 940, "需要" to 930, "正在" to 910, "微信" to 900),
            dict.exactAmong(listOf("wxyz", "wxyz"), 7).map { it.word to it.freq },
        )
        assertEquals(
            listOf("微信" to 900, "位置" to 700, "文字" to 640, "无限" to 610),
            dict.exactAmong(listOf("w", "zx"), 10).map { it.word to it.freq },
        )
    }

    @Test
    fun exactAmongAgreesWithLookingUpEverySpelling() {
        val dict = EngineFixture.build(initialRows)
        val digitLetters = listOf("abc", "def", "gh", "jkl", "mno", "pqrs", "t", "wxyz")
        var compared = 0
        fun compare(choices: List<String>) {
            for (limit in listOf(1, 3, 20, Int.MAX_VALUE)) {
                assertEquals(
                    "$choices limit $limit",
                    exhaustive(dict, choices, limit),
                    dict.exactAmong(choices, limit).map { it.word to it.freq },
                )
                compared++
            }
        }
        fun all(head: List<String>, depth: Int) {
            if (head.isNotEmpty()) compare(head)
            if (depth > 0) for (letters in digitLetters) all(head + letters, depth - 1)
        }
        all(emptyList(), 3)
        compare(listOf("wxyz", "wxyz", "gh", "mno"))
        compare(listOf("wxyz", "def", "abc", "wxyz"))
        assertEquals((8 + 64 + 512 + 2) * 4, compared)
    }

    @Test
    fun exactAmongOnlyAnswersKeysAsLongAsTheChoices() {
        val dict = EngineFixture.build(initialRows)
        assertEquals(listOf("我"), dict.exactAmong(listOf("wxyz"), 20).map { it.word })
        assertEquals(listOf("通过"), dict.exactAmong(listOf("t", "gh"), 20).map { it.word })
        assertEquals(
            listOf("无限好", "新希望", "做作者"),
            dict.exactAmong(listOf("wxyz", "wxyz", "ghwxyz"), 20).map { it.word },
        )
        assertEquals(listOf("通过式"), dict.exactAmong(listOf("t", "gh", "pqrs"), 20).map { it.word })
    }

    @Test
    fun exactAmongKeepsAWordFiledUnderSeveralKeysOnceAtItsHighestFrequency() {
        val dict = EngineFixture.build(initialRows)
        val hits = dict.exactAmong(listOf("wxyz", "wxyz"), Int.MAX_VALUE)
        assertEquals(hits.size, hits.map { it.word }.toSet().size)
        assertEquals(955, hits.single { it.word == "无限" }.freq)
        assertEquals(listOf("无限" to 955), dict.exactAmong(listOf("wy", "x"), 1).map { it.word to it.freq })
        assertEquals(
            listOf("微信" to 900, "无限" to 610),
            dict.exactAmong(listOf("ww", "x"), 5).map { it.word to it.freq },
        )
    }

    @Test
    fun exactAmongIsEmptyWhenNothingCanMatch() {
        val dict = EngineFixture.build(initialRows)
        assertEquals(emptyList<BinaryDict.WordFreq>(), dict.exactAmong(emptyList(), 20))
        assertEquals(emptyList<BinaryDict.WordFreq>(), dict.exactAmong(listOf("wxyz", "wxyz"), 0))
        assertEquals(emptyList<BinaryDict.WordFreq>(), dict.exactAmong(listOf("wxyz", ""), 20))
        assertEquals(emptyList<BinaryDict.WordFreq>(), dict.exactAmong(listOf("jkl", "wxyz"), 20))
        assertEquals(emptyList<BinaryDict.WordFreq>(), dict.exactAmong(listOf("t", "gh", "t"), 20))
        assertEquals(emptyList<BinaryDict.WordFreq>(), dict.exactAmong(listOf("wxyz", "jkl", "wxyz", "mno"), 20))
        assertEquals(emptyList<BinaryDict.WordFreq>(), BinaryDict.fromFile(dictBytes()).exactAmong(listOf("wxyz"), 20))
    }
}
