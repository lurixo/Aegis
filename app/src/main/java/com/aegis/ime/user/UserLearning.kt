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

import com.aegis.ime.decoder.T9Pinyin
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.exp
import kotlin.math.ln

class UserLearning(private val clock: () -> Long = System::currentTimeMillis) {

    data class Formed(val word: String, val reading: String)

    private class Usage(var count: Double, var lastSeen: Long)

    private class Window(val start: Long, val end: Long, val chars: List<String>, val readings: List<String>)

    private val formedByWord = HashMap<String, HashMap<String, Usage>>()
    private var formedPairs = 0
    private val pendingCounts = HashMap<String, Usage>()
    private val followsByPrev = HashMap<String, HashMap<String, Usage>>()

    private val chainRun = ArrayDeque<Pair<String, String>>()
    private var chainPos = 0L
    private val ripe = ArrayList<Window>()
    private val coveredRanges = ArrayList<Pair<Long, Long>>()

    @Volatile
    var dirty: Boolean = false
        private set

    @Volatile
    var version: Long = 0L
        private set

    @Volatile
    var enabled: Boolean = true
        @Synchronized set(value) {
            if (field == value) return
            field = value
            chainRun.clear()
            ripe.clear()
            coveredRanges.clear()
            chainPos = 0L
            version++
        }

    @Synchronized
    fun observeCommit(prevWord: String?, word: String, reading: String, now: Long) {
        if (!enabled) return
        val t = now.coerceAtLeast(0L)
        var changed = false
        if (prevWord == null) {
            if (closeChain(t)) changed = true
        } else if (recordFollow(prevWord, word, t)) {
            changed = true
        }
        val r = sanitizeReading(reading)
        if (isSingleHan(word) && r in T9Pinyin.SYLLABLES) {
            if (extendChain(word, r, t)) changed = true
        } else {
            if (closeChain(t)) changed = true
        }
        if (changed) {
            dirty = true
            version++
        }
    }

    @Synchronized
    fun observeBreak() {
        if (!enabled) return
        if (closeChain(clock())) {
            dirty = true
            version++
        }
    }

