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
 * order, to (a) boost their ranking in the decoder and (b) predict the next word.
 *
 * Pure Kotlin + a plain-text file format → fully offline, inspectable, portable (the file *is*
 * the import/export artifact), and unit-testable on the JVM without SQLite/Robolectric.
 *
 * M-2: the background dict-load thread and the main thread (onStartInput reload / commit record) touch
 * the same maps, so every accessor is `@Synchronized` — reload (clear+load) and save are then atomic
 * w.r.t. record/load, preventing lost updates, ConcurrentModificationException and a corrupted userdb.txt.
 */
class UserModel {
    private val count = HashMap<String, Int>()
    private val lastUsed = HashMap<String, Long>()
    private val bigram = HashMap<String, HashMap<String, Int>>() // prevWord -> (word -> count)

    /** Set when in-memory state diverges from disk; the IME saves on finish when dirty. */
    @Volatile
    var dirty: Boolean = false
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
    }

    /** Additive log-domain ranking boost for a word the user has used before (0 if unseen). */
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
    fun isEmpty(): Boolean = count.isEmpty()

    // --- persistence (the file is the import/export format) ---

    @Synchronized
    fun save(file: File) {
        file.bufferedWriter().use { w ->
            w.write("aegis-userdb 1\n")
            for ((word, c) in count) w.write("W\t$word\t$c\t${lastUsed[word] ?: 0}\n")
            for ((prev, m) in bigram) for ((word, c) in m) w.write("B\t$prev\t$word\t$c\n")
        }
        dirty = false
    }

    /** Replace in-memory state from disk (used when an import changed the file under us). */
    @Synchronized
    fun reload(file: File) {
        count.clear()
        lastUsed.clear()
        bigram.clear()
        loadLocked(file)
    }

    @Synchronized
    fun load(file: File) = loadLocked(file)

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
                }
            }
        }
        dirty = false
    }

    /** Merge another userdb file into this model (import; counts add up). */
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
        if (!other.isEmpty()) dirty = true
    }

    private companion object {
        const val BOOST_WEIGHT = 1.0
    }
}
