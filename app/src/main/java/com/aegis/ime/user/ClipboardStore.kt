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
    private val saveGen = AtomicLong(0)

    private class Phrase(var text: String, var note: String = "")
    private class Category(var name: String, val phrases: ArrayList<Phrase> = ArrayList())
    private val phraseCats = ArrayList<Category>()

    @Volatile
    var historyReadable: Boolean = true
        private set

    @Volatile
    var phrasesReadable: Boolean = true
        private set

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
        onWriteLane { recordHere(t) }
    }

    private fun recordHere(text: String) {
        if (LiveUserData.restoreInProgress || !historyReadable) return
        val entry = adopt(text)
        val pending = synchronized(history) {
            history.remove(entry)
            history.add(0, entry)
            PendingWrite(saveGen.incrementAndGet(), ArrayList(history))
        }
        if (pending.gen != saveGen.get() || !historyReadable) return
        runCatching { writeHistory(pending.rows) }
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
                historyReadable = true
            }
            ArrayList(history)
        }
        onWriteLaneNow { writeHistory(snapshot) }
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
        flushPendingWrites()
        if (!historyReadable) return false
        val keys = texts.toSet()
        if (!synchronized(history) { history.removeAll { it.key in keys } }) return true
        return saveHistoryNow()
    }

    fun clearHistory(): Boolean {
        flushPendingWrites()
        if (!historyReadable) return false
        if (!synchronized(history) { history.isNotEmpty().also { history.clear() } }) return true
        return saveHistoryNow()
    }

    fun history(): List<ClipEntry> {
        flushPendingWrites()
        return snapshot()
    }

    internal fun latest(): String? {
        flushPendingWrites()
        return synchronized(history) { history.firstOrNull() }?.body()
    }

    internal fun residentBodyChars(): Long {
        flushPendingWrites()
        return snapshot().sumOf { it.residentChars().toLong() }
    }

    private fun snapshot(): List<ClipEntry> = synchronized(history) { ArrayList(history) }


    fun reloadPhrases() {
        flushPendingWrites()
        loadPhrases()
    }

    fun categories(): List<String> = synchronized(phraseCats) { phraseCats.map { it.name } }

    fun phrasesIn(category: String): List<String> =
        synchronized(phraseCats) { find(category)?.phrases?.map { it.text } ?: emptyList() }

    fun phrases(): List<String> =
        synchronized(phraseCats) { phraseCats.flatMap { c -> c.phrases.map { it.text } } }

    fun noteFor(category: String, text: String): String =
        synchronized(phraseCats) { findPhrase(find(category), text)?.note.orEmpty() }

    fun setPhraseNote(category: String, text: String, note: String): Boolean = synchronized(phraseCats) {
        if (!phrasesReadable) return false
        val p = findPhrase(find(category), text) ?: return false
        p.note = note.filterNot { Character.isISOControl(it) }.trim()
        savePhrases()
        return true
    }

    fun addCategory(name: String): Boolean = synchronized(phraseCats) {
        if (!phrasesReadable) return false
        val n = name.trim()
        if (n.isEmpty() || phraseCats.any { it.name == n }) return false
        phraseCats.add(Category(n)); savePhrases(); return true
    }

    fun deleteCategory(name: String) {
        synchronized(phraseCats) {
            if (!phrasesReadable) return
            if (phraseCats.removeAll { it.name == name }) savePhrases()
        }
    }

    fun renameCategory(old: String, new: String): Boolean = synchronized(phraseCats) {
        if (!phrasesReadable) return false
        val n = new.trim()
        val c = find(old) ?: return false
        if (n.isEmpty() || (n != old && phraseCats.any { it.name == n })) return false
        c.name = n; savePhrases(); return true
    }

    fun addPhrasesTo(category: String, texts: Collection<String>): Int = synchronized(phraseCats) {
        if (category.isBlank() || !phrasesReadable) return 0
        val c = find(category) ?: Category(category).also { phraseCats.add(it) }
        val seen = c.phrases.mapTo(HashSet()) { it.text }
        val added = ArrayList<Phrase>()
        for (raw in texts) {
            val t = raw.trim()
            if (t.isEmpty() || !seen.add(t)) continue
            added.add(Phrase(t))
        }
        if (added.isNotEmpty()) {
            c.phrases.addAll(0, added)
            savePhrases()
        }
        return added.size
    }

    fun addPhrases(texts: Collection<String>): Int = synchronized(phraseCats) {
        addPhrasesTo(phraseCats.firstOrNull()?.name ?: DEFAULT_CATEGORY_ID, texts)
    }

    fun deletePhraseFrom(category: String, text: String): Boolean = deletePhrasesFrom(category, listOf(text))

    fun deletePhrasesFrom(category: String, texts: Collection<String>): Boolean {
        val victims = texts.toSet()
        val after = synchronized(phraseCats) {
            if (!phrasesReadable) return false
            val c = find(category) ?: return true
            if (!c.phrases.removeAll { it.text in victims }) return true
            serialize(phraseCats)
        }
        return savePhraseText(after)
    }

    fun deletePhrase(text: String) {
        synchronized(phraseCats) {
            if (!phrasesReadable) return
            var changed = false
            for (c in phraseCats) if (c.phrases.removeAll { it.text == text }) changed = true
            if (changed) savePhrases()
        }
    }

    fun clearPhrasesIn(category: String): Int = synchronized(phraseCats) {
        if (!phrasesReadable) return 0
        val c = find(category) ?: return 0
        val n = c.phrases.size
        if (n > 0) { c.phrases.clear(); savePhrases() }
        return n
    }

    fun editPhrase(category: String, oldText: String, newText: String): Boolean = synchronized(phraseCats) {
        if (!phrasesReadable) return false
        val c = find(category) ?: return false
        val idx = c.phrases.indexOfFirst { it.text == oldText }
        if (idx < 0) return false
        val n = newText.filterNot { Character.isISOControl(it) }.trim()
        if (n.isEmpty()) return false
        if (c.phrases.withIndex().any { (j, p) -> j != idx && p.text == n }) return false
        c.phrases[idx].text = n
        savePhrases()
        return true
    }

    fun movePhrase(fromCategory: String, text: String, toCategory: String): Boolean = synchronized(phraseCats) {
        if (!phrasesReadable) return false
        val to = find(toCategory) ?: return false
        val from = find(fromCategory) ?: return false
        if (from === to) return true
        val p = findPhrase(from, text) ?: return false
        from.phrases.remove(p)
        carryInto(to, p)
        savePhrases()
        return true
    }

    private fun carryInto(to: Category, p: Phrase) = mergePhraseInto(to, p)

    private fun mergePhraseInto(to: Category, p: Phrase) {
        if (p.text.isBlank()) return
        val existing = findPhrase(to, p.text)
        if (existing == null) to.phrases.add(Phrase(p.text, p.note))
        else if (existing.note.isEmpty() && p.note.isNotEmpty()) existing.note = p.note
    }

    fun movePhrasesTo(fromCategory: String, texts: Collection<String>, toCategory: String): Int =
        synchronized(phraseCats) {
            if (!phrasesReadable) return 0
            val to = find(toCategory) ?: return 0
            val from = find(fromCategory) ?: return 0
            if (from === to) return 0
            var moved = 0
            for (t in texts) {
                val p = findPhrase(from, t) ?: continue
                from.phrases.remove(p)
                carryInto(to, p)
                moved++
            }
            if (moved > 0) savePhrases()
            return moved
        }

    fun reorderPhrase(category: String, fromIndex: Int, toIndex: Int): Boolean = synchronized(phraseCats) {
        if (!phrasesReadable) return false
        val c = find(category) ?: return false
        val n = c.phrases.size
        if (fromIndex !in 0 until n || toIndex !in 0 until n || fromIndex == toIndex) return false
        c.phrases.add(toIndex, c.phrases.removeAt(fromIndex))
        savePhrases()
        return true
    }

    fun reorderCategory(fromIndex: Int, toIndex: Int): Boolean = synchronized(phraseCats) {
        if (!phrasesReadable) return false
        val n = phraseCats.size
        if (fromIndex !in 0 until n || toIndex !in 0 until n || fromIndex == toIndex) return false
        phraseCats.add(toIndex, phraseCats.removeAt(fromIndex))
        savePhrases()
        return true
    }

    private fun find(name: String): Category? = phraseCats.firstOrNull { it.name == name }
    private fun findPhrase(c: Category?, text: String): Phrase? = c?.phrases?.firstOrNull { it.text == text }

    private class PendingWrite(val gen: Long, val rows: List<ClipEntry>)

    private fun stampPendingWrite(): PendingWrite =
        synchronized(history) { PendingWrite(saveGen.incrementAndGet(), ArrayList(history)) }

    private fun saveHistoryNow(): Boolean {
        if (!historyReadable || LiveUserData.restoreInProgress) return false
        val pending = stampPendingWrite()
        return onWriteLaneReporting { if (pending.gen == saveGen.get()) writeHistory(pending.rows) }
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

    fun stopSaving() { runCatching { io.shutdown() } }

    private fun onWriteLane(work: () -> Unit) {
        val queued = runCatching { io.execute(work) }.isSuccess
        if (!queued) work()
    }

    private fun onWriteLaneReporting(work: () -> Unit): Boolean {
        if (Thread.currentThread() === writer) return runCatching(work).isSuccess
        val pending = runCatching { io.submit(work) }.getOrNull() ?: return false
        return try {
            pending.get()
            true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (_: ExecutionException) {
            false
        }
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

    private fun savePhrases() {
        if (LiveUserData.restoreInProgress || !phrasesReadable) return
        val text = serializePhrases()
        onWriteLane { runCatching { atomicWrite(phraseFile, text) } }
    }

    private fun savePhraseText(text: String): Boolean {
        if (LiveUserData.restoreInProgress || !phrasesReadable) return false
        return onWriteLaneReporting { atomicWrite(phraseFile, text) }
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

        private const val BIG_LINE = "B\t"
        const val BIG_THRESHOLD = 64 * 1024

        const val DEFAULT_CATEGORY_ID = "default"
        private const val LEGACY_DEFAULT_NAME = "默认"
        private val DEFAULT_PHRASES = emptyList<String>()
    }
}
