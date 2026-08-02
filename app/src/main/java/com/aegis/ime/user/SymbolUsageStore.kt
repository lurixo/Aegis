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
            val loaded = if (database == null) readLegacyEntries() else emptyList()
            used.clear()
            used.addAll(loaded)
            lastFailure = null
        }.onFailure { lastFailure = failureText(it) }
    }

    fun record(symbol: String, origin: String? = null): Boolean {
        if (symbol.isEmpty()) return false
        val key = SymbolCatalog.foldFullWidth(symbol)
        database?.let { backing ->
            return runCatching {
                backing.recordRecentItem(kind, key, StoredRecentItem(symbol, origin))
                used.removeAll { SymbolCatalog.foldFullWidth(it.symbol) == key }
                used.add(0, Entry(symbol, origin))
                while (used.size > RUNTIME_PAGE_SIZE) used.removeAt(used.lastIndex)
                lastFailure = null
                true
            }.onFailure { lastFailure = failureText(it) }.getOrDefault(false)
        }
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
        database?.let { backing ->
            if (backing.recentItemCount(kind) == 0L) return false
            return runCatching {
                backing.clearRecentItems(kind)
                used.clear()
                lastFailure = null
                true
            }.onFailure { lastFailure = failureText(it) }.getOrDefault(false)
        }
        if (used.isEmpty()) return false
        return runCatching {
            if (database == null) persist(emptyList()) else database.clearRecentItems(kind)
            used.clear()
            lastFailure = null
            true
        }.onFailure { lastFailure = failureText(it) }.getOrDefault(false)
    }

    fun importEntries(incoming: List<Entry>, merge: Boolean): Boolean {
        database?.let { backing ->
            return runCatching {
                backing.replaceRecentItems(kind, incoming.toStoredEntries(), merge)
                lastFailure = null
                true
            }.onFailure { lastFailure = failureText(it) }.getOrDefault(false)
        }
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

    fun recent(n: Int = Int.MAX_VALUE): List<String> = if (database == null) {
        used.take(n).map { it.symbol }
    } else {
        recentPage(0, minOf(n, RUNTIME_PAGE_SIZE)).map { it.symbol }
    }

    fun recentEntries(n: Int = Int.MAX_VALUE): List<Entry> = if (database == null) {
        used.take(n)
    } else {
        recentPage(0, minOf(n, RUNTIME_PAGE_SIZE))
    }

    fun recentPage(offset: Int, limit: Int): List<Entry> {
        require(offset >= 0)
        require(limit >= 0)
        return if (database == null) {
            used.drop(offset).take(limit)
        } else {
            val backing = database
            runCatching {
                backing.readRecentItems(kind, offset, minOf(limit, RUNTIME_PAGE_SIZE)).map {
                    Entry(it.value, it.origin)
                }
            }.onSuccess { page ->
                if (offset == 0) {
                    used.clear()
                    used.addAll(page.take(RUNTIME_PAGE_SIZE))
                }
                lastFailure = null
            }.onFailure { lastFailure = failureText(it) }.getOrElse {
                used.drop(offset).take(limit)
            }
        }
    }

    fun recentPageSnapshot(offset: Int, limit: Int, expectedVersion: Long? = null): PersistedPage<Entry> {
        require(offset >= 0)
        require(limit >= 0)
        return database?.let { backing ->
            runCatching {
                backing.readRecentItemsPage(kind, offset, minOf(limit, RUNTIME_PAGE_SIZE), expectedVersion)
                    .map { Entry(it.value, it.origin) }
            }.onFailure { lastFailure = failureText(it) }
                .getOrElse { PersistedPage(emptyList(), backing.dataVersion(), restartRequired = true) }
        } ?: if (expectedVersion != null && expectedVersion != 0L) {
            PersistedPage(emptyList(), 0L, restartRequired = true)
        } else {
            PersistedPage(used.drop(offset).take(limit), 0L, used.size.toLong())
        }
    }

    fun originOf(symbol: String): String? {
        val key = SymbolCatalog.foldFullWidth(symbol)
        database?.let { return it.recentItemOrigin(kind, key) }
        return used.firstOrNull { SymbolCatalog.foldFullWidth(it.symbol) == key }?.origin
    }

    fun count(): Long = database?.recentItemCount(kind) ?: used.size.toLong()

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

    private companion object {
        const val LEGACY_KIND = "legacy"
        const val RUNTIME_PAGE_SIZE = 128
    }
}
