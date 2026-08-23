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
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class ClipEntry private constructor(
    private val local: File?,
    private val origin: File?,
    internal val hash: String?,
    @Volatile private var resident: String?,
) {

    @Volatile
    private var head: String? = null

    val available: Boolean =
        hash == null || resident != null || local?.isFile == true || origin?.isFile == true

    val key: String = when {
        hash == null -> resident.orEmpty()
        available -> BIG_KEY + hash
        else -> LOST_KEY + hash
    }

    private fun source(): File? = local?.takeIf { it.isFile } ?: origin?.takeIf { it.isFile }

    fun body(): String? = resident ?: source()?.let { runCatching { it.readText() }.getOrNull() }

    fun preview(): String {
        resident?.let { return it }
        val h = hash ?: return ""
        if (!available) return lostLabel(h)
        head?.let { return it }
        val prefix = source()?.let { readHead(it) } ?: return lostLabel(h)
        head = prefix
        return prefix
    }

    internal fun pendingBody(): String? = if (hash == null) null else resident

    internal fun importSource(): File? = source()

    internal fun markPersisted() { if (hash != null) resident = null }

    internal fun residentChars(): Int = (resident?.length ?: 0) + (head?.length ?: 0)

    override fun equals(other: Any?): Boolean = other is ClipEntry && other.key == key

    override fun hashCode(): Int = key.hashCode()

    override fun toString(): String = key

    companion object {

        const val PREVIEW_CHARS = 2 * 1024

        private const val BIG_KEY = "B\t"
        private const val LOST_KEY = " B?\t"
        private const val LOST_MARK = "⚠ "
        private const val SIDECAR_HASH_CHARS = 64

        internal fun isSidecarHash(s: String): Boolean =
            s.length == SIDECAR_HASH_CHARS && s.all { it in '0'..'9' || it in 'a'..'f' }

        internal fun isReferenceKey(key: String): Boolean =
            key.startsWith(LOST_KEY) || (key.startsWith(BIG_KEY) && isSidecarHash(key.substring(BIG_KEY.length)))

        fun of(text: String): ClipEntry = ClipEntry(null, null, null, text)

        internal fun pending(dir: File, hash: String, body: String): ClipEntry =
            ClipEntry(File(dir, "$hash.txt"), null, hash, body)

        internal fun stored(dir: File, hash: String): ClipEntry =
            ClipEntry(File(dir, "$hash.txt"), null, hash, null)

        internal fun rehomed(dir: File, hash: String, origin: File?): ClipEntry =
            ClipEntry(File(dir, "$hash.txt"), origin, hash, null)

        private fun lostLabel(hash: String): String = LOST_MARK + hash.take(8)

        private fun readHead(f: File): String? = runCatching {
            f.reader().use { r ->
                val buf = CharArray(PREVIEW_CHARS)
                var n = 0
                while (n < PREVIEW_CHARS) {
                    val k = r.read(buf, n, PREVIEW_CHARS - n)
                    if (k < 0) break
                    n += k
                }
                String(buf, 0, n)
            }
        }.getOrNull()
    }
}

internal class UnreadablePhrasesException : IOException("saved phrases could not be read")

enum class PhraseEdit { ADD, MOVE, TEXT, CATEGORY, LIST }

class PhraseChange(val edit: PhraseEdit, val count: Int, val requested: Int, val saved: Boolean)

class ClipboardStore(private val dir: File) {

    private val histFile get() = File(dir, "clipboard.txt")
    private val phraseFile get() = File(dir, "phrases.txt")
    private fun clipsDir() = File(dir, "clips")

    private val tmpTag = TMP_TAGS.incrementAndGet()

    internal fun owns(other: File): Boolean = dir == other

    internal fun tempFileFor(dest: File): File = AtomicFileSwap.stagingFor(dest, tmpTag)

    private val history = ArrayList<ClipEntry>()

