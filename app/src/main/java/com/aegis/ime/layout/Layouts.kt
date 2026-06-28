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

import com.aegis.ime.layout.KeyAction.BACKSPACE
import com.aegis.ime.layout.KeyAction.CLEAR_COMPOSING
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

    fun ninePunctuation(custom: List<String> = emptyList()): List<Key> =
        listOf(
            Key("，", direct = true), Key("。", direct = true), Key("？", direct = true), Key("！", direct = true),
            Key("…", direct = true), Key("：", direct = true), Key("；", direct = true), Key("~", direct = true),
            Key(".", direct = true), Key("-", direct = true), Key("@", direct = true),
        ) + custom.map { Key(it, direct = true) } + Key("自定义", action = CUSTOM_SYMBOL)

    private fun row(vararg keys: Key) = KeyboardRow(keys.toList())

    private fun letters(s: String): List<Key> = s.map { Key(it.toString()) }

    private fun subRow(letters: String, subs: List<String>): List<Key> =
        letters.mapIndexed { i, c -> Key(c.toString(), sub = subs.getOrNull(i)) }

    private fun qwerty(lang: Lang): KeyboardLayout {
        val numbers = "1234567890".map { Key(it.toString(), direct = true) }
        val q = subRow("qwertyuiop", listOf("`", "=", "+", "$", "…", "\"", "^", "[", "]", "|"))
        val a = subRow("asdfghjkl", listOf("~", "!", "@", "#", "%", "'", "&", "*", "?"))
        val z = ArrayList<Key>().apply {
            add(Key("⇧", action = SHIFT, weight = 1.5f))
            addAll(subRow("zxcvbnm", listOf("(", ")", "-", "_", ":", ";", "/")))
            add(Key("⌫", action = BACKSPACE, weight = 1.5f))
        }
        val comma = if (lang == Lang.CN) "，" else ","
        val period = if (lang == Lang.CN) "。" else "."
        val bottom = listOf(
            Key("✎", action = SHOW_SYMBOLS, weight = 1.3f),
            Key("123", action = SWITCH_NUMBERS, weight = 1.5f),
            Key(comma, direct = true),
            Key("空格", output = " ", action = SPACE, weight = 3.5f),
            Key(period, direct = true),
            Key("中英", action = TOGGLE_LANG, weight = 1.5f),
            Key("↵", action = ENTER, accent = true, weight = 1.6f),
        )
        return KeyboardLayout(
            LayoutId.ALPHA,
            listOf(KeyboardRow(numbers), KeyboardRow(q), KeyboardRow(a), KeyboardRow(z), KeyboardRow(bottom)),
        )
    }

    private fun t9key(letters: String, digit: String) = Key(letters, output = digit)

    fun nine(lang: Lang, left: List<Key>, composing: Boolean = false): KeyboardLayout {
        val u = 1f / 4.4f
        val xL = 0f; val wL = 0.7f * u
        val x1 = 0.7f * u; val x2 = 1.7f * u; val x3 = 2.7f * u; val wM = 1f * u
        val xR = 3.7f * u; val wR = 0.7f * u
        val cells = ArrayList<PlacedKey>()
        val leftColumn = ScrollColumn(left, xL, 0f, wL, 0.75f, cellHFrac = 0.75f / 4f)
        cells.add(PlacedKey(Key("✎", action = SHOW_SYMBOLS), xL, 0.75f, wL, 0.25f))
        cells.add(PlacedKey(
            if (composing) Key("分词", action = SEGMENT, bold = true) else Key("@#", action = SWITCH_SYMBOLS, bold = true),
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
        cells.add(PlacedKey(Key("空格", output = " ", action = SPACE), 1.5f * u, 0.75f, 1.4f * u, 0.25f))
        cells.add(PlacedKey(Key("中英", action = TOGGLE_LANG), 2.9f * u, 0.75f, 0.8f * u, 0.25f))
        cells.add(PlacedKey(Key("⌫", action = BACKSPACE), xR, 0f, wR, 0.25f))
        cells.add(PlacedKey(Key("重输", action = CLEAR_COMPOSING), xR, 0.25f, wR, 0.25f))
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
                Key("返回", action = SWITCH_TEXT, weight = 1.6f),
                Key(","),
                Key("空格", output = " ", action = SPACE, weight = 4f),
                Key("."),
                Key("↵", action = ENTER, accent = true, weight = 1.6f),
            ),
        ),
    )

    private fun numpad(): KeyboardLayout = KeyboardLayout(
        LayoutId.NUMPAD,
        listOf(
            row(Key("%"), Key("1"), Key("2"), Key("3"), Key("⌫", action = BACKSPACE)),
            row(Key("+"), Key("4"), Key("5"), Key("6"), Key(".")),
            row(Key("-"), Key("7"), Key("8"), Key("9"), Key("@")),
            row(
                Key("*"),
                Key("返回", action = SWITCH_TEXT),
                Key("0"),
                Key("空格", output = " ", action = SPACE),
                Key("↵", action = ENTER, accent = true),
            ),
        ),
    )

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
                Key("©"), Key("®"), Key("™"), Key("℅"), Key("["), Key("]"), Key("¥"),
                Key("⌫", action = BACKSPACE, weight = 1.5f),
            ),
            row(
                Key("返回", action = SWITCH_TEXT, weight = 1.6f),
                Key("<"),
                Key("空格", output = " ", action = SPACE, weight = 4f),
                Key(">"),
                Key("↵", action = ENTER, accent = true, weight = 1.6f),
            ),
        ),
    )
}
