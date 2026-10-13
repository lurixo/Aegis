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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class OctagramReaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val gram = System.getenv("AEGIS_GRAM")?.let { File(it) }

    private fun runawayImage(units: Int): File {
        val size = 44 + units * 4
        val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        val format = "Rime::Grammar/1.0".toByteArray(Charsets.US_ASCII)
        b.put(format).put(ByteArray(32 - format.size))
        b.putInt(32, 0)
        b.putInt(36, units)
        b.putInt(40, 4)
        for (i in 0 until units) b.putInt(44 + i * 4, -1)
        return File(tmp.newFolder(), "runaway.gram").apply { writeBytes(b.array()) }
    }

    @Test
    fun aRunawayImageIsRefusedInsteadOfReadingPastItself() {
        val reader = OctagramReader.fromFile(runawayImage(8))
        assertNull("a jump outside the image is not an answer", reader.rawScore("中国"))
        assertNull(reader.rawScore("的"))
        assertNull(reader.rawScore("一个词组"))
    }

    @Test
    fun bestSuffixScoreKeepsTheBestSuffixStartingBeforeTheLimit() {
        val supplementary = String(Character.toChars(0x20000))
        val reader = OctagramFixture.reader(
            mapOf(
                "天气" to 3.0,
                "今天天气" to 5.5,
                "é天气" to 7.25,
                "ꀀ天气" to 2.5,
                "${supplementary}气" to 9.0,
                "气" to 1.0,
            ),
        )
        for (text in listOf("今天天气", "é天气", "ꀀ天气", "好${supplementary}气", "\uD800天气", "天气", "")) {
            for (limit in 0..text.length) {
                var expected = 0.0
                var start = 0
                while (start < limit && start < text.length) {
                    reader.rawScore(text.substring(start))?.let { if (it > expected) expected = it }
                    start += Character.charCount(text.codePointAt(start))
                }
                assertEquals("$text before $limit", expected, reader.bestSuffixScore(text, limit), 0.0)
            }
        }
    }

    @Test
    fun queryKeepsTheBestCollocationOfFiveContextAndFiveWordCharacters() {
        val supplementary = String(Character.toChars(0x20000))
        val reader = OctagramFixture.reader(
            mapOf(
                "市买" to 10.0,
                "去超市买东" to 20.0,
                "二三四五六七" to 25.0,
                "一二三四五六七" to 40.0,
                "甲乙丙丁戊己" to 21.0,
                "甲乙丙丁戊己庚" to 40.0,
                "天买" to 5.0,
                "${supplementary}买" to 18.0,
            ),
        )
        assertEquals(6.0, reader.query("去超市", "买东西", false), 1e-9)
        assertEquals(-4.0, reader.query("超市", "买东西", false), 1e-9)
        assertEquals(11.0, reader.query("一二三四五六", "七", false), 1e-9)
        assertEquals(7.0, reader.query("甲", "乙丙丁戊己庚", false), 1e-9)
        assertEquals(4.0, reader.query("好$supplementary", "买", false), 1e-9)
        assertEquals(-6.0, reader.query("今天", "买", false), 1e-9)
        assertEquals(-6.0, reader.query("", "买东西", false), 1e-9)
        assertEquals(-6.0, reader.query("\uD800", "买", false), 1e-9)
    }

    @Test
    fun queryScoresTheSentenceEndOnlyForARearWordAfterContext() {
        val reader = OctagramFixture.reader(mapOf("好$" to 16.0, "甲乙丙丁戊己$" to 30.0))
        assertEquals(-2.0, reader.query("你", "好", true), 1e-9)
        assertEquals(-6.0, reader.query("你", "好", false), 1e-9)
        assertEquals(-6.0, reader.query("", "好", true), 1e-9)
        assertEquals(-6.0, reader.query("你", "甲乙丙丁戊己", true), 1e-9)
    }

    private fun codePointStarts(text: String): List<Int> {
        val starts = ArrayList<Int>()
        var i = 0
        while (i < text.length) {
            starts.add(i)
            i += Character.charCount(text.codePointAt(i))
        }
        return starts
    }

    private fun spelledOutQuery(reader: OctagramReader, context: String, word: String, isRear: Boolean): Double {
        if (context.isEmpty() || word.isEmpty()) return OctagramReader.NON_COLLOCATION_PENALTY
        var best = OctagramReader.NON_COLLOCATION_PENALTY
        val wordEnds = (codePointStarts(word).drop(1) + word.length).take(OctagramReader.QUERY_UNICODE_MAX)
        for (start in codePointStarts(context).takeLast(OctagramReader.QUERY_UNICODE_MAX)) {
            for (end in wordEnds) {
                val score = reader.rawScore(context.substring(start) + word.substring(0, end)) ?: continue
                best = maxOf(best, score + OctagramReader.COLLOCATION_PENALTY)
            }
        }
        if (isRear && word.codePointCount(0, word.length) <= OctagramReader.QUERY_UNICODE_MAX) {
            reader.rawScore("$word$")?.let { best = maxOf(best, it + OctagramReader.REAR_PENALTY) }
        }
        return best
    }

    private fun assertQueriesMatchTheirSpelledOutForm(reader: OctagramReader, contexts: List<String>, words: List<String>): Int {
        var collocations = 0
        for (context in contexts) {
            val nodes = reader.contextNodes(context)
            for (word in words) for (isRear in listOf(false, true)) {
                val expected = spelledOutQuery(reader, context, word, isRear)
                assertEquals("'$context' + '$word' rear=$isRear", expected, reader.query(context, word, isRear), 0.0)
                assertEquals("'$context' + '$word' rear=$isRear, shared context nodes", expected, reader.query(nodes, word, isRear), 0.0)
                if (expected != OctagramReader.NON_COLLOCATION_PENALTY) collocations++
            }
        }
        return collocations
    }

    @Test
    fun queryOverSharedContextNodesMatchesEverySuffixAndPrefixLookup() {
        val supplementary = String(Character.toChars(0x20000))
        val reader = OctagramFixture.reader(
            mapOf(
                "市买" to 10.0,
                "去超市买东" to 20.0,
                "超市买东西" to 23.0,
                "二三四五六七" to 25.0,
                "一二三四五六七" to 40.0,
                "甲乙丙丁戊己" to 21.0,
                "甲乙丙丁戊己庚" to 40.0,
                "天买" to 5.0,
                "${supplementary}买" to 18.0,
                "买$supplementary" to 9.0,
                "好$" to 16.0,
                "买东西$" to 12.0,
                "甲乙丙丁戊己$" to 30.0,
            ),
        )
        val contexts = listOf(
            "", "去超市", "超市", "市", "一二三四五六", "甲", "今天", "好$supplementary", supplementary, "你", "\uD800", "买",
        )
        val words = listOf(
            "买东西", "买", "买东", "七", "乙丙丁戊己庚", "乙丙丁戊己", "好", "甲乙丙丁戊己", supplementary, "${supplementary}买", "西",
        )
        assertTrue(assertQueriesMatchTheirSpelledOutForm(reader, contexts, words) > 0)
    }

    @Test
    fun queryOverSharedContextNodesMatchesTheShippedGrammar() {
        assumeTrue("AEGIS_GRAM points at the .gram", gram != null && gram.exists())
        val reader = OctagramReader.fromFile(gram!!)
        val sentences = listOf(
            "这个问题怎么解决", "今天天气真不错", "他是我最好的朋友", "我们明天去看电影", "你吃饭了吗我还没吃",
            "昨天开会的时候他说", "中华人民共和国", "经济发展的时候我们可以",
        )
        var collocations = 0
        for (sentence in sentences) {
            val contexts = (0..sentence.length).map { sentence.substring(0, it) }
            val words = ArrayList<String>()
            for (from in sentence.indices) for (to in from + 1..minOf(sentence.length, from + 6)) words.add(sentence.substring(from, to))
            collocations += assertQueriesMatchTheirSpelledOutForm(reader, contexts, words.distinct())
        }
        assertTrue("the shipped grammar scores some of the probed collocations", collocations > 0)
    }

    @Test
    fun loadsAndScores() {
        assumeTrue("AEGIS_GRAM points at the .gram", gram != null && gram.exists())
        val r = OctagramReader.fromFile(gram!!)

        val probes = listOf(
            "的时候", "我们", "中国", "时候", "你好", "工作", "一个", "这个",
            "因为", "所以", "中华人民共和国", "时间", "世界", "北京", "可以", "经济发展",
        )
        val sb = StringBuilder("octagram probe (score = log-weight, higher=more frequent)\n")
        var nonNull = 0
        for (p in probes) {
            val s = r.rawScore(p)
            if (s != null) nonNull++
            sb.append("  ${p} -> ${s?.let { "%.3f".format(it) } ?: "null"}\n")
        }
        println(sb)
        File("build/octagram_probe.txt").apply { parentFile?.mkdirs(); writeText(sb.toString()) }
        assertTrue("at least some collocations resolve", nonNull > 0)
    }
}
