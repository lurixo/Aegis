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

package com.aegis.ime.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locks the keyboard geometry/semantics. */
class LayoutsTest {

    private val nine = Layouts.nine(Lang.CN, Layouts.defaultNineLeft())
    private val qwerty = Layouts.forId(LayoutId.ALPHA, Lang.CN)

    private fun keysOf(l: KeyboardLayout): List<Key> = l.cells?.map { it.key } ?: l.rows.flatMap { it.keys }

    @Test fun nine_middle_labels_are_letters_not_digits() {
        val labels = nine.cells!!.map { it.key.label }.toSet()
        for (l in listOf("@#", "ABC", "DEF", "GHI", "JKL", "MNO", "PQRS", "TUV", "WXYZ")) {
            assertTrue("9-key missing middle key $l", l in labels)
        }
        // No COMMIT key shows a bare digit as its main label (digits are emitted via output only).
        assertTrue(
            "9-key must not label keys with digits",
            nine.cells!!.none { it.key.action == KeyAction.COMMIT && it.key.label.length == 1 && it.key.label[0] in '0'..'9' },
        )
    }

    @Test fun nine_right_column_order_is_backspace_clear_enter() {
        val cells = nine.cells!!
        val maxX = cells.maxOf { it.x }
        val right = cells.filter { it.x >= maxX - 1e-4f }.sortedBy { it.y }
        assertEquals(
            listOf(KeyAction.BACKSPACE, KeyAction.CLEAR_COMPOSING, KeyAction.ENTER),
            right.map { it.key.action },
        )
        assertTrue("enter should be the green accent key", right.last().key.accent)
    }

    @Test fun all_backspace_keys_use_the_delete_glyph() {
        val layouts = listOf(
            qwerty, nine,
            Layouts.forId(LayoutId.NUMBER, Lang.CN),
            Layouts.forId(LayoutId.SYMBOL, Lang.CN),
            Layouts.forId(LayoutId.NUMPAD, Lang.CN),
        )
        for (l in layouts) {
            keysOf(l).filter { it.action == KeyAction.BACKSPACE }.forEach { assertEquals("⌫", it.label) }
        }
    }

    @Test fun qwerty_has_number_row_and_letter_subsymbols() {
        assertEquals(
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
            qwerty.rows.first().keys.map { it.label },
        )
        val letters = qwerty.rows.drop(1).flatMap { it.keys }
            .filter { it.action == KeyAction.COMMIT && it.label.length == 1 && it.label[0] in 'a'..'z' }
        assertEquals(26, letters.size)
        assertTrue("every letter needs a super-script symbol", letters.all { it.sub != null })
    }

    @Test fun nine_space_is_in_bottom_row_not_right_column() {
        val cells = nine.cells!!
        val space = cells.first { it.key.action == KeyAction.SPACE }
        assertTrue("space belongs in the bottom row", space.y >= 0.7f)
        val maxX = cells.maxOf { it.x }
        assertTrue(
            "right column must not contain space",
            cells.none { it.key.action == KeyAction.SPACE && it.x >= maxX - 1e-4f },
        )
    }

    @Test fun nine_123_key_opens_the_numpad_grid() {
        // the 9-key 123 switches to the calculator-style numpad, not the row-based number page.
        val k123 = nine.cells!!.first { it.key.label == "123" }
        assertEquals(KeyAction.SWITCH_NUMPAD, k123.key.action)
    }

    @Test fun nine_composing_top_left_is_the_segment_key() {
        val composing = Layouts.nine(Lang.CN, Layouts.defaultNineLeft(), composing = true)
        assertTrue(
            "composing 9-key top-left must be the 分词 key",
            composing.cells!!.any { it.key.label == "分词" && it.key.action == KeyAction.SEGMENT },
        )
    }

    @Test fun qwerty_has_no_nine_switch_key_and_has_pen() {
        val actions = keysOf(qwerty).map { it.action }
        assertTrue("9-key switch is via the toolbar, not a key", KeyAction.SWITCH_NINE !in actions)
        assertTrue("pen / text-edit entry present", KeyAction.SHOW_EDIT in actions)
    }
}
