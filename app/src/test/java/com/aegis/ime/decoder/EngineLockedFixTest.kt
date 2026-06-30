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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
