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
        saveHistory()
    }

    fun delete(text: String) { if (history.remove(text)) saveHistory() }
    fun deleteAll(texts: Collection<String>) { if (history.removeAll(texts.toSet())) saveHistory() }
    fun clearHistory() { if (history.isNotEmpty()) { history.clear(); saveHistory() } }

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
            if (t.isEmpty() || c.phrases.contains(t)) continue
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

    private fun saveHistory() = runCatching { histFile.writeText(history.joinToString("\n") { encode(it) }) }

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

    private companion object {
        const val MAX_HISTORY = 1000
        const val DEFAULT_CATEGORY = "默认"
        val DEFAULT_PHRASES = listOf(
            "你好", "谢谢", "好的", "收到", "在吗？", "稍等一下", "马上到", "没问题",
            "抱歉，刚看到消息", "哈哈哈", "晚点联系你", "辛苦了",
        )
    }
}
