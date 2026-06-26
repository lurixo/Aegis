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
import com.aegis.ime.layout.KeyAction.ENTER
import com.aegis.ime.layout.KeyAction.SHIFT
import com.aegis.ime.layout.KeyAction.SPACE
import com.aegis.ime.layout.KeyAction.SWITCH_ALPHA
import com.aegis.ime.layout.KeyAction.SWITCH_NINE
import com.aegis.ime.layout.KeyAction.SWITCH_NUMBERS
import com.aegis.ime.layout.KeyAction.SWITCH_SYMBOLS
import com.aegis.ime.layout.KeyAction.TOGGLE_LANG

object Layouts {

    fun forId(id: LayoutId, lang: Lang): KeyboardLayout = when (id) {
        LayoutId.ALPHA -> qwerty(lang)
        LayoutId.NINE -> nine(lang)
        LayoutId.NUMBER -> number()
        LayoutId.SYMBOL -> symbol()
    }

    private fun row(vararg keys: Key) = KeyboardRow(keys.toList())

    private fun letters(s: String): List<Key> = s.map { Key(it.toString()) }

    private fun langKey(lang: Lang) =
        Key(if (lang == Lang.CN) "中" else "EN", action = TOGGLE_LANG, weight = 1.4f)

    private fun qwerty(lang: Lang): KeyboardLayout {
        val r3 = ArrayList<Key>().apply {
            add(Key("⇧", action = SHIFT, weight = 1.5f))
            addAll(letters("zxcvbnm"))
            add(Key("⌫", action = BACKSPACE, weight = 1.5f))
        }
        val comma = if (lang == Lang.CN) Key("，") else Key(",")
        val period = if (lang == Lang.CN) Key("。") else Key(".")
        val r4 = listOf(
            Key("?123", action = SWITCH_NUMBERS, weight = 1.6f),
            Key("九", action = SWITCH_NINE, weight = 1.2f),
            langKey(lang),
            comma,
            Key("空格", output = " ", action = SPACE, weight = 3.5f),
            period,
            Key("⏎", action = ENTER, weight = 1.6f),
        )
        return KeyboardLayout(
            LayoutId.ALPHA,
            listOf(
                KeyboardRow(letters("qwertyuiop")),
                KeyboardRow(letters("asdfghjkl")),
                KeyboardRow(r3),
                KeyboardRow(r4),
            ),
        )
    }

    private fun t9(digit: String, sub: String) = Key(digit, output = digit, sub = sub)

    private fun nine(lang: Lang): KeyboardLayout = KeyboardLayout(
        LayoutId.NINE,
        listOf(
            row(t9("1", "·"), t9("2", "ABC"), t9("3", "DEF"), Key("⌫", action = BACKSPACE)),
            row(t9("4", "GHI"), t9("5", "JKL"), t9("6", "MNO"), Key("空格", output = " ", action = SPACE)),
            row(t9("7", "PQRS"), t9("8", "TUV"), t9("9", "WXYZ"), Key("⏎", action = ENTER)),
            row(
                Key("?123", action = SWITCH_NUMBERS),
                Key("ABC", action = SWITCH_ALPHA),
                langKey(lang),
                t9("0", "，。"),
            ),
        ),
    )

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
                Key("ABC", action = SWITCH_ALPHA, weight = 1.6f),
                Key(","),
                Key("空格", output = " ", action = SPACE, weight = 4f),
                Key("."),
                Key("⏎", action = ENTER, weight = 1.6f),
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
                Key("ABC", action = SWITCH_ALPHA, weight = 1.6f),
                Key("<"),
                Key("空格", output = " ", action = SPACE, weight = 4f),
                Key(">"),
                Key("⏎", action = ENTER, weight = 1.6f),
            ),
        ),
    )
}
