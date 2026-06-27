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

/** Input language. CN routes letters through the (stubbed) pinyin engine; EN commits ASCII directly. */
enum class Lang { CN, EN }

/** Keyboard layouts: 26-key, 9-key T9, number page, symbol page, calculator-style number grid. */
enum class LayoutId { ALPHA, NINE, NUMBER, SYMBOL, NUMPAD }

/** What a key does when tapped. */
enum class KeyAction {
    COMMIT,          // emit [Key.output] (directly in EN, or into the composing buffer in CN)
    BACKSPACE,
    CLEAR_COMPOSING, // 9-key "重输": clear the composing buffer + candidates, leave committed text
    ENTER,
    SHIFT,
    SPACE,
    SWITCH_SYMBOLS,
    SWITCH_NUMBERS,
    SWITCH_ALPHA,
    SWITCH_NINE,
    SWITCH_NUMPAD,   // calculator-style number 9-grid
    TOGGLE_LANG,
    PICK_READING,    // 9-key left column: switch to an explicit pinyin reading ([Key.output]) and re-rank
    SHOW_EDIT,       // open the text-editing (cursor/selection) panel
    SEGMENT,         // 9-key 分词/隔音: lock the active syllable boundary while composing
}

/**
 * A single key.
 * @param label main glyph drawn on the key.
 * @param output text emitted on COMMIT (defaults to [label]).
 * @param sub small secondary glyph (super-script symbol on 26-key / letters under a T9 digit); null = single line.
 * @param weight relative width within its row (row-based layouts only).
 * @param direct when true, COMMIT always goes straight to the editor even in pinyin mode (number row, symbols).
 * @param accent draw as the highlighted action key (green enter).
 */
data class Key(
    val label: String,
    val output: String = label,
    val action: KeyAction = KeyAction.COMMIT,
    val sub: String? = null,
    val weight: Float = 1f,
    val direct: Boolean = false,
    val accent: Boolean = false,
)

data class KeyboardRow(val keys: List<Key>)

/**
 * A key placed by fractional rectangle (0..1 of the keyboard) — for grids the simple row model can't
 * express. [groupId] > 0 marks cells that share one merged background capsule (the 9-key peanut column).
 */
data class PlacedKey(val key: Key, val x: Float, val y: Float, val w: Float, val h: Float, val groupId: Int = 0)

/**
 * A keyboard layout. Most layouts are [rows] (equal-height rows, per-key [Key.weight] widths). Layouts
 * that need merged/spanning cells (the 9-key's peanut left column + tall enter) instead provide [cells]
 * with explicit fractional rectangles; [rowCount] then drives the measured height.
 */
data class KeyboardLayout(
    val id: LayoutId,
    val rows: List<KeyboardRow> = emptyList(),
    val cells: List<PlacedKey>? = null,
    val rowCount: Int = rows.size,
)
