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

class UserLearning internal constructor(
    private val clock: () -> Long = System::currentTimeMillis,
    private val database: UserDataDatabase? = null,
) {
    internal data class RankedFollow(val word: String, val rankKey: Double)

    constructor(clock: () -> Long = System::currentTimeMillis) : this(clock, null)

    private class Usage(var count: Double, var lastSeen: Long)

    private class Window(val start: Long, val end: Long, val chars: List<String>, val readings: List<String>)

    private val formedByWord = HashMap<String, HashMap<String, Usage>>()
    private var formedPairs = 0
    private val pendingCounts = HashMap<String, Usage>()
    private val followsByPrev = HashMap<String, HashMap<String, Usage>>()
    private val formedWeightCache = BoundedLruCache<String, Double>(RUNTIME_CACHE_SIZE)
    private val followBoostCache = BoundedLruCache<Pair<String, String>, Double>(RUNTIME_CACHE_SIZE)
    private var maximumFollowVersion = Long.MIN_VALUE
    private var maximumFollow = 0.0
    private var maximumFollowContextVersion = Long.MIN_VALUE
    private var maximumFollowContext = 0
    private var maximumFormedVersion = Long.MIN_VALUE
    private var maximumFormed = 0.0

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
    var lastFailure: String? = null
        private set

    @Synchronized
    fun observeCommit(prevWord: String?, word: String, reading: String, now: Long) {
        if (database != null) {
            observeCommitDatabase(prevWord, word, reading, now)
            return
        }
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
            finishMutation()
        }
    }

    @Synchronized
    fun observeBreak() {
        if (database != null) {
            mutateDatabase { closeDatabaseChain(clock()) }
            return
        }
        if (closeChain(clock())) {
            finishMutation()
        }
    }

    @Synchronized
    fun readingSnapshot(): Map<String, List<String>> {
        database?.let { backing ->
            val now = clock()
            val byReading = LinkedHashMap<String, MutableList<Pair<String, Double>>>()
            var offset = 0
            while (true) {
                val page = backing.readFormedEntries(offset, RUNTIME_PAGE_SIZE)
                for (entry in page) {
                    val effective = decayed(entry.usage.count, entry.usage.lastSeen, now, FORMED_HALF_LIFE_MILLIS)
                    byReading.getOrPut(entry.reading) { ArrayList() }.add(entry.word to effective)
                }
                if (page.size < RUNTIME_PAGE_SIZE) break
                offset += page.size
            }
            return byReading.mapValues { (_, entries) ->
                entries.sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })
                    .map { it.first }
            }
        }
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
        if (key.isEmpty()) return emptyList()
        database?.let {
            return formedWordsForPage(key, 0, RUNTIME_PAGE_SIZE)
        }
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
    internal fun formedWordsForPage(key: String, offset: Int, limit: Int): List<String> {
        require(offset >= 0)
        require(limit in 0..RUNTIME_PAGE_SIZE)
        if (key.isEmpty() || limit == 0) return emptyList()
        val backing = database
        if (backing == null) return formedWordsFor(key).drop(offset).take(limit)
        return backing.readFormedWordsForKey(key, key[0] in '2'..'9', offset, limit)
            .map { it.word }
            .distinct()
    }

    @Synchronized
    internal fun formedWordsForPageSnapshot(
        key: String,
        offset: Int,
        limit: Int,
        expectedVersion: Long? = null,
    ): PersistedPage<String> {
        require(offset >= 0)
        require(limit in 0..RUNTIME_PAGE_SIZE)
        if (key.isEmpty() || limit == 0) {
            val current = database?.dataVersion() ?: version
            return if (expectedVersion != null && expectedVersion != current) {
                PersistedPage(emptyList(), current, restartRequired = true)
            } else {
                PersistedPage(emptyList(), current)
            }
        }
        database?.let { backing ->
            return backing.readFormedWordsForKeyPage(key, key[0] in '2'..'9', offset, limit, expectedVersion)
                .map { it.word }
        }
        val current = version
        if (expectedVersion != null && expectedVersion != current) {
            return PersistedPage(emptyList(), current, restartRequired = true)
        }
        return PersistedPage(formedWordsForPage(key, offset, limit), current)
    }

    @Synchronized
    fun formedWeight(word: String): Double {
        database?.let { backing ->
            formedWeightCache[word]?.let { return it }
            val usage = backing.readBestFormedUsage(word)
            val weight = if (usage == null) 0.0 else formedWeight(usage)
            return weight.also { formedWeightCache.put(word, it) }
        }
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

    private fun formedWeight(usage: StoredUsage): Double {
        val now = clock()
        val effective = decayed(usage.count, usage.lastSeen, now, FORMED_HALF_LIFE_MILLIS)
        if (effective < MIN_ACTIVE) return 0.0
        val age = (now - usage.lastSeen).coerceAtLeast(0L)
        return BOOST_WEIGHT * ln(1.0 + effective) +
            RECENCY_WEIGHT * exp(-LN_2 * age.toDouble() / RECENCY_HALF_LIFE_MILLIS)
    }

    @Synchronized
    internal fun rankingBoosts(contextTail: String, words: Collection<String>): Map<String, Double> {
        val unique = words.asSequence().filter { it.isNotEmpty() }.distinct().toList()
        require(unique.size < UserDataDatabase.MAX_RUNTIME_PAGE_SIZE)
        if (unique.isEmpty()) return emptyMap()
        val backing = database ?: return unique.associateWith { formedWeight(it) + followBoost(contextTail, it) }

        val formed = LinkedHashMap<String, Double>(unique.size)
        val missingFormed = ArrayList<String>()
        for (word in unique) {
            val cached = formedWeightCache[word]
            if (cached == null) missingFormed.add(word) else formed[word] = cached
        }
        if (missingFormed.isNotEmpty()) {
            runCatching {
                val stored = backing.readBestFormedUsages(missingFormed)
                for (word in missingFormed) {
                    val weight = stored[word]?.let(::formedWeight) ?: 0.0
                    formedWeightCache.put(word, weight)
                    formed[word] = weight
                }
            }.onFailure {
                lastFailure = it.javaClass.simpleName + ": " + it.message.orEmpty()
                for (word in missingFormed) formed[word] = 0.0
            }
        }

        val follows = LinkedHashMap<String, Double>(unique.size)
        if (contextTail.isEmpty()) {
            for (word in unique) follows[word] = 0.0
        } else {
            val missingFollows = ArrayList<String>()
            for (word in unique) {
                val cached = followBoostCache[contextTail to word]
                if (cached == null) missingFollows.add(word) else follows[word] = cached
            }
            if (missingFollows.isNotEmpty()) {
                runCatching {
                    val suffixes = ArrayList<String>()
                    var start = contextTail.length
                    while (start > 0) {
                        val cp = contextTail.codePointBefore(start)
                        if (!Character.isIdeographic(cp)) break
                        start -= Character.charCount(cp)
                        suffixes.add(contextTail.substring(start))
                    }
                    val now = clock()
                    val best = HashMap<String, Double>()
                    backing.forEachFollowUsage(suffixes, missingFollows) { word, usage ->
                        val effective = decayed(usage.count, usage.lastSeen, now, FOLLOW_HALF_LIFE_MILLIS)
                        if (effective > (best[word] ?: 0.0)) best[word] = effective
                    }
                    for (word in missingFollows) {
                        val effective = best[word] ?: 0.0
                        val boost = if (effective >= MIN_ACTIVE) FOLLOW_WEIGHT * ln(1.0 + effective) else 0.0
                        followBoostCache.put(contextTail to word, boost)
                        follows[word] = boost
                    }
                }.onFailure {
                    lastFailure = it.javaClass.simpleName + ": " + it.message.orEmpty()
                    for (word in missingFollows) follows[word] = 0.0
                }
            }
        }

        return unique.associateWith { (formed[it] ?: 0.0) + (follows[it] ?: 0.0) }
    }

    @Synchronized
    fun follows(prevWord: String): List<Pair<String, Double>> {
        database?.let { backing ->
            val now = clock()
            return backing.readFollows(prevWord, 0, RUNTIME_PAGE_SIZE).mapNotNull { (word, usage) ->
                val effective = decayed(usage.count, usage.lastSeen, now, FOLLOW_HALF_LIFE_MILLIS)
                if (effective >= MIN_ACTIVE) word to effective else null
            }.sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })
        }
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
    internal fun activeFollowWords(
        previousWord: String,
        words: Collection<String>,
        rankingNow: Long,
    ): Set<String> {
        val unique = words.asSequence().filter { it.isNotEmpty() }.distinct().toList()
        require(unique.size < UserDataDatabase.MAX_RUNTIME_PAGE_SIZE)
        if (unique.isEmpty()) return emptySet()
        database?.let { backing ->
            val active = HashSet<String>()
            backing.forEachFollowUsage(listOf(previousWord), unique) { word, usage ->
                if (decayed(usage.count, usage.lastSeen, rankingNow, FOLLOW_HALF_LIFE_MILLIS) >= MIN_ACTIVE) {
                    active.add(word)
                }
            }
            return active
        }
        val follows = followsByPrev[previousWord].orEmpty()
        return unique.filterTo(HashSet()) { word ->
            val usage = follows[word] ?: return@filterTo false
            decayed(usage.count, usage.lastSeen, rankingNow, FOLLOW_HALF_LIFE_MILLIS) >= MIN_ACTIVE
        }
    }

    @Synchronized
    internal fun followsPageSnapshot(
        previousWord: String,
        offset: Int,
        limit: Int,
        expectedVersion: Long? = null,
    ): PersistedPage<Pair<String, Double>> {
        require(offset >= 0)
        require(limit in 0..RUNTIME_PAGE_SIZE)
        val backing = database
        if (backing != null) {
            val now = clock()
            val page = backing.readFollowsPage(previousWord, offset, limit, expectedVersion)
            val items = page.items.mapNotNull { (word, usage) ->
                val effective = decayed(usage.count, usage.lastSeen, now, FOLLOW_HALF_LIFE_MILLIS)
                if (effective >= MIN_ACTIVE) word to effective else null
            }.sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })
            return PersistedPage(items, page.version, page.totalCount, page.restartRequired)
        }
        val current = version
        if (expectedVersion != null && expectedVersion != current) {
            return PersistedPage(emptyList(), current, restartRequired = true)
        }
        val items = follows(previousWord)
        return PersistedPage(items.drop(offset).take(limit), current, items.size.toLong())
    }

    @Synchronized
    internal fun rankedFollowsPageSnapshot(
        previousWord: String,
        after: RankedFollow?,
        limit: Int,
        rankingNow: Long,
        expectedVersion: Long? = null,
    ): PersistedPage<RankedFollow> {
        require(limit in 0..RUNTIME_PAGE_SIZE)
        val order = compareByDescending<RankedFollow> { it.rankKey }.thenBy { it.word }
        fun followsAfter(candidate: RankedFollow): Boolean = after == null || order.compare(candidate, after) > 0
        val worstFirst = java.util.PriorityQueue(order.reversed())
        fun offer(rows: Sequence<Pair<String, StoredUsage>>, now: Long) {
            if (limit == 0) return
            for ((word, usage) in rows) {
                if (decayed(usage.count, usage.lastSeen, now, FOLLOW_HALF_LIFE_MILLIS) < MIN_ACTIVE) continue
                val candidate = RankedFollow(word, followRankKey(usage))
                if (!followsAfter(candidate)) continue
                if (worstFirst.size < limit) {
                    worstFirst.add(candidate)
                } else if (order.compare(candidate, worstFirst.peek()) < 0) {
                    worstFirst.remove()
                    worstFirst.add(candidate)
                }
            }
        }
        fun selectedPage(): List<RankedFollow> = worstFirst.toList().sortedWith(order)

        database?.let { backing ->
            var offset = 0
            var versionToken = expectedVersion
            while (true) {
                val page = backing.readFollowsPage(
                    previousWord,
                    offset,
                    RUNTIME_PAGE_SIZE,
                    versionToken,
                )
                if (page.restartRequired) {
                    return PersistedPage(emptyList(), page.version, restartRequired = true)
                }
                versionToken = page.version
                offer(page.items.asSequence(), rankingNow)
                val total = page.totalCount ?: (offset + page.items.size).toLong()
                if (offset.toLong() + RUNTIME_PAGE_SIZE >= total || offset > Int.MAX_VALUE - RUNTIME_PAGE_SIZE) break
                offset += RUNTIME_PAGE_SIZE
            }
            val verification = backing.readFollowsPage(previousWord, 0, 0, versionToken)
            if (verification.restartRequired) {
                return PersistedPage(emptyList(), verification.version, restartRequired = true)
            }
            return PersistedPage(selectedPage(), verification.version)
        }
        val current = version
        if (expectedVersion != null && expectedVersion != current) {
            return PersistedPage(emptyList(), current, restartRequired = true)
        }
        val rows = followsByPrev[previousWord].orEmpty().asSequence().map { (word, usage) ->
            word to StoredUsage(usage.count, usage.lastSeen)
        }
        offer(rows, rankingNow)
        return PersistedPage(selectedPage(), current)
    }

    internal fun rankingNow(): Long = clock()

    @Synchronized
    internal fun maximumFollowBoost(): Double {
        if (maximumFollowVersion == version) return maximumFollow
        database?.let { backing ->
            val maximum = backing.maximumFollowCount()
            maximumFollow = if (maximum >= MIN_ACTIVE) FOLLOW_WEIGHT * ln(1.0 + maximum) else 0.0
            maximumFollowVersion = version
            return maximumFollow
        }
        var maximum = 0.0
        for (words in followsByPrev.values) for (usage in words.values) {
            maximum = maxOf(maximum, usage.count)
        }
        maximumFollow = if (maximum >= MIN_ACTIVE) FOLLOW_WEIGHT * ln(1.0 + maximum) else 0.0
        maximumFollowVersion = version
        return maximumFollow
    }

    @Synchronized
    internal fun maximumFollowContextCodePoints(): Int {
        if (maximumFollowContextVersion == version) return maximumFollowContext
        database?.let {
            maximumFollowContext = it.maximumFollowContextCodePoints()
            maximumFollowContextVersion = version
            return maximumFollowContext
        }
        var maximum = 0
        for (context in followsByPrev.keys) {
            maximum = maxOf(maximum, context.codePointCount(0, context.length))
        }
        maximumFollowContext = maximum
        maximumFollowContextVersion = version
        return maximum
    }

    @Synchronized
    internal fun maximumFormedBoost(): Double {
        if (maximumFormedVersion == version) return maximumFormed
        database?.let { backing ->
            val maximum = backing.maximumFormedCount()
            maximumFormed = if (maximum >= MIN_ACTIVE) {
                BOOST_WEIGHT * ln(1.0 + maximum) + RECENCY_WEIGHT
            } else {
                0.0
            }
            maximumFormedVersion = version
            return maximumFormed
        }
        var maximum = 0.0
        for (readings in formedByWord.values) for (usage in readings.values) {
            maximum = maxOf(maximum, usage.count)
        }
        maximumFormed = if (maximum >= MIN_ACTIVE) {
            BOOST_WEIGHT * ln(1.0 + maximum) + RECENCY_WEIGHT
        } else {
            0.0
        }
        maximumFormedVersion = version
        return maximumFormed
    }

    @Synchronized
    fun followBoost(prevContext: String, word: String): Double {
        if (prevContext.isEmpty() || word.isEmpty()) return 0.0
        database?.let { backing ->
            val cacheKey = prevContext to word
            followBoostCache[cacheKey]?.let { return it }
            val now = clock()
            var best = 0.0
            var start = prevContext.length
            while (start > 0) {
                val cp = prevContext.codePointBefore(start)
                if (!Character.isIdeographic(cp)) break
                start -= Character.charCount(cp)
                val usage = backing.readFollowUsage(prevContext.substring(start), word) ?: continue
                best = maxOf(best, decayed(usage.count, usage.lastSeen, now, FOLLOW_HALF_LIFE_MILLIS))
            }
            val boost = if (best >= MIN_ACTIVE) FOLLOW_WEIGHT * ln(1.0 + best) else 0.0
            followBoostCache.put(cacheKey, boost)
            return boost
        }
        val now = clock()
        var best = 0.0
        var start = prevContext.length
        var chars = 0
        while (start > 0) {
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
        database?.let { backing ->
            mutateDatabase { backing.removeLearningWord(word) }
            formedWeightCache.remove(word)
            followBoostCache.clear()
            ripe.removeAll { it.chars.joinToString("") == word }
            return
        }
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
            finishMutation()
        }
    }

    @Synchronized
    fun isEmpty(): Boolean = database?.learningIsEmpty() ?:
        (formedByWord.isEmpty() && pendingCounts.isEmpty() && followsByPrev.isEmpty())

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
        } catch (e: Exception) {
            lastFailure = e.javaClass.simpleName + ": " + e.message.orEmpty()
            return
        }
        database?.replaceLearning(parsed.toStored())
        clearRuntimeState()
        if (database == null) {
            formedByWord.putAll(parsed.formed)
            formedPairs = parsed.formed.values.sumOf { it.size }
            pendingCounts.putAll(parsed.pending)
            followsByPrev.putAll(parsed.follows)
        }
        dirty = false
        lastFailure = null
        version++
    }

    private fun observeCommitDatabase(prevWord: String?, word: String, reading: String, now: Long) {
        mutateDatabase {
            val timestamp = now.coerceAtLeast(0L)
            var changed = if (prevWord == null) closeDatabaseChain(timestamp) else
                recordFollowDatabase(prevWord, word, timestamp)
            val normalized = sanitizeReading(reading)
            changed = if (isSingleHan(word) && normalized in T9Pinyin.SYLLABLES) {
                extendDatabaseChain(word, normalized, timestamp) || changed
            } else {
                closeDatabaseChain(timestamp) || changed
            }
            changed
        }
    }

    private fun mutateDatabase(block: () -> Boolean) {
        try {
            val changed = block()
            if (changed) {
                formedWeightCache.clear()
                followBoostCache.clear()
                version++
            }
            dirty = false
            lastFailure = null
        } catch (failure: Exception) {
            formedWeightCache.clear()
            followBoostCache.clear()
            version++
            lastFailure = failure.javaClass.simpleName + ": " + failure.message.orEmpty()
        }
    }

    private fun recordFollowDatabase(previousWord: String, word: String, now: Long): Boolean {
        if (!isCollocatable(previousWord) || !isCollocatable(word)) return false
        val backing = checkNotNull(database)
        val current = backing.readFollowUsage(previousWord, word)
        backing.upsertFollowUsage(previousWord, word, touched(current, now, FOLLOW_HALF_LIFE_MILLIS))
        return true
    }

    private fun extendDatabaseChain(character: String, reading: String, now: Long): Boolean {
        val backing = checkNotNull(database)
        chainRun.addLast(character to reading)
        chainPos++
        val size = chainRun.size
        for (length in 2..size) {
            val characters = ArrayList<String>(length)
            val readings = ArrayList<String>(length)
            for (index in size - length until size) {
                val (itemCharacter, itemReading) = chainRun[index]
                characters.add(itemCharacter)
                readings.add(itemReading)
            }
            val word = characters.joinToString("")
            val joinedReading = readings.joinToString("")
            val formed = backing.readFormedUsage(word, joinedReading)
            if (formed != null) {
                backing.upsertFormedUsage(word, joinedReading, touched(formed, now, FORMED_HALF_LIFE_MILLIS))
                coveredRanges.add((chainPos - length) to chainPos)
                continue
            }
            val pending = touched(
                backing.readPendingUsage(joinedReading, word),
                now,
                PENDING_HALF_LIFE_MILLIS,
            )
            backing.upsertPendingUsage(joinedReading, word, pending)
            if (pending.count >= PROMOTE_AT) {
                ripe.add(Window(chainPos - length, chainPos, characters, readings))
            }
        }
        return size >= 2
    }

    private fun closeDatabaseChain(now: Long): Boolean {
        if (ripe.isEmpty()) {
            val changed = chainRun.size >= 2
            chainRun.clear()
            coveredRanges.clear()
            chainPos = 0L
            return changed
        }
        val backing = checkNotNull(database)
        val ordered = ripe.sortedWith(compareByDescending<Window> { it.chars.size }.thenBy { it.start })
        val kept = ArrayList<Window>()
        for (window in ordered) {
            if (coveredRanges.any { it.first <= window.start && window.end <= it.second }) continue
            if (kept.any { it.start <= window.start && window.end <= it.end }) continue
            kept.add(window)
        }
        val seen = HashSet<String>()
        for (window in kept) {
            val reading = window.readings.joinToString("")
            val word = window.chars.joinToString("")
            if (!seen.add(pendingKey(reading, word))) continue
            val pending = backing.readPendingUsage(reading, word)
            val seed = (pending?.let {
                decayed(it.count, it.lastSeen, now, PENDING_HALF_LIFE_MILLIS)
            } ?: PROMOTE_AT).coerceIn(PROMOTE_AT, MAX_COUNT)
            val existing = backing.readFormedUsage(word, reading)
            val promoted = if (existing == null) StoredUsage(seed, now) else
                touched(existing, now, FORMED_HALF_LIFE_MILLIS)
            val delete = LinkedHashSet<Pair<String, String>>()
            delete.add(reading to word)
            for (length in 2 until window.chars.size) {
                for (start in 0..window.chars.size - length) {
                    delete.add(
                        window.readings.subList(start, start + length).joinToString("") to
                            window.chars.subList(start, start + length).joinToString(""),
                    )
                }
            }
            backing.promoteLearning(word, reading, promoted, delete)
        }
        backing.deletePendingLearning(ordered.map {
            it.readings.joinToString("") to it.chars.joinToString("")
        })
        ripe.clear()
        chainRun.clear()
        coveredRanges.clear()
        chainPos = 0L
        return true
    }

    private fun touched(current: StoredUsage?, now: Long, halfLifeMillis: Long): StoredUsage {
        val next = if (current == null) 1.0 else
            (decayed(current.count, current.lastSeen, now, halfLifeMillis) + 1.0).coerceAtMost(MAX_COUNT)
        return StoredUsage(next, now)
    }

    private fun extendChain(ch: String, reading: String, now: Long): Boolean {
        chainRun.addLast(ch to reading)
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
        internal const val PROMOTE_AT = 2.5
        internal const val PENDING_HALF_LIFE_MILLIS = 14L * 24L * 60L * 60L * 1000L
        internal const val FORMED_HALF_LIFE_MILLIS = 30L * 24L * 60L * 60L * 1000L
        internal const val FOLLOW_HALF_LIFE_MILLIS = 14L * 24L * 60L * 60L * 1000L
        private const val HEADER = "aegis-userlearn 1"
        private const val MAX_COUNT = 1.0e12
        private const val MIN_ACTIVE = 0.25
        private const val PRUNE_FLOOR = 0.0625
        private const val FOLLOW_WEIGHT = 2.5
        private const val BOOST_WEIGHT = 3.5
        private const val RECENCY_WEIGHT = 2.0
        private const val RECENCY_HALF_LIFE_MILLIS = 7L * 24L * 60L * 60L * 1000L
        private val LN_2 = ln(2.0)
        internal const val RUNTIME_PAGE_SIZE = 128
        private const val RUNTIME_CACHE_SIZE = 256

        private fun pendingKey(reading: String, word: String): String = reading + "\t" + word

        private fun decayed(count: Double, lastSeen: Long, now: Long, halfLifeMillis: Long): Double {
            val age = (now - lastSeen).coerceAtLeast(0L)
            if (age == 0L) return count
            return count * exp(-LN_2 * age.toDouble() / halfLifeMillis)
        }

        private fun followRankKey(usage: StoredUsage): Double =
            ln(usage.count) + LN_2 * usage.lastSeen.toDouble() / FOLLOW_HALF_LIFE_MILLIS

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
            allHan(word) && word.codePointCount(0, word.length) >= 2

        private fun isCollocatable(word: String): Boolean =
            allHan(word)

        private fun isFormedReading(reading: String): Boolean =
            reading.length >= 2 && reading.all { it in 'a'..'z' }

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

    @Synchronized
    internal fun storageSnapshot(): UserLearningSnapshot = database?.readLearning() ?: UserLearningSnapshot(
        formed = formedByWord.mapValues { (_, readings) ->
            readings.mapValues { (_, value) -> StoredUsage(value.count, value.lastSeen) }
        },
        pending = pendingCounts.map { (key, value) ->
            val split = key.indexOf('\t')
            (key.substring(0, split) to key.substring(split + 1)) to StoredUsage(value.count, value.lastSeen)
        }.toMap(LinkedHashMap()),
        follows = followsByPrev.mapValues { (_, words) ->
            words.mapValues { (_, value) -> StoredUsage(value.count, value.lastSeen) }
        },
    )

    private fun Parsed.toStored(): UserLearningSnapshot = UserLearningSnapshot(
        formed = formed.mapValues { (_, readings) ->
            readings.mapValues { (_, value) -> StoredUsage(value.count, value.lastSeen) }
        },
        pending = pending.map { (key, value) ->
            val split = key.indexOf('\t')
            (key.substring(0, split) to key.substring(split + 1)) to StoredUsage(value.count, value.lastSeen)
        }.toMap(LinkedHashMap()),
        follows = follows.mapValues { (_, words) ->
            words.mapValues { (_, value) -> StoredUsage(value.count, value.lastSeen) }
        },
    )

    private fun applyStored(snapshot: UserLearningSnapshot) {
        formedByWord.clear()
        pendingCounts.clear()
        followsByPrev.clear()
        chainRun.clear()
        ripe.clear()
        coveredRanges.clear()
        chainPos = 0L
        for ((word, readings) in snapshot.formed) {
            formedByWord[word] = HashMap(readings.mapValues { (_, value) -> Usage(value.count, value.lastSeen) })
        }
        formedPairs = formedByWord.values.sumOf { it.size }
        for ((key, value) in snapshot.pending) {
            pendingCounts[pendingKey(key.first, key.second)] = Usage(value.count, value.lastSeen)
        }
        for ((previous, words) in snapshot.follows) {
            followsByPrev[previous] = HashMap(words.mapValues { (_, value) -> Usage(value.count, value.lastSeen) })
        }
        dirty = false
    }

    private fun finishMutation() {
        dirty = true
        version++
    }

    @Synchronized
    internal fun reloadFromStorage() {
        clearRuntimeState()
        version++
    }

    private fun clearRuntimeState() {
        formedByWord.clear()
        pendingCounts.clear()
        followsByPrev.clear()
        chainRun.clear()
        ripe.clear()
        coveredRanges.clear()
        chainPos = 0L
        formedPairs = 0
        formedWeightCache.clear()
        followBoostCache.clear()
        maximumFollowVersion = Long.MIN_VALUE
        maximumFollowContextVersion = Long.MIN_VALUE
        maximumFormedVersion = Long.MIN_VALUE
        dirty = false
    }

    internal val isDatabaseBacked: Boolean
        get() = database != null

    internal val databaseIdentity: Any?
        get() = database

    internal fun runtimeCacheSizesForTest(): Pair<Int, Int> =
        formedWeightCache.sizeForTest() to followBoostCache.sizeForTest()
}
