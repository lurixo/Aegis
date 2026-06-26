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

    @Test fun partial_buffer_still_shows_something() {
        val pre = T9Pinyin.preedit("6") // mid-syllable, not a full syllable
        assertTrue(pre.isNotEmpty())
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
}
