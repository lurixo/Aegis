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
import kotlin.math.ln

class UserModel {
    private val count = HashMap<String, Int>()
    private val lastUsed = HashMap<String, Long>()
    private val bigram = HashMap<String, HashMap<String, Int>>()
    private val readings = HashMap<String, LinkedHashSet<String>>()

    @Volatile
    var dirty: Boolean = false
        private set

    @Volatile
    var version: Long = 0L
        private set

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

    data class Entry(val reading: String, val word: String, val count: Int)

    @Synchronized
    fun userWordEntries(): List<Entry> {
        val out = ArrayList<Entry>()
        for ((r, ws) in readings) for (w in ws) out.add(Entry(r, w, count[w] ?: 0))
        return out.sortedWith(compareByDescending<Entry> { it.count }.thenBy { it.reading }.thenBy { it.word })
    }

    @Synchronized
    fun readingSnapshot(): Map<String, List<String>> {
        val out = HashMap<String, List<String>>(readings.size)
        for ((r, ws) in readings) {
            out[r] = ws.sortedByDescending { count[it] ?: 0 }
        }
        return out
    }

    @Synchronized
    fun wordBoost(word: String): Double {
        val c = count[word] ?: return 0.0
        return BOOST_WEIGHT * ln(1.0 + c)
    }

    @Synchronized
    fun successors(prevWord: String, limit: Int): List<String> {
        val m = bigram[prevWord] ?: return emptyList()
        return m.entries.sortedByDescending { it.value }.take(limit).map { it.key }
    }

    @Synchronized
    fun isEmpty(): Boolean = count.isEmpty() && readings.isEmpty()


    @Synchronized
    fun save(file: File) {
        val tmp = File(file.absoluteFile.parentFile, file.name + ".tmp")
        try {
            tmp.bufferedWriter().use { w ->
                w.write("aegis-userdb 1\n")
                for ((word, c) in count) w.write("W\t$word\t$c\t${lastUsed[word] ?: 0}\n")
                for ((prev, m) in bigram) for ((word, c) in m) w.write("B\t$prev\t$word\t$c\n")
                for ((reading, ws) in readings) for (word in ws) w.write("R\t$reading\t$word\n")
            }
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) throw IOException("userdb swap failed")
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
        dirty = false
    }

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
        const val BOOST_WEIGHT = 3.5

        fun isStorableWord(word: String): Boolean = word.none { it == '\t' || it == '\n' || it == '\r' }

        fun sanitizeReading(reading: String): String {
            val sb = StringBuilder(reading.length)
            for (ch in reading.lowercase()) if (ch in 'a'..'z') sb.append(ch)
            return sb.toString()
        }
    }
}
