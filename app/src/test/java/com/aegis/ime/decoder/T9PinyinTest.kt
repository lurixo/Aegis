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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class T9PinyinTest {

    @Test fun nide_segments_to_pinyin_not_digits() {
        assertEquals("6433", T9Pinyin.toT9("nide"))
        val pre = T9Pinyin.preedit("6433")
        assertTrue("preedit should be pinyin, was '$pre'", pre.none { it in '0'..'9' })
        assertEquals("ni'de", pre)
    }

    @Test fun nihao_segments() {
        assertEquals("64426", T9Pinyin.toT9("nihao"))
        assertEquals("ni'hao", T9Pinyin.preedit("64426"))
    }

    @Test fun common_reading_wins_ambiguous_group() {
        assertEquals("ni'de", T9Pinyin.preedit("6433"))
    }

    @Test fun whole_syllable_digit_groups_stay_atomic_in_preedit() {
        val readings = listOf(
            "deng", "feng", "geng", "heng", "keng", "leng", "mang", "nang",
            "tang", "weng", "xing", "ying", "zeng", "zhei", "zhua",
        )
        for (reading in readings) {
            val digits = T9Pinyin.toT9(reading)
            val canonical = T9Pinyin.syllableReading(digits)
            assertTrue("$reading must have a whole-syllable reading for $digits", canonical.isNotEmpty())
            assertEquals("$reading ($digits) must use the atomic reading", canonical, T9Pinyin.preedit(digits))
            assertTrue("$reading ($digits) must stay whole", '\'' !in T9Pinyin.preedit(digits))
        }
    }

    @Test fun first_syllable_options_nonEmpty_and_pinyin() {
        val opts = T9Pinyin.firstSyllableOptions("6433", 4)
        assertTrue(opts.isNotEmpty())
        assertTrue(opts.all { s -> s.all { it in 'a'..'z' } })
        assertTrue("ni should be an option for 64..", opts.contains("ni"))
    }

    @Test fun first_syllable_options_surface_the_full_syllable_xuan_yuan() {
        val opts = T9Pinyin.firstSyllableOptions("9826", 4)
        assertTrue("xuan must be offered, was $opts", opts.contains("xuan"))
        assertTrue("yuan must be offered, was $opts", opts.contains("yuan"))
        assertTrue("xian must be offered", T9Pinyin.firstSyllableOptions("9426", 4).contains("xian"))
    }

    @Test fun syllabic_nasals_are_known_syllables() {
        assertEquals(listOf("ng"), T9Pinyin.segmentLetters("ng"))
        assertEquals(listOf("n"), T9Pinyin.segmentLetters("n"))
        assertEquals(listOf("m"), T9Pinyin.segmentLetters("m"))
        assertTrue("ng should be selectable from its T9 code", "ng" in T9Pinyin.firstSyllableOptions("64", 6))
        assertEquals("ni", T9Pinyin.preedit("64"))
    }

    @Test fun source_backed_rare_readings_are_segmentable_and_selectable() {
        for (reading in listOf("cei", "fiao", "tei")) {
            assertEquals(listOf(reading), T9Pinyin.segmentLetters(reading))
            assertTrue(
                "$reading should be selectable from its T9 code",
                reading in T9Pinyin.leftColumnReadings(T9Pinyin.toT9(reading), 32),
            )
        }
    }

    @Test fun jiangzhi_keeps_the_jiang_boundary_in_continuous_input() {
        assertEquals(listOf("jiang", "zhi"), T9Pinyin.segmentLetters("jiangzhi"))

        val digits = T9Pinyin.toT9("jiangzhi")
        assertTrue("jiang must be selectable from its T9 code", "jiang" in T9Pinyin.leftColumnReadings(digits, 12))
        val locked = T9Pinyin.lockFirstReading(digits, "jiang")!!
        assertEquals("jiang'zhi", locked.display)
        assertEquals("jiangzhi", locked.letters)
    }

    @Test fun partial_buffer_still_shows_something() {
        val pre = T9Pinyin.preedit("6")
        assertTrue(pre.isNotEmpty())
    }

    @Test fun midsyllable_tail_never_shows_a_digit() {
        val pre = T9Pinyin.preedit("647")
        assertTrue("preedit leaked a digit: '$pre'", pre.none { it in '0'..'9' })
        assertTrue("preedit should keep the confirmed prefix: '$pre'", pre.startsWith("ni"))
    }

    @Test fun preedit_renders_forced_cuts_as_separators() {
        assertEquals("ni'", T9Pinyin.preedit("64", setOf(2)))
        assertTrue(T9Pinyin.preedit("6433", setOf(2)).startsWith("ni'"))
        assertEquals(T9Pinyin.preedit("6433"), T9Pinyin.preedit("6433", emptySet()))
    }

    @Test fun letter_preedit_prefers_whole_syllables_and_segments_complete_sequences() {
        assertEquals("ni'hao", T9Pinyin.preeditLetters("nihao"))
        assertEquals("xian", T9Pinyin.preeditLetters("xian"))
        assertEquals("ni'hao'z", T9Pinyin.preeditLetters("nihaoz"))
    }

    @Test fun letter_preedit_renders_forced_cuts_without_changing_the_raw_letters() {
        assertEquals("xi'an", T9Pinyin.preeditLetters("xian", setOf(2)))
        assertEquals("xi'", T9Pinyin.preeditLetters("xi", setOf(2)))
        assertEquals("chai'ci", T9Pinyin.preeditLetters("chai'ci"))
    }

    @Test fun letter_reading_column_exposes_every_reachable_leading_syllable_and_fallback() {
        val readings = T9Pinyin.leftColumnLetterReadings("xian", 24)
        assertEquals("xian", readings.first())
        assertTrue("xian must keep the xi|an path reachable, was $readings", "xi" in readings)
        assertTrue("single-letter fallback must remain reachable, was $readings", "x" in readings)
        assertTrue("the next layer must not appear early", "an" !in readings)
    }

    @Test fun longest_decodable_prefix_drops_unfinished_tail() {
        assertEquals("64", T9Pinyin.longestDecodablePrefix("647"))
        assertEquals("6433", T9Pinyin.longestDecodablePrefix("6433"))
        assertEquals("", T9Pinyin.longestDecodablePrefix(""))
    }

    @Test fun lock_first_reading_keeps_the_rest_of_the_buffer() {
        val r = T9Pinyin.lockFirstReading("6433", "ni")!!
        assertEquals("ni'de", r.display)
        assertEquals("nide", r.letters)
    }

    @Test fun lock_first_reading_single_syllable_buffer() {
        val r = T9Pinyin.lockFirstReading("64", "ni")!!
        assertEquals("ni", r.display)
        assertEquals("ni", r.letters)
    }

    @Test fun lock_first_reading_rejects_non_prefix_reading() {
        assertNull(T9Pinyin.lockFirstReading("64", "mie"))
    }


    private fun assertCleanColumn(opts: List<String>) {
        assertTrue("no empty placeholder slots, was $opts", opts.none { it.isEmpty() })
        assertTrue("only a-z (no punctuation / digits), was $opts", opts.all { s -> s.all { it in 'a'..'z' } })
        assertEquals("no duplicates, was $opts", opts.size, opts.toSet().size)
    }

    @Test fun left_column_ceshi_is_syllable_then_first_key_letters() {
        val opts = T9Pinyin.leftColumnReadings("23744", 4)
        assertEquals(listOf("ce", "a", "b", "c"), opts)
        assertCleanColumn(opts)
    }

    @Test fun left_column_ni_full_content_matches_reference() {
        val opts = T9Pinyin.leftColumnReadings("64744336488", 6)
        assertTrue("must offer ni, was $opts", "ni" in opts)
        assertTrue("must offer mi, was $opts", "mi" in opts)
        assertTrue("must offer the first-key letters m/n/o, was $opts", listOf("m", "n", "o").all { it in opts })
        assertCleanColumn(opts)
    }

    @Test fun left_column_ni_at_production_limit_keeps_the_real_syllables() {
        val opts = T9Pinyin.leftColumnReadings("64744336488", 4)
        assertTrue("ni must survive the cap, was $opts", "ni" in opts)
        assertTrue("mi must survive the cap, was $opts", "mi" in opts)
        assertCleanColumn(opts)
    }

    @Test fun left_column_can_reach_xuan_9826() {
        assertTrue("xuan must be offered", "xuan" in T9Pinyin.leftColumnReadings("9826", 6))
    }

    @Test fun left_column_multi_candidate_shi() {
        val opts = T9Pinyin.leftColumnReadings("744", 6)
        assertTrue("shi must be offered, was $opts", "shi" in opts)
        assertCleanColumn(opts)
    }

    @Test fun left_column_single_ambiguous_key_shows_letters_not_blanks() {
        assertEquals(listOf("w", "x", "y", "z"), T9Pinyin.leftColumnReadings("9", 4))
    }

    @Test fun left_column_is_deterministic_same_input_same_output() {
        repeat(5) { assertEquals(T9Pinyin.leftColumnReadings("23744", 4), T9Pinyin.leftColumnReadings("23744", 4)) }
        assertEquals(T9Pinyin.leftColumnReadings("9826", 6), T9Pinyin.leftColumnReadings("9826", 6))
    }

    @Test fun left_column_respects_the_limit_and_handles_blank() {
        assertTrue(T9Pinyin.leftColumnReadings("64744336488", 4).size <= 4)
        assertTrue(T9Pinyin.leftColumnReadings("", 4).isEmpty())
        assertTrue(T9Pinyin.leftColumnReadings("1", 4).isEmpty())
    }

    @Test fun leftColumnPagesPreserveEveryAmbiguousReadingInOrder() {
        val digits = "742642"
        val reference = T9Pinyin.leftColumnReadings(digits, Int.MAX_VALUE)
        val actual = ArrayList<String>()
        val pageSizes = ArrayList<Int>()
        var page = T9Pinyin.leftColumnReadingsPage(digits, inputEpoch = 23L, pageSize = 5)
        while (true) {
            pageSizes.add(page.items.size)
            actual.addAll(page.items)
            val continuation = page.continuation ?: break
            page = continueCandidatePage(continuation, inputEpoch = 23L, pageSize = 5)
        }

        assertEquals(18, reference.size)
        assertEquals(listOf(5, 5, 5, 3), pageSizes)
        assertEquals(reference, actual)
    }
}
