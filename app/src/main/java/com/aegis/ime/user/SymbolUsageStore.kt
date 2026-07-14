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
import java.io.IOException

class SymbolUsageStore(private val dir: File) {

    data class Entry(val symbol: String, val origin: String?)

    private val file get() = File(dir, "symbol_usage.txt")
    private val used = ArrayList<Entry>()

    fun load() {
        used.clear()
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
        while (used.size > MAX) used.removeAt(used.size - 1)
    }

    fun record(symbol: String, origin: String? = null) {
        if (symbol.isEmpty()) return
        val key = SymbolCatalog.foldFullWidth(symbol)
        used.removeAll { SymbolCatalog.foldFullWidth(it.symbol) == key }
        used.add(0, Entry(symbol, origin))
        while (used.size > MAX) used.removeAt(used.size - 1)
        runCatching { persist() }
    }

    fun clear() {
        used.clear()
        persist()
    }

    fun importEntries(incoming: List<Entry>, merge: Boolean): Boolean {
        if (!merge) used.clear()
        val seen = used.mapTo(HashSet()) { SymbolCatalog.foldFullWidth(it.symbol) }
        for (e in incoming) {
            if (e.symbol.isEmpty()) continue
            if (seen.add(SymbolCatalog.foldFullWidth(e.symbol))) used.add(e)
        }
        while (used.size > MAX) used.removeAt(used.size - 1)
        persist()
        return true
    }

    private fun persist() {
        val text = used.joinToString("\n") { if (it.origin == null) it.symbol else "${it.symbol}\t${it.origin}" }
        val tmp = File(dir, "symbol_usage.txt.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) {
                tmp.delete()
                throw IOException("symbol usage swap failed")
            }
        }
    }

    fun recent(n: Int = MAX): List<String> = used.take(n).map { it.symbol }

    fun recentEntries(n: Int = MAX): List<Entry> = used.take(n)

    fun originOf(symbol: String): String? {
        val key = SymbolCatalog.foldFullWidth(symbol)
        return used.firstOrNull { SymbolCatalog.foldFullWidth(it.symbol) == key }?.origin
    }

    private companion object { const val MAX = 30 }
}
