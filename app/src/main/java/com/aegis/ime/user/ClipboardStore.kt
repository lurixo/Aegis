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

class ClipboardStore private constructor(
    private val dir: File,
    private val database: UserDataDatabase?,
    @Suppress("UNUSED_PARAMETER") marker: Unit,
) {

    constructor(dir: File) : this(dir, null, Unit)

    internal constructor(dir: File, database: UserDataDatabase) : this(dir, database, Unit)

    private val histFile get() = File(dir, "clipboard.txt")
    private val phraseFile get() = File(dir, "phrases.txt")
    private fun clipsDir() = File(dir, "clips")

    private val history = ArrayList<String>()

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "aegis-clip-io").apply { isDaemon = true } }
    private val saveGen = AtomicLong(0)

    private class Phrase(var text: String, var note: String = "")
    private class Category(var name: String, val phrases: ArrayList<Phrase> = ArrayList())
    private val phraseCats = ArrayList<Category>()
    private val phrasePageCache = BoundedLruCache<String, List<String>>(RUNTIME_CACHE_CATEGORIES)
    private var lastValidPhrases = ArrayList<Category>()

    @Volatile
    var lastFailure: String? = null
        private set

    fun load(purgeLegacyImages: Boolean = true): Boolean {
        database?.let { backing ->
            return runCatching {
                if (purgeLegacyImages) purgeLegacyImageDir()
                backing.ensurePhraseCategory(DEFAULT_CATEGORY_ID)
                history.clear()
                history.addAll(backing.readClipboardHistory(0, RUNTIME_PAGE_SIZE))
                phraseCats.clear()
                phraseCats.addAll(
                    backing.readPhraseCategoryNames(0, RUNTIME_PAGE_SIZE).map { Category(it) },
                )
                if (phraseCats.isEmpty()) phraseCats.add(Category(DEFAULT_CATEGORY_ID))
                phrasePageCache.clear()
                lastValidPhrases.clear()
                lastFailure = null
                true
            }.onFailure { lastFailure = failureText(it) }.getOrDefault(false)
        }
        val previousHistory = ArrayList(history)
        val previousPhrases = copyCategories(phraseCats)
        return runCatching {
            if (purgeLegacyImages) purgeLegacyImageDir()
            val seen = HashSet<String>()
            val loadedHistory = if (histFile.exists()) {
                histFile.readLines().mapNotNull { line ->
                    readEntry(line)?.takeIf { entry ->
                        entry.isNotBlank() && !isLegacyImageEntry(entry) && seen.add(entry)
                    }
                }
            } else {
                emptyList()
            }
            val loadedPhrases = readLegacyPhrases()
            if (loadedPhrases.isEmpty()) loadedPhrases.add(Category(DEFAULT_CATEGORY_ID))
            history.clear()
            history.addAll(loadedHistory)
            phraseCats.clear()
            phraseCats.addAll(loadedPhrases)
            lastValidPhrases = copyCategories(phraseCats)
            lastFailure = null
            true
        }.onFailure {
            history.clear()
            history.addAll(previousHistory)
            phraseCats.clear()
            phraseCats.addAll(previousPhrases)
            lastFailure = failureText(it)
        }.getOrDefault(false)
    }

    private fun purgeLegacyImageDir() { runCatching { File(dir, "clipboard_images").deleteRecursively() } }

    private fun readEntry(line: String): String? =
        if (line.startsWith(BIG_LINE)) {
            File(clipsDir(), line.substring(BIG_LINE.length) + ".txt").takeIf { it.isFile }
                ?.let { runCatching { it.readText() }.getOrNull() } ?: decode(line)
        } else {
            decode(line)
        }

    private fun loadPhrases() {
        database?.let {
            it.ensurePhraseCategory(DEFAULT_CATEGORY_ID)
            phraseCats.clear()
            lastValidPhrases.clear()
            return
        }
        val loaded = readLegacyPhrases()
        if (loaded.isEmpty()) loaded.add(Category(DEFAULT_CATEGORY_ID))
        phraseCats.clear()
        phraseCats.addAll(loaded)
        lastValidPhrases = copyCategories(phraseCats)
    }

    private fun readLegacyPhrases(): ArrayList<Category> {
        if (!phraseFile.exists()) {
            return arrayListOf(Category(DEFAULT_CATEGORY_ID, ArrayList(DEFAULT_PHRASES.map { Phrase(it) })))
        }
        val lines = phraseFile.readLines()
        if (lines.none { it.startsWith("C\t") }) {
            val c = Category(DEFAULT_CATEGORY_ID)
            lines.forEach { decode(it)?.let { p -> if (p.isNotBlank()) c.phrases.add(Phrase(p)) } }
            return arrayListOf(c)
        }
        return canonicalCategories(parseCategories(lines)).also { categories ->
            if (categories.isEmpty()) categories.add(Category(DEFAULT_CATEGORY_ID))
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

    fun record(text: String?): Boolean {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return false
        val backing = database
        if (backing != null) {
            return runCatching {
                backing.recordClipboard(t)
                history.remove(t)
                history.add(0, t)
                while (history.size > RUNTIME_PAGE_SIZE) history.removeAt(history.lastIndex)
                lastFailure = null
                true
            }.onFailure { lastFailure = failureText(it) }.getOrDefault(false)
        }
        history.remove(t)
        history.add(0, t)
        scheduleSave()
        return true
    }

    fun importHistory(entries: List<String>, merge: Boolean): Boolean {
        val incoming = ArrayList<String>()
        val incomingSeen = HashSet<String>()
        for (entry in entries) {
            val value = entry.trim()
            if (value.isNotEmpty() && incomingSeen.add(value)) incoming.add(value)
        }
        val candidate = if (merge) ArrayList(history) else ArrayList()
        val present = candidate.toHashSet()
        for (entry in incoming) if (present.add(entry)) candidate.add(entry)
        val backing = database
        if (backing != null) {
            return runCatching {
                backing.replaceClipboardHistory(incoming, merge)
                history.clear()
                history.addAll(candidate.take(RUNTIME_PAGE_SIZE))
                lastFailure = null
                true
            }.onFailure { lastFailure = failureText(it) }.getOrDefault(false)
        }
        if (merge) {
            history.clear()
            history.addAll(candidate)
        } else {
            history.clear()
            history.addAll(candidate)
        }
        return runCatching {
            writeHistory(ArrayList(history))
            lastFailure = null
            true
        }.onFailure { lastFailure = failureText(it) }.getOrDefault(false)
    }

    fun delete(text: String): Boolean = deleteAll(listOf(text))

    fun deleteAll(texts: Collection<String>): Boolean {
        val targets = texts.toSet()
        val backing = database
        if (backing != null) {
            if (targets.none(backing::containsClipboard)) return false
            return runCatching {
                backing.deleteClipboardHistory(targets)
                history.removeAll(targets)
                lastFailure = null
                true
            }.onFailure { lastFailure = failureText(it) }.getOrDefault(false)
        }
        if (targets.none(history::contains)) return false
        history.removeAll(targets)
        scheduleSave()
        return true
    }

    fun clearHistory(): Boolean {
        val backing = database
        if (backing != null) {
            if (backing.clipboardHistoryCount() == 0L) return false
            return runCatching {
                backing.clearClipboardHistory()
                history.clear()
                lastFailure = null
                true
            }.onFailure { lastFailure = failureText(it) }.getOrDefault(false)
        }
        if (history.isEmpty()) return false
        history.clear()
        scheduleSave()
        return true
    }

    fun history(): List<String> = database?.let { backing ->
        runCatching { backing.readClipboardHistory(0, RUNTIME_PAGE_SIZE) }
            .onSuccess { page -> history.clear(); history.addAll(page); lastFailure = null }
            .onFailure { lastFailure = failureText(it) }
            .getOrElse { history.toList() }
    } ?: history.toList()

    fun historyPage(offset: Int, limit: Int): List<String> {
        require(offset >= 0)
        require(limit >= 0)
        return database?.let { backing ->
            runCatching { backing.readClipboardHistory(offset, minOf(limit, RUNTIME_PAGE_SIZE)) }
                .onFailure { lastFailure = failureText(it) }
                .getOrElse { history.drop(offset).take(limit) }
        } ?: history.drop(offset).take(limit)
    }

    fun historyCount(): Long = database?.clipboardHistoryCount() ?: history.size.toLong()

    internal fun latest(): String? = database?.readClipboardHistory(limit = 1)?.firstOrNull() ?: history.firstOrNull()


    fun reloadPhrases(): Boolean = runCatching {
        loadPhrases()
        lastFailure = null
        true
    }.onFailure { lastFailure = failureText(it) }.getOrDefault(false)

    fun categories(): List<String> = database?.let { backing ->
        runCatching { backing.readPhraseCategoryNames(0, RUNTIME_PAGE_SIZE) }
            .onSuccess { names ->
                phraseCats.clear()
                phraseCats.addAll(names.map { Category(it) })
                lastFailure = null
            }
            .onFailure { lastFailure = failureText(it) }
            .getOrElse { phraseCats.map { it.name } }
    } ?: phraseCats.map { it.name }

    fun categoryPage(offset: Int, limit: Int): List<String> {
        require(offset >= 0)
        require(limit in 0..RUNTIME_PAGE_SIZE)
        return database?.let { backing ->
            runCatching { backing.readPhraseCategoryNames(offset, limit) }
                .onFailure { lastFailure = failureText(it) }
                .getOrElse { phraseCats.drop(offset).take(limit).map { it.name } }
        } ?: phraseCats.drop(offset).take(limit).map { it.name }
    }

    fun categoryCount(): Long = database?.phraseCategoryCount() ?: phraseCats.size.toLong()

    fun phrasesIn(category: String): List<String> = database?.let { backing ->
        runCatching { backing.readPhrases(category, 0, RUNTIME_PAGE_SIZE).map { it.text } }
            .onSuccess { phrasePageCache.put(category, it); lastFailure = null }
            .onFailure { lastFailure = failureText(it) }
            .getOrElse { phrasePageCache[category].orEmpty() }
    } ?: (find(category)?.phrases?.map { it.text } ?: emptyList())

    fun phrasesPage(category: String, offset: Int, limit: Int): List<String> {
        require(offset >= 0)
        require(limit >= 0)
        return database?.let { backing ->
            runCatching {
                backing.readPhrases(category, offset, minOf(limit, RUNTIME_PAGE_SIZE)).map { it.text }
            }
                .onSuccess { if (offset == 0) phrasePageCache.put(category, it) }
                .onFailure { lastFailure = failureText(it) }
                .getOrElse { phrasePageCache[category].orEmpty().drop(offset).take(limit) }
        } ?: phrasesIn(category).drop(offset).take(limit)
    }

    fun phraseCount(category: String): Long = database?.phraseCount(category) ?:
        (find(category)?.phrases?.size?.toLong() ?: 0L)

    fun phrases(): List<String> = if (database == null) phraseCats.flatMap { c -> c.phrases.map { it.text } } else {
        categories().asSequence().flatMap { phrasesIn(it).asSequence() }.take(RUNTIME_PAGE_SIZE).toList()
    }

    fun noteFor(category: String, text: String): String = database?.phraseNote(category, text) ?:
        findPhrase(find(category), text)?.note.orEmpty()

    fun setPhraseNote(category: String, text: String, note: String): Boolean {
        database?.let { backing ->
            val normalized = note.filterNot { Character.isISOControl(it) }.trim()
            return runCatching { backing.setPhraseNote(category, text, normalized) }
                .onFailure { lastFailure = failureText(it) }.getOrDefault(false)
        }
        val p = findPhrase(find(category), text) ?: return false
        p.note = note.filterNot { Character.isISOControl(it) }.trim()
        return savePhrases()
    }

    fun addCategory(name: String): Boolean {
        val n = name.trim()
        database?.let { backing ->
            if (n.isEmpty()) return false
            return runCatching { backing.addPhraseCategory(n) }
                .onFailure { lastFailure = failureText(it) }.getOrDefault(false)
        }
        if (n.isEmpty() || phraseCats.any { it.name == n }) return false
        phraseCats.add(Category(n))
        return savePhrases()
    }

    fun deleteCategory(name: String): Boolean = database?.let { backing ->
        runCatching { backing.deletePhraseCategory(name) }
            .onFailure { lastFailure = failureText(it) }.getOrDefault(false)
    } ?: if (phraseCats.removeAll { it.name == name }) savePhrases() else false

    fun renameCategory(old: String, new: String): Boolean {
        val n = new.trim()
        database?.let { backing ->
            if (n.isEmpty()) return false
            return runCatching { backing.renamePhraseCategory(old, n) }
                .onFailure { lastFailure = failureText(it) }.getOrDefault(false)
        }
        val c = find(old) ?: return false
        if (n.isEmpty() || (n != old && phraseCats.any { it.name == n })) return false
        c.name = n
        return savePhrases()
    }

    fun addPhrasesTo(category: String, texts: Collection<String>): Int {
        if (category.isBlank()) return 0
        database?.let { backing ->
            val normalized = texts.map { it.trim() }.filter { it.isNotEmpty() }
            return runCatching { backing.addPhrases(category, normalized) }
                .onSuccess { added ->
                    if (added > 0) {
                        val current = phrasePageCache[category].orEmpty()
                        phrasePageCache.put(category, (normalized + current).distinct().take(RUNTIME_PAGE_SIZE))
                    }
                }
                .onFailure { lastFailure = failureText(it) }.getOrDefault(0)
        }
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
            if (!savePhrases()) return 0
        }
        return added.size
    }

    fun addPhrases(texts: Collection<String>): Int =
        addPhrasesTo(categories().firstOrNull() ?: DEFAULT_CATEGORY_ID, texts)

    fun deletePhraseFrom(category: String, text: String): Boolean {
        database?.let { backing ->
            return runCatching { backing.deletePhrase(category, text) }
                .onFailure { lastFailure = failureText(it) }.getOrDefault(false)
        }
        val categoryData = find(category) ?: return false
        return if (categoryData.phrases.removeAll { it.text == text }) savePhrases() else false
    }

    fun deletePhrase(text: String): Boolean {
        database?.let { backing ->
            return runCatching { backing.deletePhraseEverywhere(text) }
                .onFailure { lastFailure = failureText(it) }.getOrDefault(false)
        }
        var changed = false
        for (c in phraseCats) if (c.phrases.removeAll { it.text == text }) changed = true
        return changed && savePhrases()
    }

    fun clearPhrasesIn(category: String): Int {
        database?.let { backing ->
            return runCatching { backing.clearPhrases(category) }
                .onFailure { lastFailure = failureText(it) }.getOrDefault(0)
        }
        val c = find(category) ?: return 0
        val n = c.phrases.size
        if (n > 0) {
            c.phrases.clear()
            if (!savePhrases()) return 0
        }
        return n
    }

    fun editPhrase(category: String, oldText: String, newText: String): Boolean {
        database?.let { backing ->
            val normalized = newText.filterNot { Character.isISOControl(it) }.trim()
            return runCatching { backing.editPhrase(category, oldText, normalized) }
                .onFailure { lastFailure = failureText(it) }.getOrDefault(false)
        }
        val c = find(category) ?: return false
        val idx = c.phrases.indexOfFirst { it.text == oldText }
        if (idx < 0) return false
        val n = newText.filterNot { Character.isISOControl(it) }.trim()
        if (n.isEmpty()) return false
        if (c.phrases.withIndex().any { (j, p) -> j != idx && p.text == n }) return false
        c.phrases[idx].text = n
        return savePhrases()
    }

    fun movePhrase(fromCategory: String, text: String, toCategory: String): Boolean {
        database?.let { backing ->
            if (fromCategory == toCategory) return true
            return runCatching { backing.movePhrases(fromCategory, listOf(text), toCategory) == 1 }
                .onFailure { lastFailure = failureText(it) }.getOrDefault(false)
        }
        val to = find(toCategory) ?: return false
        val from = find(fromCategory) ?: return false
        if (from === to) return true
        val p = findPhrase(from, text) ?: return false
        from.phrases.remove(p)
        carryInto(to, p)
        return savePhrases()
    }

    private fun carryInto(to: Category, p: Phrase) = mergePhraseInto(to, p)

    private fun mergePhraseInto(to: Category, p: Phrase) {
        if (p.text.isBlank()) return
        val existing = findPhrase(to, p.text)
        if (existing == null) to.phrases.add(Phrase(p.text, p.note))
        else if (existing.note.isEmpty() && p.note.isNotEmpty()) existing.note = p.note
    }

    fun movePhrasesTo(fromCategory: String, texts: Collection<String>, toCategory: String): Int {
        database?.let { backing ->
            return runCatching { backing.movePhrases(fromCategory, texts, toCategory) }
                .onFailure { lastFailure = failureText(it) }.getOrDefault(0)
        }
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
        if (moved > 0 && !savePhrases()) return 0
        return moved
    }

    fun reorderPhrase(category: String, fromIndex: Int, toIndex: Int): Boolean {
        database?.let { backing ->
            return runCatching { backing.reorderPhrase(category, fromIndex, toIndex) }
                .onFailure { lastFailure = failureText(it) }.getOrDefault(false)
        }
        val c = find(category) ?: return false
        val n = c.phrases.size
        if (fromIndex !in 0 until n || toIndex !in 0 until n || fromIndex == toIndex) return false
        c.phrases.add(toIndex, c.phrases.removeAt(fromIndex))
        return savePhrases()
    }

    fun reorderCategory(fromIndex: Int, toIndex: Int): Boolean {
        database?.let { backing ->
            return runCatching { backing.reorderPhraseCategory(fromIndex, toIndex) }
                .onFailure { lastFailure = failureText(it) }.getOrDefault(false)
        }
        val n = phraseCats.size
        if (fromIndex !in 0 until n || toIndex !in 0 until n || fromIndex == toIndex) return false
        phraseCats.add(toIndex, phraseCats.removeAt(fromIndex))
        return savePhrases()
    }

    private fun find(name: String): Category? = phraseCats.firstOrNull { it.name == name }
    private fun findPhrase(c: Category?, text: String): Phrase? = c?.phrases?.firstOrNull { it.text == text }

    private fun scheduleSave() {
        val snapshot = ArrayList(history)
        val gen = saveGen.incrementAndGet()
        runCatching {
            io.execute {
                if (gen == saveGen.get()) {
                    runCatching { writeHistory(snapshot) }
                        .onSuccess { lastFailure = null }
                        .onFailure { lastFailure = failureText(it) }
                }
            }
        }.onFailure { lastFailure = failureText(it) }
    }

    private fun writeHistory(snapshot: List<String>) {
        val sb = StringBuilder()
        val referenced = HashSet<String>()
        for (e in snapshot) {
            if (e.length > BIG_THRESHOLD) {
                val hash = sha256(e)
                referenced.add(hash)
                val sideDir = clipsDir()
                if (!sideDir.exists() && !sideDir.mkdirs()) throw IOException("clipboard sidecar directory creation failed")
                val f = File(sideDir, "$hash.txt")
                if (!f.isFile) atomicWrite(f, e)
                sb.append(BIG_LINE).append(hash).append('\n')
            } else {
                sb.append(encode(e)).append('\n')
            }
        }
        atomicWrite(histFile, sb.toString())
        clipsDir().listFiles()?.forEach { f ->
            if (f.name.endsWith(".txt") && f.name.removeSuffix(".txt") !in referenced) runCatching { f.delete() }
        }
    }

    private fun atomicWrite(dest: File, text: String) {
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        tmp.writeText(text)
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

    internal fun flushPendingWrites() {
        if (database != null) return
        runCatching {
            io.submit { }.get()
        }.onFailure { lastFailure = failureText(it) }
    }

    private fun savePhrases(): Boolean = runCatching {
        persistPhrasesOrThrow()
        lastValidPhrases = copyCategories(phraseCats)
        lastFailure = null
        true
    }.onFailure {
        restoreLastValidPhrases()
        lastFailure = failureText(it)
    }.getOrDefault(false)

    private fun savePhrasesOrThrow() {
        try {
            persistPhrasesOrThrow()
            lastValidPhrases = copyCategories(phraseCats)
            lastFailure = null
        } catch (failure: Exception) {
            restoreLastValidPhrases()
            lastFailure = failureText(failure)
            throw failure
        }
    }

    private fun persistPhrasesOrThrow() {
        if (database == null) atomicWrite(phraseFile, serializePhrases())
        else database.replacePhraseCategories(storedCategories())
    }

    private fun storedCategories(): List<StoredPhraseCategory> = phraseCats.map { category ->
        StoredPhraseCategory(category.name, category.phrases.map { phrase -> StoredPhrase(phrase.text, phrase.note) })
    }

    private fun copyCategories(source: List<Category>): ArrayList<Category> = source.mapTo(ArrayList()) { category ->
        Category(category.name, category.phrases.mapTo(ArrayList()) { phrase -> Phrase(phrase.text, phrase.note) })
    }

    private fun restoreLastValidPhrases() {
        phraseCats.clear()
        phraseCats.addAll(copyCategories(lastValidPhrases))
    }

    internal fun storageSnapshot(): ClipboardDataSnapshot = ClipboardDataSnapshot(history(), storedCategories())

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
        internal const val RUNTIME_PAGE_SIZE = 128
        private const val RUNTIME_CACHE_CATEGORIES = 32
        private const val LEGACY_DEFAULT_NAME = "默认"
        private val DEFAULT_PHRASES = emptyList<String>()
    }

    private fun failureText(failure: Throwable): String =
        failure.javaClass.simpleName + ": " + failure.message.orEmpty()
}
