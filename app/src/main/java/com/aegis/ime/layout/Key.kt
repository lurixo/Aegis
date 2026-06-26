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

enum class Lang { CN, EN }

enum class LayoutId { ALPHA, NINE, NUMBER, SYMBOL, NUMPAD }

enum class KeyAction {
    COMMIT,
    BACKSPACE,
    CLEAR_COMPOSING,
    ENTER,
    SHIFT,
    SPACE,
    SWITCH_SYMBOLS,
    SWITCH_NUMBERS,
    SWITCH_ALPHA,
    SWITCH_NINE,
    SWITCH_NUMPAD,
    TOGGLE_LANG,
    PICK_READING,
    SHOW_EDIT,
}

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

data class PlacedKey(val key: Key, val x: Float, val y: Float, val w: Float, val h: Float)

data class KeyboardLayout(
    val id: LayoutId,
    val rows: List<KeyboardRow> = emptyList(),
    val cells: List<PlacedKey>? = null,
    val rowCount: Int = rows.size,
)
