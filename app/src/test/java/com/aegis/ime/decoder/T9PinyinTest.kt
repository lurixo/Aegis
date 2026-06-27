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
        // n6 i4 d3 e3 -> "6433"; the preedit must be readable pinyin, never the raw digits.
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
        // "64" encodes both mi and ni; "de" is far more common after it -> ni'de over mi'de.
        assertEquals("ni'de", T9Pinyin.preedit("6433"))
    }

    @Test fun first_syllable_options_nonEmpty_and_pinyin() {
        val opts = T9Pinyin.firstSyllableOptions("6433", 4)
        assertTrue(opts.isNotEmpty())
        assertTrue(opts.all { s -> s.all { it in 'a'..'z' } })
        assertTrue("ni should be an option for 64..", opts.contains("ni"))
    }

    @Test fun first_syllable_options_surface_the_full_syllable_xuan_yuan() {
        // xuan/yuan = 9826 (x,y both on key 9). The left column (limit 4) must offer the WHOLE syllables,
        // not just the 2-letter prefixes yu/wu/xu/zu — else the user can never pick xuan to reach 选 (★T).
        val opts = T9Pinyin.firstSyllableOptions("9826", 4)
        assertTrue("xuan must be offered, was $opts", opts.contains("xuan"))
        assertTrue("yuan must be offered, was $opts", opts.contains("yuan"))
        // xian = 9426 likewise
        assertTrue("xian must be offered", T9Pinyin.firstSyllableOptions("9426", 4).contains("xian"))
    }

    @Test fun partial_buffer_still_shows_something() {
        val pre = T9Pinyin.preedit("6") // mid-syllable, not a full syllable
        assertTrue(pre.isNotEmpty())
    }

    @Test fun midsyllable_tail_never_shows_a_digit() {
        // ★N: "647" = ni(64) + a half-typed 7 (PQRS); preedit must stay pinyin-like, never leak 0-9.
        val pre = T9Pinyin.preedit("647")
        assertTrue("preedit leaked a digit: '$pre'", pre.none { it in '0'..'9' })
        assertTrue("preedit should keep the confirmed prefix: '$pre'", pre.startsWith("ni"))
    }

    @Test fun preedit_renders_forced_cuts_as_separators() {
        // a forced boundary must show as 隔音符 ' — including a trailing one right after 分词.
        assertEquals("ni'", T9Pinyin.preedit("64", setOf(2)))          // boundary at the very end
        assertTrue(T9Pinyin.preedit("6433", setOf(2)).startsWith("ni'")) // internal boundary kept in place
        assertEquals(T9Pinyin.preedit("6433"), T9Pinyin.preedit("6433", emptySet())) // no cuts == plain preedit
    }

    @Test fun longest_decodable_prefix_drops_unfinished_tail() {
        assertEquals("64", T9Pinyin.longestDecodablePrefix("647")) // ni + half-typed 7
        assertEquals("6433", T9Pinyin.longestDecodablePrefix("6433")) // already fully decodable
        assertEquals("", T9Pinyin.longestDecodablePrefix("")) // nothing to decode
    }

    @Test fun lock_first_reading_keeps_the_rest_of_the_buffer() {
        // #12b: picking "ni" over "6433" must keep the trailing "de", not collapse to "ni".
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
        assertNull(T9Pinyin.lockFirstReading("64", "mie")) // mie -> 643, not a prefix of 64
    }

    // ---- 9-key left column = real readings only (syllables + 首键字母), no blanks/punct ----

    private fun assertCleanColumn(opts: List<String>) {
        assertTrue("no empty placeholder slots, was $opts", opts.none { it.isEmpty() })
        assertTrue("only a-z (no punctuation / digits), was $opts", opts.all { s -> s.all { it in 'a'..'z' } })
        assertEquals("no duplicates, was $opts", opts.size, opts.toSet().size)
    }

    @Test fun left_column_ceshi_is_syllable_then_first_key_letters() {
        // ce'shi: first key '2' (ABC). Expect the real syllable ce + the first-key letters a,b,c — exactly
        // the second expected output, and crucially NOT the first's "ce, a, ▢, ▢" (the blank-pad bug).
        val opts = T9Pinyin.leftColumnReadings("23744", 4) // c-e-s-h-i
        assertEquals(listOf("ce", "a", "b", "c"), opts)
        assertCleanColumn(opts)
    }

    @Test fun left_column_ni_full_content_matches_reference() {
        // ni'shuo'de'bu'dui (expected candidate layout): first key '6' (MNO) → mi, ni + the letters o, m, n.
        // Asserted at the expanded-screen depth (limit 6) where the whole reference set is present.
        val opts = T9Pinyin.leftColumnReadings("64744336488", 6)
        assertTrue("must offer ni, was $opts", "ni" in opts)
        assertTrue("must offer mi, was $opts", "mi" in opts)
        assertTrue("must offer the first-key letters m/n/o, was $opts", listOf("m", "n", "o").all { it in opts })
        assertCleanColumn(opts)
    }

    @Test fun left_column_ni_at_production_limit_keeps_the_real_syllables() {
        // Guards the actual on-device depth (the resting peanut shows ≤ NINE_LEFT_SLOTS): the real
        // syllable readings ni & mi must survive the cap (only the bare first-key letters may be trimmed),
        // and the column stays clean. (Closes the limit-6-only test blind spot.)
        val opts = T9Pinyin.leftColumnReadings("64744336488", 4)
        assertTrue("ni must survive the cap, was $opts", "ni" in opts)
        assertTrue("mi must survive the cap, was $opts", "mi" in opts)
        assertCleanColumn(opts)
    }

    @Test fun left_column_can_reach_xuan_9826() {
        // 9826 = xuan/yuan share a T9 code — xuan must be selectable from the column (★T regression guard).
        assertTrue("xuan must be offered", "xuan" in T9Pinyin.leftColumnReadings("9826", 6))
    }

    @Test fun left_column_multi_candidate_shi() {
        val opts = T9Pinyin.leftColumnReadings("744", 6) // shi
        assertTrue("shi must be offered, was $opts", "shi" in opts)
        assertCleanColumn(opts)
    }

    @Test fun left_column_single_ambiguous_key_shows_letters_not_blanks() {
        // A bare "9" forms no syllable — show the key's letters w,x,y,z, NEVER empty placeholders.
        assertEquals(listOf("w", "x", "y", "z"), T9Pinyin.leftColumnReadings("9", 4))
    }

    @Test fun left_column_is_deterministic_same_input_same_output() {
        // Locks the A-vs-B "same input ce'shi, different column" inconsistency on debug.8.
        repeat(5) { assertEquals(T9Pinyin.leftColumnReadings("23744", 4), T9Pinyin.leftColumnReadings("23744", 4)) }
        assertEquals(T9Pinyin.leftColumnReadings("9826", 6), T9Pinyin.leftColumnReadings("9826", 6))
    }

    @Test fun left_column_respects_the_limit_and_handles_blank() {
        assertTrue(T9Pinyin.leftColumnReadings("64744336488", 4).size <= 4)
        assertTrue(T9Pinyin.leftColumnReadings("", 4).isEmpty())
        assertTrue(T9Pinyin.leftColumnReadings("1", 4).isEmpty()) // 0/1 aren't T9 letter keys
    }
}
