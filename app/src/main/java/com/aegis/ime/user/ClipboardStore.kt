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
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class ClipboardStore(private val dir: File) {

    private val histFile get() = File(dir, "clipboard.txt")
    private val phraseFile get() = File(dir, "phrases.txt")
    private fun clipsDir() = File(dir, "clips")

    private val history = ArrayList<String>()

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "aegis-clip-io").apply { isDaemon = true } }
    private val saveGen = AtomicLong(0)

    private class Phrase(var text: String, var note: String = "")
    private class Category(var name: String, val phrases: ArrayList<Phrase> = ArrayList())
    private val phraseCats = ArrayList<Category>()

    fun load() {
        history.clear()
        purgeLegacyImageDir()
        runCatching {
            if (histFile.exists()) histFile.readLines().forEach { line ->
                readEntry(line)?.let { e -> if (!isLegacyImageEntry(e)) history.add(e) }
            }
        }
        loadPhrases()
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
        phraseCats.clear()
        if (!phraseFile.exists()) {
            phraseCats.add(Category(DEFAULT_CATEGORY_ID, ArrayList(DEFAULT_PHRASES.map { Phrase(it) })))
            return
        }
        val lines = runCatching { phraseFile.readLines() }.getOrDefault(emptyList())
        if (lines.none { it.startsWith("C\t") }) {
            val c = Category(DEFAULT_CATEGORY_ID)
            lines.forEach { decode(it)?.let { p -> if (p.isNotBlank()) c.phrases.add(Phrase(p)) } }
            phraseCats.add(c)
            return
        }
        phraseCats.addAll(parseCategories(lines))
        migrateLegacyDefault()
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

    private fun migrateLegacyDefault() {
        if (phraseCats.none { it.name == DEFAULT_CATEGORY_ID }) {
            phraseCats.firstOrNull { it.name == LEGACY_DEFAULT_NAME }?.let { it.name = DEFAULT_CATEGORY_ID }
        }
    }

    fun record(text: String?) {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return
        history.remove(t)
        history.add(0, t)
        while (history.size > MAX_HISTORY) history.removeAt(history.size - 1)
        scheduleSave()
    }

    fun importHistory(entries: List<String>, merge: Boolean) {
        val incoming = entries.mapNotNull { it.trim().ifEmpty { null } }
        if (merge) {
            val present = HashSet(history)
            for (e in incoming) if (present.add(e)) history.add(e)
        } else {
            history.clear()
            val seen = HashSet<String>()
            for (e in incoming) if (seen.add(e)) history.add(e)
        }
        while (history.size > MAX_HISTORY) history.removeAt(history.size - 1)
        writeHistory(ArrayList(history))
    }

    fun delete(text: String) { if (history.remove(text)) scheduleSave() }
    fun deleteAll(texts: Collection<String>) { if (history.removeAll(texts.toSet())) scheduleSave() }
    fun clearHistory() { if (history.isNotEmpty()) { history.clear(); scheduleSave() } }

    fun history(): List<String> = history.toList()


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

    private fun carryInto(to: Category, p: Phrase) {
        val existing = findPhrase(to, p.text)
        if (existing == null) to.phrases.add(p)
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
        val snapshot = ArrayList(history)
        val gen = saveGen.incrementAndGet()
        runCatching { io.execute { if (gen == saveGen.get()) writeHistory(snapshot) } }
    }

    private fun writeHistory(snapshot: List<String>) = runCatching {
        val sb = StringBuilder()
        val referenced = HashSet<String>()
        for (e in snapshot) {
            if (e.length > BIG_THRESHOLD) {
                val hash = sha256(e)
                referenced.add(hash)
                val f = File(clipsDir().apply { mkdirs() }, "$hash.txt")
                if (!f.exists()) atomicWrite(f, e)
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
        if (!tmp.renameTo(dest)) { dest.delete(); if (!tmp.renameTo(dest)) tmp.delete() }
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    internal fun flushPendingWrites() { runCatching { io.submit { }.get() } }

    private fun savePhrases() = runCatching { phraseFile.writeText(serializePhrases()) }

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
        val parsed = parseCategories(text.lineSequence().toList())
        val hasContent = parsed.any { it.phrases.isNotEmpty() || it.name.isNotBlank() }
        if (!hasContent) return false
        if (merge) {
            for (pc in parsed) {
                if (pc.name.isBlank()) continue
                val c = find(pc.name) ?: Category(pc.name).also { phraseCats.add(it) }
                for (p in pc.phrases) {
                    val existing = findPhrase(c, p.text)
                    if (existing == null) c.phrases.add(Phrase(p.text, p.note))
                    else if (existing.note.isEmpty() && p.note.isNotEmpty()) existing.note = p.note
                }
            }
        } else {
            phraseCats.clear()
            phraseCats.addAll(parsed)
            if (phraseCats.none { it.name == DEFAULT_CATEGORY_ID }) phraseCats.add(0, Category(DEFAULT_CATEGORY_ID))
        }
        savePhrases()
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

        private const val MAX_HISTORY = 100000
        const val DEFAULT_CATEGORY_ID = "default"
        private const val LEGACY_DEFAULT_NAME = "默认"
        private val DEFAULT_PHRASES = emptyList<String>()
    }
}
