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

    internal var captureOrder: Long = 0L

    val available: Boolean =
        hash == null || resident != null || local?.isFile == true || origin?.isFile == true

    val key: String = when {
        hash == null -> resident.orEmpty().let { if (it.startsWith(IMAGE_KEY) || it.startsWith(IMAGE_TEXT_KEY)) IMAGE_TEXT_KEY + it else it }
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

        private const val IMAGE_KEY = "I\t"
        private const val IMAGE_TEXT_KEY = " I:\t"
        private const val BIG_KEY = "B\t"
        private const val LOST_KEY = " B?\t"
        private const val LOST_MARK = "⚠ "
        private const val SIDECAR_HASH_CHARS = 64

        internal fun isSidecarHash(s: String): Boolean =
            s.length == SIDECAR_HASH_CHARS && s.all { it in '0'..'9' || it in 'a'..'f' }

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

enum class PhraseEdit { ADD, TEXT, CATEGORY, LIST }

class PhraseChange(val edit: PhraseEdit, val count: Int, val requested: Int, val saved: Boolean)

enum class CategoryRemoval { REMOVED, LAST_CATEGORY, NOT_FOUND, WRITE_BLOCKED }

class ClipboardStore(private val dir: File) {

    private val histFile get() = File(dir, "clipboard.txt")
    private val phraseFile get() = File(dir, "phrases.txt")
    private fun clipsDir() = File(dir, "clips")

    private val tmpTag = TMP_TAGS.incrementAndGet()

    internal fun tempFileFor(dest: File): File = AtomicFileSwap.stagingFor(dest, tmpTag)

    private val history = ArrayList<ClipEntry>()

    private var writer: Thread? = null
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "aegis-clip-io").apply { isDaemon = true }.also { writer = it }
    }
    private val captureOrders = AtomicLong(0)
    private val saveGen = AtomicLong(0)
    private val phrasesPending = AtomicLong(0)

    private class Phrase(var text: String, var note: String = "")
    private class Category(var name: String, val phrases: ArrayList<Phrase> = ArrayList())
    private val phraseCats = ArrayList<Category>()
    private var phraseRevision = 0L

    private class LoadedPhrases(val categories: ArrayList<Category>, val readable: Boolean)
    private class PhraseWrite(val text: String, val revision: Long)

    @Volatile
    private var historyWriteFailed = false

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

    private fun reportClipWrite(landed: Boolean) {
        if (clipReport == null) return
        clipReportLane.execute { clipReport?.invoke(landed) }
    }

    private fun clipWritesAllowed(): Boolean = historyReadable && !LiveUserData.restoreInProgress

    fun load() {
        val reload = Runnable { adoptHistory(readHistory()) }
        val queued = if (Thread.currentThread() === writer) null else runCatching { io.submit(reload) }.getOrNull()
        if (queued == null) reload.run() else runCatching { queued.get() }
        loadPhrases()
    }

    private class LoadedHistory(val entries: List<ClipEntry>, val readable: Boolean)

    private fun readHistory(): LoadedHistory {
        purgeLegacyImageDir()
        val entries = ArrayList<ClipEntry>()
        val seen = HashSet<String>()
        val readable = runCatching {
            if (histFile.exists()) Files.readAllLines(histFile.toPath()).forEach { line ->
                readEntry(line)?.let { e ->
                    if (e.key.isNotBlank() && !isLegacyImageEntry(e.key) && seen.add(e.key)) entries.add(e)
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

    private fun purgeLegacyImageDir() { runCatching { File(dir, "clipboard_images").deleteRecursively() } }

    private fun readEntry(line: String): ClipEntry? {
        if (line.startsWith(BIG_LINE)) {
            val hash = line.substring(BIG_LINE.length)
            if (ClipEntry.isSidecarHash(hash)) return ClipEntry.stored(clipsDir(), hash)
        }
        return decode(line)?.let(ClipEntry::of)
    }

    private fun loadPhrases() {
        val loaded = readPhrases()
        synchronized(phraseCats) { adoptPhrases(loaded) }
    }

    private fun readPhrases(): LoadedPhrases {
        if (!phraseFile.exists()) {
            val defaults = Category(DEFAULT_CATEGORY_ID, ArrayList(DEFAULT_PHRASES.map { Phrase(it) }))
            return LoadedPhrases(arrayListOf(defaults), true)
        }
        val read = runCatching { Files.readAllLines(phraseFile.toPath()) }
        val lines = read.getOrDefault(emptyList())
        val categories = ArrayList<Category>()
        if (lines.none { it.startsWith("C\t") }) {
            val c = Category(DEFAULT_CATEGORY_ID)
            lines.forEach { decode(it)?.let { p -> if (p.isNotBlank()) c.phrases.add(Phrase(p)) } }
            categories.add(c)
        } else {
            categories.addAll(canonicalCategories(parseCategories(lines)))
            if (categories.isEmpty()) categories.add(Category(DEFAULT_CATEGORY_ID))
        }
        return LoadedPhrases(categories, read.isSuccess)
    }

    private fun adoptPhrases(loaded: LoadedPhrases) {
        phraseCats.clear()
        phraseCats.addAll(loaded.categories)
        phrasesReadable = loaded.readable
        phraseEdited()
    }

    private fun phraseEdited() {
        phraseRevision++
    }

    private fun phraseSnapshot(): PhraseWrite {
        phraseEdited()
        return PhraseWrite(serialize(phraseCats), phraseRevision)
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
        val indexes = HashMap<String, HashMap<String, Phrase>>()
        for (source in categories) {
            if (source.name.isBlank()) continue
            val dest = byName[source.name] ?: Category(source.name).also {
                byName[source.name] = it
                out.add(it)
            }
            val index = indexes.getOrPut(source.name) { HashMap() }
            for (p in source.phrases) mergePhraseInto(dest, p, index)
        }
        return out
    }

    fun record(text: String?) {
        if (text.isNullOrBlank()) return
        if (LiveUserData.restoreInProgress || !historyReadable) return
        val entry = adopt(text).apply { captureOrder = captureOrders.incrementAndGet() }
        val pending = synchronized(history) {
            val previous = history.firstOrNull()
            history.remove(entry)
            val at = history.indexOfFirst { it.captureOrder <= entry.captureOrder }.let { if (it < 0) history.size else it }
            if (at == 0 && previous != null && previous == entry && !historyWriteFailed &&
                (previous.hash == null || previous.pendingBody() != null || previous.importSource() != null)) {
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
        try {
            onWriteLaneNow { writeHistory(snapshot) }
        } catch (failure: Throwable) {
            historyWriteFailed = true
            throw failure
        }
        historyWriteFailed = false
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
        val body = entry.body().orEmpty()
        return if (body.isBlank()) null else adopt(body)
    }

    fun delete(text: String) = deleteAll(listOf(text))

    fun deleteAll(texts: Collection<String>): Boolean {
        if (!clipWritesAllowed()) { reportClipWrite(false); return false }
        val keys = texts.toSet()
        if (!synchronized(history) { history.removeAll { it.key in keys } }) return true
        saveHistoryLater()
        return true
    }

    fun latestEntry(): ClipEntry? = synchronized(history) { history.firstOrNull() }

    fun editClip(key: String, newText: String): Boolean {
        if (!clipWritesAllowed()) { reportClipWrite(false); return false }
        if (newText.isBlank()) { reportClipWrite(false); return false }
        val replacement = adopt(newText)
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
        if (!clipWritesAllowed()) { reportClipWrite(false); return false }
        if (!synchronized(history) { history.isNotEmpty().also { history.clear() } }) return true
        saveHistoryLater()
        return true
    }

    fun history(): List<ClipEntry> = snapshot()

    internal fun latest(): String? = synchronized(history) { history.firstOrNull() }?.body()

    internal fun residentBodyChars(): Long = snapshot().sumOf { it.residentChars().toLong() }

    private fun snapshot(): List<ClipEntry> = synchronized(history) { ArrayList(history) }

    fun categories(): List<String> = synchronized(phraseCats) { phraseCats.map { it.name } }

    fun phrasesIn(category: String): List<String> =
        synchronized(phraseCats) { find(category)?.phrases?.map { it.text } ?: emptyList() }

    fun phrases(): List<String> =
        synchronized(phraseCats) { phraseCats.flatMap { c -> c.phrases.map { it.text } } }

    fun addCategory(name: String): Boolean {
        if (!phraseWritesAllowed()) { refusePhraseWrite(PhraseEdit.CATEGORY, 1); return false }
        val after = synchronized(phraseCats) {
            val n = categoryName(name)
            if (n.isBlank() || phraseCats.any { it.name == n }) return false
            phraseCats.add(Category(n))
            phraseSnapshot()
        }
        writePhrases(PhraseEdit.CATEGORY, 1, 1, after)
        return true
    }

    fun deleteCategory(name: String): CategoryRemoval {
        if (!phraseWritesAllowed()) { refusePhraseWrite(PhraseEdit.LIST, 1); return CategoryRemoval.WRITE_BLOCKED }
        val after = synchronized(phraseCats) {
            if (phraseCats.none { it.name == name }) return CategoryRemoval.NOT_FOUND
            if (phraseCats.all { it.name == name }) return CategoryRemoval.LAST_CATEGORY
            phraseCats.removeAll { it.name == name }
            phraseSnapshot()
        }
        writePhrases(PhraseEdit.LIST, 1, 1, after)
        return CategoryRemoval.REMOVED
    }

    fun renameCategory(old: String, new: String): Boolean {
        if (!phraseWritesAllowed()) { refusePhraseWrite(PhraseEdit.TEXT, 1); return false }
        val after = synchronized(phraseCats) {
            val n = categoryName(new)
            val c = find(old) ?: return false
            if (n.isBlank() || (n != old && phraseCats.any { it.name == n })) return false
            c.name = n
            phraseSnapshot()
        }
        writePhrases(PhraseEdit.TEXT, 1, 1, after)
        return true
    }

    fun addPhrasesTo(category: String, texts: Collection<String>): Int {
        val requested = texts.size
        val name = sanitizePhraseText(category)
        if (name.isBlank()) return 0
        if (!phraseWritesAllowed()) { refusePhraseWrite(PhraseEdit.ADD, requested); return 0 }
        var added = 0
        val after = synchronized(phraseCats) {
            val c = find(category) ?: Category(name).also { phraseCats.add(it); phraseEdited() }
            val seen = c.phrases.mapTo(HashSet()) { sanitizePhraseText(it.text) }
            val fresh = ArrayList<Phrase>()
            for (raw in texts) {
                val t = sanitizePhraseText(raw)
                if (t.isBlank() || !seen.add(t)) continue
                fresh.add(Phrase(t))
            }
            if (fresh.isEmpty()) null
            else {
                c.phrases.addAll(0, fresh)
                added = fresh.size
                phraseSnapshot()
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
            phraseSnapshot()
        }
        writePhrases(PhraseEdit.LIST, victims.size, texts.size, after)
        return true
    }

    fun clearPhrasesIn(category: String): Int {
        if (!phraseWritesAllowed()) { refusePhraseWrite(PhraseEdit.LIST, 0); return 0 }
        var cleared = 0
        val after = synchronized(phraseCats) {
            val c = find(category) ?: return 0
            if (c.phrases.isEmpty()) return 0
            cleared = c.phrases.size
            c.phrases.clear()
            phraseSnapshot()
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
            if (n.isBlank()) return false
            if (c.phrases.withIndex().any { (j, p) -> j != idx && sanitizePhraseText(p.text) == n }) return false
            c.phrases[idx].text = n
            phraseSnapshot()
        }
        writePhrases(PhraseEdit.TEXT, 1, 1, after)
        return true
    }

    private fun mergePhraseInto(to: Category, p: Phrase, index: HashMap<String, Phrase>?) {
        if (p.text.isBlank()) return
        val existing = if (index == null) findPhrase(to, p.text) else index[p.text]
        if (existing == null) Phrase(p.text, p.note).also { to.phrases.add(it); index?.put(it.text, it) }
        else if (existing.note.isEmpty() && p.note.isNotEmpty()) existing.note = p.note
    }

    fun reorderCategory(fromIndex: Int, toIndex: Int): Boolean {
        if (!phraseWritesAllowed()) { refusePhraseWrite(PhraseEdit.LIST, 1); return false }
        val after = synchronized(phraseCats) {
            val n = phraseCats.size
            if (fromIndex !in 0 until n || toIndex !in 0 until n || fromIndex == toIndex) return false
            phraseCats.add(toIndex, phraseCats.removeAt(fromIndex))
            phraseSnapshot()
        }
        writePhrases(PhraseEdit.LIST, 1, 1, after)
        return true
    }

    private fun find(name: String): Category? =
        phraseCats.firstOrNull { it.name == name }
            ?: sanitizePhraseText(name).let { n -> phraseCats.firstOrNull { sanitizePhraseText(it.name) == n } }
    private fun findPhrase(c: Category?, text: String): Phrase? = c?.phrases?.firstOrNull { it.text == text }

    private class PendingWrite(val gen: Long, val rows: List<ClipEntry>)

    private fun stampPendingWrite(): PendingWrite =
        synchronized(history) { PendingWrite(saveGen.incrementAndGet(), ArrayList(history)) }

    private fun saveHistoryLater() {
        val pending = stampPendingWrite()
        val queued = runCatching {
            io.execute {
                val landed = pending.gen != saveGen.get() ||
                    runCatching { writeHistory(pending.rows) }.isSuccess.also { historyWriteFailed = !it }
                reportClipWrite(landed)
            }
        }.isSuccess
        if (!queued) {
            historyWriteFailed = true
            reportClipWrite(false)
        }
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

    private fun writePhrases(edit: PhraseEdit, count: Int, requested: Int, write: PhraseWrite) {
        phrasesPending.incrementAndGet()
        val queued = runCatching {
            io.execute {
                val landed = runCatching { atomicWrite(phraseFile, write.text) }.isSuccess
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

        fun sanitizePhraseText(s: String): String =
            s.filterNot { it != '\n' && it != '\r' && it != '\t' && Character.isISOControl(it) }

        fun categoryName(s: String): String = foldLineBreaks(sanitizePhraseText(s))

        fun foldLineBreaks(s: String): String = s.trim { it in LINE_BREAKS }.replace(LINE_BREAK_RUN, " ")

        private const val LINE_BREAKS = "\n\r\u2028\u2029"
        private val LINE_BREAK_RUN = Regex("[$LINE_BREAKS]+")

        private const val BIG_LINE = "B\t"
        const val BIG_THRESHOLD = 64 * 1024

        const val DEFAULT_CATEGORY_ID = "default"
        private const val LEGACY_DEFAULT_NAME = "默认"
        private val DEFAULT_PHRASES = emptyList<String>()
    }
}
