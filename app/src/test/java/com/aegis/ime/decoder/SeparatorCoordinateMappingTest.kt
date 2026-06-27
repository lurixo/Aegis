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
import org.junit.Test

class SeparatorCoordinateMappingTest {

    private fun decoder(): PinyinDecoder = PinyinDecoder(EngineFixture.build(listOf(
        EngineFixture.Row("xian", "先", 900), EngineFixture.Row("xi", "西", 900),
        EngineFixture.Row("an", "安", 900), EngineFixture.Row("ni", "你", 900),
        EngineFixture.Row("hao", "好", 900), EngineFixture.Row("chai", "拆", 900),
        EngineFixture.Row("ci", "次", 900),
    )))

    private fun spans(input: String, cuts: Set<Int> = emptySet()): List<Triple<String, Int, Int>> =
        decoder().syllables(input, cuts).map { Triple(it.reading, it.start, it.end) }

    @Test fun an_explicit_separator_preserves_offsets_in_the_original_input() {
        assertEquals(listOf(Triple("xi", 0, 3), Triple("an", 3, 5)), spans("xi'an"))
        assertEquals(listOf(Triple("xian", 0, 4)), spans("xian"))
        assertEquals(listOf(Triple("xi", 0, 2), Triple("an", 2, 4)), spans("xian", setOf(2)))
    }

    @Test fun supplied_cuts_on_either_side_of_a_separator_use_original_coordinates() {
        assertEquals(listOf(Triple("xi", 0, 2), Triple("an", 2, 5), Triple("xian", 5, 9)),
            spans("xian'xian", setOf(2)))
        assertEquals(listOf(Triple("xian", 0, 5), Triple("xi", 5, 7), Triple("an", 7, 9)),
            spans("xian'xian", setOf(7)))
    }

    @Test fun leading_trailing_and_repeated_separators_keep_their_original_extent() {
        assertEquals(listOf(Triple("xian", 1, 5)), spans("'xian"))
        assertEquals(listOf(Triple("xian", 0, 5)), spans("xian'"))
        assertEquals(listOf(Triple("chai", 0, 6), Triple("ci", 6, 8)), spans("chai''ci"))
        assertEquals(listOf(Triple("ni", 0, 3), Triple("hao", 3, 7)), spans("ni'hao'"))
        assertEquals(emptyList<Triple<String, Int, Int>>(), spans("'''"))
    }

    @Test fun nine_key_readings_keep_separator_and_manual_cut_coordinates() {
        assertEquals(listOf(Triple("ni", 0, 3), Triple("hao", 3, 6)), spans("64'426"))
        assertEquals(listOf(Triple("ni", 0, 2), Triple("hao", 2, 5)), spans("64426", setOf(2)))
    }
}
