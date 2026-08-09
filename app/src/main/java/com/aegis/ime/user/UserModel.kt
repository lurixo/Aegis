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
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.exp
import kotlin.math.ln

class UserModel(private val clock: () -> Long = System::currentTimeMillis) {
    private val count = HashMap<String, Int>()
    private val lastUsed = HashMap<String, Long>()
    private val bigram = HashMap<String, HashMap<String, Int>>()
    private val readings = HashMap<String, LinkedHashSet<String>>()
    private val manual = HashMap<String, LinkedHashSet<String>>()

    @Volatile
    var dirty: Boolean = false
        private set

    @Volatile
    var version: Long = 0L
        private set

    @Volatile
    var forgottenCount: Int = 0
        private set

    @Volatile
    private var sourceReadable: Boolean = true

    @Volatile
    private var unreadableSource: String? = null

    @Volatile
    private var partiallyRead: Boolean = false

    val readable: Boolean get() = sourceReadable && !partiallyRead

    private var sweptOnLoad: Int = 0

    @Volatile
    var autoLearnEnabled: Boolean = true

    @Synchronized
    fun record(prevWord: String?, word: String, now: Long) {
        if (!autoLearnEnabled || !isValidWord(word)) return
        count[word] = saturatingAdd(count[word] ?: 0, 1)
        lastUsed[word] = now.coerceAtLeast(0L)
        if (!prevWord.isNullOrEmpty() && isValidWord(prevWord)) {
            val m = bigram.getOrPut(prevWord) { HashMap() }
            m[word] = saturatingAdd(m[word] ?: 0, 1)
        }
        dirty = true
        version++
    }

    @Synchronized
    fun recordWord(reading: String, word: String, now: Long, incrementCount: Boolean) {
        if (!autoLearnEnabled) return
        val r = sanitizeReading(reading)
        if (!isValidWord(word) || r.isEmpty() || r.length > MAX_READING_LENGTH) return
        readings.getOrPut(r) { LinkedHashSet() }.add(word)
        if (incrementCount) {
            count[word] = saturatingAdd(count[word] ?: 0, 1)
        } else if (word !in count) {
            count[word] = 1
        }
        lastUsed[word] = now.coerceAtLeast(0L)
        dirty = true
        version++
    }

    @Synchronized
    fun addManualWord(reading: String, word: String, now: Long): Boolean {
        val w = word.trim()
        if (!acceptsManualWord(w, reading)) return false
        val r = sanitizeReading(reading)
        if (r.isNotEmpty()) {
            readings.getOrPut(r) { LinkedHashSet() }.add(w)
            manual.getOrPut(r) { LinkedHashSet() }.add(w)
        }
        count[w] = saturatingAdd(count[w] ?: 0, 1)
        lastUsed[w] = now.coerceAtLeast(0L)
        dirty = true
        version++
        return true
    }

    @Synchronized
    fun removeWord(reading: String, word: String) {
        val r = sanitizeReading(reading)
        val set = readings[r] ?: return
        if (!set.remove(word)) return
        if (set.isEmpty()) readings.remove(r)
        manual[r]?.let { if (it.remove(word) && it.isEmpty()) manual.remove(r) }
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
        val emptyManual = ArrayList<String>()
        for ((r, ws) in manual) if (ws.remove(word) && ws.isEmpty()) emptyManual.add(r)
        for (r in emptyManual) manual.remove(r)
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
        val now = clock()
        for ((r, ws) in readings) {
            out[r] = ws.sortedWith(
                compareByDescending<String> { usageScore(count[it] ?: 0, lastUsed[it] ?: 0L, now) }
                    .thenBy { it },
            )
        }
        return out
    }

    @Synchronized
    fun manualSnapshot(): Map<String, Set<String>> {
        val out = HashMap<String, Set<String>>(manual.size)
        for ((r, ws) in manual) out[r] = LinkedHashSet(ws)
        return out
    }

