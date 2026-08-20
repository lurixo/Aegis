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
import java.nio.file.Files
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

class SymbolUsageStore(private val dir: File) {

    data class Entry(val symbol: String, val origin: String?)

    private data class RemovedEntry(
        val entry: Entry,
        val previousKey: String?,
        val nextKey: String?,
    )

    private val file get() = File(dir, "symbol_usage.txt")
    private val used = ArrayList<Entry>()
    private val tmpTag = TMP_TAGS.incrementAndGet()

    internal fun tempFile(): File = AtomicFileSwap.stagingFor(file, tmpTag)

    @Volatile
    var readable: Boolean = true
        private set

    private val loadGen = AtomicLong(0)

    @Volatile
    private var clearFailed = false

    private var clearedAway: List<Entry> = emptyList()
    private val failedRemovals = ConcurrentLinkedQueue<RemovedEntry>()

    @Volatile
    private var reportLane: Executor = Executor { it.run() }

    @Volatile
    private var report: ((Boolean) -> Unit)? = null

    fun reportWritesTo(lane: Executor, report: (Boolean) -> Unit) {
        reportLane = lane
        this.report = report
    }

    fun stopReportingWrites() { report = null }

    private fun reportWrite(landed: Boolean) {
        if (report == null) return
        reportLane.execute { report?.invoke(landed) }
    }

    fun load() {
        val restored = settleDestructiveWrites(persistRecovery = false)
        if (restored && readable && !LiveUserData.restoreInProgress) {
            val recovered = serialize()
            if (runCatching { onWriteLaneNow { writeRecoveryAtomically(recovered) } }.isFailure) {
                readable = false
                return
            }
        }
        loadGen.incrementAndGet()
        used.clear()
        val seen = HashSet<String>()
        readable = runCatching {
            if (file.exists()) Files.readAllLines(file.toPath()).forEach { line ->
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
        settleDestructiveWrites()
        if (symbol.isEmpty() || !readable) return
        val key = SymbolCatalog.foldFullWidth(symbol)
        used.removeAll { SymbolCatalog.foldFullWidth(it.symbol) == key }
        used.add(0, Entry(symbol, origin))
        while (used.size > MAX) used.removeAt(used.size - 1)
        if (LiveUserData.restoreInProgress) return
        writeLater(serialize())
    }

    fun clear(): Boolean {
        settleDestructiveWrites()
        if (!readable) { reportWrite(false); return false }
        if (used.isEmpty()) return true
        clearedAway = ArrayList(used)
        used.clear()
        writeLater("", tellClear = true)
        return true
    }

    fun remove(symbol: String): Boolean {
        settleDestructiveWrites()
        if (!readable) { reportWrite(false); return false }
        if (symbol.isEmpty()) return true
        val key = SymbolCatalog.foldFullWidth(symbol)
        val index = used.indexOfFirst { SymbolCatalog.foldFullWidth(it.symbol) == key }
        if (index < 0) return true
        val removed = RemovedEntry(
            entry = used[index],
            previousKey = used.getOrNull(index - 1)?.symbol?.let(SymbolCatalog::foldFullWidth),
            nextKey = used.getOrNull(index + 1)?.symbol?.let(SymbolCatalog::foldFullWidth),
        )
        used.removeAt(index)
        if (LiveUserData.restoreInProgress) return true
        writeLater(serialize(), removed = removed)
        return true
    }

    private fun settleDestructiveWrites(persistRecovery: Boolean = true): Boolean {
        var restored = settleClear()
        while (true) {
            val removed = failedRemovals.poll() ?: break
            val removedKey = SymbolCatalog.foldFullWidth(removed.entry.symbol)
            if (used.any { SymbolCatalog.foldFullWidth(it.symbol) == removedKey }) continue
            val nextIndex = removed.nextKey?.let { key ->
                used.indexOfFirst { SymbolCatalog.foldFullWidth(it.symbol) == key }.takeIf { it >= 0 }
            }
            val previousIndex = removed.previousKey?.let { key ->
                used.indexOfFirst { SymbolCatalog.foldFullWidth(it.symbol) == key }.takeIf { it >= 0 }
            }
            val index = nextIndex ?: previousIndex?.plus(1) ?: used.size
            used.add(index.coerceIn(0, used.size), removed.entry)
            restored = true
        }
        while (used.size > MAX) used.removeAt(used.size - 1)
        if (restored && persistRecovery && readable && !LiveUserData.restoreInProgress) {
            writeLater(serialize(), recovery = true)
        }
        return restored
    }

    private fun settleClear(): Boolean {
        if (!clearFailed) return false
        clearFailed = false
        val back = clearedAway
        clearedAway = emptyList()
        val seen = used.mapTo(HashSet()) { SymbolCatalog.foldFullWidth(it.symbol) }
        for (e in back) if (seen.add(SymbolCatalog.foldFullWidth(e.symbol))) used.add(e)
        while (used.size > MAX) used.removeAt(used.size - 1)
        return true
    }

    private fun writeLater(
        text: String,
        tellClear: Boolean = false,
        removed: RemovedEntry? = null,
        recovery: Boolean = false,
    ) {
        val gen = loadGen.get()
        val queued = runCatching {
            io.execute {
                val overtaken = gen != loadGen.get()
                val landed = !overtaken && runCatching {
                    if (recovery) writeRecoveryAtomically(text) else writeAtomically(text)
                }.isSuccess
                if (tellClear || removed != null) {
                    if (!landed && !overtaken && tellClear) clearFailed = true
                    if (!landed && !overtaken && removed != null) failedRemovals.add(removed)
                    reportWrite(landed)
                }
            }
        }.isSuccess
        if (!queued && (tellClear || removed != null)) {
            if (tellClear) clearFailed = true
            if (removed != null) failedRemovals.add(removed)
            reportWrite(false)
        }
    }

    fun importEntries(incoming: List<Entry>, merge: Boolean): Boolean {
        settleDestructiveWrites()
        if (merge) {
            if (!readable) return false
        } else {
            used.clear()
            readable = true
        }
        val seen = used.mapTo(HashSet()) { SymbolCatalog.foldFullWidth(it.symbol) }
        for (e in incoming) {
            if (e.symbol.isEmpty()) continue
            if (seen.add(SymbolCatalog.foldFullWidth(e.symbol))) used.add(e)
        }
        while (used.size > MAX) used.removeAt(used.size - 1)
        persist()
        return true
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

    private fun writeAtomically(text: String) = AtomicFileSwap.write(file, tmpTag, text)

    private fun writeRecoveryAtomically(text: String) {
        val current = if (file.exists()) file.readText().trimEnd('\r', '\n') else ""
        if (current != text) writeAtomically(text)
    }

    fun recent(n: Int = MAX): List<String> {
        settleDestructiveWrites()
        return used.take(n).map { it.symbol }
    }

    fun recentEntries(n: Int = MAX): List<Entry> {
        settleDestructiveWrites()
        return used.take(n)
    }

    fun originOf(symbol: String): String? {
        settleDestructiveWrites()
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
