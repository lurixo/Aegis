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
import com.aegis.ime.layout.KeyAction.CLEAR_COMPOSING
import com.aegis.ime.layout.KeyAction.CUSTOM_OPERATOR
import com.aegis.ime.layout.KeyAction.CUSTOM_SYMBOL
import com.aegis.ime.layout.KeyAction.ENTER
import com.aegis.ime.layout.KeyAction.SHIFT
import com.aegis.ime.layout.KeyAction.SEGMENT
import com.aegis.ime.layout.KeyAction.SHOW_SYMBOLS
import com.aegis.ime.layout.KeyAction.SPACE
import com.aegis.ime.layout.KeyAction.SWITCH_NUMBERS
import com.aegis.ime.layout.KeyAction.SWITCH_NUMPAD
import com.aegis.ime.layout.KeyAction.SWITCH_SYMBOLS
import com.aegis.ime.layout.KeyAction.SWITCH_TEXT
import com.aegis.ime.layout.KeyAction.TOGGLE_LANG

object Layouts {

    fun forId(id: LayoutId, lang: Lang): KeyboardLayout = when (id) {
        LayoutId.ALPHA -> qwerty(lang)
        LayoutId.NINE -> nine(lang, ninePunctuation())
        LayoutId.NUMBER -> number()
        LayoutId.SYMBOL -> symbol()
        LayoutId.NUMPAD -> numpad()
    }

    private const val NINE_LEFT_U = 0.85f
    private const val NINE_MAIN_U = 1.0f
    private const val NINE_RIGHT_U = 0.85f
    private const val NINE_TOTAL_U = NINE_LEFT_U + 3f * NINE_MAIN_U + NINE_RIGHT_U

    val nineFixedPunctuation: List<String> = listOf("，", "。", "？", "！", "…", "：", "；", "~", ".", "-", "@")

    fun ninePunctuation(custom: List<String> = emptyList()): List<Key> =
        nineFixedPunctuation.map { Key(it, direct = true) } +
            custom.map { Key(it, direct = true) } + Key(labelRes = R.string.kbd_custom, action = CUSTOM_SYMBOL)

    private fun row(vararg keys: Key) = KeyboardRow(keys.toList())

    private fun letters(s: String): List<Key> = s.map { Key(it.toString()) }

    private fun subRow(lang: Lang, letters: String, enSubs: List<String>): List<Key> =
        letters.mapIndexed { i, c -> Key(c.toString(), sub = if (lang == Lang.EN) enSubs.getOrNull(i) else null) }

    private fun qwerty(lang: Lang): KeyboardLayout {
        val numbers = "1234567890".map { Key(it.toString(), direct = true) }
        val q = subRow(lang, "qwertyuiop", listOf("`", "=", "+", "$", "…", "\"", "^", "[", "]", "|"))
        val a = subRow(lang, "asdfghjkl", listOf("~", "!", "@", "#", "%", "'", "&", "*", "?"))
        val z = subRow(lang, "zxcvbnm", listOf("(", ")", "-", "_", ":", ";", "/"))
        val comma = if (lang == Lang.CN) "，" else ","
        val period = if (lang == Lang.CN) "。" else "."
        val bottom = listOf(
            Key(labelRes = R.string.kbd_symbols, action = SHOW_SYMBOLS, weight = 1.5f),
            Key("123", action = SWITCH_NUMPAD, weight = 1.5f),
            Key(comma, direct = true),
            Key(labelRes = R.string.kbd_space, output = " ", action = SPACE, weight = 3.5f),
            Key(period, direct = true),
            Key(action = TOGGLE_LANG, weight = 1.5f),
            Key("↵", action = ENTER, accent = true, weight = 1.6f),
        )
        val cells = ArrayList<PlacedKey>()
        fun addRow(keys: List<Key>, x: Float, y: Float) {
            keys.forEachIndexed { index, key -> cells.add(PlacedKey(key, x + index * 0.1f, y, 0.1f, 0.2f)) }
        }
        addRow(numbers, 0f, 0f)
        addRow(q, 0f, 0.2f)
        addRow(a, 0.05f, 0.4f)
        cells.add(PlacedKey(Key("⇧", action = SHIFT, weight = 1.5f), 0f, 0.6f, 0.15f, 0.2f))
        addRow(z, 0.15f, 0.6f)
        cells.add(PlacedKey(Key("⌫", action = BACKSPACE, weight = 1.5f), 0.85f, 0.6f, 0.15f, 0.2f))
        val bottomWeight = bottom.sumOf { it.weight.toDouble() }.toFloat()
        var bottomX = 0f
        bottom.forEach { key ->
            val width = key.weight / bottomWeight
            cells.add(PlacedKey(key, bottomX, 0.8f, width, 0.2f))
            bottomX += width
        }
        return KeyboardLayout(LayoutId.ALPHA, cells = cells, rowCount = 5)
    }

