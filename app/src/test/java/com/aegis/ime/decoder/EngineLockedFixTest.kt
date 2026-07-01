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

/**
 * debug.18 engine fixes, proven against the [EngineFixture] dict (the committed seed asset is freq≥400 with
 * no extension-area rares, so it CANNOT reproduce these). This is the SHARED decoder layer both keyboards use:
 * a 26-key 隔音符 buffer and a 9-key locked-reading buffer both reach [PinyinDecoder.decodeCovered] with the
 * SAME (clean letters, interior cuts), so proving it here proves it for both layouts. The per-layout entry
 * (cut derivation) is covered by [com.aegis.ime.ime.BothKeyboardsAtomicFixTest].
 *
 *  FIX-1: codePointCount (not String.length) decides "single char", so U+20000+ rares stop flooding the grid
 *         AND start appearing in the homophone layer.
 *  FIX-2: a buffer with forced syllable boundaries decodes BOUNDARY-ALIGNED & atomic — a locked syllable is
 *         never re-split (no 西安 from xian), and multi-syllable words (实现/九键/词库) are kept.
 */
class EngineLockedFixTest {

    private val dict = EngineFixture.dict()
    private val d = PinyinDecoder(dict)

    private fun isSupp(s: String) = s.codePointCount(0, s.length) == 1 && Character.isSupplementaryCodePoint(s.codePointAt(0))
    private fun words(c: List<Cand>) = c.map { it.word }

    /** The 9-key locked / 26-key 隔音符 decode: cumulative syllable boundaries → interior cuts → decodeCovered. */
    private fun locked(readings: List<String>): List<Cand> {
        val full = readings.joinToString("")
        val cuts = HashSet<Int>(); var acc = 0
        for (r in readings) { acc += r.length; if (acc < full.length) cuts.add(acc) }
        return d.decodeCovered(full, 30, cuts)
    }

    /** Whole-buffer ("pure sentence") candidates = those covering every letter. */
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

    // ---------------- FIX-1: extension-area rares ----------------

    @Test fun unlockedDecodeDoesNotFloodTheFrontWithSupplementarySingles() {
        // BEFORE: decodeCovered("ciku") = [词库, 𠀀, 𠀁, … 𠀍] — 14 surrogate-pair singles misjudged as words.
        val top = words(d.decodeCovered("ciku", 30))
        assertFalse("no extension-area single floods the front", top.take(15).any { isSupp(it) })
        assertEquals("the real word still leads", "词库", top.first())
        assertTrue("the common 同音字 follow it", top.take(6).containsAll(listOf("次", "此", "词")))
    }

    @Test fun homophoneLayerIncludesSupplementaryAtItsFrequencyTail() {
        // BEFORE: homophonesOf used length==1 → surrogate-pair singles were DROPPED from navigation entirely.
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
        val decoder = PinyinDecoder(EngineFixture.build(listOf(
            EngineFixture.Row("cea", rare, 10),
            EngineFixture.Row("ceb", "测词", 10),
            EngineFixture.Row("cec", "册词", 9),
        )))
        val words = decoder.decode("ce", 3)

        assertEquals("same-frequency common word wins the prefix completion tie", "测词", words.first())
        assertTrue("supplementary rare remains reachable in prefix completions", rare in words)
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

    // ---------------- FIX-2: boundary-aligned atomic decode (per the 4 inputs) ----------------

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
        assertTrue("common ci 同音字 follow the word", w.take(6).containsAll(listOf("次", "此")))
    }

    @Test fun jiujian_keepsTheWord() {
        assertCleanAtomic(listOf("jiu", "jian"), "九键")
    }

    @Test fun diuzi_surfacesDiuziAndZiIsNavigable() {
        val c = locked(listOf("diu", "zi"))
        val w = words(c)
        assertFalse("no extension-area single in the top 10", w.take(10).any { isSupp(it) })
        assertTrue("丢字 present", "丢字" in w)
        // 字 (the 2nd syllable) reaches the grid via per-syllable navigation, not the leading strip.
        assertTrue("字 navigable at syllable 1", "字" in d.homophonesAt("diuzi", 1))
        pureSentences(c, 5).forEach { assertEquals(2, it.codePointCount(0, it.length)) }
    }

    @Test fun bushixian_keepsBushixianDropsXian() {
        // The headline case: 现 surfaces, 不实现 is present, and the locked xian is NEVER split
        // into xi|an → 西安 (the 600-freq 西安 keyed by the single syllable is dropped).
        val c = locked(listOf("bu", "shi", "xian"))
        val w = words(c)
        assertFalse("no extension-area single in the top 10", w.take(10).any { isSupp(it) })
        assertFalse("NO candidate contains 西安", w.any { it.contains("西安") })
        assertTrue("不实现 present", "不实现" in w)
        assertTrue("common bu 同音字 follow the best sentence", w.take(6).containsAll(listOf("不", "部")))
        assertTrue("现 navigable at the last syllable", "现" in d.homophonesAt("bushixian", 2))
        pureSentences(c, "bushixian".length).forEach {
            assertEquals("every pure sentence spans 3 syllables", 3, it.codePointCount(0, it.length))
        }
    }

    @Test fun shixian_surfacesShixianAsTheLeadingWord() {
        // 实现 IS leading-aligned in shi'xian (covers the whole buffer), so it is a #1/#2 candidate here. In
        // bu'shi'xian it is non-leading and surfaces as part of 不实现 / after the 不 prefix is committed.
        val w = words(locked(listOf("shi", "xian")))
        assertTrue("实现 in #1/#2", w.take(2).contains("实现"))
        assertFalse("no 西安", w.any { it.contains("西安") })
    }

    @Test fun aLockedFirstSyllableIsNeverReSplitIntoSubReadings() {
        // The declared first syllable `xian` lists ONLY its own 同音字 (现 县 …) — it is NOT re-split into the
        // sub-readings the user did not ask for: 西 (xi, the leak that the unlocked lossless layer DOES emit).
        val w = words(locked(listOf("xian", "ku")))
        assertTrue("现 (the xian reading) present", "现" in w)
        assertFalse("西 (the xi sub-reading) must NOT appear — the locked xian is atomic", "西" in w)
        assertFalse("no 西安 either", w.any { it.contains("西安") })
        // sanity: the UNLOCKED buffer DOES surface 西 (the ambiguous xi'an split) — proving the difference is the lock.
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
        assertTrue("xiang homophones remain prominent", w.take(8).containsAll(listOf("向", "想", "相", "像", "香")))
        assertFalse("selected xiang must not leak xian candidates", "西安" in w)
        assertFalse("selected xiang must not leak xi prefix singles", "西" in w)
        assertFalse("selected xiang must not leak xia prefix singles", "下" in w)

        val unlocked = words(d.decodeCovered("xiangku", 30))
        assertTrue("control: free typing still keeps the xian prefix candidate", "西安" in unlocked)
        assertTrue("control: free typing still keeps the xi prefix single", "西" in unlocked)
        assertTrue("control: free typing still keeps the xia prefix single", "下" in unlocked)
    }
}
