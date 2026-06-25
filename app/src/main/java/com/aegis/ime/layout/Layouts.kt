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

import com.aegis.ime.R
import com.aegis.ime.layout.KeyAction.BACKSPACE
import com.aegis.ime.layout.KeyAction.ENTER
import com.aegis.ime.layout.KeyAction.SHIFT
import com.aegis.ime.layout.KeyAction.SEGMENT
import com.aegis.ime.layout.KeyAction.SHOW_SYMBOLS
import com.aegis.ime.layout.KeyAction.SPACE
import com.aegis.ime.layout.KeyAction.SWITCH_NUMPAD
import com.aegis.ime.layout.KeyAction.TOGGLE_LANG

object Layouts {

    fun forId(id: LayoutId, lang: Lang, composing: Boolean = false): KeyboardLayout = when (id) {
        LayoutId.ALPHA -> qwerty(lang, composing)
    }

    private fun subRow(letters: String, subs: List<String>): List<Key> =
        letters.mapIndexed { i, c -> Key(c.toString(), sub = subs.getOrNull(i)) }

    private fun qwerty(lang: Lang, composing: Boolean): KeyboardLayout {
        val q = subRow("qwertyuiop", listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"))
        val a = subRow(
            "asdfghjkl",
            if (lang == Lang.CN) listOf("～", "！", "@", "＃", "％", "＇", "＆", "＊", "？")
            else listOf("~", "!", "@", "#", "%", "'", "&", "*", "?"),
        )
        val z = subRow(
            "zxcvbnm",
            if (lang == Lang.CN) listOf("（", "）", "－", "＿", "：", "；", "／")
            else listOf("(", ")", "-", "_", ":", ";", "/"),
        )
        val comma = if (lang == Lang.CN) "，" else ","
        val period = if (lang == Lang.CN) "。" else "."
        val bottom = listOf(
            Key(labelRes = R.string.kbd_symbols, action = SHOW_SYMBOLS, rail = true, weight = 1.5f),
            Key("123", action = SWITCH_NUMPAD, rail = true, weight = 1.5f),
            Key(comma, output = comma, direct = true),
            Key(labelRes = R.string.kbd_space, output = " ", action = SPACE, weight = 3.5f),
            Key(period, output = period, direct = true),
            Key(action = TOGGLE_LANG, rail = true, weight = 1.5f),
            Key("↵", action = ENTER, accent = true, weight = 1.6f),
        )
        val cells = ArrayList<PlacedKey>()
        fun addRow(keys: List<Key>, x: Float, y: Float) {
            keys.forEachIndexed { index, key -> cells.add(PlacedKey(key, x + index * 0.1f, y, 0.1f, 0.25f)) }
        }
        addRow(q, 0f, 0f)
        addRow(a, 0.05f, 0.25f)
        cells.add(PlacedKey(
            if (lang == Lang.CN && composing) Key(labelRes = R.string.kbd_split, action = SEGMENT, rail = true, weight = 1.5f)
            else Key("⇧", action = SHIFT, rail = true, weight = 1.5f),
            0f,
            0.5f,
            0.15f,
            0.25f,
        ))
        addRow(z, 0.15f, 0.5f)
        cells.add(PlacedKey(Key("⌫", action = BACKSPACE, rail = true, weight = 1.5f), 0.85f, 0.5f, 0.15f, 0.25f))
        val bottomWeight = bottom.sumOf { it.weight.toDouble() }.toFloat()
        var bottomX = 0f
        bottom.forEach { key ->
            val width = key.weight / bottomWeight
            cells.add(PlacedKey(key, bottomX, 0.75f, width, 0.25f))
            bottomX += width
        }
        return KeyboardLayout(LayoutId.ALPHA, cells = cells, rowCount = 4)
    }
}