    private fun t9key(letters: String, digit: String) = Key(letters, output = digit)

    fun nine(lang: Lang, left: List<Key>, composing: Boolean = false): KeyboardLayout {
        val u = 1f / NINE_TOTAL_U
        val xL = 0f; val wL = NINE_LEFT_U * u
        val x1 = NINE_LEFT_U * u; val x2 = (NINE_LEFT_U + 1f) * u; val x3 = (NINE_LEFT_U + 2f) * u; val wM = NINE_MAIN_U * u
        val xR = (NINE_LEFT_U + 3f) * u; val wR = NINE_RIGHT_U * u
        val cells = ArrayList<PlacedKey>()
        val leftColumn = ScrollColumn(left, xL, 0f, wL, 0.75f, cellHFrac = 0.75f / 4f)
        cells.add(PlacedKey(Key(labelRes = R.string.kbd_symbols, action = SHOW_SYMBOLS), xL, 0.75f, wL, 0.25f))
        cells.add(PlacedKey(
            if (composing) Key(labelRes = R.string.kbd_split, action = SEGMENT, bold = true) else Key("@#", action = SWITCH_NUMBERS, bold = true),
            x1, 0f, wM, 0.25f,
        ))
        cells.add(PlacedKey(t9key("ABC", "2"), x2, 0f, wM, 0.25f))
        cells.add(PlacedKey(t9key("DEF", "3"), x3, 0f, wM, 0.25f))
        cells.add(PlacedKey(t9key("GHI", "4"), x1, 0.25f, wM, 0.25f))
        cells.add(PlacedKey(t9key("JKL", "5"), x2, 0.25f, wM, 0.25f))
        cells.add(PlacedKey(t9key("MNO", "6"), x3, 0.25f, wM, 0.25f))
        cells.add(PlacedKey(t9key("PQRS", "7"), x1, 0.5f, wM, 0.25f))
        cells.add(PlacedKey(t9key("TUV", "8"), x2, 0.5f, wM, 0.25f))
        cells.add(PlacedKey(t9key("WXYZ", "9"), x3, 0.5f, wM, 0.25f))
        cells.add(PlacedKey(Key("123", action = SWITCH_NUMPAD), x1, 0.75f, 0.8f * u, 0.25f))
        cells.add(PlacedKey(Key(labelRes = R.string.kbd_space, output = " ", action = SPACE), x1 + 0.8f * u, 0.75f, 1.4f * u, 0.25f))
        cells.add(PlacedKey(Key(action = TOGGLE_LANG), x1 + 2.2f * u, 0.75f, 0.8f * u, 0.25f))
        cells.add(PlacedKey(Key("⌫", action = BACKSPACE), xR, 0f, wR, 0.25f))
        cells.add(PlacedKey(Key(labelRes = R.string.kbd_redo, action = CLEAR_COMPOSING), xR, 0.25f, wR, 0.25f))
        cells.add(PlacedKey(Key("↵", action = ENTER, accent = true), xR, 0.5f, wR, 0.5f))
        return KeyboardLayout(LayoutId.NINE, cells = cells, rowCount = 4, scrollColumn = leftColumn)
    }

