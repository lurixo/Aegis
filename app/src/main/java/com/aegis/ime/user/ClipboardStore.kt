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

/**
 * On-device clipboard history + canned phrases (issue #7 / C). Plain-text files in
 * filesDir — newest first, de-duplicated, capped. Nothing leaves the device.
 *
 * C5: canned phrases are organised into named **categories** the user can create / rename / delete and
 * fill (incl. batch-add from clipboard). Persisted as a marker file: `C\t<name>` starts a category,
 * `P\t<phrase>` is a phrase under it. A legacy flat phrases.txt (no markers) migrates into the default
 * category on first load.
 */
class ClipboardStore(private val dir: File) {

    private val histFile get() = File(dir, "clipboard.txt")
    private val phraseFile get() = File(dir, "phrases.txt")

    private val history = ArrayList<String>()

    private class Category(var name: String, val phrases: ArrayList<String> = ArrayList())
    private val phraseCats = ArrayList<Category>()

    fun load() {
        history.clear()
        runCatching { if (histFile.exists()) histFile.readLines().forEach { decode(it)?.let(history::add) } }
        loadPhrases()
    }

    private fun loadPhrases() {
        phraseCats.clear()
        if (!phraseFile.exists()) {
            // First run: seed the default category with the starter phrases. An existing file is honoured
            // verbatim — emptying a category keeps the (now-empty) category — but a fully empty file falls
            // through to the migration branch below and re-seeds an empty "默认" so there is always ≥1 usable
            // category for the UI to target.
            phraseCats.add(Category(DEFAULT_CATEGORY, ArrayList(DEFAULT_PHRASES)))
            return
        }
        val lines = runCatching { phraseFile.readLines() }.getOrDefault(emptyList())
        if (lines.none { it.startsWith("C\t") }) {
            // Legacy flat format (pre-C5): every line is a phrase → migrate into the default category.
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

    /** Add [text] to the front of the history (dedup, trim, persist). No-op for blank text. */
    fun record(text: String?) {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return
        history.remove(t)
        history.add(0, t)
        while (history.size > MAX_HISTORY) history.removeAt(history.size - 1)
        saveHistory()
    }

    /**
     * U22: record an IMAGE history entry — the saved file [path] tagged with [IMG_PREFIX] so it rides the
     * SAME history list (dedup/MRU/persist reused) while staying distinguishable from text entries.
     */
    fun recordImage(path: String) { if (path.isNotEmpty()) record(IMG_PREFIX + path) }

    /** C7 多选删除: drop one / many history entries (and persist). No-op for entries not present. */
    fun delete(text: String) { if (history.remove(text)) saveHistory() }
    fun deleteAll(texts: Collection<String>) { if (history.removeAll(texts.toSet())) saveHistory() }
    fun clearHistory() { if (history.isNotEmpty()) { history.clear(); saveHistory() } }

    fun history(): List<String> = history.toList()

    // --- C5 categorized phrases ---

    /** Re-read just the phrase categories from disk (picks up edits made in the manager Activity). */
    fun reloadPhrases() = loadPhrases()

    /** Category names, in display order. Always at least one after [load]. */
    fun categories(): List<String> = phraseCats.map { it.name }

    /** Phrases in [category] (empty list if the category is unknown). */
    fun phrasesIn(category: String): List<String> = find(category)?.phrases?.toList() ?: emptyList()

    /** All phrases across every category, flattened in category order. */
    fun phrases(): List<String> = phraseCats.flatMap { it.phrases }

    /** Create a new (empty) category. Returns false for blank / duplicate names. */
    fun addCategory(name: String): Boolean {
        val n = name.trim()
        if (n.isEmpty() || phraseCats.any { it.name == n }) return false
        phraseCats.add(Category(n)); savePhrases(); return true
    }

    /** Delete a whole category (and its phrases). */
    fun deleteCategory(name: String) { if (phraseCats.removeAll { it.name == name }) savePhrases() }

    /** Rename a category. Returns false if [old] is missing, or [new] is blank / collides. */
    fun renameCategory(old: String, new: String): Boolean {
        val n = new.trim()
        val c = find(old) ?: return false
        if (n.isEmpty() || (n != old && phraseCats.any { it.name == n })) return false
        c.name = n; savePhrases(); return true
    }

    /**
     * C5/C7 批量添加常用语: append [texts] to [category] (creating it if absent; trim, dedup, persist).
     * Returns the number actually added.
     */
    fun addPhrasesTo(category: String, texts: Collection<String>): Int {
        if (category.isBlank()) return 0 // never create a blank-named category
        val c = find(category) ?: Category(category).also { phraseCats.add(it) }
        var added = 0
        for (raw in texts) {
            val t = raw.trim()
            // M-2 defense: an image marker must never become a phrase (it would be a dead "图片已不存在" item).
            if (t.isEmpty() || isImageEntry(t) || c.phrases.contains(t)) continue
            c.phrases.add(t); added++
        }
        if (added > 0) savePhrases()
        return added
    }

    /** Back-compat: add to the first/default category. */
    fun addPhrases(texts: Collection<String>): Int =
        addPhrasesTo(phraseCats.firstOrNull()?.name ?: DEFAULT_CATEGORY, texts)

    /** Remove a phrase from a specific category (常用语 management). */
    fun deletePhraseFrom(category: String, text: String) {
        find(category)?.let { if (it.phrases.remove(text)) savePhrases() }
    }

    /** Remove a phrase wherever it appears (back-compat convenience). */
    fun deletePhrase(text: String) {
        var changed = false
        for (c in phraseCats) if (c.phrases.remove(text)) changed = true
        if (changed) savePhrases()
    }

    private fun find(name: String): Category? = phraseCats.firstOrNull { it.name == name }

    private fun saveHistory() = runCatching { histFile.writeText(history.joinToString("\n") { encode(it) }) }

    private fun savePhrases() = runCatching {
        val sb = StringBuilder()
        for (c in phraseCats) {
            sb.append("C\t").append(encode(c.name)).append('\n')
            for (p in c.phrases) sb.append("P\t").append(encode(p)).append('\n')
        }
        phraseFile.writeText(sb.toString())
    }

    // Single-line encoding so multi-line clips survive a line-based file. BOTH \n and \r are escaped:
    // readLines() splits on a lone \r / \r\n too, so a CRLF clip (common from desktop/web) would otherwise
    // be truncated on reload.
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
        // U22: image history entries are stored as IMG_PREFIX + file path in the shared history list.
        const val IMG_PREFIX = "img:"
        fun isImageEntry(entry: String): Boolean = entry.startsWith(IMG_PREFIX)
        fun imagePath(entry: String): String = if (isImageEntry(entry)) entry.substring(IMG_PREFIX.length) else entry

        private const val MAX_HISTORY = 100000 // U9: effectively no 条数上限 (kept large only as a file-bloat backstop)
        private const val DEFAULT_CATEGORY = "默认"
        private val DEFAULT_PHRASES = listOf(
            "你好", "谢谢", "好的", "收到", "在吗？", "稍等一下", "马上到", "没问题",
            "抱歉，刚看到消息", "哈哈哈", "晚点联系你", "辛苦了",
        )
    }
}
