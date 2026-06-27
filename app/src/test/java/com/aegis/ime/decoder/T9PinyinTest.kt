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

    @Test fun partial_buffer_still_shows_something() {
        val pre = T9Pinyin.preedit("6")
        assertTrue(pre.isNotEmpty())
    }

    @Test fun midsyllable_tail_never_shows_a_digit() {
        val pre = T9Pinyin.preedit("647")
        assertTrue("preedit leaked a digit: '$pre'", pre.none { it in '0'..'9' })
        assertTrue("preedit should keep the confirmed prefix: '$pre'", pre.startsWith("ni"))
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
}
