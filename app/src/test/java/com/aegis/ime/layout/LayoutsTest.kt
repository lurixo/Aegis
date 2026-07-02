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

    private val nine = Layouts.nine(Lang.CN, Layouts.ninePunctuation())
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
        val composing = Layouts.nine(Lang.CN, Layouts.ninePunctuation(), composing = true)
        assertTrue(
            "composing 9-key top-left must be the 分词 key",
            composing.cells!!.any { it.key.label == "分词" && it.key.action == KeyAction.SEGMENT },
        )
    }

    @Test fun nine_left_column_is_a_scroll_column_not_fixed_cells() {
        // A3: the left column is a scrollable strip (any length), NOT fixed peanut cells. A long list does
        // not become cells and never overflows the upper 0.75 band; the pen stays as a real cell below it.
        val longList = (1..20).map { Key("r$it", action = KeyAction.PICK_READING) }
        val l = Layouts.nine(Lang.CN, longList, composing = true)
        val sc = l.scrollColumn!!
        val cells = l.cells!!
        assertEquals("scroll column carries the full list", longList.map { it.label }, sc.items.map { it.label })
        assertTrue("no left cells leak into the placed cells", cells.none { it.groupId == 1 })
        assertTrue("scroll region sits in the upper band", sc.y >= -1e-4f && sc.y + sc.h <= 0.75f + 1e-4f)
        assertTrue("scroll region within keyboard width", sc.x >= -1e-4f && sc.x + sc.w <= 1f + 1e-4f)
        // D1: pen below the column now opens the symbols panel (was SHOW_EDIT pre-C/D).
        assertTrue("pen present below the column", cells.any { it.key.action == KeyAction.SHOW_SYMBOLS })
    }

    @Test fun nine_resting_left_column_is_the_full_punctuation_list() {
        // Chinese IME behavior note.
        val sc = Layouts.nine(Lang.CN, Layouts.ninePunctuation()).scrollColumn!!
        assertEquals(
            listOf("，", "。", "？", "！", "…", "：", "；", "~", ".", "-", "@", "自定义"),
            sc.items.map { it.label },
        )
        assertEquals(KeyAction.CUSTOM_SYMBOL, sc.items.last().action)
    }

    @Test fun nine_punctuation_inserts_custom_marks_before_the_自定义_entry() {
        // Chinese IME behavior note.
        val sc = Layouts.nine(Lang.CN, Layouts.ninePunctuation(listOf("、", "《"))).scrollColumn!!
        assertEquals(
            listOf("，", "。", "？", "！", "…", "：", "；", "~", ".", "-", "@", "、", "《", "自定义"),
            sc.items.map { it.label },
        )
        assertEquals(KeyAction.CUSTOM_SYMBOL, sc.items.last().action)
        assertTrue("custom marks commit directly", sc.items.filter { it.label in listOf("、", "《") }.all { it.direct })
    }

    // ---- I2: numpad operator scroll column (+ user-custom operators) ----

    @Test fun numpad_operators_are_defaults_then_custom_then_自定义() {
        val ops = Layouts.numpadOperators(listOf("√", "^"))
        val labels = ops.map { it.label }
        assertTrue("default math operators present",
            listOf("+", "-", "×", "÷", "=", "(", ")", "%", ".").all { it in labels })
        assertTrue("custom operators appended after the defaults", listOf("√", "^").all { it in labels })
        assertEquals("自定义 is the last entry", "自定义", labels.last())
        assertEquals("自定义 opens the operator panel", KeyAction.CUSTOM_OPERATOR, ops.last().action)
        assertTrue("every operator commits directly", ops.dropLast(1).all { it.direct })
    }

    @Test fun numpad_operators_dedupe_a_custom_equal_to_a_default() {
        val ops = Layouts.numpadOperators(listOf("+", "√")).map { it.label }
        assertEquals("a custom operator equal to a built-in default is not duplicated", 1, ops.count { it == "+" })
        assertTrue("a genuinely new custom operator is still added", "√" in ops)
    }

    @Test fun numpad_left_column_is_a_scrollable_operator_strip() {
        val ops = Layouts.numpadOperators()
        val np = Layouts.numpad(ops)
        assertEquals("operators populate the scroll column", ops.map { it.label }, np.scrollColumn!!.items.map { it.label })
        assertEquals("numpad is 4 rows so it shares the short-page height (no 9-key⇄123 resize)", 4, np.rowCount)
        val grid = np.cells!!.map { it.key.label }
        assertTrue("the digit grid is intact", (0..9).all { it.toString() in grid })
        assertTrue("backspace + enter present", "⌫" in grid && "↵" in grid)
        // The operator column occupies the leftmost fifth; the grid fills the rest.
        assertTrue("operator column is the leftmost strip", np.scrollColumn!!.x <= 1e-4f)
    }

    @Test fun qwerty_has_no_nine_switch_key_and_pen_opens_symbols() {
        val actions = keysOf(qwerty).map { it.action }
        assertTrue("9-key switch is via the startup setting, not a key", KeyAction.SWITCH_NINE !in actions)
        // Chinese IME behavior note.
        // Chinese IME behavior note.
        assertTrue("pen / symbols entry present", KeyAction.SHOW_SYMBOLS in actions)
        val pen = keysOf(qwerty).first { it.action == KeyAction.SHOW_SYMBOLS }
        assertEquals("符号", pen.label)
    }

    @Test fun qwerty_pen_width_matches_the_adjacent_function_keys() {
        // U-polish (pen-width): the 26-key bottom-row ✎ (symbols) must be as wide as the neighbouring 123 /
        // Chinese IME behavior note.
        val bottom = qwerty.rows.last().keys
        val pen = bottom.first { it.action == KeyAction.SHOW_SYMBOLS }
        val num = bottom.first { it.action == KeyAction.SWITCH_NUMBERS }
        val lang = bottom.first { it.action == KeyAction.TOGGLE_LANG }
        assertEquals("pen width == 123 width", num.weight, pen.weight, 1e-4f)
        assertEquals("pen width == 中英 width", lang.weight, pen.weight, 1e-4f)
    }
}
