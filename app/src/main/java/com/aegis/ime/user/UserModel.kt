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
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.exp
import kotlin.math.ln

class UserModel internal constructor(
    private val clock: () -> Long = System::currentTimeMillis,
    private val database: UserDataDatabase? = null,
) {
    internal data class RankedSuccessor(val word: String, val score: Double)

    constructor(clock: () -> Long = System::currentTimeMillis) : this(clock, null)
    private val count = HashMap<String, Int>()
    private val lastUsed = HashMap<String, Long>()
    private val bigram = HashMap<String, HashMap<String, Int>>()
    private val readings = HashMap<String, LinkedHashSet<String>>()
    private val wordBoostCache = BoundedLruCache<String, Double>(RUNTIME_CACHE_SIZE)
    private val readingCache = BoundedLruCache<String, List<String>>(RUNTIME_CACHE_SIZE)
    private var maximumBoostVersion = Long.MIN_VALUE
    private var maximumBoost = 0.0

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
    fun record(prevWord: String?, word: String, now: Long): Boolean {
        if (!isValidWord(word)) return false
        if (!persist { database?.recordWord(word, null, prevWord?.takeIf(::isValidWord), now, true) }) return false
        if (database != null) {
            cacheStoredWord(word, now)
            dirty = false
            version++
            return true
        }
        count[word] = saturatingAdd(count[word] ?: 0, 1)
        lastUsed[word] = now.coerceAtLeast(0L)
        if (!prevWord.isNullOrEmpty() && isValidWord(prevWord)) {
            val m = bigram.getOrPut(prevWord) { HashMap() }
            m[word] = saturatingAdd(m[word] ?: 0, 1)
        }
        dirty = true
        version++
        return true
    }

    @Synchronized
    fun recordWord(reading: String, word: String, now: Long, incrementCount: Boolean): Boolean {
        val r = sanitizeReading(reading)
        if (!isValidWord(word) || r.isEmpty()) return false
        if (!persist { database?.recordWord(word, r, null, now, incrementCount) }) return false
        if (database != null) {
            cacheReading(r, word, remove = false)
            cacheStoredWord(word, now)
            dirty = false
            version++
            return true
        }
        readings.getOrPut(r) { LinkedHashSet() }.add(word)
        if (incrementCount) {
            count[word] = saturatingAdd(count[word] ?: 0, 1)
        } else if (word !in count) {
            count[word] = 1
        }
        lastUsed[word] = now.coerceAtLeast(0L)
        dirty = true
        version++
        return true
    }

    @Synchronized
    fun addManualWord(reading: String, word: String, now: Long): Boolean {
        val w = word.trim()
        if (!isValidWord(w)) return false
        val r = sanitizeReading(reading)
        if (!persist { database?.recordWord(w, r.ifEmpty { null }, null, now, true) }) return false
        if (database != null) {
            if (r.isNotEmpty()) cacheReading(r, w, remove = false)
            cacheStoredWord(w, now)
            dirty = false
            version++
            return true
        }
        if (r.isNotEmpty()) readings.getOrPut(r) { LinkedHashSet() }.add(w)
        count[w] = saturatingAdd(count[w] ?: 0, 1)
        lastUsed[w] = now.coerceAtLeast(0L)
        dirty = true
        version++
        return true
    }

    @Synchronized
    fun removeWord(reading: String, word: String): Boolean {
        val r = sanitizeReading(reading)
        if (database != null) {
            if (!database.hasUserReading(r, word)) return false
            if (!persist { database.removeReading(r, word) }) return false
            wordBoostCache.remove(word)
            cacheReading(r, word, remove = true)
            dirty = false
            version++
            return true
        }
        val set = readings[r] ?: return false
        if (word !in set) return false
        if (!persist { database?.removeReading(r, word) }) return false
        set.remove(word)
        if (set.isEmpty()) readings.remove(r)
        if (readings.values.none { word in it }) {
            count.remove(word)
            lastUsed.remove(word)
            bigram.remove(word)
            for (m in bigram.values) m.remove(word)
        }
        dirty = true
        version++
        return true
    }

    @Synchronized
    fun removeWord(word: String): Boolean {
        if (word.isEmpty()) return false
        if (database != null) {
            if (database.readStoredWord(word) == null) return false
            if (!persist { database.removeWord(word) }) return false
            wordBoostCache.remove(word)
            for ((reading, words) in readingCache.snapshot()) {
                if (word in words) cacheReading(reading, word, remove = true)
            }
            dirty = false
            version++
            return true
        }
        if (word !in count && readings.values.none { word in it }) return false
        if (!persist { database?.removeWord(word) }) return false
        var changed = false
        if (count.remove(word) != null) changed = true
        if (lastUsed.remove(word) != null) changed = true
        val emptyReadings = ArrayList<String>()
        for ((r, ws) in readings) if (ws.remove(word)) { changed = true; if (ws.isEmpty()) emptyReadings.add(r) }
        for (r in emptyReadings) readings.remove(r)
        if (bigram.remove(word) != null) changed = true
        for (m in bigram.values) if (m.remove(word) != null) changed = true
        if (changed) { dirty = true; version++ }
        return changed
    }

    data class Entry(val reading: String, val word: String, val count: Int)

    @Synchronized
    fun userWordEntries(): List<Entry> {
        database?.let { backing ->
            val out = ArrayList<Entry>()
            var offset = 0
            while (true) {
                val page = backing.readUserWordEntries(offset = offset, limit = RUNTIME_PAGE_SIZE)
                for (entry in page) out.add(Entry(entry.reading, entry.word, entry.count))
                if (page.size < RUNTIME_PAGE_SIZE) break
                offset += page.size
            }
            return out
        }
        val out = ArrayList<Entry>()
        for ((r, ws) in readings) for (w in ws) out.add(Entry(r, w, count[w] ?: 0))
        return out.sortedWith(compareByDescending<Entry> { it.count }.thenBy { it.reading }.thenBy { it.word })
    }

    @Synchronized
    fun readingSnapshot(): Map<String, List<String>> {
        database?.let { backing ->
            return runCatching {
                val out = LinkedHashMap<String, MutableList<String>>()
                var offset = 0
                while (true) {
                    val page = backing.readUserWordEntries(offset = offset, limit = RUNTIME_PAGE_SIZE)
                    for (entry in page) out.getOrPut(entry.reading) { ArrayList() }.add(entry.word)
                    if (page.size < RUNTIME_PAGE_SIZE) break
                    offset += page.size
                }
                for ((reading, words) in out) readingCache.put(reading, words.take(RUNTIME_PAGE_SIZE))
                out
            }.onFailure { lastFailure = it.javaClass.simpleName + ": " + it.message.orEmpty() }
                .getOrElse { readingCache.snapshot() }
        }
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
    fun wordBoost(word: String): Double {
        database?.let { backing ->
            wordBoostCache[word]?.let { return it }
            return runCatching {
                val stored = backing.readStoredWord(word)
                val boost = if (stored == null) 0.0 else usageScore(stored.count, stored.lastUsed, clock())
                boost.also { wordBoostCache.put(word, it) }
            }.onFailure { lastFailure = it.javaClass.simpleName + ": " + it.message.orEmpty() }.getOrDefault(0.0)
        }
        val c = count[word] ?: return 0.0
        return usageScore(c, lastUsed[word] ?: 0L, clock())
    }

    @Synchronized
    internal fun wordBoosts(words: Collection<String>): Map<String, Double> {
        val unique = words.asSequence().filter { it.isNotEmpty() }.distinct().toList()
        require(unique.size <= UserDataDatabase.MAX_RUNTIME_PAGE_SIZE)
        if (unique.isEmpty()) return emptyMap()
        val backing = database ?: return unique.associateWith(::wordBoost)
        val out = LinkedHashMap<String, Double>(unique.size)
        val missing = ArrayList<String>()
        for (word in unique) {
            val cached = wordBoostCache[word]
            if (cached == null) missing.add(word) else out[word] = cached
        }
        if (missing.isNotEmpty()) {
            runCatching {
                val stored = backing.readStoredWords(missing)
                val now = clock()
                for (word in missing) {
                    val row = stored[word]
                    val boost = if (row == null) 0.0 else usageScore(row.count, row.lastUsed, now)
                    wordBoostCache.put(word, boost)
                    out[word] = boost
                }
            }.onFailure {
                lastFailure = it.javaClass.simpleName + ": " + it.message.orEmpty()
                for (word in missing) out[word] = 0.0
            }
        }
        return out
    }

    @Synchronized
    internal fun maximumWordBoost(): Double {
        if (maximumBoostVersion == version) return maximumBoost
        database?.let { backing ->
            val maximum = backing.maximumUserWordCount()
            maximumBoost = if (maximum <= 0) 0.0 else BOOST_WEIGHT * ln(1.0 + maximum) + RECENCY_WEIGHT
            maximumBoostVersion = version
            return maximumBoost
        }
        var maximum = 0.0
        for (value in count.values) {
            maximum = maxOf(maximum, BOOST_WEIGHT * ln(1.0 + value) + RECENCY_WEIGHT)
        }
        maximumBoost = maximum
        maximumBoostVersion = version
        return maximumBoost
    }

    @Synchronized
    fun successors(prevWord: String, limit: Int): List<String> {
        if (limit <= 0) return emptyList()
        database?.let { backing ->
            return backing.readUserSuccessors(
                prevWord,
                offset = 0,
                limit = minOf(limit, RUNTIME_PAGE_SIZE),
            ).map { it.word }
        }
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
    internal fun successorsPageSnapshot(
        previousWord: String,
        offset: Int,
        limit: Int,
        expectedVersion: Long? = null,
    ): PersistedPage<String> {
        require(offset >= 0)
        require(limit in 0..RUNTIME_PAGE_SIZE)
        database?.let { backing ->
            return backing.readUserSuccessorsPage(previousWord, offset, limit, expectedVersion)
                .map { it.word }
        }
        val current = version
        if (expectedVersion != null && expectedVersion != current) {
            return PersistedPage(emptyList(), current, restartRequired = true)
        }
        val items = successors(previousWord, Int.MAX_VALUE)
        return PersistedPage(items.drop(offset).take(limit), current, items.size.toLong())
    }

    @Synchronized
    internal fun rankedSuccessorsPageSnapshot(
        previousWord: String,
        after: RankedSuccessor?,
        limit: Int,
        rankingNow: Long,
        expectedVersion: Long? = null,
    ): PersistedPage<RankedSuccessor> {
        require(limit in 0..RUNTIME_PAGE_SIZE)
        val order = compareByDescending<RankedSuccessor> { it.score }.thenBy { it.word }
        fun followsAfter(candidate: RankedSuccessor): Boolean = after == null || order.compare(candidate, after) > 0
        val worstFirst = java.util.PriorityQueue(order.reversed())
        fun offer(rows: Sequence<StoredUserWordEntry>) {
            if (limit == 0) return
            for (entry in rows) {
                val candidate = RankedSuccessor(
                    entry.word,
                    usageScore(entry.count, entry.lastUsed, rankingNow),
                )
                if (!followsAfter(candidate)) continue
                if (worstFirst.size < limit) {
                    worstFirst.add(candidate)
                } else if (order.compare(candidate, worstFirst.peek()) < 0) {
                    worstFirst.remove()
                    worstFirst.add(candidate)
                }
            }
        }
        fun selectedPage(): List<RankedSuccessor> = worstFirst.toList().sortedWith(order)

        database?.let { backing ->
            var offset = 0
            var versionToken = expectedVersion
            while (true) {
                val page = backing.readUserSuccessorsPage(
                    previousWord,
                    offset,
                    RUNTIME_PAGE_SIZE,
                    versionToken,
                )
                if (page.restartRequired) {
                    return PersistedPage(emptyList(), page.version, restartRequired = true)
                }
                versionToken = page.version
                offer(page.items.asSequence())
                val total = page.totalCount ?: (offset + page.items.size).toLong()
                if (offset.toLong() + RUNTIME_PAGE_SIZE >= total || offset > Int.MAX_VALUE - RUNTIME_PAGE_SIZE) break
                offset += RUNTIME_PAGE_SIZE
            }
            val verification = backing.readUserSuccessorsPage(previousWord, 0, 0, versionToken)
            if (verification.restartRequired) {
                return PersistedPage(emptyList(), verification.version, restartRequired = true)
            }
            return PersistedPage(selectedPage(), verification.version)
        }
        val current = version
        if (expectedVersion != null && expectedVersion != current) {
            return PersistedPage(emptyList(), current, restartRequired = true)
        }
        val rows = bigram[previousWord].orEmpty().asSequence().map { (word, value) ->
            StoredUserWordEntry("", word, value, lastUsed[word] ?: 0L)
        }
        offer(rows)
        return PersistedPage(selectedPage(), current)
    }

    internal fun rankingNow(): Long = clock()

    @Synchronized
    fun isEmpty(): Boolean = database?.userDataIsEmpty() ?: (count.isEmpty() && readings.isEmpty())

    @Synchronized
    internal fun entryCount(query: String = ""): Long = database?.userWordEntryCount(query) ?: run {
        if (query.isBlank()) userWordEntries().size.toLong() else UserDictSearch.filter(userWordEntries(), query).size.toLong()
    }

    @Synchronized
    internal fun entryPage(query: String = "", offset: Int, limit: Int): List<Entry> {
        require(offset >= 0)
        require(limit in 0..RUNTIME_PAGE_SIZE)
        return database?.readUserWordEntries(query, offset, limit)?.map {
            Entry(it.reading, it.word, it.count)
        } ?: UserDictSearch.filter(userWordEntries(), query).drop(offset).take(limit)
    }

    @Synchronized
    internal fun entryPageSnapshot(
        query: String = "",
        offset: Int,
        limit: Int,
        expectedVersion: Long? = null,
    ): PersistedPage<Entry> {
        require(offset >= 0)
        require(limit in 0..RUNTIME_PAGE_SIZE)
        database?.let { backing ->
            return backing.readUserWordEntriesPage(query, offset, limit, expectedVersion).map {
                Entry(it.reading, it.word, it.count)
            }
        }
        val current = version
        if (expectedVersion != null && expectedVersion != current) {
            return PersistedPage(emptyList(), current, restartRequired = true)
        }
        val filtered = UserDictSearch.filter(userWordEntries(), query)
        return PersistedPage(filtered.drop(offset).take(limit), current, filtered.size.toLong())
    }

    @Synchronized
    internal fun wordsForKeyPage(key: String, offset: Int, limit: Int): List<String> {
        require(offset >= 0)
        require(limit in 0..RUNTIME_PAGE_SIZE)
        if (key.isEmpty()) return emptyList()
        val t9 = key[0] in '2'..'9'
        return database?.let { backing ->
            runCatching {
                backing.readUserWordsForKey(key, t9, offset, limit).map { it.word }.also {
                    if (!t9 && offset == 0) readingCache.put(key, it)
                }
            }.onFailure { lastFailure = it.javaClass.simpleName + ": " + it.message.orEmpty() }.getOrElse {
                val cached = if (t9) readingCache.snapshot().entries
                    .filter { com.aegis.ime.decoder.T9Pinyin.toT9(it.key) == key }
                    .flatMap { it.value }.distinct() else readingCache[key].orEmpty()
                cached.drop(offset).take(limit)
            }
        } ?:
            readingSnapshot().let { snapshot ->
                if (t9) snapshot.entries.filter { com.aegis.ime.decoder.T9Pinyin.toT9(it.key) == key }.flatMap { it.value }
                else snapshot[key].orEmpty()
            }.distinct().drop(offset).take(limit)
    }

    @Synchronized
    internal fun wordsForKeyPageSnapshot(
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
            return backing.readUserWordsForKeyPage(key, key[0] in '2'..'9', offset, limit, expectedVersion)
                .map { it.word }
        }
        val current = version
        if (expectedVersion != null && expectedVersion != current) {
            return PersistedPage(emptyList(), current, restartRequired = true)
        }
        return PersistedPage(wordsForKeyPage(key, offset, limit), current)
    }

    @Synchronized
    internal fun containsWordForKey(key: String, word: String): Boolean {
        if (key.isEmpty() || word.isEmpty()) return false
        database?.let { return it.hasUserWordForKey(key, key[0] in '2'..'9', word) }
        return if (key[0] in '2'..'9') {
            readings.any { (reading, words) ->
                com.aegis.ime.decoder.T9Pinyin.toT9(reading) == key && word in words
            }
        } else {
            word in readings[key].orEmpty()
        }
    }

    @Synchronized
    internal fun wordsForKeyIn(key: String, words: Collection<String>): Set<String> {
        val unique = words.asSequence().filter { it.isNotEmpty() }.distinct().toList()
        require(unique.size < UserDataDatabase.MAX_RUNTIME_PAGE_SIZE)
        if (key.isEmpty() || unique.isEmpty()) return emptySet()
        return database?.userWordsForKeyIn(key, key[0] in '2'..'9', unique) ?:
            unique.filterTo(HashSet()) { containsWordForKey(key, it) }
    }


    @Synchronized
    fun save(file: File) {
        val tmp = File(file.absoluteFile.parentFile, file.name + ".tmp")
        try {
            val backing = database
            if (backing != null) {
                tmp.outputStream().use(backing::writeUserDictionary)
            } else {
                tmp.bufferedWriter().use { w ->
                    w.write("$HEADER\n")
                    for ((word, c) in count) w.write("W\t$word\t$c\t${lastUsed[word] ?: 0}\n")
                    for ((prev, m) in bigram) for ((word, c) in m) w.write("B\t$prev\t$word\t$c\n")
                    for ((reading, ws) in readings) for (word in ws) w.write("R\t$reading\t$word\n")
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
    fun reload(file: File) {
        database?.let { backing ->
            file.inputStream().use { backing.importUserDictionary(it, merge = false) }
            clearRuntimeState()
            version++
            return
        }
        val parsed = parse(file)
        clearRuntimeState()
        applyParsed(parsed)
        version++
    }

    @Synchronized
    fun load(file: File) {
        database?.let { backing ->
            file.inputStream().use { backing.importUserDictionary(it, merge = false) }
            clearRuntimeState()
            version++
            return
        }
        val parsed = parse(file)
        applyParsed(parsed)
        version++
    }

    private fun applyParsed(parsed: Parsed) {
        count.putAll(parsed.count)
        lastUsed.putAll(parsed.lastUsed)
        for ((prev, words) in parsed.bigram) {
            bigram.getOrPut(prev) { HashMap() }.putAll(words)
        }
        for ((reading, words) in parsed.readings) {
            readings.getOrPut(reading) { LinkedHashSet() }.addAll(words)
        }
        dirty = false
    }

    @Synchronized
    fun importFrom(file: File, now: Long): Boolean {
        database?.let { backing ->
            return try {
                file.inputStream().use { backing.importUserDictionary(it, merge = true) }
                clearRuntimeState()
                version++
                lastFailure = null
                true
            } catch (failure: Exception) {
                lastFailure = failure.javaClass.simpleName + ": " + failure.message.orEmpty()
                false
            }
        }
        val parsed = parse(file)
        if (parsed.count.isEmpty() && parsed.readings.isEmpty()) return false
        val before = storageSnapshot()
        for ((word, c) in parsed.count) {
            count[word] = saturatingAdd(count[word] ?: 0, c)
            lastUsed[word] = maxOf(lastUsed[word] ?: 0, parsed.lastUsed[word] ?: now)
        }
        for ((prev, m) in parsed.bigram) {
            val dst = bigram.getOrPut(prev) { HashMap() }
            for ((word, c) in m) dst[word] = saturatingAdd(dst[word] ?: 0, c)
        }
        for ((reading, ws) in parsed.readings) readings.getOrPut(reading) { LinkedHashSet() }.addAll(ws)
        val merged = inMemorySnapshot()
        if (!persist { database?.replaceUserData(merged) }) {
            applyStored(before)
            return false
        }
        dirty = database == null
        version++
        return true
    }

    @Synchronized
    internal fun storageSnapshot(): UserDataSnapshot = database?.readUserData() ?: inMemorySnapshot()

    private fun inMemorySnapshot(): UserDataSnapshot = UserDataSnapshot(
        words = count.mapValues { (word, value) -> StoredWord(value, lastUsed[word] ?: 0L) },
        bigrams = bigram.mapValues { (_, words) -> LinkedHashMap(words) },
        readings = readings.mapValues { (_, words) -> LinkedHashSet(words) },
    )

    @Synchronized
    internal fun replaceFromStorage(snapshot: UserDataSnapshot) {
        database?.replaceUserData(snapshot)
        if (database == null) applyStored(snapshot) else clearRuntimeState()
        dirty = false
        version++
    }

    @Synchronized
    internal fun reloadFromStorage() {
        clearRuntimeState()
        version++
    }

    private fun clearRuntimeState() {
        count.clear()
        lastUsed.clear()
        bigram.clear()
        readings.clear()
        wordBoostCache.clear()
        readingCache.clear()
        maximumBoostVersion = Long.MIN_VALUE
        dirty = false
    }

    private fun cacheReading(reading: String, word: String, remove: Boolean) {
        if (reading.isEmpty()) return
        val values = ArrayList(readingCache[reading].orEmpty())
        if (remove) values.remove(word) else if (word !in values && values.size < RUNTIME_PAGE_SIZE) values.add(word)
        if (values.isEmpty()) readingCache.remove(reading) else readingCache.put(reading, values)
    }

    private fun cacheStoredWord(word: String, fallbackNow: Long) {
        val stored = runCatching { database?.readStoredWord(word) }.getOrNull()
        val count = stored?.count ?: 1
        val used = stored?.lastUsed ?: fallbackNow.coerceAtLeast(0L)
        wordBoostCache.put(word, usageScore(count, used, clock()))
    }

    internal val isDatabaseBacked: Boolean
        get() = database != null

    internal val databaseIdentity: Any?
        get() = database

    internal fun runtimeCacheSizesForTest(): Pair<Int, Int> =
        wordBoostCache.sizeForTest() to readingCache.sizeForTest()

    private data class Parsed(
        val count: HashMap<String, Int> = HashMap(),
        val lastUsed: HashMap<String, Long> = HashMap(),
        val bigram: HashMap<String, HashMap<String, Int>> = HashMap(),
        val readings: HashMap<String, LinkedHashSet<String>> = HashMap(),
    )

    companion object {
        private const val HEADER = "aegis-userdb 1"
        private const val MAX_COUNT = 1_000_000_000
        private const val BOOST_WEIGHT = 3.5
        private const val RECENCY_WEIGHT = 2.0
        private const val RECENCY_HALF_LIFE_MILLIS = 7L * 24L * 60L * 60L * 1000L
        private val LN_2 = ln(2.0)
        internal const val RUNTIME_PAGE_SIZE = 128
        private const val RUNTIME_CACHE_SIZE = 256

        private fun parse(file: File): Parsed {
            if (!file.exists() || file.length() == 0L) return Parsed()
            val parsed = Parsed()
            file.bufferedReader().use { reader ->
                require(reader.readLine() == HEADER) { "unsupported userdb header" }
                while (true) {
                    val line = reader.readLine() ?: break
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
                                p.size == 3 && p[1].isNotEmpty() &&
                                    p[1] == sanitizeReading(p[1]) && isValidWord(p[2]),
                            ) { "invalid userdb reading row" }
                            require(parsed.readings.getOrPut(p[1]) { LinkedHashSet() }.add(p[2])) {
                                "duplicate userdb reading"
                            }
                        }
                        else -> throw IllegalArgumentException("invalid userdb row")
                    }
                }
            }
            require(parsed.bigram.values.all { words -> words.keys.all { it in parsed.count } }) {
                "userdb bigram target is missing"
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
            word.isNotEmpty() && isStorableWord(word)

        private fun isStorableWord(word: String): Boolean =
            word.none { it == '\t' || it == '\n' || it == '\r' }

        private fun sanitizeReading(reading: String): String {
            val sb = StringBuilder(reading.length)
            for (ch in reading.lowercase()) if (ch in 'a'..'z') sb.append(ch)
            return sb.toString()
        }
    }

    private fun Parsed.toStored(): UserDataSnapshot = UserDataSnapshot(
        words = count.mapValues { (word, value) -> StoredWord(value, lastUsed[word] ?: 0L) },
        bigrams = bigram.mapValues { (_, words) -> LinkedHashMap(words) },
        readings = readings.mapValues { (_, words) -> LinkedHashSet(words) },
    )

    private fun applyStored(snapshot: UserDataSnapshot) {
        count.clear()
        lastUsed.clear()
        bigram.clear()
        readings.clear()
        for ((word, state) in snapshot.words) {
            count[word] = state.count
            lastUsed[word] = state.lastUsed
        }
        for ((previous, words) in snapshot.bigrams) bigram[previous] = HashMap(words)
        for ((reading, words) in snapshot.readings) readings[reading] = LinkedHashSet(words)
        dirty = false
    }

    private inline fun persist(block: () -> Unit): Boolean {
        if (database == null) return true
        return try {
            block()
            lastFailure = null
            true
        } catch (e: Exception) {
            lastFailure = e.javaClass.simpleName + ": " + e.message.orEmpty()
            false
        }
    }
}
