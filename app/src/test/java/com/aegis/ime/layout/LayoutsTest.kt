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

class LayoutsTest {

    private val nine = Layouts.nine(Lang.CN, Layouts.ninePunctuation())
    private val qwerty = Layouts.forId(LayoutId.ALPHA, Lang.CN)
    private val qwertyEn = Layouts.forId(LayoutId.ALPHA, Lang.EN)

    private fun keysOf(l: KeyboardLayout): List<Key> = l.cells?.map { it.key } ?: l.rows.flatMap { it.keys }

    @Test fun nine_middle_labels_are_letters_not_digits() {
        val labels = nine.cells!!.map { it.key.label }.toSet()
        for (l in listOf("@#", "ABC", "DEF", "GHI", "JKL", "MNO", "PQRS", "TUV", "WXYZ")) {
            assertTrue("9-key missing middle key $l", l in labels)
        }
        assertTrue(
            "9-key must not label keys with digits",
            nine.cells.none { it.key.action == KeyAction.COMMIT && it.key.label.length == 1 && it.key.label[0] in '0'..'9' },
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

    @Test fun qwerty_is_four_rows_with_digit_subsymbols_on_the_top_letter_row() {
        assertEquals("26-key drops the standalone digit row for four rows", 4, qwerty.rowCount)
        assertEquals("26-key drops the standalone digit row for four rows", 4, qwertyEn.rowCount)
        for (layout in listOf(qwerty, qwertyEn)) {
            assertTrue(
                "no standalone digit key remains on the 26-key",
                layout.cells!!.none { it.key.action == KeyAction.COMMIT && it.key.label.length == 1 && it.key.label[0] in '0'..'9' },
            )
            val topRow = layout.cells.filter { it.y < 0.1f }.sortedBy { it.x }
            assertEquals("qwertyuiop".map { it.toString() }, topRow.map { it.key.label })
            assertEquals(
                listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
                topRow.map { it.key.sub },
            )
        }
    }

    @Test fun chinese_qwerty_uses_fullwidth_sub_symbols_while_english_stays_halfwidth() {
        val english = listOf(
            "1", "2", "3", "4", "5", "6", "7", "8", "9", "0",
            "~", "!", "@", "#", "%", "'", "&", "*", "?",
            "(", ")", "-", "_", ":", ";", "/",
        )
        val chinese = listOf(
            "1", "2", "3", "4", "5", "6", "7", "8", "9", "0",
            "～", "！", "＠", "＃", "％", "＇", "＆", "＊", "？",
            "（", "）", "－", "＿", "：", "；", "／",
        )
        for ((layout, expected) in listOf(qwerty to chinese, qwertyEn to english)) {
            val letters = keysOf(layout)
                .filter { it.action == KeyAction.COMMIT && it.label.length == 1 && it.label[0] in 'a'..'z' }
            assertEquals(26, letters.size)
            assertEquals(expected, letters.map { it.sub })
        }
    }

    @Test fun chinese_qwerty_flanking_symbols_match_english_positions_but_keep_fullwidth_outputs() {
        fun flanking(layout: KeyboardLayout): List<PlacedKey> {
            val bottom = layout.cells!!.filter { it.y >= 0.75f }.sortedBy { it.x }
            val space = bottom.indexOfFirst { it.key.action == KeyAction.SPACE }
            return listOf(bottom[space - 1], bottom[space + 1])
        }

        val cn = flanking(qwerty)
        val en = flanking(qwertyEn)
        assertEquals(listOf("，", "。"), cn.map { it.key.label })
        assertEquals(listOf(",", "."), en.map { it.key.label })
        assertEquals(listOf("，", "。"), cn.map { it.key.output })
        assertEquals(listOf(",", "."), en.map { it.key.output })
        assertEquals(en.map { listOf(it.x, it.y, it.w, it.h) }, cn.map { listOf(it.x, it.y, it.w, it.h) })
        assertTrue((cn + en).all { it.key.direct })
    }

    @Test fun chinese_qwerty_replaces_shift_with_segment_only_while_composing() {
        val resting = Layouts.forId(LayoutId.ALPHA, Lang.CN)
        val composing = Layouts.forId(LayoutId.ALPHA, Lang.CN, composing = true)
        val english = Layouts.forId(LayoutId.ALPHA, Lang.EN, composing = true)

        assertEquals(1, keysOf(resting).count { it.action == KeyAction.SHIFT })
        assertEquals(0, keysOf(resting).count { it.action == KeyAction.SEGMENT })
        assertEquals(0, keysOf(composing).count { it.action == KeyAction.SHIFT })
        assertEquals(1, keysOf(composing).count {
            it.action == KeyAction.SEGMENT && it.labelRes == com.aegis.ime.R.string.kbd_split
        })
        assertEquals(1, keysOf(english).count { it.action == KeyAction.SHIFT })
        assertEquals(0, keysOf(english).count { it.action == KeyAction.SEGMENT })
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
        val k123 = nine.cells!!.first { it.key.label == "123" }
        assertEquals(KeyAction.SWITCH_NUMPAD, k123.key.action)
    }

    @Test fun alphabet_123_and_nine_page_key_keep_their_distinct_flows() {
        val alphabet123 = qwerty.cells!!.first { it.key.label == "123" }
        val ninePage = nine.cells!!.first { it.key.label == "@#" }
        assertEquals(KeyAction.SWITCH_NUMPAD, alphabet123.key.action)
        assertEquals(KeyAction.SWITCH_NUMBERS, ninePage.key.action)
    }

    @Test fun nine_composing_top_left_is_the_segment_key() {
        val composing = Layouts.nine(Lang.CN, Layouts.ninePunctuation(), composing = true)
        assertTrue(
            "composing 9-key top-left must be the 分词 key",
            composing.cells!!.any { it.key.labelRes == com.aegis.ime.R.string.kbd_split && it.key.action == KeyAction.SEGMENT },
        )
    }

    @Test fun nine_left_column_is_a_scroll_column_not_fixed_cells() {
        val longList = (1..20).map { Key("r$it", action = KeyAction.PICK_READING) }
        val l = Layouts.nine(Lang.CN, longList, composing = true)
        val sc = l.scrollColumn!!
        val cells = l.cells!!
        assertEquals("scroll column carries the full list", longList.map { it.label }, sc.items.map { it.label })
        assertTrue("no left cells leak into the placed cells", cells.none { it.groupId == 1 })
        assertTrue("scroll region sits in the upper band", sc.y >= -1e-4f && sc.y + sc.h <= 0.75f + 1e-4f)
        assertTrue("scroll region within keyboard width", sc.x >= -1e-4f && sc.x + sc.w <= 1f + 1e-4f)
        assertTrue("pen present below the column", cells.any { it.key.action == KeyAction.SHOW_SYMBOLS })
    }

    @Test fun nine_resting_left_column_is_the_full_punctuation_list() {
        val sc = Layouts.nine(Lang.CN, Layouts.ninePunctuation()).scrollColumn!!
        assertEquals(
            listOf("，", "。", "？", "！", "…", "：", "；", "~", ".", "-", "@"),
            sc.items.dropLast(1).map { it.label },
        )
        assertEquals(com.aegis.ime.R.string.kbd_custom, sc.items.last().labelRes)
        assertEquals(KeyAction.CUSTOM_SYMBOL, sc.items.last().action)
    }

    @Test fun nine_punctuation_inserts_custom_marks_before_the_自定义_entry() {
        val sc = Layouts.nine(Lang.CN, Layouts.ninePunctuation(listOf("、", "《"))).scrollColumn!!
        assertEquals(
            listOf("，", "。", "？", "！", "…", "：", "；", "~", ".", "-", "@", "、", "《"),
            sc.items.dropLast(1).map { it.label },
        )
        assertEquals(com.aegis.ime.R.string.kbd_custom, sc.items.last().labelRes)
        assertEquals(KeyAction.CUSTOM_SYMBOL, sc.items.last().action)
        assertTrue("custom marks commit directly", sc.items.filter { it.label in listOf("、", "《") }.all { it.direct })
    }


    @Test fun numpad_operators_are_defaults_then_custom_then_自定义() {
        val ops = Layouts.numpadOperators(listOf("√", "^"))
        val labels = ops.map { it.label }
        assertTrue("default math operators present",
            listOf("+", "-", "×", "÷", "=", "(", ")", "%", ".").all { it in labels })
        assertTrue("custom operators appended after the defaults", listOf("√", "^").all { it in labels })
        assertEquals("custom entry (labelRes) is the last item", com.aegis.ime.R.string.kbd_custom, ops.last().labelRes)
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
        assertTrue("operator column is the leftmost strip", np.scrollColumn.x <= 1e-4f)
    }

    @Test fun qwerty_has_no_nine_switch_key_and_pen_opens_symbols() {
        val actions = keysOf(qwerty).map { it.action }
        assertTrue("9-key switch is via the startup setting, not a key", KeyAction.SWITCH_NINE !in actions)
        assertTrue("pen / symbols entry present", KeyAction.SHOW_SYMBOLS in actions)
        val pen = keysOf(qwerty).first { it.action == KeyAction.SHOW_SYMBOLS }
        assertEquals(com.aegis.ime.R.string.kbd_symbols, pen.labelRes)
    }

    @Test fun qwerty_pen_width_matches_the_adjacent_function_keys() {
        val bottom = qwerty.cells!!.filter { it.y >= 0.75f }.map { it.key }
        val pen = bottom.first { it.action == KeyAction.SHOW_SYMBOLS }
        val num = bottom.first { it.action == KeyAction.SWITCH_NUMPAD }
        val lang = bottom.first { it.action == KeyAction.TOGGLE_LANG }
        assertEquals("pen width == 123 width", num.weight, pen.weight, 1e-4f)
        assertEquals("pen width == 中英 width", lang.weight, pen.weight, 1e-4f)
    }

    @Test fun symbol_page_has_no_duplicate_key_labels() {
        val labels = keysOf(Layouts.forId(LayoutId.SYMBOL, Lang.CN))
            .filter { it.labelRes == null }
            .map { it.label }
        assertEquals("symbol page labels must be unique: $labels", labels.distinct(), labels)
        assertTrue("§ fills the freed slot", "§" in labels)
    }

    @Test fun rail_fill_marks_exactly_the_intended_function_keys() {
        fun rails(l: KeyboardLayout) = keysOf(l).filter { it.rail }
        assertEquals(
            setOf(KeyAction.SHOW_SYMBOLS, KeyAction.SWITCH_NUMPAD, KeyAction.TOGGLE_LANG, KeyAction.SHIFT, KeyAction.BACKSPACE),
            rails(qwerty).map { it.action }.toSet(),
        )
        assertEquals(5, rails(qwerty).size)
        assertEquals(
            setOf(KeyAction.SHOW_SYMBOLS, KeyAction.SWITCH_NUMPAD, KeyAction.TOGGLE_LANG, KeyAction.BACKSPACE, KeyAction.CLEAR_COMPOSING),
            rails(nine).map { it.action }.toSet(),
        )
        assertEquals(5, rails(nine).size)
        val composing = Layouts.nine(Lang.CN, Layouts.ninePunctuation(), composing = true)
        assertEquals(rails(nine).map { it.action }.toSet(), rails(composing).map { it.action }.toSet())
        val numpad = Layouts.forId(LayoutId.NUMPAD, Lang.CN)
        assertEquals(
            setOf(KeyAction.BACKSPACE, KeyAction.COMMIT, KeyAction.SWITCH_TEXT, KeyAction.SPACE),
            rails(numpad).map { it.action }.toSet(),
        )
        assertEquals(4, rails(numpad).size)
        assertEquals(listOf("."), rails(numpad).filter { it.action == KeyAction.COMMIT }.map { it.label })
        for (id in listOf(LayoutId.NUMBER, LayoutId.SYMBOL)) {
            val page = Layouts.forId(id, Lang.CN)
            val switch = if (id == LayoutId.NUMBER) KeyAction.SWITCH_SYMBOLS else KeyAction.SWITCH_NUMBERS
            assertEquals(setOf(switch, KeyAction.BACKSPACE, KeyAction.SWITCH_TEXT), rails(page).map { it.action }.toSet())
            assertEquals(3, rails(page).size)
        }
        assertTrue("space keeps the key surface outside the numpad", keysOf(qwerty).none { it.rail && it.action == KeyAction.SPACE })
        assertTrue(keysOf(nine).none { it.rail && it.action == KeyAction.SPACE })
        assertTrue("enter keeps the accent fill", listOf(qwerty, nine, numpad).flatMap(::keysOf).none { it.rail && it.accent })
        assertTrue(nine.scrollColumn!!.items.none { it.rail })
        assertTrue(numpad.scrollColumn!!.items.none { it.rail })
    }

    @Test fun number_and_symbol_pages_share_the_control_width_baseline() {
        for (id in listOf(LayoutId.NUMBER, LayoutId.SYMBOL)) {
            val layout = Layouts.forId(id, Lang.CN)
            val controls = layout.rows.flatMap { it.keys }.filter {
                it.action in setOf(
                    KeyAction.SWITCH_NUMBERS,
                    KeyAction.SWITCH_SYMBOLS,
                    KeyAction.SWITCH_TEXT,
                    KeyAction.BACKSPACE,
                    KeyAction.ENTER,
                )
            }
            assertTrue(controls.isNotEmpty())
            assertTrue(controls.all { it.weight == 1.5f })
            assertEquals(3f, layout.rows.last().keys.first { it.action == KeyAction.SPACE }.weight, 0f)
        }
    }
}
