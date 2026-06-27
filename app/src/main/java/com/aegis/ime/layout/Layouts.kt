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
import com.aegis.ime.layout.KeyAction.SWITCH_ALPHA
import com.aegis.ime.layout.KeyAction.SWITCH_NUMBERS
import com.aegis.ime.layout.KeyAction.SWITCH_NUMPAD
import com.aegis.ime.layout.KeyAction.SWITCH_SYMBOLS
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
            Key("✎", action = SHOW_SYMBOLS, weight = 1.3f), // D: pencil → symbols panel
            Key("123", action = SWITCH_NUMBERS, weight = 1.5f),
            Key(comma, sub = "A", direct = true), // ★D punctuation direct; caps/English badge unchanged
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
            if (composing) Key("分词", action = SEGMENT) else Key("@#", action = SWITCH_SYMBOLS),
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
                Key("ABC", action = SWITCH_ALPHA, weight = 1.6f),
                Key(","),
                Key("空格", output = " ", action = SPACE, weight = 4f),
                Key("."),
                Key("↵", action = ENTER, accent = true, weight = 1.6f),
            ),
        ),
    )

    /** Calculator-style number 9-grid for fast digit entry (issue #6). */
    private fun numpad(): KeyboardLayout = KeyboardLayout(
        LayoutId.NUMPAD,
        listOf(
            row(Key("%"), Key("1"), Key("2"), Key("3"), Key("⌫", action = BACKSPACE)),
            row(Key("+"), Key("4"), Key("5"), Key("6"), Key(".")),
            row(Key("-"), Key("7"), Key("8"), Key("9"), Key("@")),
            row(
                Key("*"),
                Key("返回", action = SWITCH_ALPHA),
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
                Key("ABC", action = SWITCH_ALPHA, weight = 1.6f),
                Key("<"),
                Key("空格", output = " ", action = SPACE, weight = 4f),
                Key(">"),
                Key("↵", action = ENTER, accent = true, weight = 1.6f),
            ),
        ),
    )
}
