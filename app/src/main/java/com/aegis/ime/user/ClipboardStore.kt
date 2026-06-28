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

    private class Category(var name: String, val phrases: ArrayList<String> = ArrayList())
    private val phraseCats = ArrayList<Category>()

    fun load() {
        history.clear()
        runCatching { if (histFile.exists()) histFile.readLines().forEach { readEntry(it)?.let(history::add) } }
        loadPhrases()
    }

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
            phraseCats.add(Category(DEFAULT_CATEGORY, ArrayList(DEFAULT_PHRASES)))
            return
        }
        val lines = runCatching { phraseFile.readLines() }.getOrDefault(emptyList())
        if (lines.none { it.startsWith("C\t") }) {
            val c = Category(DEFAULT_CATEGORY)
            lines.forEach { decode(it)?.let { p -> if (p.isNotBlank()) c.phrases.add(p) } }
            phraseCats.add(c)
            return
        }
        var cur: Category? = null
        for (line in lines) when {
            line.startsWith("C\t") -> Category(decode(line.substring(2)).orEmpty()).also { phraseCats.add(it); cur = it }
            line.startsWith("P\t") -> decode(line.substring(2))?.let { cur?.phrases?.add(it) }
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

    fun recordImage(path: String) { if (path.isNotEmpty()) record(IMG_PREFIX + path) }

    fun delete(text: String) { if (history.remove(text)) scheduleSave() }
    fun deleteAll(texts: Collection<String>) { if (history.removeAll(texts.toSet())) scheduleSave() }
    fun clearHistory() { if (history.isNotEmpty()) { history.clear(); scheduleSave() } }

    fun history(): List<String> = history.toList()


    fun reloadPhrases() = loadPhrases()

    fun categories(): List<String> = phraseCats.map { it.name }

    fun phrasesIn(category: String): List<String> = find(category)?.phrases?.toList() ?: emptyList()

    fun phrases(): List<String> = phraseCats.flatMap { it.phrases }

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
        var added = 0
        for (raw in texts) {
            val t = raw.trim()
            if (t.isEmpty() || isImageEntry(t) || c.phrases.contains(t)) continue
            c.phrases.add(t); added++
        }
        if (added > 0) savePhrases()
        return added
    }

    fun addPhrases(texts: Collection<String>): Int =
        addPhrasesTo(phraseCats.firstOrNull()?.name ?: DEFAULT_CATEGORY, texts)

    fun deletePhraseFrom(category: String, text: String) {
        find(category)?.let { if (it.phrases.remove(text)) savePhrases() }
    }

    fun deletePhrase(text: String) {
        var changed = false
        for (c in phraseCats) if (c.phrases.remove(text)) changed = true
        if (changed) savePhrases()
    }

    private fun find(name: String): Category? = phraseCats.firstOrNull { it.name == name }

    private fun scheduleSave() {
        val snapshot = ArrayList(history)
        val gen = saveGen.incrementAndGet()
        runCatching { io.execute { if (gen == saveGen.get()) writeHistory(snapshot) } }
    }

    private fun writeHistory(snapshot: List<String>) = runCatching {
        val sb = StringBuilder()
        val referenced = HashSet<String>()
        for (e in snapshot) {
            if (e.length > BIG_THRESHOLD && !isImageEntry(e)) {
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

    internal fun awaitWritesForTest() { runCatching { io.submit { }.get() } }

    private fun savePhrases() = runCatching {
        val sb = StringBuilder()
        for (c in phraseCats) {
            sb.append("C\t").append(encode(c.name)).append('\n')
            for (p in c.phrases) sb.append("P\t").append(encode(p)).append('\n')
        }
        phraseFile.writeText(sb.toString())
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
        const val IMG_PREFIX = "img:"
        fun isImageEntry(entry: String): Boolean = entry.startsWith(IMG_PREFIX)
        fun imagePath(entry: String): String = if (isImageEntry(entry)) entry.substring(IMG_PREFIX.length) else entry

        private const val BIG_LINE = "B\t"
        const val BIG_THRESHOLD = 64 * 1024

        private const val MAX_HISTORY = 100000
        private const val DEFAULT_CATEGORY = "默认"
        private val DEFAULT_PHRASES = listOf(
            "你好", "谢谢", "好的", "收到", "在吗？", "稍等一下", "马上到", "没问题",
            "抱歉，刚看到消息", "哈哈哈", "晚点联系你", "辛苦了",
        )
    }
}
