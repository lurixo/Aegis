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

class SymbolUsageStore private constructor(
    private val dir: File,
    private val database: UserDataDatabase?,
    private val kind: String,
    @Suppress("UNUSED_PARAMETER") marker: Unit,
) {

    constructor(dir: File) : this(dir, null, LEGACY_KIND, Unit)

    internal constructor(dir: File, database: UserDataDatabase, kind: String) : this(dir, database, kind, Unit)

    data class Entry(val symbol: String, val origin: String?)

    private val file get() = File(dir, "symbol_usage.txt")
    private val used = ArrayList<Entry>()

    @Volatile
    var lastFailure: String? = null
        private set

    fun load() {
        runCatching {
            val loaded = if (database == null) readLegacyEntries() else {
                database.readRecentItems(kind).map { Entry(it.value, it.origin) }
            }
            used.clear()
            used.addAll(loaded)
            lastFailure = null
        }.onFailure { lastFailure = failureText(it) }
    }

    fun record(symbol: String, origin: String? = null): Boolean {
        if (symbol.isEmpty()) return false
        val key = SymbolCatalog.foldFullWidth(symbol)
        val candidate = ArrayList(used)
        candidate.removeAll { SymbolCatalog.foldFullWidth(it.symbol) == key }
        candidate.add(0, Entry(symbol, origin))
        return runCatching {
            if (database == null) persist(candidate)
            else database.recordRecentItem(kind, key, StoredRecentItem(symbol, origin))
            used.clear()
            used.addAll(candidate)
            lastFailure = null
            true
        }.onFailure { lastFailure = failureText(it) }.getOrDefault(false)
    }

    fun clear(): Boolean {
        if (used.isEmpty()) return false
        return runCatching {
            if (database == null) persist(emptyList()) else database.clearRecentItems(kind)
            used.clear()
            lastFailure = null
            true
        }.onFailure { lastFailure = failureText(it) }.getOrDefault(false)
    }

    fun importEntries(incoming: List<Entry>, merge: Boolean): Boolean {
        val candidate = if (merge) ArrayList(used) else ArrayList()
        val seen = candidate.mapTo(HashSet()) { SymbolCatalog.foldFullWidth(it.symbol) }
        for (e in incoming) {
            if (e.symbol.isEmpty()) continue
            if (seen.add(SymbolCatalog.foldFullWidth(e.symbol))) candidate.add(e)
        }
        return runCatching {
            if (database == null) persist(candidate)
            else database.replaceRecentItems(kind, incoming.toStoredEntries(), merge)
            used.clear()
            used.addAll(candidate)
            lastFailure = null
            true
        }.onFailure { lastFailure = failureText(it) }.getOrDefault(false)
    }

    private fun persist(entries: List<Entry>) {
        val text = entries.joinToString("\n") { if (it.origin == null) it.symbol else "${it.symbol}\t${it.origin}" }
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

    fun recent(n: Int = Int.MAX_VALUE): List<String> = used.take(n).map { it.symbol }

    fun recentEntries(n: Int = Int.MAX_VALUE): List<Entry> = used.take(n)

    fun recentPage(offset: Int, limit: Int): List<Entry> {
        require(offset >= 0)
        require(limit >= 0)
        return if (database == null) {
            used.drop(offset).take(limit)
        } else {
            database.readRecentItems(kind, offset, limit).map { Entry(it.value, it.origin) }
        }
    }

    fun originOf(symbol: String): String? {
        val key = SymbolCatalog.foldFullWidth(symbol)
        return used.firstOrNull { SymbolCatalog.foldFullWidth(it.symbol) == key }?.origin
    }

    internal fun storageEntries(): List<Pair<String, StoredRecentItem>> = used.toStoredEntries()

    private fun List<Entry>.toStoredEntries(): List<Pair<String, StoredRecentItem>> {
        val seen = HashSet<String>()
        return mapNotNull { entry ->
            val identity = SymbolCatalog.foldFullWidth(entry.symbol)
            if (entry.symbol.isEmpty() || !seen.add(identity)) null
            else identity to StoredRecentItem(entry.symbol, entry.origin)
        }
    }

    private fun readLegacyEntries(): List<Entry> {
        if (!file.exists()) return emptyList()
        val out = ArrayList<Entry>()
        val seen = HashSet<String>()
        file.readLines().forEach { line ->
            if (line.isEmpty()) return@forEach
            val tab = line.indexOf('\t')
            val symbol = if (tab >= 0) line.substring(0, tab) else line
            val origin = if (tab >= 0) line.substring(tab + 1).ifEmpty { null } else null
            if (symbol.isNotEmpty() && seen.add(SymbolCatalog.foldFullWidth(symbol))) out.add(Entry(symbol, origin))
        }
        return out
    }

    private fun failureText(failure: Throwable): String =
        failure.javaClass.simpleName + ": " + failure.message.orEmpty()

    private companion object { const val LEGACY_KIND = "legacy" }
}
