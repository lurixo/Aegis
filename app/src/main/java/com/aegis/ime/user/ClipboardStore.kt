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
    // E5: oversized text entries (e.g. a million-char paste) live in their OWN content-addressed file here, so
    // clipboard.txt stays a tiny index (a B-marker per big entry) that writes fast — content never truncated.
    private fun clipsDir() = File(dir, "clips")

    private val history = ArrayList<String>()

    // E5: history persistence is async + coalesced, so record()/delete() never block the (main-thread) caller
    // on a multi-MB writeText/encode. A single IO thread serializes writes; a generation counter drops stale
    // snapshots so only the latest state is written.
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "aegis-clip-io").apply { isDaemon = true } }
    private val saveGen = AtomicLong(0)

    private class Category(var name: String, val phrases: ArrayList<String> = ArrayList())
    private val phraseCats = ArrayList<Category>()

    fun load() {
        history.clear()
        runCatching { if (histFile.exists()) histFile.readLines().forEach { readEntry(it)?.let(history::add) } }
        loadPhrases()
    }

    /**
     * E5: parse one clipboard.txt line into its full-text entry. A "B\t<hash>" line whose side file exists is a
     * big entry (read in full); EVERYTHING ELSE — inline entries (kept BARE, identical to the pre-E5 format) and
     * legacy lines — is decoded as-is. Inline lines carry NO prefix on purpose, so there is no marker that can
     * collide with arbitrary clip content; and a "B\t…" line with no backing file falls back to literal text
     * (a pre-E5 tab-delimited clip that merely starts with "B"+TAB is preserved, never dropped/corrupted).
     */
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
        scheduleSave()
    }

    /**
     * U22: record an IMAGE history entry — the saved file [path] tagged with [IMG_PREFIX] so it rides the
     * SAME history list (dedup/MRU/persist reused) while staying distinguishable from text entries.
     */
    fun recordImage(path: String) { if (path.isNotEmpty()) record(IMG_PREFIX + path) }

    /** C7 多选删除: drop one / many history entries (and persist). No-op for entries not present. */
    fun delete(text: String) { if (history.remove(text)) scheduleSave() }
    fun deleteAll(texts: Collection<String>) { if (history.removeAll(texts.toSet())) scheduleSave() }
    fun clearHistory() { if (history.isNotEmpty()) { history.clear(); scheduleSave() } }

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

    /**
     * debug.16: edit a saved phrase's text IN PLACE within [category] (order preserved). [newText] is
     * sanitized (ISO control chars stripped, then trimmed). Returns false — leaving the store unchanged —
     * if [category] or [oldText] is missing, the sanitized text is empty, or it would duplicate a DIFFERENT
     * existing phrase in the same category. On success persists and returns true. Replacing a phrase with its
     * own (sanitized) value is a permitted no-op that still returns true.
     */
    fun editPhrase(category: String, oldText: String, newText: String): Boolean {
        val c = find(category) ?: return false
        val idx = c.phrases.indexOf(oldText)
        if (idx < 0) return false
        val n = newText.filterNot { Character.isISOControl(it) }.trim()
        if (n.isEmpty()) return false
        if (c.phrases.withIndex().any { (j, p) -> j != idx && p == n }) return false // collides with another item
        c.phrases[idx] = n
        savePhrases()
        return true
    }

    /**
     * debug.16: move [text] from [fromCategory] to [toCategory] (dedup at the target). [toCategory] must
     * already exist (returns false otherwise — never auto-creates on a move). Moving to the same category is
     * a no-op that returns true. Returns false if [text] is not actually in [fromCategory] (nothing to move —
     * so a missing phrase can never be phantom-created at the target). On a real move the phrase is removed
     * from the source and appended to the target only if absent there, then persisted.
     */
    fun movePhrase(fromCategory: String, text: String, toCategory: String): Boolean {
        val to = find(toCategory) ?: return false
        val from = find(fromCategory) ?: return false
        if (from === to) return true // same category → nothing to move (don't reorder)
        if (!from.phrases.remove(text)) return false // not in source → nothing to move (no phantom at target)
        if (!to.phrases.contains(text)) to.phrases.add(text)
        savePhrases()
        return true
    }

    private fun find(name: String): Category? = phraseCats.firstOrNull { it.name == name }

    /**
     * E5: snapshot the list on the CALLER thread (cheap — copies references) and write it on the IO thread, so
     * the caller (main thread) never blocks on multi-MB IO. A generation counter writes only the latest
     * snapshot if several mutations queue up. TRADE-OFF (by design): persistence is now async, so a process
     * kill in the brief window before the IO thread flushes can lose the most recent entry — acceptable vs the
     * main-thread jank a synchronous million-char writeText caused, and the window is just the write duration
     * (no debounce delay).
     */
    private fun scheduleSave() {
        val snapshot = ArrayList(history)
        val gen = saveGen.incrementAndGet()
        runCatching { io.execute { if (gen == saveGen.get()) writeHistory(snapshot) } }
    }

    /** E5: write the history index — big entries (> [BIG_THRESHOLD]) go to a content-addressed side file
     *  (written once; clipboard.txt keeps only a B-marker) so the main file stays small; small entries stay
     *  inline (bare, legacy-compatible). Writes are atomic (temp+rename, never a partial file). Then drop any
     *  side file no longer referenced. Runs on the IO thread. */
    private fun writeHistory(snapshot: List<String>) = runCatching {
        val sb = StringBuilder()
        val referenced = HashSet<String>()
        for (e in snapshot) {
            if (e.length > BIG_THRESHOLD && !isImageEntry(e)) {
                val hash = sha256(e)
                referenced.add(hash)
                val f = File(clipsDir().apply { mkdirs() }, "$hash.txt")
                if (!f.exists()) atomicWrite(f, e) // content-addressed → write once, atomic, never truncate
                sb.append(BIG_LINE).append(hash).append('\n')
            } else {
                sb.append(encode(e)).append('\n') // inline = bare line (no prefix → no collision with content)
            }
        }
        atomicWrite(histFile, sb.toString())
        // Orphan sweep: a big entry that was deleted/evicted leaves a side file no current entry references.
        clipsDir().listFiles()?.forEach { f ->
            if (f.name.endsWith(".txt") && f.name.removeSuffix(".txt") !in referenced) runCatching { f.delete() }
        }
    }

    /** Write [text] to [dest] via a temp file + rename so a crash mid-write can never leave a partial file. */
    private fun atomicWrite(dest: File, text: String) {
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(dest)) { dest.delete(); if (!tmp.renameTo(dest)) tmp.delete() }
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /** Test seam: block until every queued async write has finished (IO thread is single-threaded / FIFO). */
    internal fun awaitWritesForTest() { runCatching { io.submit { }.get() } }

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

        // E5: a big entry is externalised to clips/<sha256>.txt and indexed by a "B\t<sha256>" line; every
        // other line is a bare inline entry (legacy-compatible). Above this many chars an entry is externalised.
        private const val BIG_LINE = "B\t"        // a big entry: B\t<sha256>  → content in clips/<sha256>.txt
        const val BIG_THRESHOLD = 64 * 1024       // chars; above this an entry is stored in its own side file

        private const val MAX_HISTORY = 100000 // U9: effectively no 条数上限 (kept large only as a file-bloat backstop)
        private const val DEFAULT_CATEGORY = "默认"
        // debug.14 item1: ship NO preset phrases — first run seeds only the empty "默认" category (kept so the
        // UI always has ≥1 add target). An existing phrases.txt is still honoured verbatim (user data untouched).
        private val DEFAULT_PHRASES = emptyList<String>()
    }
}