    @Synchronized
    fun readingSnapshot(): Map<String, List<String>> {
        if (!enabled) return emptyMap()
        val now = clock()
        val byReading = HashMap<String, ArrayList<Pair<String, Double>>>()
        for ((word, m) in formedByWord) {
            for ((reading, u) in m) {
                byReading.getOrPut(reading) { ArrayList() }
                    .add(word to decayed(u.count, u.lastSeen, now, FORMED_HALF_LIFE_MILLIS))
            }
        }
        val out = HashMap<String, List<String>>(byReading.size)
        for ((reading, list) in byReading) {
            out[reading] = list
                .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })
                .map { it.first }
        }
        return out
    }

    @Synchronized
    fun formedWordsFor(key: String): List<String> {
        if (!enabled || key.isEmpty()) return emptyList()
        val t9 = key[0] in '2'..'9'
        val now = clock()
        val out = ArrayList<Pair<String, Double>>()
        for ((word, m) in formedByWord) {
            for ((reading, u) in m) {
                val k = if (t9) T9Pinyin.toT9(reading) else reading
                if (k == key) out.add(word to decayed(u.count, u.lastSeen, now, FORMED_HALF_LIFE_MILLIS))
            }
        }
        return out
            .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })
            .map { it.first }
            .distinct()
    }

    @Synchronized
    fun formedWeight(word: String): Double {
        if (!enabled) return 0.0
        val m = formedByWord[word] ?: return 0.0
        val now = clock()
        var bestCount = 0.0
        var bestSeen = 0L
        for (u in m.values) {
            val eff = decayed(u.count, u.lastSeen, now, FORMED_HALF_LIFE_MILLIS)
            if (eff > bestCount) {
                bestCount = eff
                bestSeen = u.lastSeen
            }
        }
        if (bestCount < MIN_ACTIVE) return 0.0
        val age = (now - bestSeen).coerceAtLeast(0L)
        return BOOST_WEIGHT * ln(1.0 + bestCount) +
            RECENCY_WEIGHT * exp(-LN_2 * age.toDouble() / RECENCY_HALF_LIFE_MILLIS)
    }

    @Synchronized
    fun follows(prevWord: String): List<Pair<String, Double>> {
        if (!enabled) return emptyList()
        val m = followsByPrev[prevWord] ?: return emptyList()
        val now = clock()
        val out = ArrayList<Pair<String, Double>>(m.size)
        for ((word, u) in m) {
            val eff = decayed(u.count, u.lastSeen, now, FOLLOW_HALF_LIFE_MILLIS)
            if (eff >= MIN_ACTIVE) out.add(word to eff)
        }
        return out.sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })
    }

    @Synchronized
    fun followBoost(prevContext: String, word: String): Double {
        if (!enabled || prevContext.isEmpty() || word.isEmpty()) return 0.0
        val now = clock()
        var best = 0.0
        var start = prevContext.length
        var chars = 0
        while (start > 0 && chars < WINDOW_MAX) {
            val cp = prevContext.codePointBefore(start)
            if (!Character.isIdeographic(cp)) break
            start -= Character.charCount(cp)
            chars++
            val u = followsByPrev[prevContext.substring(start)]?.get(word) ?: continue
            val eff = decayed(u.count, u.lastSeen, now, FOLLOW_HALF_LIFE_MILLIS)
            if (eff > best) best = eff
        }
        return if (best >= MIN_ACTIVE) FOLLOW_WEIGHT * ln(1.0 + best) else 0.0
    }

    @Synchronized
    fun removeWord(word: String) {
        if (word.isEmpty()) return
        var changed = false
        formedByWord.remove(word)?.let {
            formedPairs -= it.size
            changed = true
        }
        val deadPending = pendingCounts.keys.filter { it.endsWith("\t" + word) }
        for (key in deadPending) {
            pendingCounts.remove(key)
            changed = true
        }
        ripe.removeAll { it.chars.joinToString("") == word }
        if (followsByPrev.remove(word) != null) changed = true
        val prevIt = followsByPrev.iterator()
        while (prevIt.hasNext()) {
            val entry = prevIt.next()
            if (entry.value.remove(word) != null) {
                changed = true
                if (entry.value.isEmpty()) prevIt.remove()
            }
        }
        if (changed) {
            dirty = true
            version++
        }
    }

    @Synchronized
    fun formedEntries(): List<Formed> {
        val now = clock()
        val out = ArrayList<Pair<Formed, Double>>(formedPairs)
        for ((word, m) in formedByWord) {
            for ((reading, u) in m) {
                out.add(Formed(word, reading) to decayed(u.count, u.lastSeen, now, FORMED_HALF_LIFE_MILLIS))
            }
        }
        return out
            .sortedWith(
                compareByDescending<Pair<Formed, Double>> { it.second }
                    .thenBy { it.first.word }
                    .thenBy { it.first.reading },
            )
            .map { it.first }
    }

    @Synchronized
    fun removeFormed(word: String, reading: String) {
        val m = formedByWord[word] ?: return
        if (m.remove(reading) == null) return
        formedPairs--
        if (m.isEmpty()) formedByWord.remove(word)
        pendingCounts.remove(pendingKey(reading, word))
        ripe.removeAll { it.chars.joinToString("") == word && it.readings.joinToString("") == reading }
        dirty = true
        version++
    }

    @Synchronized
    fun clear() {
        val had = !isEmpty()
        formedByWord.clear()
        formedPairs = 0
        pendingCounts.clear()
        followsByPrev.clear()
        chainRun.clear()
        ripe.clear()
        coveredRanges.clear()
        chainPos = 0L
        if (had) {
            dirty = true
            version++
        }
    }

    @Synchronized
    fun isEmpty(): Boolean = formedByWord.isEmpty() && pendingCounts.isEmpty() && followsByPrev.isEmpty()

    @Synchronized
    fun save(file: File) {
        val now = clock()
        var mutated = closeChain(now)
        if (sweep(now)) mutated = true
        if (mutated) version++
        val tmp = File(file.absoluteFile.parentFile, file.name + ".tmp")
        try {
            tmp.bufferedWriter().use { w ->
                w.write("$HEADER\n")
                for ((word, m) in formedByWord) {
                    for ((reading, u) in m) w.write("F\t$reading\t$word\t${u.count}\t${u.lastSeen}\n")
                }
                for ((key, u) in pendingCounts) w.write("P\t$key\t${u.count}\t${u.lastSeen}\n")
                for ((prev, m) in followsByPrev) {
                    for ((word, u) in m) w.write("C\t$prev\t$word\t${u.count}\t${u.lastSeen}\n")
                }
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
    fun load(file: File) {
        val parsed = try {
            parse(file)
        } catch (_: Exception) {
            Parsed()
        }
        formedByWord.clear()
        pendingCounts.clear()
        followsByPrev.clear()
        chainRun.clear()
        ripe.clear()
        coveredRanges.clear()
        chainPos = 0L
        formedByWord.putAll(parsed.formed)
        formedPairs = parsed.formed.values.sumOf { it.size }
        pendingCounts.putAll(parsed.pending)
        followsByPrev.putAll(parsed.follows)
        dirty = false
        version++
    }

    private fun extendChain(ch: String, reading: String, now: Long): Boolean {
        chainRun.addLast(ch to reading)
        if (chainRun.size > WINDOW_MAX) chainRun.removeFirst()
        chainPos++
        var changed = false
        val n = chainRun.size
        for (len in 2..n) {
            val chars = ArrayList<String>(len)
            val readings = ArrayList<String>(len)
            for (i in n - len until n) {
                val (c, r) = chainRun[i]
                chars.add(c)
                readings.add(r)
            }
            val word = chars.joinToString("")
            val joined = readings.joinToString("")
            val formedUsage = formedByWord[word]?.get(joined)
            if (formedUsage != null) {
                touch(formedUsage, now, FORMED_HALF_LIFE_MILLIS)
                coveredRanges.add((chainPos - len) to chainPos)
                changed = true
                continue
            }
            val key = pendingKey(joined, word)
            val u = pendingCounts[key]
            if (u == null) {
                pendingCounts[key] = Usage(1.0, now)
            } else {
                touch(u, now, PENDING_HALF_LIFE_MILLIS)
                if (u.count >= PROMOTE_AT) ripe.add(Window(chainPos - len, chainPos, chars, readings))
            }
            changed = true
        }
        if (ripe.size + coveredRanges.size >= RIPE_CAP) {
            if (closeChain(now)) changed = true
        }
        return changed
    }

    private fun closeChain(now: Long): Boolean {
        var changed = false
        if (ripe.isNotEmpty()) {
            val ordered = ripe.sortedWith(compareByDescending<Window> { it.chars.size }.thenBy { it.start })
            val kept = ArrayList<Window>()
            for (w in ordered) {
                if (coveredRanges.any { it.first <= w.start && w.end <= it.second }) continue
                if (kept.any { it.start <= w.start && w.end <= it.end }) continue
                kept.add(w)
            }
            val seen = HashSet<String>()
            for (w in kept) {
                if (seen.add(pendingKey(w.readings.joinToString(""), w.chars.joinToString("")))) {
                    promote(w, now)
                    changed = true
                }
            }
            for (w in ordered) {
                pendingCounts.remove(pendingKey(w.readings.joinToString(""), w.chars.joinToString("")))
            }
            ripe.clear()
        }
        chainRun.clear()
        coveredRanges.clear()
        chainPos = 0L
        return changed
    }

    private fun promote(w: Window, now: Long) {
        val word = w.chars.joinToString("")
        val reading = w.readings.joinToString("")
        val u = pendingCounts[pendingKey(reading, word)]
        val seed = (u?.let { decayed(it.count, it.lastSeen, now, PENDING_HALF_LIFE_MILLIS) } ?: PROMOTE_AT)
            .coerceIn(PROMOTE_AT, MAX_COUNT)
        for (len in 2 until w.chars.size) {
            for (start in 0..w.chars.size - len) {
                pendingCounts.remove(
                    pendingKey(
                        w.readings.subList(start, start + len).joinToString(""),
                        w.chars.subList(start, start + len).joinToString(""),
                    ),
                )
            }
        }
        val existing = formedByWord[word]?.get(reading)
        if (existing != null) {
            touch(existing, now, FORMED_HALF_LIFE_MILLIS)
            return
        }
        formedByWord.getOrPut(word) { HashMap() }[reading] = Usage(seed, now)
        formedPairs++
    }

    private fun recordFollow(prev: String, word: String, now: Long): Boolean {
        if (!isCollocatable(prev) || !isCollocatable(word)) return false
        val m = followsByPrev[prev]
        if (m == null) {
            followsByPrev[prev] = hashMapOf(word to Usage(1.0, now))
            return true
        }
        val u = m[word]
        if (u != null) {
            touch(u, now, FOLLOW_HALF_LIFE_MILLIS)
            return true
        }
        if (m.size >= FOLLOW_PER_PREV) {
            var worstWord: String? = null
            var worst = Double.MAX_VALUE
            for ((cw, cu) in m) {
                val eff = decayed(cu.count, cu.lastSeen, now, FOLLOW_HALF_LIFE_MILLIS)
                if (eff < worst) {
                    worst = eff
                    worstWord = cw
                }
            }
            if (worst >= FOLLOW_EVICT_FLOOR) return false
            m.remove(worstWord)
        }
        m[word] = Usage(1.0, now)
        return true
    }

    private fun sweep(now: Long): Boolean {
        var removed = false
        val fw = formedByWord.iterator()
        while (fw.hasNext()) {
            val entry = fw.next()
            val inner = entry.value.iterator()
            while (inner.hasNext()) {
                val u = inner.next().value
                if (decayed(u.count, u.lastSeen, now, FORMED_HALF_LIFE_MILLIS) < PRUNE_FLOOR) {
                    inner.remove()
                    formedPairs--
                    removed = true
                }
            }
            if (entry.value.isEmpty()) fw.remove()
        }
        val pd = pendingCounts.iterator()
        while (pd.hasNext()) {
            val u = pd.next().value
            if (decayed(u.count, u.lastSeen, now, PENDING_HALF_LIFE_MILLIS) < PRUNE_FLOOR) {
                pd.remove()
                removed = true
            }
        }
        val fp = followsByPrev.iterator()
        while (fp.hasNext()) {
            val entry = fp.next()
            val inner = entry.value.iterator()
            while (inner.hasNext()) {
                val u = inner.next().value
                if (decayed(u.count, u.lastSeen, now, FOLLOW_HALF_LIFE_MILLIS) < PRUNE_FLOOR) {
                    inner.remove()
                    removed = true
                }
            }
            if (entry.value.isEmpty()) fp.remove()
        }
        return removed
    }

    private class Parsed(
        val formed: HashMap<String, HashMap<String, Usage>> = HashMap(),
        val pending: HashMap<String, Usage> = HashMap(),
        val follows: HashMap<String, HashMap<String, Usage>> = HashMap(),
    )

    internal companion object {
        internal const val WINDOW_MAX = 4
        internal const val PROMOTE_AT = 2.5
        internal const val FOLLOW_PER_PREV = 8
        internal const val PENDING_HALF_LIFE_MILLIS = 14L * 24L * 60L * 60L * 1000L
        internal const val FORMED_HALF_LIFE_MILLIS = 30L * 24L * 60L * 60L * 1000L
        internal const val FOLLOW_HALF_LIFE_MILLIS = 14L * 24L * 60L * 60L * 1000L
        private const val HEADER = "aegis-userlearn 1"
        private const val MAX_LINE_LENGTH = 4_096
        private const val MAX_COLLOC_LEN = 4
        private const val MAX_FORMED_READING = 24
        private const val MAX_COUNT = 1.0e12
        private const val RIPE_CAP = 64
        private const val MIN_ACTIVE = 0.25
        private const val PRUNE_FLOOR = 0.0625
        private const val FOLLOW_EVICT_FLOOR = 1.0
        private const val FOLLOW_WEIGHT = 2.5
        private const val BOOST_WEIGHT = 3.5
        private const val RECENCY_WEIGHT = 2.0
        private const val RECENCY_HALF_LIFE_MILLIS = 7L * 24L * 60L * 60L * 1000L
        private val LN_2 = ln(2.0)

        private fun pendingKey(reading: String, word: String): String = reading + "\t" + word

        private fun decayed(count: Double, lastSeen: Long, now: Long, halfLifeMillis: Long): Double {
            val age = (now - lastSeen).coerceAtLeast(0L)
            if (age == 0L) return count
            return count * exp(-LN_2 * age.toDouble() / halfLifeMillis)
        }

        private fun touch(u: Usage, now: Long, halfLifeMillis: Long) {
            u.count = (decayed(u.count, u.lastSeen, now, halfLifeMillis) + 1.0).coerceAtMost(MAX_COUNT)
            u.lastSeen = now
        }

        private fun allHan(word: String): Boolean {
            if (word.isEmpty()) return false
            var i = 0
            while (i < word.length) {
                val cp = word.codePointAt(i)
                if (!Character.isIdeographic(cp)) return false
                i += Character.charCount(cp)
            }
            return true
        }

        private fun isSingleHan(word: String): Boolean =
            word.isNotEmpty() && word.codePointCount(0, word.length) == 1 && allHan(word)

        private fun isFormableWord(word: String): Boolean =
            allHan(word) && word.codePointCount(0, word.length) in 2..WINDOW_MAX

        private fun isCollocatable(word: String): Boolean =
            allHan(word) && word.codePointCount(0, word.length) in 1..MAX_COLLOC_LEN

        private fun isFormedReading(reading: String): Boolean =
            reading.length in 2..MAX_FORMED_READING && reading.all { it in 'a'..'z' }

        private fun sanitizeReading(reading: String): String {
            val sb = StringBuilder(reading.length)
            for (ch in reading.lowercase()) if (ch in 'a'..'z') sb.append(ch)
            return sb.toString()
        }

        private fun parse(file: File): Parsed {
            if (!file.exists() || file.length() == 0L) return Parsed()
            val parsed = Parsed()
            file.bufferedReader().use { reader ->
                require(reader.readLine() == HEADER) { "unsupported userlearn header" }
                while (true) {
                    val line = reader.readLine() ?: break
                    require(line.length <= MAX_LINE_LENGTH) { "userlearn line is too long" }
                    val p = line.split('\t')
                    require(p.size == 5) { "invalid userlearn row" }
                    val count = p[3].toDoubleOrNull()
                    val seen = p[4].toLongOrNull()
                    require(
                        count != null && count.isFinite() && count > 0.0 && count <= MAX_COUNT &&
                            seen != null && seen >= 0L,
                    ) { "invalid userlearn values" }
                    when (p[0]) {
                        "F" -> {
                            require(isFormedReading(p[1]) && isFormableWord(p[2])) { "invalid userlearn formed row" }
                            val m = parsed.formed.getOrPut(p[2]) { HashMap() }
                            require(m.put(p[1], Usage(count, seen)) == null) { "duplicate userlearn formed row" }
                        }
                        "P" -> {
                            require(isFormedReading(p[1]) && isFormableWord(p[2])) { "invalid userlearn pending row" }
                            require(parsed.pending.put(pendingKey(p[1], p[2]), Usage(count, seen)) == null) {
                                "duplicate userlearn pending row"
                            }
                        }
                        "C" -> {
                            require(isCollocatable(p[1]) && isCollocatable(p[2])) { "invalid userlearn follow row" }
                            val m = parsed.follows.getOrPut(p[1]) { HashMap() }
                            require(m.put(p[2], Usage(count, seen)) == null) { "duplicate userlearn follow row" }
                        }
                        else -> throw IllegalArgumentException("invalid userlearn row")
                    }
                }
            }
            return parsed
        }
    }
}
