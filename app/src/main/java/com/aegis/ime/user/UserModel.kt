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
import kotlin.math.ln

/**
 * On-device user adaptation: learns which words/phrases the user actually commits and in what
 * order, to (a) boost their ranking in the decoder, (b) predict the next word, and (c) recall a
 * self-created multi-character word — one the user assembled character by character that the main
 * dictionary does not carry — the next time its reading is typed.
 *
 * Pure Kotlin + a plain-text file format → fully offline, inspectable, portable (the file *is*
 * the import/export artifact), and unit-testable on the JVM without SQLite/Robolectric.
 *
 * Three stores, one file:
 *  - [count]/[lastUsed]: per-word usage → [wordBoost] ranking lift (W lines).
 *  - [bigram]: learned next-word pairs → prediction (B lines).
 *  - [readings]: reading → self-created/manually-added words, so the decoder can offer a word that
 *    is not in the main dictionary when its pinyin reading is typed (R lines). A word here also has
 *    a [count] entry, so it is boosted as well as recalled.
 *
 * M-2: the background dict-load thread and the main thread (onStartInput reload / commit record) touch
 * the same maps, so every accessor is `@Synchronized` — reload (clear+load) and save are then atomic
 * w.r.t. record/load, preventing lost updates, ConcurrentModificationException and a corrupted userdb.txt.
 * [version] increments on every mutation so the decoder can cheaply detect it must rebuild its own
 * per-keyspace recall index (it snapshots [readingSnapshot] only when the version moved).
 */
class UserModel {
    private val count = HashMap<String, Int>()
    private val lastUsed = HashMap<String, Long>()
    private val bigram = HashMap<String, HashMap<String, Int>>() // prevWord -> (word -> count)
    private val readings = HashMap<String, LinkedHashSet<String>>() // reading (letters) -> words, insertion-ordered

    /** Set when in-memory state diverges from disk; the IME saves on finish when dirty. */
    @Volatile
    var dirty: Boolean = false
        private set

    /** Bumped on every mutation; a live decoder rebuilds its recall index only when this changes. */
    @Volatile
    var version: Long = 0L
        private set

    /** Record that the user committed [word] after [prevWord] (null = sentence start). */
    @Synchronized
    fun record(prevWord: String?, word: String, now: Long) {
        if (word.isEmpty()) return
        count[word] = (count[word] ?: 0) + 1
        lastUsed[word] = now
        if (!prevWord.isNullOrEmpty()) {
            val m = bigram.getOrPut(prevWord) { HashMap() }
            m[word] = (m[word] ?: 0) + 1
        }
        dirty = true
        version++
    }

    /**
     * Record a [word] under its full pinyin [reading] (letters) so it can be recalled by that reading —
     * the persistence side of a self-created word. [incrementCount] adds a usage tick (true when the word
     * was not already counted through the normal commit path, e.g. an assembled multi-chunk word whose
     * whole form the commit path never learned; false when the caller already counted it, so recall is
     * added without double-counting). A word with no prior count always gets at least one, so it carries a
     * boost as well as a recall entry.
     */
    @Synchronized
    fun recordWord(reading: String, word: String, now: Long, incrementCount: Boolean) {
        val r = sanitizeReading(reading)
        if (word.isEmpty() || r.isEmpty() || !isStorableWord(word)) return
        readings.getOrPut(r) { LinkedHashSet() }.add(word)
        if (incrementCount) {
            count[word] = (count[word] ?: 0) + 1
            lastUsed[word] = now
        } else if (word !in count) {
            count[word] = 1
            lastUsed[word] = now
        }
        dirty = true
        version++
    }

    /** Manual add (settings UI): store [word] under optional [reading]; a blank reading adds a boost-only
     *  entry (no recall). Always counts once so a freshly added word ranks like a used one. */
    @Synchronized
    fun addManualWord(reading: String, word: String, now: Long) {
        val w = word.trim()
        if (w.isEmpty() || !isStorableWord(w)) return
        val r = sanitizeReading(reading)
        if (r.isNotEmpty()) readings.getOrPut(r) { LinkedHashSet() }.add(w)
        count[w] = (count[w] ?: 0) + 1
        lastUsed[w] = now
        dirty = true
        version++
    }

    /** Reading-scoped delete (settings UI, one row = one reading+word): drop [word] from THIS [reading]'s recall
     *  only; its boost/predictions are removed just when no reading recalls it any more. */
    @Synchronized
    fun removeWord(reading: String, word: String) {
        val r = sanitizeReading(reading)
        val set = readings[r] ?: return
        if (!set.remove(word)) return
        if (set.isEmpty()) readings.remove(r)
        if (readings.values.none { word in it }) {
            count.remove(word)
            lastUsed.remove(word)
            bigram.remove(word)
            for (m in bigram.values) m.remove(word)
        }
        dirty = true
        version++
    }

    /** Full delete: drop [word] from recall, boost and predictions under every reading. */
    @Synchronized
    fun removeWord(word: String) {
        if (word.isEmpty()) return
        var changed = false
        if (count.remove(word) != null) changed = true
        if (lastUsed.remove(word) != null) changed = true
        val emptyReadings = ArrayList<String>()
        for ((r, ws) in readings) if (ws.remove(word)) { changed = true; if (ws.isEmpty()) emptyReadings.add(r) }
        for (r in emptyReadings) readings.remove(r)
        if (bigram.remove(word) != null) changed = true
        for (m in bigram.values) if (m.remove(word) != null) changed = true
        if (changed) { dirty = true; version++ }
    }

