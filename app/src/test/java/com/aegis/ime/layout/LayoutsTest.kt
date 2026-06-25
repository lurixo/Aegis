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

    private val qwerty = Layouts.forId(LayoutId.ALPHA, Lang.CN)
    private val qwertyEn = Layouts.forId(LayoutId.ALPHA, Lang.EN)

    private fun keysOf(l: KeyboardLayout): List<Key> = l.cells?.map { it.key } ?: l.rows.flatMap { it.keys }

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
            assertEquals(topRow.map { it.key.sub }, topRow.map { it.key.swipeUp })
        }
    }

    @Test fun chinese_qwerty_keeps_at_halfwidth_and_other_sub_symbols_fullwidth() {
        val english = listOf(
            "1", "2", "3", "4", "5", "6", "7", "8", "9", "0",
            "~", "!", "@", "#", "%", "'", "&", "*", "?",
            "(", ")", "-", "_", ":", ";", "/",
        )
        val chinese = listOf(
            "1", "2", "3", "4", "5", "6", "7", "8", "9", "0",
            "～", "！", "@", "＃", "％", "＇", "＆", "＊", "？",
            "（", "）", "－", "＿", "：", "；", "／",
        )
        for ((layout, expected) in listOf(qwerty to chinese, qwertyEn to english)) {
            val letters = keysOf(layout)
                .filter { it.action == KeyAction.COMMIT && it.label.length == 1 && it.label[0] in 'a'..'z' }
            assertEquals(26, letters.size)
            assertEquals(expected, letters.map { it.sub })
            assertEquals(expected, letters.map { it.swipeUp })
            assertTrue(letters.all { it.swipeDown == null })
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

    @Test fun qwerty_pen_opens_symbols() {
        val actions = keysOf(qwerty).map { it.action }
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
}
