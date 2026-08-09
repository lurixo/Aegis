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
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class SymbolUsageStore(private val dir: File) {

    data class Entry(val symbol: String, val origin: String?)

    private val file get() = File(dir, "symbol_usage.txt")
    private val used = ArrayList<Entry>()
    private val tmpTag = TMP_TAGS.incrementAndGet()

    internal fun tempFile(): File = File(dir, "symbol_usage.txt.$tmpTag.tmp")

    @Volatile
    var readable: Boolean = true
        private set

    fun load() {
        flushPendingWrites()
        used.clear()
        val seen = HashSet<String>()
        readable = runCatching {
            if (file.exists()) file.readLines().forEach { line ->
                if (line.isEmpty()) return@forEach
                val tab = line.indexOf('\t')
                val symbol = if (tab >= 0) line.substring(0, tab) else line
                val origin = if (tab >= 0) line.substring(tab + 1).ifEmpty { null } else null
                if (symbol.isNotEmpty() && seen.add(SymbolCatalog.foldFullWidth(symbol))) used.add(Entry(symbol, origin))
            }
        }.isSuccess
        while (used.size > MAX) used.removeAt(used.size - 1)
    }

    fun record(symbol: String, origin: String? = null) {
        if (symbol.isEmpty()) return
        val key = SymbolCatalog.foldFullWidth(symbol)
        used.removeAll { SymbolCatalog.foldFullWidth(it.symbol) == key }
        used.add(0, Entry(symbol, origin))
        while (used.size > MAX) used.removeAt(used.size - 1)
        if (LiveUserData.restoreInProgress) return
        val text = serialize()
        onWriteLane { runCatching { writeAtomically(text) } }
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

    private fun onWriteLane(work: () -> Unit) {
        val queued = runCatching { io.execute(work) }.isSuccess
        if (!queued) work()
    }

    private fun onWriteLaneNow(work: () -> Unit) {
        if (Thread.currentThread() === writer) return work()
        val pending = runCatching { io.submit(work) }.getOrNull() ?: return work()
        try {
            pending.get()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("symbol usage write did not finish", e)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    private fun persist() {
        val text = serialize()
        onWriteLaneNow { writeAtomically(text) }
    }

    private fun serialize(): String =
        used.joinToString("\n") { if (it.origin == null) it.symbol else "${it.symbol}\t${it.origin}" }

    private fun writeAtomically(text: String) {
        val tmp = tempFile()
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

    companion object {
        private const val MAX = 30

        private val TMP_TAGS = AtomicLong(0)

        @Volatile
        private var writer: Thread? = null

        private val io = Executors.newSingleThreadExecutor { r ->
            Thread(r, "aegis-usage-io").apply { isDaemon = true }.also { writer = it }
        }

        internal fun flushPendingWrites() {
            if (Thread.currentThread() === writer) return
            runCatching { io.submit { }.get() }
        }
    }
}