    private var writer: Thread? = null
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "aegis-clip-io").apply { isDaemon = true }.also { writer = it }
    }
    private var changer: Thread? = null
    private val changes = Executors.newSingleThreadExecutor { r ->
        Thread(r, "aegis-clip-changes").apply { isDaemon = true }.also { changer = it }
    }
    private val saveGen = AtomicLong(0)
    private val phrasesPending = AtomicLong(0)

    private class Phrase(var text: String, var note: String = "")
    private class Category(var name: String, val phrases: ArrayList<Phrase> = ArrayList())
    private val phraseCats = ArrayList<Category>()

    @Volatile
    var historyReadable: Boolean = true
        private set

    @Volatile
    var phrasesReadable: Boolean = true
        private set

    @Volatile
    private var writeReportLane: Executor = Executor { it.run() }

    @Volatile
    private var writeReport: ((PhraseChange) -> Unit)? = null

    fun reportPhraseWritesTo(lane: Executor, report: (PhraseChange) -> Unit) {
        writeReportLane = lane
        writeReport = report
    }

    fun stopReportingPhraseWrites() { writeReport = null }

    @Volatile
    private var clipReportLane: Executor = Executor { it.run() }

    @Volatile
    private var clipReport: ((Boolean) -> Unit)? = null

    fun reportClipWritesTo(lane: Executor, report: (Boolean) -> Unit) {
        clipReportLane = lane
        clipReport = report
    }

    fun stopReportingClipWrites() { clipReport = null }

    private fun reportClipWrite(landed: Boolean) {
        if (clipReport == null) return
        clipReportLane.execute { clipReport?.invoke(landed) }
    }

    private fun clipWritesAllowed(): Boolean = historyReadable && !LiveUserData.restoreInProgress

    fun load() {
        flushPendingWrites()
        synchronized(history) {
            history.clear()
            purgeLegacyImageDir()
            val seen = HashSet<String>()
            historyReadable = runCatching {
                if (histFile.exists()) Files.readAllLines(histFile.toPath()).forEach { line ->
                    readEntry(line)?.let { e ->
                        if (e.key.isNotBlank() && !isLegacyImageEntry(e.key) && seen.add(e.key)) history.add(e)
                    }
                }
            }.isSuccess
            if (!historyReadable) history.clear()
        }
        loadPhrases()
    }

    private fun purgeLegacyImageDir() { runCatching { File(dir, "clipboard_images").deleteRecursively() } }

    private fun readEntry(line: String): ClipEntry? {
        if (line.startsWith(BIG_LINE)) {
            val hash = line.substring(BIG_LINE.length)
            if (ClipEntry.isSidecarHash(hash)) return ClipEntry.stored(clipsDir(), hash)
        }
        return decode(line)?.let(ClipEntry::of)
    }

    private fun loadPhrases() {
        synchronized(phraseCats) {
            phraseCats.clear()
            if (!phraseFile.exists()) {
                phrasesReadable = true
                phraseCats.add(Category(DEFAULT_CATEGORY_ID, ArrayList(DEFAULT_PHRASES.map { Phrase(it) })))
                return
            }
            val read = runCatching { Files.readAllLines(phraseFile.toPath()) }
            phrasesReadable = read.isSuccess
            val lines = read.getOrDefault(emptyList())
            if (lines.none { it.startsWith("C\t") }) {
                val c = Category(DEFAULT_CATEGORY_ID)
                lines.forEach { decode(it)?.let { p -> if (p.isNotBlank()) c.phrases.add(Phrase(p)) } }
                phraseCats.add(c)
                return
            }
            phraseCats.addAll(canonicalCategories(parseCategories(lines)))
            if (phraseCats.isEmpty()) phraseCats.add(Category(DEFAULT_CATEGORY_ID))
        }
    }

    private fun parseCategories(lines: List<String>): List<Category> {
        val out = ArrayList<Category>()
        var cur: Category? = null
        var last: Phrase? = null
        for (line in lines) when {
            line.startsWith("C\t") -> { val c = Category(decode(line.substring(2)).orEmpty()); out.add(c); cur = c; last = null }
            line.startsWith("P\t") -> decode(line.substring(2))?.let { p -> Phrase(p).also { cur?.phrases?.add(it); last = it } }
            line.startsWith("N\t") -> decode(line.substring(2))?.let { n -> last?.note = n }
        }
        return out
    }

    private fun canonicalCategories(categories: List<Category>): ArrayList<Category> {
        val out = mergeSameNameCategories(categories)
        if (out.none { it.name == DEFAULT_CATEGORY_ID }) {
            out.firstOrNull { it.name == LEGACY_DEFAULT_NAME }?.let { it.name = DEFAULT_CATEGORY_ID }
        }
        return mergeSameNameCategories(out)
    }

    private fun mergeSameNameCategories(categories: List<Category>): ArrayList<Category> {
        val out = ArrayList<Category>()
        val byName = LinkedHashMap<String, Category>()
        for (source in categories) {
            if (source.name.isBlank()) continue
            val dest = byName[source.name] ?: Category(source.name).also {
                byName[source.name] = it
                out.add(it)
            }
            for (p in source.phrases) mergePhraseInto(dest, p)
        }
        return out
    }

    fun record(text: String?) {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return
        onChangeLane { recordHere(t) }
    }

    private fun recordHere(text: String) {
        if (LiveUserData.restoreInProgress || !historyReadable) return
        val entry = adopt(text)
        val pending = synchronized(history) {
            history.remove(entry)
            history.add(0, entry)
            PendingWrite(saveGen.incrementAndGet(), ArrayList(history))
        }
        if (!historyReadable) return
        onWriteLane { if (pending.gen == saveGen.get()) runCatching { writeHistory(pending.rows) } }
    }

    fun importHistory(entries: List<ClipEntry>, merge: Boolean) {
        flushPendingWrites()
        val incoming = entries.mapNotNull(::adopt)
        val snapshot = synchronized(history) {
            if (merge) {
                if (!historyReadable) throw IOException("clipboard history could not be read")
                val present = HashSet(history)
                for (e in incoming) if (present.add(e)) history.add(e)
            } else {
                history.clear()
                val seen = HashSet<ClipEntry>()
                for (e in incoming) if (seen.add(e)) history.add(e)
            }
            ArrayList(history)
        }
        onWriteLaneNow { writeHistory(snapshot) }
        if (!merge) historyReadable = true
    }

    private fun adopt(text: String): ClipEntry =
        if (text.length > BIG_THRESHOLD) ClipEntry.pending(clipsDir(), sha256(text), text) else ClipEntry.of(text)

    private fun adopt(entry: ClipEntry): ClipEntry? {
        entry.hash?.let { hash ->
            val pending = entry.pendingBody()
            return if (pending != null) ClipEntry.pending(clipsDir(), hash, pending)
            else ClipEntry.rehomed(clipsDir(), hash, entry.importSource())
        }
        val body = entry.body()?.trim().orEmpty()
        return if (body.isEmpty()) null else adopt(body)
    }

    fun delete(text: String) = deleteAll(listOf(text))

    fun deleteAll(texts: Collection<String>): Boolean {
        settleChanges()
        if (!clipWritesAllowed()) { reportClipWrite(false); return false }
        val keys = texts.toSet()
        if (!synchronized(history) { history.removeAll { it.key in keys } }) return true
        saveHistoryLater()
        return true
    }

    fun editClip(key: String, newText: String): Boolean {
        settleChanges()
        if (!clipWritesAllowed()) { reportClipWrite(false); return false }
        val text = newText.trim()
        if (text.isEmpty()) { reportClipWrite(false); return false }
        val replacement = adopt(text)
        var missing = false
        val changed = synchronized(history) {
            val at = history.indexOfFirst { it.key == key }
            if (at < 0) { missing = true; false }
            else if (history[at].key == replacement.key) false
            else {
                val duplicate = history.indexOfFirst { it.key == replacement.key }
                if (duplicate < 0 || duplicate == at) history[at] = replacement
                else {
                    history[minOf(at, duplicate)] = replacement
                    history.removeAt(maxOf(at, duplicate))
                }
                true
            }
        }
        if (missing) { reportClipWrite(false); return false }
        if (changed) saveHistoryLater()
        return true
    }

    fun clearHistory(): Boolean {
        settleChanges()
        if (!clipWritesAllowed()) { reportClipWrite(false); return false }
        if (!synchronized(history) { history.isNotEmpty().also { history.clear() } }) return true
        saveHistoryLater()
        return true
    }

    fun history(): List<ClipEntry> {
        settleChanges()
        return snapshot()
    }

    internal fun latest(): String? {
        settleChanges()
        return synchronized(history) { history.firstOrNull() }?.body()
    }

    internal fun residentBodyChars(): Long {
        settleChanges()
        return snapshot().sumOf { it.residentChars().toLong() }
    }

    private fun snapshot(): List<ClipEntry> = synchronized(history) { ArrayList(history) }


    fun reloadPhrases() {
        if (phrasesPending.get() > 0L) return
        loadPhrases()
    }

    fun categories(): List<String> = synchronized(phraseCats) { phraseCats.map { it.name } }

    fun phrasesIn(category: String): List<String> =
        synchronized(phraseCats) { find(category)?.phrases?.map { it.text } ?: emptyList() }

    fun phrases(): List<String> =
        synchronized(phraseCats) { phraseCats.flatMap { c -> c.phrases.map { it.text } } }

    fun noteFor(category: String, text: String): String =
        synchronized(phraseCats) { findPhrase(find(category), text)?.note.orEmpty() }

    fun setPhraseNote(category: String, text: String, note: String): Boolean {
        if (!phraseWritesAllowed()) { refusePhraseWrite(PhraseEdit.TEXT, 1); return false }
        val after = synchronized(phraseCats) {
            val p = findPhrase(find(category), text) ?: return false
            p.note = sanitizePhraseText(note)
            serialize(phraseCats)
        }
        writePhrases(PhraseEdit.TEXT, 1, 1, after)
        return true
    }

    fun addCategory(name: String): Boolean {
        if (!phraseWritesAllowed()) { refusePhraseWrite(PhraseEdit.CATEGORY, 1); return false }
        val after = synchronized(phraseCats) {
            val n = sanitizePhraseText(name)
            if (n.isEmpty() || phraseCats.any { it.name == n }) return false
            phraseCats.add(Category(n))
            serialize(phraseCats)
        }
        writePhrases(PhraseEdit.CATEGORY, 1, 1, after)
        return true
    }

    fun deleteCategory(name: String) {
        if (!phraseWritesAllowed()) { refusePhraseWrite(PhraseEdit.LIST, 1); return }
        val after = synchronized(phraseCats) {
            if (!phraseCats.removeAll { it.name == name }) return
            serialize(phraseCats)
        }
        writePhrases(PhraseEdit.LIST, 1, 1, after)
    }

    fun renameCategory(old: String, new: String): Boolean {
        if (!phraseWritesAllowed()) { refusePhraseWrite(PhraseEdit.TEXT, 1); return false }
        val after = synchronized(phraseCats) {
            val n = sanitizePhraseText(new)
            val c = find(old) ?: return false
            if (n.isEmpty() || (n != old && phraseCats.any { it.name == n })) return false
            c.name = n
            serialize(phraseCats)
        }
        writePhrases(PhraseEdit.TEXT, 1, 1, after)
        return true
    }

    fun addPhrasesTo(category: String, texts: Collection<String>): Int {
        val requested = texts.size
        val name = sanitizePhraseText(category)
        if (name.isEmpty()) return 0
        if (!phraseWritesAllowed()) { refusePhraseWrite(PhraseEdit.ADD, requested); return 0 }
        var added = 0
        val after = synchronized(phraseCats) {
            val c = find(category) ?: find(name) ?: Category(name).also { phraseCats.add(it) }
            val seen = c.phrases.mapTo(HashSet()) { sanitizePhraseText(it.text) }
            val fresh = ArrayList<Phrase>()
            for (raw in texts) {
                val t = sanitizePhraseText(raw)
                if (t.isEmpty() || !seen.add(t)) continue
                fresh.add(Phrase(t))
            }
            if (fresh.isEmpty()) null
            else {
                c.phrases.addAll(0, fresh)
                added = fresh.size
                serialize(phraseCats)
            }
        }
        if (after == null) reportPhraseWrite(PhraseChange(PhraseEdit.ADD, 0, requested, true))
        else writePhrases(PhraseEdit.ADD, added, requested, after)
        return added
    }

    fun addPhrases(texts: Collection<String>): Int =
        addPhrasesTo(synchronized(phraseCats) { phraseCats.firstOrNull()?.name ?: DEFAULT_CATEGORY_ID }, texts)

    fun deletePhraseFrom(category: String, text: String): Boolean = deletePhrasesFrom(category, listOf(text))

    fun deletePhrasesFrom(category: String, texts: Collection<String>): Boolean {
        if (!phraseWritesAllowed()) { refusePhraseWrite(PhraseEdit.LIST, texts.size); return false }
        val victims = texts.toSet()
        val after = synchronized(phraseCats) {
            val c = find(category) ?: return true
            if (!c.phrases.removeAll { it.text in victims }) return true
            serialize(phraseCats)
        }
        writePhrases(PhraseEdit.LIST, victims.size, texts.size, after)
        return true
    }

    fun deletePhrase(text: String) {
        if (!phraseWritesAllowed()) { refusePhraseWrite(PhraseEdit.LIST, 1); return }
        val after = synchronized(phraseCats) {
            var changed = false
            for (c in phraseCats) if (c.phrases.removeAll { it.text == text }) changed = true
            if (!changed) return
            serialize(phraseCats)
        }
        writePhrases(PhraseEdit.LIST, 1, 1, after)
    }

    fun clearPhrasesIn(category: String): Int {
        if (!phraseWritesAllowed()) { refusePhraseWrite(PhraseEdit.LIST, 0); return 0 }
        var cleared = 0
        val after = synchronized(phraseCats) {
            val c = find(category) ?: return 0
            if (c.phrases.isEmpty()) return 0
            cleared = c.phrases.size
            c.phrases.clear()
            serialize(phraseCats)
        }
        writePhrases(PhraseEdit.LIST, cleared, cleared, after)
        return cleared
    }

    fun editPhrase(category: String, oldText: String, newText: String): Boolean {
        if (!phraseWritesAllowed()) { refusePhraseWrite(PhraseEdit.TEXT, 1); return false }
        val after = synchronized(phraseCats) {
            val c = find(category) ?: return false
            val idx = c.phrases.indexOfFirst { it.text == oldText }
            if (idx < 0) return false
            val n = sanitizePhraseText(newText)
            if (n.isEmpty()) return false
            if (c.phrases.withIndex().any { (j, p) -> j != idx && p.text == n }) return false
            c.phrases[idx].text = n
            serialize(phraseCats)
        }
        writePhrases(PhraseEdit.TEXT, 1, 1, after)
        return true
    }

    fun movePhrase(fromCategory: String, text: String, toCategory: String): Boolean {
        if (!phraseWritesAllowed()) { refusePhraseWrite(PhraseEdit.MOVE, 1); return false }
        val after = synchronized(phraseCats) {
            val to = find(toCategory) ?: return false
            val from = find(fromCategory) ?: return false
            if (from === to) return true
            val p = findPhrase(from, text) ?: return false
            from.phrases.remove(p)
            carryInto(to, p)
            serialize(phraseCats)
        }
        writePhrases(PhraseEdit.MOVE, 1, 1, after)
        return true
    }

    private fun carryInto(to: Category, p: Phrase) = mergePhraseInto(to, p)

    private fun mergePhraseInto(to: Category, p: Phrase) {
        if (p.text.isBlank()) return
        val existing = findPhrase(to, p.text)
        if (existing == null) to.phrases.add(Phrase(p.text, p.note))
        else if (existing.note.isEmpty() && p.note.isNotEmpty()) existing.note = p.note
    }

    fun movePhrasesTo(fromCategory: String, texts: Collection<String>, toCategory: String): Int {
        val requested = texts.size
        if (!phraseWritesAllowed()) { refusePhraseWrite(PhraseEdit.MOVE, requested); return 0 }
        var moved = 0
        val after = synchronized(phraseCats) {
            val to = find(toCategory) ?: return 0
            val from = find(fromCategory) ?: return 0
            if (from === to) return 0
            for (t in texts) {
                val p = findPhrase(from, t) ?: continue
                from.phrases.remove(p)
                carryInto(to, p)
                moved++
            }
            if (moved == 0) return 0
            serialize(phraseCats)
        }
        writePhrases(PhraseEdit.MOVE, moved, requested, after)
        return moved
    }

    fun reorderPhrase(category: String, fromIndex: Int, toIndex: Int): Boolean {
        if (!phraseWritesAllowed()) { refusePhraseWrite(PhraseEdit.LIST, 1); return false }
        val after = synchronized(phraseCats) {
            val c = find(category) ?: return false
            val n = c.phrases.size
            if (fromIndex !in 0 until n || toIndex !in 0 until n || fromIndex == toIndex) return false
            c.phrases.add(toIndex, c.phrases.removeAt(fromIndex))
            serialize(phraseCats)
        }
        writePhrases(PhraseEdit.LIST, 1, 1, after)
        return true
    }

    fun reorderCategory(fromIndex: Int, toIndex: Int): Boolean {
        if (!phraseWritesAllowed()) { refusePhraseWrite(PhraseEdit.LIST, 1); return false }
        val after = synchronized(phraseCats) {
            val n = phraseCats.size
            if (fromIndex !in 0 until n || toIndex !in 0 until n || fromIndex == toIndex) return false
            phraseCats.add(toIndex, phraseCats.removeAt(fromIndex))
            serialize(phraseCats)
        }
        writePhrases(PhraseEdit.LIST, 1, 1, after)
        return true
    }

    private fun find(name: String): Category? = phraseCats.firstOrNull { it.name == name }
    private fun findPhrase(c: Category?, text: String): Phrase? = c?.phrases?.firstOrNull { it.text == text }

    private class PendingWrite(val gen: Long, val rows: List<ClipEntry>)

    private fun stampPendingWrite(): PendingWrite =
        synchronized(history) { PendingWrite(saveGen.incrementAndGet(), ArrayList(history)) }

    private fun saveHistoryLater() {
        val pending = stampPendingWrite()
        val queued = runCatching {
            io.execute {
                val landed = pending.gen != saveGen.get() ||
                    runCatching { writeHistory(pending.rows) }.isSuccess
                reportClipWrite(landed)
            }
        }.isSuccess
        if (!queued) reportClipWrite(false)
    }

    private fun writeHistory(snapshot: List<ClipEntry>) {
        val sb = StringBuilder()
        val referenced = HashSet<String>()
        for (e in snapshot) {
            val hash = e.hash
            if (hash != null) {
                referenced.add(hash)
                persistSidecar(hash, e)
                sb.append(BIG_LINE).append(hash).append('\n')
            } else {
                sb.append(encodeEntry(e.body().orEmpty())).append('\n')
            }
        }
        atomicWrite(histFile, sb.toString())
        clipsDir().listFiles()?.forEach { f ->
            if (f.name.endsWith(".txt") && f.name.removeSuffix(".txt") !in referenced) runCatching { f.delete() }
        }
    }

    private fun persistSidecar(hash: String, entry: ClipEntry) {
        val dest = File(clipsDir(), "$hash.txt")
        if (!dest.isFile) {
            val pending = entry.pendingBody()
            val source = entry.importSource()
            when {
                pending != null -> { makeClipsDir(); atomicWrite(dest, pending) }
                source != null -> { makeClipsDir(); atomicCopy(source, dest) }
                else -> return
            }
        }
        entry.markPersisted()
    }

    private fun makeClipsDir() {
        val sideDir = clipsDir()
        if (!sideDir.exists() && !sideDir.mkdirs()) throw IOException("clipboard sidecar directory creation failed")
    }

    private fun encodeEntry(text: String): String {
        val line = encode(text)
        return if (line.startsWith(BIG_LINE) && ClipEntry.isSidecarHash(line.substring(BIG_LINE.length))) "\\$line" else line
    }

    private fun atomicWrite(dest: File, text: String) = AtomicFileSwap.write(dest, tmpTag, text)

    private fun atomicCopy(source: File, dest: File) = AtomicFileSwap.copy(source, dest, tmpTag)

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    internal fun flushPendingWrites() {
        settleChanges()
        if (Thread.currentThread() === writer) return
        runCatching { io.submit { }.get() }
    }

    internal fun settleChanges() {
        if (Thread.currentThread() === changer || Thread.currentThread() === writer) return
        runCatching { changes.submit { }.get() }
    }

    fun stopSaving() {
        runCatching { changes.shutdown() }
        runCatching { io.shutdown() }
    }

    private fun onChangeLane(work: () -> Unit) {
        if (Thread.currentThread() === changer) return work()
        val queued = runCatching { changes.execute(work) }.isSuccess
        if (!queued) work()
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
            throw IOException("clipboard write did not finish", e)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    private fun phraseWritesAllowed(): Boolean = phrasesReadable && !LiveUserData.restoreInProgress

    private fun writePhrases(edit: PhraseEdit, count: Int, requested: Int, text: String) {
        phrasesPending.incrementAndGet()
        val queued = runCatching {
            io.execute {
                val landed = runCatching { atomicWrite(phraseFile, text) }.isSuccess
                phrasesPending.decrementAndGet()
                reportPhraseWrite(PhraseChange(edit, count, requested, landed))
            }
        }.isSuccess
        if (!queued) {
            phrasesPending.decrementAndGet()
            reportPhraseWrite(PhraseChange(edit, count, requested, false))
        }
    }

    private fun refusePhraseWrite(edit: PhraseEdit, requested: Int) =
        reportPhraseWrite(PhraseChange(edit, 0, requested, false))

    private fun reportPhraseWrite(change: PhraseChange) {
        if (writeReport == null) return
        writeReportLane.execute { writeReport?.invoke(change) }
    }

    private fun savePhrasesOrThrow(text: String) {
        onWriteLaneNow { atomicWrite(phraseFile, text) }
    }

    private fun serializePhrases(): String = synchronized(phraseCats) { serialize(phraseCats) }

    private fun serialize(categories: List<Category>): String {
        val sb = StringBuilder()
        for (c in categories) {
            sb.append("C\t").append(encode(c.name)).append('\n')
            for (p in c.phrases) {
                sb.append("P\t").append(encode(p.text)).append('\n')
                if (p.note.isNotEmpty()) sb.append("N\t").append(encode(p.note)).append('\n')
            }
        }
        return sb.toString()
    }


    fun exportPhrasesText(): String {
        if (!phrasesReadable) throw UnreadablePhrasesException()
        return serializePhrases()
    }

    fun importPhrasesText(text: String, merge: Boolean): Boolean {
        val parsed = canonicalCategories(parseCategories(text.lineSequence().toList()))
        if (parsed.isEmpty()) return false
        val next = synchronized(phraseCats) {
            if (merge) {
                if (!phrasesReadable) throw UnreadablePhrasesException()
                val out = canonicalCategories(phraseCats)
                for (pc in parsed) {
                    val c = out.firstOrNull { it.name == pc.name } ?: Category(pc.name).also { out.add(it) }
                    for (p in pc.phrases) mergePhraseInto(c, p)
                }
                out
            } else {
                val out = ArrayList(parsed)
                if (out.none { it.name == DEFAULT_CATEGORY_ID }) out.add(0, Category(DEFAULT_CATEGORY_ID))
                out
            }
        }
        savePhrasesOrThrow(serialize(next))
        synchronized(phraseCats) {
            phraseCats.clear()
            phraseCats.addAll(next)
            if (!merge) phrasesReadable = true
        }
        return true
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

        private const val LEGACY_IMG_PREFIX = "img:"
        private const val LEGACY_IMG_DIR = "/clipboard_images/"
        fun isLegacyImageEntry(entry: String): Boolean =
            entry.startsWith(LEGACY_IMG_PREFIX) && entry.contains(LEGACY_IMG_DIR)

        fun shouldCapture(historyEnabled: Boolean): Boolean = historyEnabled

        fun sanitizePhraseText(s: String): String = s
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .filterNot { it != '\n' && Character.isISOControl(it) }
            .trim()

        private const val BIG_LINE = "B\t"
        const val BIG_THRESHOLD = 64 * 1024

        const val DEFAULT_CATEGORY_ID = "default"
        private const val LEGACY_DEFAULT_NAME = "默认"
        private val DEFAULT_PHRASES = emptyList<String>()
    }
}