    /** One user-recall entry as shown/managed in the settings list. */
    data class Entry(val reading: String, val word: String, val count: Int)

    /** Every recall entry (reading + word), most-used first — the settings list model. */
    @Synchronized
    fun userWordEntries(): List<Entry> {
        val out = ArrayList<Entry>()
        for ((r, ws) in readings) for (w in ws) out.add(Entry(r, w, count[w] ?: 0))
        return out.sortedWith(compareByDescending<Entry> { it.count }.thenBy { it.reading }.thenBy { it.word })
    }

    /** Snapshot reading -> words (each list most-used first) for a decoder to build its recall index. */
    @Synchronized
    fun readingSnapshot(): Map<String, List<String>> {
        val out = HashMap<String, List<String>>(readings.size)
        for ((r, ws) in readings) {
            out[r] = ws.sortedByDescending { count[it] ?: 0 }
        }
        return out
    }

    /** Additive log-domain ranking boost for a word the user has used before (0 if unseen).
     *  A steeper weight than the original ln(1+c) so a repeatedly chosen homophone rises within a handful
     *  of uses (perceptible) instead of the ~200 the unit weight needed; the concave ln shape still
     *  saturates, so a word used a few times cannot permanently outweigh everything. Unseen words score 0,
     *  so a fresh buffer / an un-adapted install (and every cold-start ordering audit) is unaffected. */
    @Synchronized
    fun wordBoost(word: String): Double {
        val c = count[word] ?: return 0.0
        return BOOST_WEIGHT * ln(1.0 + c)
    }

    /** Learned next-word predictions after [prevWord], most-used first. */
    @Synchronized
    fun successors(prevWord: String, limit: Int): List<String> {
        val m = bigram[prevWord] ?: return emptyList()
        return m.entries.sortedByDescending { it.value }.take(limit).map { it.key }
    }

    @Synchronized
    fun isEmpty(): Boolean = count.isEmpty() && readings.isEmpty()

    // --- persistence (the file is the import/export format) ---

    @Synchronized
    fun save(file: File) {
        file.bufferedWriter().use { w ->
            w.write("aegis-userdb 1\n")
            for ((word, c) in count) w.write("W\t$word\t$c\t${lastUsed[word] ?: 0}\n")
            for ((prev, m) in bigram) for ((word, c) in m) w.write("B\t$prev\t$word\t$c\n")
            for ((reading, ws) in readings) for (word in ws) w.write("R\t$reading\t$word\n")
        }
        dirty = false
    }

    /** Replace in-memory state from disk (used when an import changed the file under us). */
    @Synchronized
    fun reload(file: File) {
        count.clear()
        lastUsed.clear()
        bigram.clear()
        readings.clear()
        loadLocked(file)
        version++
    }

    @Synchronized
    fun load(file: File) {
        loadLocked(file)
        version++
    }

    /** Body of [load]; callers already hold the monitor (reload reuses it without re-locking). */
    private fun loadLocked(file: File) {
        if (!file.exists()) return
        file.bufferedReader().useLines { lines ->
            for (line in lines) {
                val p = line.split('\t')
                when {
                    p.size == 4 && p[0] == "W" -> {
                        val word = p[1]
                        count[word] = p[2].toIntOrNull() ?: continue
                        lastUsed[word] = p[3].toLongOrNull() ?: 0
                    }
                    p.size == 4 && p[0] == "B" ->
                        bigram.getOrPut(p[1]) { HashMap() }[p[2]] = p[3].toIntOrNull() ?: continue
                    p.size == 3 && p[0] == "R" -> {
                        val r = sanitizeReading(p[1])
                        if (r.isNotEmpty() && p[2].isNotEmpty()) readings.getOrPut(r) { LinkedHashSet() }.add(p[2])
                    }
                }
            }
        }
        dirty = false
    }

    /** Merge another userdb file into this model (import; counts add up, recall entries union). */
    @Synchronized
    fun importFrom(file: File, now: Long) {
        val other = UserModel().apply { load(file) }
        for ((word, c) in other.count) {
            count[word] = (count[word] ?: 0) + c
            lastUsed[word] = maxOf(lastUsed[word] ?: 0, other.lastUsed[word] ?: now)
        }
        for ((prev, m) in other.bigram) {
            val dst = bigram.getOrPut(prev) { HashMap() }
            for ((word, c) in m) dst[word] = (dst[word] ?: 0) + c
        }
        for ((reading, ws) in other.readings) readings.getOrPut(reading) { LinkedHashSet() }.addAll(ws)
        if (!other.isEmpty()) { dirty = true; version++ }
    }

    private companion object {
        // Log-domain weight on ln(1+usageCount). At 2.5 a same-reading homophone with a typical few-unit
        // frequency gap is overtaken within a handful of uses (e.g. a gap of ~3.5 in ~3 uses; 2.5*ln(1+3)≈3.5)
        // rather than the ~200 the original unit weight required, while the concave shape keeps a lightly used
        // word from permanently dominating.
        const val BOOST_WEIGHT = 2.5

        /** A word is storable only if it carries no field delimiter (TAB) or record delimiter (newline), which
         *  would corrupt the tab-delimited userdb.txt line format on the next save/load round trip. */
        fun isStorableWord(word: String): Boolean = word.none { it == '\t' || it == '\n' || it == '\r' }

        /** Reading key = lowercase pinyin letters only (drop separators/case/stray marks); "" if nothing left. */
        fun sanitizeReading(reading: String): String {
            val sb = StringBuilder(reading.length)
            for (ch in reading.lowercase()) if (ch in 'a'..'z') sb.append(ch)
            return sb.toString()
        }
    }
}
