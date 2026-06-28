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

import java.io.File

/**
 * Backs the "常用" symbol category (D): most-recently-used first, de-duplicated, capped, persisted to
 * filesDir. Re-using a symbol moves it back to the front, so repeatedly-used glyphs stay near the top
 * while newly-used ones surface immediately ("历史 + 时间自动排序", recency-ordered). Single-token symbols
 * only — no newlines — so a plain line-per-entry file is enough.
 */
class SymbolUsageStore(private val dir: File) {

    private val file get() = File(dir, "symbol_usage.txt")
    private val used = ArrayList<String>()

    fun load() {
        used.clear()
        // U2: de-dup on load too — a pre-existing file with duplicate lines (from any older bug) is cleaned
        // so 常用 never shows the same symbol twice. record() already de-dups; this guards the read path.
        val seen = HashSet<String>()
        runCatching { if (file.exists()) file.readLines().forEach { if (it.isNotEmpty() && seen.add(it)) used.add(it) } }
        while (used.size > MAX) used.removeAt(used.size - 1) // honour the cap even for a hand-edited file
    }

    /** Move [symbol] to the front (dedup, cap, persist). No-op for blank symbols. */
    fun record(symbol: String) {
        if (symbol.isEmpty()) return
        used.remove(symbol)
        used.add(0, symbol)
        while (used.size > MAX) used.removeAt(used.size - 1)
        runCatching { file.writeText(used.joinToString("\n")) }
    }

    /** The most-recently-used symbols, newest first (at most [n]). */
    fun recent(n: Int = MAX): List<String> = used.take(n)

    private companion object { const val MAX = 30 }
}