    @Synchronized
    fun wordBoost(word: String): Double {
        val c = count[word] ?: return 0.0
        return usageScore(c, lastUsed[word] ?: 0L, clock())
    }

    @Synchronized
    fun successors(prevWord: String, limit: Int): List<String> {
        if (limit <= 0) return emptyList()
        val m = bigram[prevWord] ?: return emptyList()
        val now = clock()
        return m.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> {
                    usageScore(it.value, lastUsed[it.key] ?: 0L, now)
                }.thenBy { it.key },
            )
            .take(limit)
            .map { it.key }
    }

    @Synchronized
    fun isEmpty(): Boolean = count.isEmpty() && readings.isEmpty() && sweptOnLoad == 0


    @Synchronized
    fun save(file: File) {
        if (partiallyRead) throw IOException("user dictionary was only partly read")
        if (unreadableSource == file.absolutePath) throw IOException("user dictionary could not be read")
        val tmp = File(file.absoluteFile.parentFile, file.name + ".tmp")
        try {
            tmp.bufferedWriter().use { w ->
                w.write("${if (forgottenCount > 0) HEADER else MARKED_HEADER}\n")
                if (forgottenCount > 0) w.write("G\t$forgottenCount\n")
                for ((word, c) in count) w.write("W\t$word\t$c\t${lastUsed[word] ?: 0}\n")
                for ((prev, m) in bigram) for ((word, c) in m) w.write("B\t$prev\t$word\t$c\n")
                for ((reading, ws) in readings) for (word in ws) w.write("R\t$reading\t$word\n")
                for ((reading, ws) in manual) for (word in ws) w.write("M\t$reading\t$word\n")
            }
            try {
                Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
        dirty = false
    }

    @Synchronized
    fun reload(file: File) {
        val parsed = parse(file)
        count.clear()
        lastUsed.clear()
        bigram.clear()
        readings.clear()
        manual.clear()
        forgottenCount = 0
        sweptOnLoad = 0
        partiallyRead = true
        applyParsed(parsed)
        partiallyRead = false
        sourceReadable = true
        unreadableSource = null
        version++
    }

    @Synchronized
    fun load(file: File, sweepStale: Boolean = true) {
        sourceReadable = false
        unreadableSource = file.absolutePath
        val parsed = parse(file)
        partiallyRead = true
        applyParsed(parsed)
        sweptOnLoad = if (sweepStale) forgetStale(clock()) else 0
        partiallyRead = false
        sourceReadable = true
        unreadableSource = null
        version++
    }

    @Synchronized
    fun forgetStale(now: Long): Int {
        if (readings.isEmpty()) return 0
        val verdict = HashMap<String, Boolean>()
        val emptied = ArrayList<String>()
        var removed = 0
        val orphans = HashSet<String>()
        for ((reading, words) in readings) {
            val kept = manual[reading]
            val each = words.iterator()
            while (each.hasNext()) {
                val word = each.next()
                if (kept != null && word in kept) continue
                if (verdict.getOrPut(word) { isStale(word, now) } != true) continue
                each.remove()
                orphans.add(word)
                removed++
            }
            if (words.isEmpty()) emptied.add(reading)
        }
        if (removed == 0) return 0
        for (reading in emptied) readings.remove(reading)
        for (words in manual.values) orphans.removeAll(words)
        for (word in orphans) {
            count.remove(word)
            lastUsed.remove(word)
            bigram.remove(word)
        }
        if (orphans.isNotEmpty()) for (m in bigram.values) m.keys.removeAll(orphans)
        forgottenCount = saturatingAdd(forgottenCount, removed)
        dirty = true
        version++
        return removed
    }

    private fun isStale(word: String, now: Long): Boolean {
        val c = count[word] ?: return false
        if (c <= 0) return false
        val used = lastUsed[word] ?: return false
        if (used <= 0L) return false
        val age = (now - used).coerceAtLeast(0L)
        return c * exp(-LN_2 * age.toDouble() / FORGET_HALF_LIFE_MILLIS) < FORGET_FLOOR
    }

    private fun applyParsed(parsed: Parsed) {
        forgottenCount = saturatingAdd(forgottenCount, parsed.forgotten)
        count.putAll(parsed.count)
        lastUsed.putAll(parsed.lastUsed)
        for ((prev, words) in parsed.bigram) {
            bigram.getOrPut(prev) { HashMap() }.putAll(words)
        }
        for ((reading, words) in parsed.readings) {
            readings.getOrPut(reading) { LinkedHashSet() }.addAll(words)
        }
        for ((reading, words) in parsed.manual) {
            manual.getOrPut(reading) { LinkedHashSet() }.addAll(words)
        }
        dirty = false
    }

    @Synchronized
    fun importFrom(file: File, now: Long): Boolean {
        val parsed = parse(file)
        if (parsed.count.isEmpty() && parsed.readings.isEmpty()) return false
        for ((word, c) in parsed.count) {
            count[word] = saturatingAdd(count[word] ?: 0, c)
            lastUsed[word] = maxOf(lastUsed[word] ?: 0, parsed.lastUsed[word] ?: now)
        }
        for ((prev, m) in parsed.bigram) {
            val dst = bigram.getOrPut(prev) { HashMap() }
            for ((word, c) in m) dst[word] = saturatingAdd(dst[word] ?: 0, c)
        }
        for ((reading, ws) in parsed.readings) readings.getOrPut(reading) { LinkedHashSet() }.addAll(ws)
        for ((reading, ws) in parsed.manual) manual.getOrPut(reading) { LinkedHashSet() }.addAll(ws)
        dirty = true
        version++
        return true
    }

    private data class Parsed(
        val count: HashMap<String, Int> = HashMap(),
        val lastUsed: HashMap<String, Long> = HashMap(),
        val bigram: HashMap<String, HashMap<String, Int>> = HashMap(),
        val readings: HashMap<String, LinkedHashSet<String>> = HashMap(),
        val manual: HashMap<String, LinkedHashSet<String>> = HashMap(),
        var forgotten: Int = 0,
    )

    companion object {
        private const val HEADER = "aegis-userdb 3"
        private const val MARKED_HEADER = "aegis-userdb 2"
        private const val LEGACY_HEADER = "aegis-userdb 1"
        internal const val FORGET_HALF_LIFE_MILLIS = 30L * 24L * 60L * 60L * 1000L
        internal const val FORGET_FLOOR = 0.0625
        private const val MAX_LINE_LENGTH = 4_096
        private const val MAX_WORD_LENGTH = 256
        private const val MAX_READING_LENGTH = 256
        private const val MAX_COUNT = 1_000_000_000
        private const val BOOST_WEIGHT = 3.5
        private const val RECENCY_WEIGHT = 2.0
        private const val RECENCY_HALF_LIFE_MILLIS = 7L * 24L * 60L * 60L * 1000L
        private val LN_2 = ln(2.0)

        private fun parse(file: File): Parsed {
            if (!file.exists() || file.length() == 0L) return Parsed()
            val parsed = Parsed()
            file.bufferedReader().use { reader ->
                val header = reader.readLine()
                require(header == HEADER || header == MARKED_HEADER || header == LEGACY_HEADER) {
                    "unsupported userdb header"
                }
                val marked = header != LEGACY_HEADER
                val counted = header == HEADER
                var sawForgotten = false
                while (true) {
                    val line = reader.readLine() ?: break
                    require(line.length <= MAX_LINE_LENGTH) { "userdb line is too long" }
                    val p = line.split('\t')
                    when (p.firstOrNull()) {
                        "W" -> {
                            require(p.size == 4 && isValidWord(p[1])) { "invalid userdb word row" }
                            val value = p[2].toIntOrNull()
                            val used = p[3].toLongOrNull()
                            require(value != null && value in 1..MAX_COUNT && used != null && used >= 0L) {
                                "invalid userdb word values"
                            }
                            require(parsed.count.put(p[1], value) == null) { "duplicate userdb word" }
                            parsed.lastUsed[p[1]] = used
                        }
                        "B" -> {
                            require(p.size == 4 && isValidWord(p[1]) && isValidWord(p[2])) {
                                "invalid userdb bigram row"
                            }
                            val value = p[3].toIntOrNull()
                            require(value != null && value in 1..MAX_COUNT) { "invalid userdb bigram count" }
                            val words = parsed.bigram.getOrPut(p[1]) { HashMap() }
                            require(words.put(p[2], value) == null) { "duplicate userdb bigram" }
                        }
                        "R" -> {
                            require(
                                p.size == 3 && p[1].isNotEmpty() && p[1].length <= MAX_READING_LENGTH &&
                                    p[1] == sanitizeReading(p[1]) && isValidWord(p[2]),
                            ) { "invalid userdb reading row" }
                            require(parsed.readings.getOrPut(p[1]) { LinkedHashSet() }.add(p[2])) {
                                "duplicate userdb reading"
                            }
                        }
                        "M" -> {
                            require(marked) { "unsupported userdb row" }
                            require(
                                p.size == 3 && p[1].isNotEmpty() && p[1].length <= MAX_READING_LENGTH &&
                                    p[1] == sanitizeReading(p[1]) && isValidWord(p[2]),
                            ) { "invalid userdb manual row" }
                            require(parsed.manual.getOrPut(p[1]) { LinkedHashSet() }.add(p[2])) {
                                "duplicate userdb manual"
                            }
                        }
                        "G" -> {
                            require(counted) { "unsupported userdb row" }
                            require(!sawForgotten) { "duplicate userdb forgotten total" }
                            val value = p.getOrNull(1)?.toIntOrNull()
                            require(p.size == 2 && value != null && value in 0..MAX_COUNT) {
                                "invalid userdb forgotten total"
                            }
                            parsed.forgotten = value
                            sawForgotten = true
                        }
                        else -> throw IllegalArgumentException("invalid userdb row")
                    }
                }
            }
            require(parsed.bigram.values.all { words -> words.keys.all { it in parsed.count } }) {
                "userdb bigram target is missing"
            }
            require(parsed.manual.all { (r, ws) -> parsed.readings[r]?.containsAll(ws) == true }) {
                "userdb manual target is missing"
            }
            return parsed
        }

        private fun usageScore(count: Int, lastUsed: Long, now: Long): Double {
            val frequency = BOOST_WEIGHT * ln(1.0 + count.coerceIn(0, MAX_COUNT))
            if (lastUsed <= 0L) return frequency
            val age = (now - lastUsed).coerceAtLeast(0L)
            return frequency + RECENCY_WEIGHT * exp(-LN_2 * age.toDouble() / RECENCY_HALF_LIFE_MILLIS)
        }

        private fun saturatingAdd(left: Int, right: Int): Int =
            minOf(MAX_COUNT.toLong(), left.toLong() + right.toLong()).toInt()

        private fun isValidWord(word: String): Boolean =
            word.isNotEmpty() && word.length <= MAX_WORD_LENGTH && isStorableWord(word)

        private fun isStorableWord(word: String): Boolean =
            word.none { it == '\t' || it == '\n' || it == '\r' }

        internal fun acceptsManualWord(word: String, reading: String): Boolean {
            val r = sanitizeReading(reading)
            return isValidWord(word.trim()) && (r.isEmpty() || r.length <= MAX_READING_LENGTH)
        }

        internal fun normalizeReading(reading: String): String = sanitizeReading(reading)

        private fun sanitizeReading(reading: String): String {
            val sb = StringBuilder(reading.length)
            for (ch in reading.lowercase()) if (ch in 'a'..'z') sb.append(ch)
            return sb.toString()
        }
    }
}
