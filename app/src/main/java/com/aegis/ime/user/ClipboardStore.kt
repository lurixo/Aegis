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
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class ClipEntry private constructor(
    @Volatile private var resident: String?,
) {

    internal var captureOrder: Long = 0L

    val key: String = resident.orEmpty()

    fun body(): String? = resident

    override fun equals(other: Any?): Boolean = other is ClipEntry && other.key == key

    override fun hashCode(): Int = key.hashCode()

    override fun toString(): String = key

    companion object {

        private const val SIDECAR_HASH_CHARS = 64

        internal fun isSidecarHash(s: String): Boolean =
            s.length == SIDECAR_HASH_CHARS && s.all { it in '0'..'9' || it in 'a'..'f' }

        fun of(text: String): ClipEntry = ClipEntry(text)
    }
}

class ClipboardStore(private val dir: File) {

    private val histFile get() = File(dir, "clipboard.txt")

    private val tmpTag = TMP_TAGS.incrementAndGet()

    private val history = ArrayList<ClipEntry>()

    private var writer: Thread? = null
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "aegis-clip-io").apply { isDaemon = true }.also { writer = it }
    }
    private val captureOrders = AtomicLong(0)
    private val saveGen = AtomicLong(0)

    @Volatile
    private var historyWriteFailed = false

    @Volatile
    var historyReadable: Boolean = true
        private set

    fun load() {
        val reload = Runnable { adoptHistory(readHistory()) }
        val queued = if (Thread.currentThread() === writer) null else runCatching { io.submit(reload) }.getOrNull()
        if (queued == null) reload.run() else runCatching { queued.get() }
    }

    private class LoadedHistory(val entries: List<ClipEntry>, val readable: Boolean)

    private fun readHistory(): LoadedHistory {
        val entries = ArrayList<ClipEntry>()
        val seen = HashSet<String>()
        val readable = runCatching {
            if (histFile.exists()) Files.readAllLines(histFile.toPath()).forEach { line ->
                readEntry(line)?.let { e ->
                    if (e.key.isNotBlank() && seen.add(e.key)) entries.add(e)
                }
            }
        }.isSuccess
        return LoadedHistory(if (readable) entries else emptyList(), readable)
    }

    private fun adoptHistory(loaded: LoadedHistory) {
        synchronized(history) {
            history.clear()
            history.addAll(loaded.entries)
            historyReadable = loaded.readable
            historyWriteFailed = false
            saveGen.incrementAndGet()
        }
    }

    private fun readEntry(line: String): ClipEntry? {
        return decode(line)?.let(ClipEntry::of)
    }

    fun record(text: String?) {
        if (text.isNullOrBlank()) return
        if (LiveUserData.restoreInProgress || !historyReadable) return
        val entry = adopt(text).apply { captureOrder = captureOrders.incrementAndGet() }
        val pending = synchronized(history) {
            val previous = history.firstOrNull()
            history.remove(entry)
            val at = history.indexOfFirst { it.captureOrder <= entry.captureOrder }.let { if (it < 0) history.size else it }
            if (at == 0 && previous != null && previous == entry && !historyWriteFailed) {
                previous.captureOrder = entry.captureOrder
                history.add(0, previous)
                null
            } else {
                history.add(at, entry)
                PendingWrite(saveGen.incrementAndGet(), ArrayList(history))
            }
        }
        if (pending == null || !historyReadable) return
        onWriteLane { if (pending.gen == saveGen.get()) historyWriteFailed = runCatching { writeHistory(pending.rows) }.isFailure }
    }

    private fun adopt(text: String): ClipEntry =
        ClipEntry.of(text)

    fun history(): List<ClipEntry> = snapshot()

    internal fun latest(): String? = synchronized(history) { history.firstOrNull() }?.body()

    private fun snapshot(): List<ClipEntry> = synchronized(history) { ArrayList(history) }

    private class PendingWrite(val gen: Long, val rows: List<ClipEntry>)

    private fun writeHistory(snapshot: List<ClipEntry>) {
        val sb = StringBuilder()
        for (e in snapshot) {
                sb.append(encodeEntry(e.body().orEmpty())).append('\n')
        }
        atomicWrite(histFile, sb.toString())
    }

    private fun encodeEntry(text: String): String {
        val line = encode(text)
        return line
    }

    private fun atomicWrite(dest: File, text: String) = AtomicFileSwap.write(dest, tmpTag, text)

    internal fun flushPendingWrites() {
        if (Thread.currentThread() === writer) return
        runCatching { io.submit { }.get() }
    }

    fun stopSaving() {
        runCatching { io.shutdown() }
    }

    private fun onWriteLane(work: () -> Unit) {
        val queued = runCatching { io.execute(work) }.isSuccess
        if (!queued) work()
    }

    private fun encode(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")
    private fun decode(line: String): String? {
        if (line.isEmpty()) return null
        val sb = StringBuilder(line.length)
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '\\' && i + 1 < line.length) {
                when (line[i + 1]) {
                    'n' -> sb.append('\n'); 'r' -> sb.append('\r'); '\\' -> sb.append('\\'); else -> sb.append(line[i + 1])
                }
                i += 2
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    companion object {
        private val TMP_TAGS = AtomicLong(0)

        fun foldLineBreaks(s: String): String = s.trim { it in LINE_BREAKS }.replace(LINE_BREAK_RUN, " ")

        private const val LINE_BREAKS = "\n\r\u2028\u2029"
        private val LINE_BREAK_RUN = Regex("[$LINE_BREAKS]+")
    }
}