    private fun number(): KeyboardLayout = KeyboardLayout(
        LayoutId.NUMBER,
        listOf(
            KeyboardRow(letters("1234567890")),
            row(
                Key("@"), Key("#"), Key("￥"), Key("_"), Key("&"),
                Key("-"), Key("+"), Key("("), Key(")"), Key("/"),
            ),
            row(
                Key("=\\<", action = SWITCH_SYMBOLS, weight = 1.5f),
                Key("*"), Key("\""), Key("'"), Key(":"), Key(";"), Key("!"), Key("?"),
                Key("⌫", action = BACKSPACE, weight = 1.5f),
            ),
            row(
                Key(labelRes = R.string.kbd_back, action = SWITCH_TEXT, weight = 1.5f),
                Key(","),
                Key(labelRes = R.string.kbd_space, output = " ", action = SPACE, weight = 3f),
                Key("."),
                Key("↵", action = ENTER, accent = true, weight = 1.5f),
            ),
        ),
    )

    val defaultNumpadOperators: List<String> = listOf("+", "-", "×", "÷", "=", "(", ")", "%", ".")

    fun numpadOperators(custom: List<String> = emptyList()): List<Key> =
        (defaultNumpadOperators + custom).distinct().map { Key(it, direct = true) } +
            Key(labelRes = R.string.kbd_custom, action = CUSTOM_OPERATOR)

    fun numpad(operators: List<Key> = numpadOperators()): KeyboardLayout {
        val u = 1f / NINE_TOTAL_U
        val wL = NINE_LEFT_U * u
        val x1 = NINE_LEFT_U * u; val x2 = (NINE_LEFT_U + 1f) * u; val x3 = (NINE_LEFT_U + 2f) * u; val wM = NINE_MAIN_U * u
        val xR = (NINE_LEFT_U + 3f) * u; val wR = NINE_RIGHT_U * u
        val opCol = ScrollColumn(operators, 0f, 0f, wL, 1f, cellHFrac = 0.25f)
        val cells = ArrayList<PlacedKey>()
        fun digit(label: String, x: Float, row: Float) = cells.add(PlacedKey(Key(label), x, row, wM, 0.25f))
        digit("1", x1, 0f); digit("2", x2, 0f); digit("3", x3, 0f)
        digit("4", x1, 0.25f); digit("5", x2, 0.25f); digit("6", x3, 0.25f)
        digit("7", x1, 0.5f); digit("8", x2, 0.5f); digit("9", x3, 0.5f)
        cells.add(PlacedKey(Key("⌫", action = BACKSPACE), xR, 0f, wR, 0.25f))
        cells.add(PlacedKey(Key("."), xR, 0.25f, wR, 0.25f))
        cells.add(PlacedKey(Key("↵", action = ENTER, accent = true), xR, 0.5f, wR, 0.5f))
        cells.add(PlacedKey(Key(labelRes = R.string.kbd_back, action = SWITCH_TEXT), x1, 0.75f, wM, 0.25f))
        cells.add(PlacedKey(Key("0"), x2, 0.75f, wM, 0.25f))
        cells.add(PlacedKey(Key(labelRes = R.string.kbd_space, output = " ", action = SPACE), x3, 0.75f, wM, 0.25f))
        return KeyboardLayout(LayoutId.NUMPAD, cells = cells, rowCount = 4, scrollColumn = opCol)
    }

    private fun symbol(): KeyboardLayout = KeyboardLayout(
        LayoutId.SYMBOL,
        listOf(
            row(
                Key("~"), Key("`"), Key("|"), Key("•"), Key("√"),
                Key("π"), Key("÷"), Key("×"), Key("¶"), Key("∆"),
            ),
            row(
                Key("£"), Key("¢"), Key("€"), Key("¥"), Key("^"),
                Key("°"), Key("="), Key("{"), Key("}"), Key("\\"),
            ),
            row(
                Key("?123", action = SWITCH_NUMBERS, weight = 1.5f),
                Key("©"), Key("®"), Key("™"), Key("℅"), Key("["), Key("]"), Key("§"),
                Key("⌫", action = BACKSPACE, weight = 1.5f),
            ),
            row(
                Key(labelRes = R.string.kbd_back, action = SWITCH_TEXT, weight = 1.5f),
                Key("<"),
                Key(labelRes = R.string.kbd_space, output = " ", action = SPACE, weight = 3f),
                Key(">"),
                Key("↵", action = ENTER, accent = true, weight = 1.5f),
            ),
        ),
    )
}
