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

import com.aegis.ime.dict.CharBigramLM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class EngineLockedFixTest {

    private val dict = EngineFixture.dict()
    private val d = PinyinDecoder(dict)

    private fun isSupp(s: String) = s.codePointCount(0, s.length) == 1 && Character.isSupplementaryCodePoint(s.codePointAt(0))
    private fun words(c: List<Cand>) = c.map { it.word }

    private fun locked(readings: List<String>): List<Cand> {
        val full = readings.joinToString("")
        val cuts = HashSet<Int>(); var acc = 0
        for (r in readings) { acc += r.length; if (acc < full.length) cuts.add(acc) }
        return d.decodeCovered(full, 30, cuts)
    }

    private fun pureSentences(c: List<Cand>, fullLen: Int) = c.filter { it.coveredLen == fullLen }.map { it.word }

    private fun lm(uni: Map<Int, Long>, bi: Map<Pair<Int, Int>, Long>): CharBigramLM {
        val cps = sortedSetOf<Int>()
        cps.addAll(uni.keys)
        for ((pair, _) in bi) { cps.add(pair.first); cps.add(pair.second) }
        val codes = cps.toList()
        val idOf = HashMap<Int, Int>(codes.size * 2)
        for (i in codes.indices) idOf[codes[i]] = i
        val rows = Array(codes.size) { ArrayList<Pair<Int, Long>>() }
        val rowTotal = LongArray(codes.size)
        for ((pair, count) in bi) {
            val c1 = idOf.getValue(pair.first)
            val c2 = idOf.getValue(pair.second)
            rowTotal[c1] += count
            rows[c1].add(c2 to count)
        }
        val rowStart = IntArray(codes.size + 1)
        var numBigrams = 0
        for (i in rows.indices) {
            rows[i].sortBy { it.first }
            rowStart[i] = numBigrams
            numBigrams += rows[i].size
        }
        rowStart[codes.size] = numBigrams

        val out = ByteArrayOutputStream()
        fun le(v: Int) { out.write(v); out.write(v ushr 8); out.write(v ushr 16); out.write(v ushr 24) }
        fun leLong(v: Long) { for (s in 0 until 64 step 8) out.write((v ushr s).toInt() and 0xFF) }
        out.write(byteArrayOf('A'.code.toByte(), 'E'.code.toByte(), 'G'.code.toByte(), 'L'.code.toByte()))
        le(1)
        le(codes.size)
        leLong(uni.values.sum().coerceAtLeast(1L))
        for (cp in codes) le(cp)
        for (cp in codes) leLong(uni[cp] ?: 1L)
        for (v in rowTotal) leLong(v)
        for (v in rowStart) le(v)
        le(numBigrams)
        for (row in rows) for ((c2, _) in row) le(c2)
        for (row in rows) for ((_, count) in row) leLong(count)

        val file = File.createTempFile("aegis_lm_fixture", ".bin")
        file.deleteOnExit()
        file.writeBytes(out.toByteArray())
        return CharBigramLM.fromFile(file)
    }


    @Test fun unlockedDecodeDoesNotFloodTheFrontWithSupplementarySingles() {
        val top = words(d.decodeCovered("ciku", 30))
        assertFalse("no extension-area single floods the front", top.take(15).any { isSupp(it) })
        assertEquals("the real word still leads", "词库", top.first())
        assertTrue("the common 同音字 follow it", top.take(6).containsAll(listOf("次", "此", "词")))
    }

    @Test fun homophoneLayerIncludesSupplementaryAtItsFrequencyTail() {
        val h = d.homophonesAt("ciku", 0)
        assertTrue("common 次 present", "次" in h)
        assertTrue("supplementary 同音字 present (was lost)", h.any { isSupp(it) })
        assertTrue("common chars rank ABOVE the freq=1 supplementary tail", h.indexOf("次") < h.indexOfFirst { isSupp(it) })
    }

    @Test fun tiedSupplementarySinglesFallBehindCommonSinglesOnTheLetterPath() {
        val rare = EngineFixture.supplementary(700)
        val decoder = PinyinDecoder(EngineFixture.build(listOf(
            EngineFixture.Row("ce", rare, 10),
            EngineFixture.Row("ce", "测", 10),
            EngineFixture.Row("ce", "册", 9),
        )))
        val words = words(decoder.decodeCovered("ce", 10))

        assertEquals("same-frequency common BMP char beats source-earlier supplementary rare", "测", words.first())
        assertTrue("supplementary rare remains reachable", rare in words)
        assertTrue("same-frequency common char ranks before the supplementary rare", words.indexOf("测") < words.indexOf(rare))
    }

    @Test fun tiedSupplementarySinglesFallBehindCommonSinglesOnTheT9Path() {
        val rare = EngineFixture.supplementary(701)
        val ceDigits = T9Pinyin.toT9("ce")
        val decoder = PinyinDecoder(EngineFixture.build(listOf(
            EngineFixture.Row(ceDigits, rare, 10),
            EngineFixture.Row(ceDigits, "测", 10),
            EngineFixture.Row(ceDigits, "册", 9),
        )))
        val words = words(decoder.decodeCovered(ceDigits, 10))

        assertEquals("same-frequency common BMP char beats source-earlier supplementary rare on T9", "测", words.first())
        assertTrue("supplementary rare remains reachable on T9", rare in words)
        assertTrue("same-frequency common char ranks before the supplementary rare on T9", words.indexOf("测") < words.indexOf(rare))
    }

    @Test fun prefixCompletionsDoNotLetTiedSupplementarySinglesCrowdOutCommonWords() {
        val rare = EngineFixture.supplementary(702)
        val rows = ArrayList<EngineFixture.Row>()
        rows.add(EngineFixture.Row("cea", rare, 10))
        rows.add(EngineFixture.Row("ceb", "测词", 10))
        rows.add(EngineFixture.Row("cec", "册词", 9))
        for (i in 0 until 200) rows.add(EngineFixture.Row("cez${i.toString().padStart(3, '0')}", "低$i", 1))
        val decoder = PinyinDecoder(EngineFixture.build(rows))
        val words = decoder.decodeCovered("ce", 30).map { it.word }

        assertEquals("same-frequency common word wins the prefix completion tie", "测词", words.first())
        assertTrue("supplementary rare remains reachable in prefix completions", rare in words)
    }

    @Test fun boundedPrefixCompletionsKeepTieBreakForShortLetterAndDigitPrefixes() {
        val letterRare = EngineFixture.supplementary(703)
        val letterRows = ArrayList<EngineFixture.Row>()
        letterRows.add(EngineFixture.Row("cea", letterRare, 10))
        letterRows.add(EngineFixture.Row("ceb", "测", 10))
        for (i in 0 until 200) letterRows.add(EngineFixture.Row("cez${i.toString().padStart(3, '0')}", "低$i", 1))
        val letterWords = PinyinDecoder(EngineFixture.build(letterRows)).decodeCovered("ce", 30).map { it.word }

        assertEquals("same-frequency common BMP char wins the bounded letter-prefix tie", "测", letterWords.first())
        assertTrue("supplementary rare remains reachable in bounded letter-prefix completions", letterRare in letterWords)

        val digitRare = EngineFixture.supplementary(704)
        val digitRows = ArrayList<EngineFixture.Row>()
        digitRows.add(EngineFixture.Row("22", digitRare, 10))
        digitRows.add(EngineFixture.Row("23", "测", 10))
        for (i in 0 until 200) digitRows.add(EngineFixture.Row("29${i.toString().padStart(3, '0')}", "低$i", 1))
        val digitWords = PinyinDecoder(EngineFixture.build(digitRows)).decodeCovered("2", 30).map { it.word }

        assertEquals("same-frequency common BMP char wins the bounded digit-prefix tie", "测", digitWords.first())
        assertTrue("supplementary rare remains reachable in bounded digit-prefix completions", digitRare in digitWords)
    }

    @Test fun oneUnitPrefixCompletionsUseTheBoundedTopIndexOnLiveDecodePaths() {
        val fanout = 300
        val letterRows = ArrayList<EngineFixture.Row>()
        for (i in 0 until fanout) {
            letterRows.add(EngineFixture.Row("sa${i.toString().padStart(3, '0')}", "低频字$i", 1))
        }
        letterRows.add(EngineFixture.Row("sz999", "高频词", 5000))
        val letterDict = EngineFixture.build(letterRows)
        val letterTop = letterDict.prefixByFreq("s", fanout + 10)
        assertTrue("one-letter prefix index must not materialize every matching key", letterTop.size < letterRows.size)
        assertEquals("高频词", letterTop.first().word)

        val letterDecoder = PinyinDecoder(letterDict)
        assertEquals("高频词", letterDecoder.decodeCovered("s", 1).single().word)

        val digitRows = ArrayList<EngineFixture.Row>()
        for (i in 0 until fanout) {
            digitRows.add(EngineFixture.Row("92${i.toString().padStart(3, '0')}", "低频九$i", 1))
        }
        digitRows.add(EngineFixture.Row("99", "高频九", 5000))
        val digitDict = EngineFixture.build(digitRows)
        val digitTop = digitDict.prefixByFreq("9", fanout + 10)
        assertTrue("one-digit prefix index must not materialize every matching key", digitTop.size < digitRows.size)
        assertEquals("高频九", digitTop.first().word)

        val digitDecoder = PinyinDecoder(digitDict)
        assertEquals("高频九", digitDecoder.decodeCovered("9", 1).single().word)
    }

    @Test fun supplementaryPlaneHanContextConditionsDecodeCoveredButBmpSymbolsDoNot() {
        val plane3Han = 0x30000
        val star = 0x2605
        val yijing = 0x4DC0
        val ge = '各'.code
        val plain = '个'.code
        val context = String(Character.toChars(plane3Han))
        val tinyDict = EngineFixture.build(listOf(
            EngineFixture.Row("ge", "个", 1000),
            EngineFixture.Row("ge", "各", 900),
        ))
        val tinyLm = lm(
            mapOf(plane3Han to 1000L, star to 1000L, yijing to 1000L, ge to 1000L, plain to 1000L),
            mapOf((plane3Han to ge) to 1000L, (star to ge) to 1000L, (yijing to ge) to 1000L),
        )
        val decoder = PinyinDecoder(tinyDict, tinyLm)

        assertEquals("Plane 3 CJK context should affect ranking", "各", decoder.decodeCovered("ge", 10, context = context).first().word)
        assertEquals("an obvious BMP symbol must break context", "个", decoder.decodeCovered("ge", 10, context = "★").first().word)
        assertEquals(
            "U+4DC0 must break context even though a broad numeric Han range would include it",
            "个",
            decoder.decodeCovered("ge", 10, context = String(Character.toChars(yijing))).first().word,
        )
    }


    private fun assertCleanAtomic(readings: List<String>, vararg topWords: String) {
        val c = locked(readings)
        val w = words(c)
        val full = readings.joinToString("").length
        assertFalse("$readings: no extension-area single in the top 10", w.take(10).any { isSupp(it) })
        for (tw in topWords) assertTrue("$readings: $tw must be in #1/#2", w.take(2).contains(tw))
        assertFalse("$readings: NO candidate may contain 西安 (a locked syllable is never re-split)", w.any { it.contains("西安") })
        for (s in pureSentences(c, full)) assertEquals(
            "$readings: every pure-sentence candidate '$s' spans exactly ${readings.size} syllables",
            readings.size, s.codePointCount(0, s.length),
        )
    }

    @Test fun ciku_keepsTheWordAndCommonChars() {
        assertCleanAtomic(listOf("ci", "ku"), "词库")
        val w = words(locked(listOf("ci", "ku")))
        val singles = w.filter { it.codePointCount(0, it.length) == 1 }
        assertTrue("a single layer exists", singles.isNotEmpty())
        assertTrue("the list closes on single characters", w.takeLastWhile { it.codePointCount(0, it.length) == 1 }.isNotEmpty())
        assertEquals("common ci 同音字 lead the single layer", listOf("次", "此"), singles.take(2))
    }

    @Test fun jiujian_keepsTheWord() {
        assertCleanAtomic(listOf("jiu", "jian"), "九键")
    }

    @Test fun diuzi_surfacesDiuziAndZiIsNavigable() {
        val c = locked(listOf("diu", "zi"))
        val w = words(c)
        assertFalse("no extension-area single in the top 10", w.take(10).any { isSupp(it) })
        assertTrue("丢字 present", "丢字" in w)
        assertTrue("字 navigable at syllable 1", "字" in d.homophonesAt("diuzi", 1))
        pureSentences(c, 5).forEach { assertEquals(2, it.codePointCount(0, it.length)) }
    }

    @Test fun bushixian_keepsBushixianDropsXian() {
        val c = locked(listOf("bu", "shi", "xian"))
        val w = words(c)
        assertFalse("no extension-area single in the top 10", w.take(10).any { isSupp(it) })
        assertFalse("NO candidate contains 西安", w.any { it.contains("西安") })
        assertTrue("不实现 present", "不实现" in w)
        val singles = w.filter { it.codePointCount(0, it.length) == 1 }
        assertTrue("a single layer exists", singles.isNotEmpty())
        assertTrue("the list closes on single characters", w.takeLastWhile { it.codePointCount(0, it.length) == 1 }.isNotEmpty())
        assertEquals("common bu 同音字 lead the single layer", listOf("不", "部"), singles.take(2))
        assertTrue("现 navigable at the last syllable", "现" in d.homophonesAt("bushixian", 2))
        pureSentences(c, "bushixian".length).forEach {
            assertEquals("every pure sentence spans 3 syllables", 3, it.codePointCount(0, it.length))
        }
    }

    @Test fun shixian_surfacesShixianAsTheLeadingWord() {
        val w = words(locked(listOf("shi", "xian")))
        assertTrue("实现 in #1/#2", w.take(2).contains("实现"))
        assertFalse("no 西安", w.any { it.contains("西安") })
    }

    @Test fun aLockedFirstSyllableIsNeverReSplitIntoSubReadings() {
        val w = words(locked(listOf("xian", "ku")))
        assertTrue("现 (the xian reading) present", "现" in w)
        assertFalse("西 (the xi sub-reading) must NOT appear — the locked xian is atomic", "西" in w)
        assertFalse("no 西安 either", w.any { it.contains("西安") })
        assertTrue("control: unlocked xianku still surfaces 西 (xi)", "西" in words(d.decodeCovered("xianku", 30)))
    }

    @Test fun aSingleLockedSyllableIsAtomicToo() {
        val w = words(d.decodeCoveredAtomic("xiang", 30))

        assertTrue("common xiang homophones stay prominent", w.take(7).containsAll(listOf("向", "想", "相", "像", "香")))
        assertFalse("selected xiang must not leak xian candidates", "西安" in w)
        assertFalse("selected xiang must not leak xi prefix singles", "西" in w)
        assertFalse("selected xiang must not leak xia prefix singles", "下" in w)
    }

    @Test fun aPartiallyLockedFirstSyllableIsAtomicButCanJoinTheTail() {
        val w = words(d.decodeCoveredAtomic("xiangku", 30, setOf("xiang".length)))

        assertTrue("cross-boundary word remains available", "想哭" in w.take(3))
        assertEquals("the top xiang homophones lead the single-character tail", listOf("向", "想", "相"),
            w.filter { it.codePointCount(0, it.length) == 1 }.take(3))
        assertTrue("all common xiang homophones remain reachable", w.containsAll(listOf("向", "想", "相", "像", "香")))
        assertFalse("selected xiang must not leak xian candidates", "西安" in w)
        assertFalse("selected xiang must not leak xi prefix singles", "西" in w)
        assertFalse("selected xiang must not leak xia prefix singles", "下" in w)

        val unlocked = words(d.decodeCovered("xiangku", 30))
        assertTrue("control: free typing still keeps the xian prefix candidate", "西安" in unlocked)
        assertTrue("control: free typing still keeps the xi prefix single", "西" in unlocked)
        assertTrue("control: free typing still keeps the xia prefix single", "下" in unlocked)
    }
}
