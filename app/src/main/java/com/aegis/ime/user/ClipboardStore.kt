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
import java.security.MessageDigest
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

class ClipboardStore(private val dir: File) {

    private val histFile get() = File(dir, "clipboard.txt")
    private val phraseFile get() = File(dir, "phrases.txt")
    private fun clipsDir() = File(dir, "clips")

    private val history = ArrayList<ClipEntry>()

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "aegis-clip-io").apply { isDaemon = true } }
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
                if (histFile.exists()) histFile.readLines().forEach { line ->
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
        phraseCats.clear()
        if (!phraseFile.exists()) {
            phrasesReadable = true
            phraseCats.add(Category(DEFAULT_CATEGORY_ID, ArrayList(DEFAULT_PHRASES.map { Phrase(it) })))
            return
        }
        val read = runCatching { phraseFile.readLines() }
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
        val gen = saveGen.incrementAndGet()
        val queued = runCatching { io.execute { recordHere(t, gen) } }.isSuccess
        if (!queued) recordHere(t, gen)
    }

    private fun recordHere(text: String, gen: Long) {
        val entry = adopt(text)
        val snapshot = synchronized(history) {
            history.remove(entry)
            history.add(0, entry)
            ArrayList(history)
        }
        if (gen != saveGen.get() || !historyReadable) return
        runCatching { writeHistory(snapshot) }
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
        writeHistory(snapshot)
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

    fun deleteAll(texts: Collection<String>) {
        flushPendingWrites()
        val keys = texts.toSet()
        if (synchronized(history) { history.removeAll { it.key in keys } }) scheduleSave()
    }

    fun clearHistory() {
        flushPendingWrites()
        val emptied = synchronized(history) { history.isNotEmpty().also { history.clear() } }
        if (emptied) scheduleSave()
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


    fun reloadPhrases() = loadPhrases()

    fun categories(): List<String> = phraseCats.map { it.name }

    fun phrasesIn(category: String): List<String> = find(category)?.phrases?.map { it.text } ?: emptyList()

    fun phrases(): List<String> = phraseCats.flatMap { c -> c.phrases.map { it.text } }

    fun noteFor(category: String, text: String): String = findPhrase(find(category), text)?.note.orEmpty()

    fun setPhraseNote(category: String, text: String, note: String): Boolean {
        val p = findPhrase(find(category), text) ?: return false
        p.note = note.filterNot { Character.isISOControl(it) }.trim()
        savePhrases()
        return true
    }

    fun addCategory(name: String): Boolean {
        val n = name.trim()
        if (n.isEmpty() || phraseCats.any { it.name == n }) return false
        phraseCats.add(Category(n)); savePhrases(); return true
    }

    fun deleteCategory(name: String) { if (phraseCats.removeAll { it.name == name }) savePhrases() }

    fun renameCategory(old: String, new: String): Boolean {
        val n = new.trim()
        val c = find(old) ?: return false
        if (n.isEmpty() || (n != old && phraseCats.any { it.name == n })) return false
        c.name = n; savePhrases(); return true
    }

    fun addPhrasesTo(category: String, texts: Collection<String>): Int {
        if (category.isBlank()) return 0
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

    fun addPhrases(texts: Collection<String>): Int =
        addPhrasesTo(phraseCats.firstOrNull()?.name ?: DEFAULT_CATEGORY_ID, texts)

    fun deletePhraseFrom(category: String, text: String) {
        find(category)?.let { c -> if (c.phrases.removeAll { it.text == text }) savePhrases() }
    }

    fun deletePhrase(text: String) {
        var changed = false
        for (c in phraseCats) if (c.phrases.removeAll { it.text == text }) changed = true
        if (changed) savePhrases()
    }

    fun clearPhrasesIn(category: String): Int {
        val c = find(category) ?: return 0
        val n = c.phrases.size
        if (n > 0) { c.phrases.clear(); savePhrases() }
        return n
    }

    fun editPhrase(category: String, oldText: String, newText: String): Boolean {
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

    fun movePhrase(fromCategory: String, text: String, toCategory: String): Boolean {
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

    fun movePhrasesTo(fromCategory: String, texts: Collection<String>, toCategory: String): Int {
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

    fun reorderPhrase(category: String, fromIndex: Int, toIndex: Int): Boolean {
        val c = find(category) ?: return false
        val n = c.phrases.size
        if (fromIndex !in 0 until n || toIndex !in 0 until n || fromIndex == toIndex) return false
        c.phrases.add(toIndex, c.phrases.removeAt(fromIndex))
        savePhrases()
        return true
    }

    fun reorderCategory(fromIndex: Int, toIndex: Int): Boolean {
        val n = phraseCats.size
        if (fromIndex !in 0 until n || toIndex !in 0 until n || fromIndex == toIndex) return false
        phraseCats.add(toIndex, phraseCats.removeAt(fromIndex))
        savePhrases()
        return true
    }

    private fun find(name: String): Category? = phraseCats.firstOrNull { it.name == name }
    private fun findPhrase(c: Category?, text: String): Phrase? = c?.phrases?.firstOrNull { it.text == text }

    private fun scheduleSave() {
        if (!historyReadable) return
        val snapshot = snapshot()
        val gen = saveGen.incrementAndGet()
        runCatching { io.execute { if (gen == saveGen.get()) runCatching { writeHistory(snapshot) } } }
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

    private fun atomicWrite(dest: File, text: String) {
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        tmp.writeText(text)
        swapInto(tmp, dest)
    }

    private fun atomicCopy(source: File, dest: File) {
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        source.copyTo(tmp, overwrite = true)
        swapInto(tmp, dest)
    }

    private fun swapInto(tmp: File, dest: File) {
        if (!tmp.renameTo(dest)) {
            dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.delete()
                throw IOException("atomic write swap failed")
            }
        }
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    internal fun flushPendingWrites() { runCatching { io.submit { }.get() } }

    private fun savePhrases() { runCatching { savePhrasesOrThrow() } }

    private fun savePhrasesOrThrow() { atomicWrite(phraseFile, serializePhrases()) }

    private fun serializePhrases(): String {
        val sb = StringBuilder()
        for (c in phraseCats) {
            sb.append("C\t").append(encode(c.name)).append('\n')
            for (p in c.phrases) {
                sb.append("P\t").append(encode(p.text)).append('\n')
                if (p.note.isNotEmpty()) sb.append("N\t").append(encode(p.note)).append('\n')
            }
        }
        return sb.toString()
    }


    fun exportPhrasesText(): String = serializePhrases()

    fun importPhrasesText(text: String, merge: Boolean): Boolean {
        val parsed = canonicalCategories(parseCategories(text.lineSequence().toList()))
        if (parsed.isEmpty()) return false
        if (merge) {
            val normalized = canonicalCategories(phraseCats)
            phraseCats.clear()
            phraseCats.addAll(normalized)
            for (pc in parsed) {
                val c = find(pc.name) ?: Category(pc.name).also { phraseCats.add(it) }
                for (p in pc.phrases) mergePhraseInto(c, p)
            }
        } else {
            phraseCats.clear()
            phraseCats.addAll(parsed)
            if (phraseCats.none { it.name == DEFAULT_CATEGORY_ID }) phraseCats.add(0, Category(DEFAULT_CATEGORY_ID))
        }
        savePhrasesOrThrow()
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
