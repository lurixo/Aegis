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

package com.aegis.ime.ime

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PreeditModelTest {

    @Test fun alpha_inserted_apostrophes_consume_no_raw_input() {
        val m = PreeditModel.align("ni'hao", 0, "nihao", emptyList(), emptyList(), 5)
        assertArrayEquals(intArrayOf(0, 1, 2, 2, 3, 4, 5), m.rawIndexAt)
        assertEquals(6, m.displayCaret())
        assertEquals(2, m.rawIndexForDisplay(2))
        assertEquals(2, m.rawIndexForDisplay(3))
    }

    @Test fun alpha_typed_apostrophe_is_a_raw_character() {
        val m = PreeditModel.align("ni'hao", 0, "ni'hao", emptyList(), emptyList(), 3)
        assertArrayEquals(intArrayOf(0, 1, 2, 3, 4, 5, 6), m.rawIndexAt)
        assertEquals(3, m.displayCaret())
    }

    @Test fun caret_display_sits_after_an_inserted_apostrophe() {
        val m = PreeditModel.align("ni'hao", 0, "nihao", emptyList(), emptyList(), 2)
        assertEquals(3, m.displayCaret())
    }

    @Test fun nine_key_letters_map_one_to_one_onto_digits() {
        val m = PreeditModel.align("ni'hao", 0, "64426", emptyList(), emptyList(), 2)
        assertArrayEquals(intArrayOf(0, 1, 2, 2, 3, 4, 5), m.rawIndexAt)
        assertEquals(3, m.displayCaret())
    }

    @Test fun locked_readings_are_reported_as_display_ranges() {
        val m = PreeditModel.align("ni'hao'ma", 0, "nihaoma", listOf(2, 3), listOf(2, 3), 5)
        assertArrayEquals(intArrayOf(0, 1, 2, 2, 3, 4, 5, 5, 6, 7), m.rawIndexAt)
        assertEquals(listOf(0..1, 3..5), m.lockedRanges)
        assertEquals(7, m.displayCaret())
    }

    @Test fun locked_reading_with_leading_typed_apostrophe_hides_it() {
        val m = PreeditModel.align("ni'hao", 0, "ni'hao", listOf(2, 3), listOf(2, 4), 6)
        assertArrayEquals(intArrayOf(0, 1, 2, 2, 4, 5, 6), m.rawIndexAt)
        assertEquals(listOf(0..1, 3..5), m.lockedRanges)
    }

    @Test fun committed_prefix_is_not_editable_and_maps_to_the_composing_start() {
        val m = PreeditModel.align("你好ma", 2, "ma", emptyList(), emptyList(), 0)
        assertArrayEquals(intArrayOf(0, 0, 0, 1, 2), m.rawIndexAt)
        assertEquals(2, m.editableFrom)
        assertEquals(2, m.displayCaret())
        assertEquals(0, m.rawIndexForDisplay(1))
    }

    @Test fun nine_key_locked_reading_followed_by_active_digits() {
        val m = PreeditModel.align("ni'hao", 0, "64426", listOf(2), listOf(2), 3)
        assertArrayEquals(intArrayOf(0, 1, 2, 2, 3, 4, 5), m.rawIndexAt)
        assertEquals(listOf(0..1), m.lockedRanges)
        assertEquals(4, m.displayCaret())
    }

    @Test fun forced_cut_at_the_end_shows_a_trailing_apostrophe() {
        val m = PreeditModel.align("ni'", 0, "ni", emptyList(), emptyList(), 2)
        assertArrayEquals(intArrayOf(0, 1, 2, 2), m.rawIndexAt)
        assertEquals(3, m.displayCaret())
    }
}
