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

package com.aegis.ime.user

import android.content.SharedPreferences

/**
  * Chinese IME behavior note.
 * persisted in [SharedPreferences]. Surfaced in the punctuation column between the fixed marks and the
  * Chinese IME behavior note.
 */
class CustomSymbolStore(
    private val prefs: SharedPreferences,
    /** Pref key — defaults to the A3 punctuation list; the I2 numpad operator column uses its own key. */
    private val key: String = "custom_symbols",
) {

    fun list(): List<String> =
        prefs.getString(key, "").orEmpty().split("\n").filter { it.isNotEmpty() }

    /**
     * Add one symbol (U13: control chars incl. internal \n\r are STRIPPED — not just trimmed — so a pasted
     * multi-line value can't split into several column marks; then trimmed, de-duplicated, capped at [MAX]).
     */
    fun add(symbol: String): Boolean {
        val s = symbol.filterNot { it.isISOControl() }.trim()
        val cur = list()
        if (s.isEmpty() || s in cur || cur.size >= MAX) return false
        save(cur + s)
        return true
    }

    fun remove(symbol: String) {
        val cur = list()
        if (symbol in cur) save(cur - symbol)
    }

    private fun save(items: List<String>) {
        prefs.edit().putString(key, items.joinToString("\n")).apply()
    }

    private companion object {
        const val MAX = 200 // U13: let the user add essentially any number of custom symbols
    }
}
