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

/** The four P1 keyboard layouts. */
enum class LayoutId { ALPHA, NINE, NUMBER, SYMBOL }

/** What a key does when tapped. */
enum class KeyAction {
    COMMIT,          // emit [Key.output] (directly in EN, or into the composing buffer in CN)
    BACKSPACE,
    ENTER,
    SHIFT,
    SPACE,
    SWITCH_SYMBOLS,
    SWITCH_NUMBERS,
    SWITCH_ALPHA,
    SWITCH_NINE,
    TOGGLE_LANG,
}

/**
 * A single key.
 * @param label main glyph drawn on the key.
 * @param output text emitted on COMMIT (defaults to [label]).
 * @param sub small secondary glyph (e.g. the letters under a T9 digit); null = single line.
 * @param weight relative width within its row.
 */
data class Key(
    val label: String,
    val output: String = label,
    val action: KeyAction = KeyAction.COMMIT,
    val sub: String? = null,
    val weight: Float = 1f,
)

data class KeyboardRow(val keys: List<Key>)

data class KeyboardLayout(val id: LayoutId, val rows: List<KeyboardRow>)
