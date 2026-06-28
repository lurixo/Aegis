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

/** Static layout definitions. Pure data — no Android dependencies. */
object Layouts {

    fun forId(id: LayoutId, lang: Lang): KeyboardLayout = when (id) {
        LayoutId.ALPHA -> qwerty(lang)
        LayoutId.NINE -> nine(lang, ninePunctuation())
        LayoutId.NUMBER -> number()
        LayoutId.SYMBOL -> symbol()
        LayoutId.NUMPAD -> numpad()
    }

    /**
     * Resting 9-key left column (A3): the scrollable punctuation list, top→bottom ，。？！…：；~.-@ then any
     * user [custom] marks, then 自定义 (opens the customization panel). ★D: each mark commits straight to
     * the editor (never buffers as pinyin).
     */
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

    /**
     * 26-key full-pinyin / English: number row 1–0, letters with super-script
     * symbols, ✕ backspace at the z-row end, bottom row 笔 / 123 / , / SPACE / . / 中英 / ↵(green).
     * Keyboard switching (9-key) is via the candidate-bar toolbar, so there is no 九 key here.
     */
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
            Key("✎", action = SHOW_SYMBOLS, weight = 1.5f), // D: pencil → symbols panel (width matches 123/中英)
            Key("123", action = SWITCH_NUMBERS, weight = 1.5f),
            Key(comma, direct = true), // ★D punctuation direct (U6: dropped the vestigial "A" swipe-up badge)
            Key("空格", output = " ", action = SPACE, weight = 3.5f),
            Key(period, direct = true), // ★D: punctuation commits directly
            Key("中英", action = TOGGLE_LANG, weight = 1.5f),
            Key("↵", action = ENTER, accent = true, weight = 1.6f),
        )
        return KeyboardLayout(
            LayoutId.ALPHA,
            listOf(KeyboardRow(numbers), KeyboardRow(q), KeyboardRow(a), KeyboardRow(z), KeyboardRow(bottom)),
        )
    }

    /** A 9-key letter cell: shows the letters (e.g. "ABC") but emits the T9 digit for decoding. */
    private fun t9key(letters: String, digit: String) = Key(letters, output = digit)

    /**
     * 9-key T9. The left column (A3) is a vertically-SCROLLABLE strip over the
     * upper 0.75 — all pinyin combinations while composing, the punctuation list at rest — with the pen
     * below it; the tall green enter spans rows 2–3. [left] = the full (possibly long) left-column list;
     * [KeyboardView] draws/scrolls/hit-tests it via [KeyboardLayout.scrollColumn], NOT as fixed cells.
     */
    fun nine(lang: Lang, left: List<Key>, composing: Boolean = false): KeyboardLayout {
        val u = 1f / 4.4f                 // column unit: widths 0.7 | 1 | 1 | 1 | 0.7
        val xL = 0f; val wL = 0.7f * u
        val x1 = 0.7f * u; val x2 = 1.7f * u; val x3 = 2.7f * u; val wM = 1f * u
        val xR = 3.7f * u; val wR = 0.7f * u
        val cells = ArrayList<PlacedKey>()
        // A3: scrollable left column over the upper 0.75 band; ~4 rows visible, scroll for the rest.
        val leftColumn = ScrollColumn(left, xL, 0f, wL, 0.75f, cellHFrac = 0.75f / 4f)
        cells.add(PlacedKey(Key("✎", action = SHOW_SYMBOLS), xL, 0.75f, wL, 0.25f)) // D1: pencil → symbols panel
        // middle 3×3: letters as the main label, T9 digit as the emitted output; "1" position = symbols
        // (idle "@#", more symbols "@!./" while composing).
        // Top-left: while composing it is the 分词/隔音 key (lock a syllable boundary);
        // idle it stays the symbols shortcut (punctuation otherwise via the left column / 符号 panel).
        cells.add(PlacedKey(
            // I6: bold = render at the prominent primary weight of the surrounding letter keys (the 分词 /
            // @# labels used the small faint secondary style and read as "未加粗").
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
        // bottom row: 123 / SPACE (wide) / 中英 — space moved here off the right column.
        // 123 switches to the calculator-style number 9-grid (NUMPAD), not the row-based number page.
        cells.add(PlacedKey(Key("123", action = SWITCH_NUMPAD), x1, 0.75f, 0.8f * u, 0.25f))
        cells.add(PlacedKey(Key("空格", output = " ", action = SPACE), 1.5f * u, 0.75f, 1.4f * u, 0.25f))
        cells.add(PlacedKey(Key("中英", action = TOGGLE_LANG), 2.9f * u, 0.75f, 0.8f * u, 0.25f))
        // right column: 退格(top) / 重输(mid) / 回车(green, tall).
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
                Key("返回", action = SWITCH_TEXT, weight = 1.6f), // H-1: back to the user's text keyboard
                Key(","),
                Key("空格", output = " ", action = SPACE, weight = 4f),
                Key("."),
                Key("↵", action = ENTER, accent = true, weight = 1.6f),
            ),
        ),
    )

    /**
     * I2: the numpad's left scroll column — the default math operators, then the user's [custom] operators,
     * then the 自定义 entry that adds more. Each operator commits straight to the editor (so the inline
     * calculator can then evaluate the expression). Mirrors [ninePunctuation] for the pinyin column.
     */
    /** The built-in default operators (exposed so the 自定义 panel can keep them out of its add list). */
    val defaultNumpadOperators: List<String> = listOf("+", "-", "×", "÷", "=", "(", ")", "%", ".")

    fun numpadOperators(custom: List<String> = emptyList()): List<Key> =
        // distinct() so a custom operator equal to a built-in default doesn't render twice in the column.
        (defaultNumpadOperators + custom).distinct().map { Key(it, direct = true) } +
            Key("自定义", action = CUSTOM_OPERATOR)

    /**
     * Calculator-style number 9-grid (issue #6). I2: the left column is a vertically
     * SCROLLABLE operator strip ([operators], same mechanism as the 9-key left column) instead of the old
     * fixed +/−/×/% column; the digit/function grid fills the other four columns. [rowCount] = 4 so it
     * shares the 4-row page height (no resize when switching 9-key ⇄ 123).
     */
    fun numpad(operators: List<Key> = numpadOperators()): KeyboardLayout {
        val u = 1f / 5f
        val opCol = ScrollColumn(operators, 0f, 0f, u, 1f, cellHFrac = 0.25f) // 4 visible, scroll for the rest
        val cells = ArrayList<PlacedKey>()
        fun cell(key: Key, col: Int, row: Int) = cells.add(PlacedKey(key, col * u, row * 0.25f, u, 0.25f))
        cell(Key("1"), 1, 0); cell(Key("2"), 2, 0); cell(Key("3"), 3, 0); cell(Key("⌫", action = BACKSPACE), 4, 0)
        cell(Key("4"), 1, 1); cell(Key("5"), 2, 1); cell(Key("6"), 3, 1); cell(Key("."), 4, 1)
        cell(Key("7"), 1, 2); cell(Key("8"), 2, 2); cell(Key("9"), 3, 2); cell(Key("@"), 4, 2)
        // H-1: 返回 goes back to the user's text keyboard (9-key by default).
        cell(Key("返回", action = SWITCH_TEXT), 1, 3); cell(Key("0"), 2, 3)
        cell(Key("空格", output = " ", action = SPACE), 3, 3); cell(Key("↵", action = ENTER, accent = true), 4, 3)
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
                Key("©"), Key("®"), Key("™"), Key("℅"), Key("["), Key("]"), Key("¥"),
                Key("⌫", action = BACKSPACE, weight = 1.5f),
            ),
            row(
                Key("返回", action = SWITCH_TEXT, weight = 1.6f), // H-1: back to the user's text keyboard
                Key("<"),
                Key("空格", output = " ", action = SPACE, weight = 4f),
                Key(">"),
                Key("↵", action = ENTER, accent = true, weight = 1.6f),
            ),
        ),
    )
}
