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

    @Test fun left_column_never_offers_letters_that_start_no_syllable() {
        assertEquals(listOf("t"), T9Pinyin.leftColumnReadings("8", 4))
        assertEquals(listOf("g", "h"), T9Pinyin.leftColumnReadings("4", 4))
        for (digits in listOf("4", "8", "44", "48", "84", "88", "448", "884")) {
            val col = T9Pinyin.leftColumnReadings(digits, 24)
            assertTrue("$digits offers no i/u/v, was $col", col.none { it in setOf("i", "u", "v") })
        }
        for (letters in listOf("i", "u", "v", "iu", "uv", "vi")) {
            val col = T9Pinyin.leftColumnLetterReadings(letters, 24)
            assertTrue("$letters offers no i/u/v, was $col", col.none { it in setOf("i", "u", "v") })
        }
        assertEquals(listOf("tu", "t"), T9Pinyin.leftColumnLetterReadings("tu", 24))
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

    @Test fun a_whole_unlisted_syllable_beats_a_pair_of_frequent_fragments() {
        assertEquals(listOf("ying"), T9Pinyin.segment("9464"))
        assertEquals(listOf("zhei"), T9Pinyin.segment("9434"))
        assertEquals(listOf("zhua"), T9Pinyin.segment("9482"))
        assertEquals(listOf("feng"), T9Pinyin.segment("3364"))
        assertEquals(listOf("mang"), T9Pinyin.segment("6264"))
        assertEquals(listOf("ying"), T9Pinyin.segmentLetters("ying"))
    }

    @Test fun guessed_letters_stay_one_to_one_with_the_digits() {
        for (digits in listOf("64426", "94664486", "96636", "9", "77", "1", "6426")) {
            assertEquals(digits, digits.length, T9Pinyin.guessLetters(digits).length)
        }
        assertEquals("nihao", T9Pinyin.guessLetters("64426"))
        assertEquals("", T9Pinyin.guessLetters(""))
    }

    @Test fun letter_runs_follow_the_displayed_apostrophes() {
        assertEquals(listOf(0..1, 2..4), T9Pinyin.letterRuns("nihao"))
        assertEquals(listOf(0..0, 1..3), T9Pinyin.letterRuns("nhao"))
        assertTrue(T9Pinyin.letterRuns("").isEmpty())
    }

    @Test fun deleting_a_digit_removes_only_its_letter() {
        assertEquals("nhao", T9Pinyin.reviseLetters("nihao", "6426", 1, 1, 0))
        assertEquals("ihao", T9Pinyin.reviseLetters("nihao", "4426", 0, 1, 0))
        assertEquals("niha", T9Pinyin.reviseLetters("nihao", "6442", 4, 1, 0))
        assertEquals("n'hao", T9Pinyin.preeditLetters("nhao"))
    }

    @Test fun typing_at_the_tail_reguesses_only_the_last_syllable() {
        var letters = ""
        for (i in "64426".indices) letters = T9Pinyin.reviseLetters(letters, "64426".substring(0, i + 1), i, 0, 1)
        println("sequential 64426 -> $letters")
        assertEquals("nihao", letters)
        assertEquals("nihao", T9Pinyin.reviseLetters("niha", "64426", 4, 0, 1))
        assertEquals("ni" + T9Pinyin.guessLetters("426"), T9Pinyin.reviseLetters("niha", "64426", 4, 0, 1))
        val extended = T9Pinyin.reviseLetters("hao", "4266", 3, 0, 1)
        assertEquals(T9Pinyin.guessLetters("4266"), extended)
        assertTrue(extended, T9Pinyin.preeditLetters(extended).startsWith("hao'"))
        val retyped = T9Pinyin.reviseLetters("mi", "644", 2, 0, 1)
        assertEquals(T9Pinyin.guessLetters("644"), retyped)
        assertTrue(retyped, retyped.startsWith("ni"))
    }

    @Test fun chained_typing_before_a_syllable_reguesses_the_syllable_being_typed() {
        val first = T9Pinyin.reviseLetters("hao", "6426", 0, 0, 1)
        assertEquals("mhao", first)
        assertEquals("nihao", T9Pinyin.reviseLetters(first, "64426", 1, 0, 1, chained = true))
        assertEquals("mihao", T9Pinyin.reviseLetters("mhao", "64426", 1, 0, 1))
        assertEquals("nihao", T9Pinyin.reviseLetters("nhao", "64426", 1, 0, 1, chained = true))
    }

    @Test fun inserting_a_digit_mid_buffer_never_rewrites_the_neighbouring_letters() {
        assertEquals("nihao", T9Pinyin.reviseLetters("nhao", "64426", 1, 0, 1))
        assertEquals("zhong", T9Pinyin.reviseLetters("zong", "94664", 1, 0, 1))
        assertEquals("zhongguo", T9Pinyin.reviseLetters("hongguo", "94664486", 0, 0, 1))
        assertEquals("nizhao", T9Pinyin.reviseLetters("nihao", "649426", 2, 0, 1))
        for (digit in "23456789") {
            val prior = "zhongguo"
            val digits = "94" + digit + "664486"
            val revised = T9Pinyin.reviseLetters(prior, digits, 2, 0, 1)
            assertEquals(digits, prior, revised.removeRange(2, 3))
            val restored = T9Pinyin.reviseLetters(revised, "94664486", 2, 1, 0)
            assertEquals(prior, restored)
        }
    }

    @Test fun inconsistent_revisions_fall_back_to_a_fresh_guess() {
        assertEquals(T9Pinyin.guessLetters("64426"), T9Pinyin.reviseLetters("ni", "64426", 2, 0, 1))
        assertEquals(T9Pinyin.guessLetters("64426"), T9Pinyin.reviseLetters("nihaoo", "64426", 9, 1, 0))
        assertEquals(T9Pinyin.guessLetters("64426"), T9Pinyin.reviseLetters("nih", "64426", 3, 0, 2))
    }
}
