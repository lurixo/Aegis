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

import com.aegis.ime.layout.SymbolCatalog
import java.io.File

/**
 * Most-recently-used store for the "常用" tab (symbols panel) and, reused, the emoji panel. Each entry keeps
 * the symbol's actual code-point form plus the category it was entered from (its origin), so the recent badge
 * shows the true source instead of guessing the first catalogue category. Re-using a symbol moves it to the
 * front. De-dup is on the full/half-width-folded key, so a full-width and its half-width twin (e.g. ％ and %)
 * keep only the most-recently entered one, in its real form. Persisted one entry per line as "symbol\torigin"
 * (the origin is omitted when there is none), which also reads back an older symbol-only file unchanged.
 */
class SymbolUsageStore(private val dir: File) {

    data class Entry(val symbol: String, val origin: String?)

    private val file get() = File(dir, "symbol_usage.txt")
    private val used = ArrayList<Entry>()

    fun load() {
        used.clear()
        // De-dup on load too — a pre-existing file with duplicate/twin lines (from an older format or bug) is
        // collapsed to one entry per folded key, newest kept.
        val seen = HashSet<String>()
        runCatching {
            if (file.exists()) file.readLines().forEach { line ->
                if (line.isEmpty()) return@forEach
                val tab = line.indexOf('\t')
                val symbol = if (tab >= 0) line.substring(0, tab) else line
                val origin = if (tab >= 0) line.substring(tab + 1).ifEmpty { null } else null
                if (symbol.isNotEmpty() && seen.add(SymbolCatalog.foldFullWidth(symbol))) used.add(Entry(symbol, origin))
            }
        }
        while (used.size > MAX) used.removeAt(used.size - 1) // honour the cap even for a hand-edited file
    }

    /** Move [symbol] to the front, tagged with its [origin] category (full/half-width-folded de-dup, cap,
     *  persist). No-op for blank symbols. */
    fun record(symbol: String, origin: String? = null) {
        if (symbol.isEmpty()) return
        val key = SymbolCatalog.foldFullWidth(symbol)
        used.removeAll { SymbolCatalog.foldFullWidth(it.symbol) == key }
        used.add(0, Entry(symbol, origin))
        while (used.size > MAX) used.removeAt(used.size - 1)
        runCatching { file.writeText(used.joinToString("\n") { if (it.origin == null) it.symbol else "${it.symbol}\t${it.origin}" }) }
    }

    /** The most-recently-used symbols, newest first (at most [n]) — code-point form only. */
    fun recent(n: Int = MAX): List<String> = used.take(n).map { it.symbol }

    /** The recent entries with their stored origin category, newest first (at most [n]). */
    fun recentEntries(n: Int = MAX): List<Entry> = used.take(n)

    /** The stored origin category for a currently-recent [symbol] (matched on the folded key), or null when
     *  it is unknown — e.g. an entry loaded from the older symbol-only format. */
    fun originOf(symbol: String): String? {
        val key = SymbolCatalog.foldFullWidth(symbol)
        return used.firstOrNull { SymbolCatalog.foldFullWidth(it.symbol) == key }?.origin
    }

    private companion object { const val MAX = 30 }
}
