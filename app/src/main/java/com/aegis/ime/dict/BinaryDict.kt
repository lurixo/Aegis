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

package com.aegis.ime.dict

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.PriorityQueue

class BinaryDict private constructor(private val buf: ByteBuffer) {

    private val numKeys: Int
    private val numEntries: Int
    private val keyBlobOff: Int
    private val wordBlobOff: Int
    private val keyArrOff: Int
    private val entryArrOff: Int
    private val shortPrefixTop: Array<List<WordFreq>>
    private val widePrefixTop = arrayOfNulls<IntArray>(WIDE_PREFIX_SLOTS)

    val totalFreq: Long

    init {
        buf.order(ByteOrder.LITTLE_ENDIAN)
        val size = buf.limit().toLong()
        require(size >= HEADER_SIZE) { "truncated header" }
        require(buf.get(0) == 'A'.code.toByte() && buf.get(1) == 'E'.code.toByte() &&
            buf.get(2) == 'G'.code.toByte() && buf.get(3) == 'D'.code.toByte()) { "bad magic" }
        val version = buf.getInt(4)
        require(version == FORMAT_VERSION) { "unsupported dictionary format $version" }
        numKeys = buf.getInt(8)
        numEntries = buf.getInt(12)
        require(numKeys >= 0 && numEntries >= 0) { "bad counts $numKeys/$numEntries" }
        totalFreq = buf.getLong(16)
        val keyBlobLen = buf.getInt(24)
        require(keyBlobLen >= 0 && HEADER_SIZE + keyBlobLen.toLong() + 4L <= size) { "bad key blob length" }
        keyBlobOff = HEADER_SIZE
        val wordBlobLenPos = HEADER_SIZE + keyBlobLen
        val wordBlobLen = buf.getInt(wordBlobLenPos)
        require(wordBlobLen >= 0) { "bad word blob length" }
        val keyArrStart = wordBlobLenPos.toLong() + 4L + wordBlobLen.toLong()
        val entryArrStart = keyArrStart + numKeys.toLong() * KEY_RECORD_BYTES
        require(entryArrStart <= size) { "dictionary ends before its key table does" }
        require(entryArrStart + numEntries.toLong() * 12L <= size) { "dictionary ends before its entry table does" }
        wordBlobOff = wordBlobLenPos + 4
        keyArrOff = keyArrStart.toInt()
        entryArrOff = entryArrStart.toInt()
        validateRecords(keyBlobLen, wordBlobLen)
        shortPrefixTop = buildShortPrefixTop()
    }

    private fun validateRecords(keyBlobLen: Int, wordBlobLen: Int) {
        var i = 0
        while (i < numKeys) {
            val ko = keyOffset(i).toLong()
            val kl = keyLen(i).toLong()
            require(ko >= 0 && kl >= 0 && ko + kl <= keyBlobLen) { "key $i leaves its blob" }
            require(entryStart(i) in 0..numEntries) { "key $i points outside the entry table" }
            i++
        }
        var j = 0
        while (j < numEntries) {
            val wo = buf.getInt(entryArrOff + j * 12).toLong()
            val wl = buf.getInt(entryArrOff + j * 12 + 4).toLong()
            require(wo >= 0 && wl >= 0 && wo + wl <= wordBlobLen) { "entry $j leaves its blob" }
            j++
        }
    }

    data class WordFreq(val word: String, val freq: Int)

    private data class PrefixHit(val word: String, val freq: Int, val tieRank: Int, val order: Int, val entry: Int)

    fun exact(key: String, limit: Int = Int.MAX_VALUE): List<WordFreq> {
        if (key.isEmpty() || numKeys == 0) return emptyList()
        val q = key.toByteArray(Charsets.US_ASCII)
        val i = lowerBound(q)
        if (i >= numKeys || compareKey(i, q) != 0) return emptyList()
        val es = entryStart(i)
        val ee = if (i + 1 < numKeys) entryStart(i + 1) else numEntries
        val take = minOf((ee - es).coerceAtLeast(0), limit.coerceAtLeast(0))
        if (take == 0) return emptyList()
        val out = ArrayList<WordFreq>(take)
        var j = es
        while (j < ee && out.size < limit) {
            val wo = buf.getInt(entryArrOff + j * 12)
            val wl = buf.getInt(entryArrOff + j * 12 + 4)
            val fr = buf.getInt(entryArrOff + j * 12 + 8)
            out.add(WordFreq(readWord(wo, wl), fr))
            j++
        }
        return out
    }

    fun containsExactWord(key: String, word: String): Boolean = exactWordFreq(key, word) != null

    fun exactWordFreq(key: String, word: String): Int? {
        if (key.isEmpty() || word.isEmpty() || numKeys == 0) return null
        val wordBytes = word.toByteArray(Charsets.UTF_8)
        val q = key.toByteArray(Charsets.US_ASCII)
        val i = lowerBound(q)
        if (i >= numKeys || compareKey(i, q) != 0) return null
        val es = entryStart(i)
        val ee = if (i + 1 < numKeys) entryStart(i + 1) else numEntries
        var j = es
        while (j < ee) {
            val entryOff = entryArrOff + j * 12
            val wo = buf.getInt(entryOff)
            val wl = buf.getInt(entryOff + 4)
            if (wordEquals(wo, wl, wordBytes)) return buf.getInt(entryOff + 8)
            j++
        }
        return null
    }

    fun prefixByFreq(prefix: String, limit: Int): List<WordFreq> {
        if (prefix.isEmpty() || limit <= 0 || numKeys == 0) return emptyList()
        shortPrefixIndex(prefix)?.let { return it.take(limit) }
        if (limit <= SHORT_PREFIX_TOP_N) widePrefixIndex(prefix)?.let { return entryWords(it, limit) }
        return scanByFreq(prefix, limit)
    }

    internal fun scanByFreq(prefix: String, limit: Int): List<WordFreq> {
        if (prefix.isEmpty() || limit <= 0 || numKeys == 0) return emptyList()
        val q = prefix.toByteArray(Charsets.US_ASCII)
        val top = PriorityQueue<PrefixHit>(Comparator { a, b -> comparePrefixWorstFirst(a, b) })
        val byWord = HashMap<String, PrefixHit>()
        var order = 0
        var i = lowerBound(q)
        while (i < numKeys && startsWith(i, q)) {
            if ((i and CHECKPOINT_MASK) == 0) DecodeCancellation.checkpoint()
            val es = entryStart(i)
            val ee = if (i + 1 < numKeys) entryStart(i + 1) else numEntries
            var j = es
            while (j < ee) {
                val entryOff = entryArrOff + j * 12
                val fr = buf.getInt(entryOff + 8)
                if (top.size >= limit) {
                    val worst = top.peek() ?: break
                    if (fr < worst.freq || (fr == worst.freq && worst.tieRank == 0)) break
                }
                val wo = buf.getInt(entryOff)
                val wl = buf.getInt(entryOff + 4)
                val word = readWord(wo, wl)
                val hit = PrefixHit(word, fr, supplementarySingleTieRank(word), order++, j)
                offerPrefixHit(top, byWord, hit, limit)
                j++
            }
            i++
        }
        return sortedPrefixHits(top)
    }

    fun exactAmong(choices: List<String>, limit: Int): List<WordFreq> {
        if (choices.isEmpty() || limit <= 0 || numKeys == 0) return emptyList()
        val letters = Array(choices.size) { choices[it].toByteArray(Charsets.US_ASCII) }
        val entries = ArrayList<Int>()
        collectAmong(letters, 0, 0, numKeys, limit, entries)
        entries.sortByDescending { buf.getInt(entryArrOff + it * 12 + 8) }
        val out = ArrayList<WordFreq>(minOf(limit, entries.size))
        val seen = HashSet<String>()
        for (j in entries) {
            val entryOff = entryArrOff + j * 12
            val word = readWord(buf.getInt(entryOff), buf.getInt(entryOff + 4))
            if (!seen.add(word)) continue
            out.add(WordFreq(word, buf.getInt(entryOff + 8)))
            if (out.size == limit) break
        }
        return out
    }

    fun query(input: String, limit: Int): List<String> {
        if (input.isEmpty() || numKeys == 0) return emptyList()
        val q = input.toByteArray(Charsets.US_ASCII)
        val out = LinkedHashSet<String>()
        var i = lowerBound(q)
        while (i < numKeys && out.size < limit && startsWith(i, q)) {
            addWords(i, out, limit)
            i++
        }
        return out.toList()
    }

    private fun keyOffset(i: Int) = buf.getInt(keyArrOff + i * 12)
    private fun keyLen(i: Int) = buf.getInt(keyArrOff + i * 12 + 4)
    private fun entryStart(i: Int) = buf.getInt(keyArrOff + i * 12 + 8)

    private fun lowerBound(q: ByteArray): Int {
        var lo = 0
        var hi = numKeys
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (compareKey(mid, q) < 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun compareKey(i: Int, q: ByteArray): Int {
        val off = keyBlobOff + keyOffset(i)
        val len = keyLen(i)
        val n = minOf(len, q.size)
        var k = 0
        while (k < n) {
            val a = buf.get(off + k).toInt() and 0xFF
            val b = q[k].toInt() and 0xFF
            if (a != b) return a - b
            k++
        }
        return len - q.size
    }

    private fun startsWith(i: Int, q: ByteArray): Boolean {
        val len = keyLen(i)
        if (len < q.size) return false
        val off = keyBlobOff + keyOffset(i)
        for (k in q.indices) {
            if ((buf.get(off + k).toInt() and 0xFF) != (q[k].toInt() and 0xFF)) return false
        }
        return true
    }

    private fun collectAmong(
        letters: Array<ByteArray>,
        depth: Int,
        lo: Int,
        hi: Int,
        limit: Int,
        out: MutableList<Int>,
    ) {
        DecodeCancellation.checkpoint()
        for (letter in letters[depth]) {
            val b = letter.toInt() and 0xFF
            val from = keyByteBound(lo, hi, depth, b)
            val to = keyByteBound(from, hi, depth, b + 1)
            if (from == to) continue
            if (depth + 1 < letters.size) {
                collectAmong(letters, depth + 1, from, to, limit, out)
            } else if (keyLen(from) == letters.size) {
                val es = entryStart(from)
                val ee = if (from + 1 < numKeys) entryStart(from + 1) else numEntries
                var j = es
                while (j < ee && j - es < limit) {
                    out.add(j)
                    j++
                }
            }
        }
    }

    private fun keyByteBound(lo: Int, hi: Int, at: Int, b: Int): Int {
        var l = lo
        var h = hi
        while (l < h) {
            val mid = (l + h) ushr 1
            if (keyByteAt(mid, at) < b) l = mid + 1 else h = mid
        }
        return l
    }

    private fun keyByteAt(i: Int, at: Int): Int =
        if (keyLen(i) <= at) -1 else buf.get(keyBlobOff + keyOffset(i) + at).toInt() and 0xFF

    private fun addWords(i: Int, out: MutableSet<String>, limit: Int) {
        val es = entryStart(i)
        val ee = if (i + 1 < numKeys) entryStart(i + 1) else numEntries
        var j = es
        while (j < ee && out.size < limit) {
            val wo = buf.getInt(entryArrOff + j * 12)
            val wl = buf.getInt(entryArrOff + j * 12 + 4)
            out.add(readWord(wo, wl))
            j++
        }
    }

    private fun shortPrefixIndex(prefix: String): List<WordFreq>? {
        if (prefix.length != 1) return null
        val c = prefix[0].code
        return if (isShortPrefixByte(c)) shortPrefixTop[c] else null
    }

    private fun widePrefixIndex(prefix: String): IntArray? {
        val slot = when (prefix.length) {
            2 -> pairSlot(prefix[0].code, prefix[1].code)
            3 -> tripleSlot(prefix[0].code, prefix[1].code, prefix[2].code)
            else -> -1
        }
        return if (slot < 0) null else widePrefixTop[slot]
    }

    private fun entryWords(entries: IntArray, limit: Int): List<WordFreq> {
        val take = minOf(entries.size, limit)
        val out = ArrayList<WordFreq>(take)
        for (k in 0 until take) {
            val entryOff = entryArrOff + entries[k] * 12
            out.add(WordFreq(readWord(buf.getInt(entryOff), buf.getInt(entryOff + 4)), buf.getInt(entryOff + 8)))
        }
        return out
    }

    private fun buildShortPrefixTop(): Array<List<WordFreq>> {
        val slots = SHORT_PREFIX_BUCKETS + WIDE_PREFIX_SLOTS
        val heaps = arrayOfNulls<PriorityQueue<PrefixHit>>(slots)
        val maps = arrayOfNulls<HashMap<String, PrefixHit>>(slots)
        val order = IntArray(slots)
        var firstEnd = 0
        var pairEnd = 0
        var pair = -1
        var digitPair = false
        var tripleEnd = 0
        var triple = -1
        var i = 0
        while (i < numKeys) {
            val len = keyLen(i)
            val off = keyBlobOff + keyOffset(i)
            val first = if (len > 0) buf.get(off).toInt() and 0xFF else -1
            if (isShortPrefixByte(first)) {
                val es = entryStart(i)
                val ee = if (i + 1 < numKeys) entryStart(i + 1) else numEntries
                val lead = if (es < ee) buf.getInt(entryArrOff + es * 12 + 8) else 0
                offerEntries(first, es, ee, lead, heaps, maps, order)
                if (len > 1) {
                    if (i >= pairEnd) {
                        if (i >= firstEnd) firstEnd = keyByteBound(i, numKeys, 0, first + 1)
                        val second = buf.get(off + 1).toInt() and 0xFF
                        pairEnd = keyByteBound(i, firstEnd, 1, second + 1)
                        pair = if (pairEnd - i >= WIDE_PREFIX_MIN_KEYS) pairSlot(first, second) else -1
                        digitPair = digitUnit(first) >= 0 && digitUnit(second) >= 0
                    }
                    if (pair >= 0) offerEntries(SHORT_PREFIX_BUCKETS + pair, es, ee, lead, heaps, maps, order)
                }
                if (len > 2 && digitPair) {
                    if (i >= tripleEnd) {
                        val third = buf.get(off + 2).toInt() and 0xFF
                        tripleEnd = keyByteBound(i, pairEnd, 2, third + 1)
                        triple = if (tripleEnd - i >= WIDE_PREFIX_MIN_KEYS) {
                            tripleSlot(first, buf.get(off + 1).toInt() and 0xFF, third)
                        } else {
                            -1
                        }
                    }
                    if (triple >= 0) offerEntries(SHORT_PREFIX_BUCKETS + triple, es, ee, lead, heaps, maps, order)
                }
            }
            i++
        }
        for (slot in 0 until WIDE_PREFIX_SLOTS) {
            val top = heaps[SHORT_PREFIX_BUCKETS + slot] ?: continue
            val best = top.toList().sortedWith(Comparator { a, b -> comparePrefixBestFirst(a, b) })
            widePrefixTop[slot] = IntArray(best.size) { best[it].entry }
        }
        return Array(SHORT_PREFIX_BUCKETS) { b -> heaps[b]?.let { sortedPrefixHits(it) } ?: emptyList() }
    }

    private fun offerEntries(
        slot: Int,
        es: Int,
        ee: Int,
        lead: Int,
        heaps: Array<PriorityQueue<PrefixHit>?>,
        maps: Array<HashMap<String, PrefixHit>?>,
        order: IntArray,
    ) {
        val top = heaps[slot] ?: PriorityQueue<PrefixHit>(
            SHORT_PREFIX_TOP_N,
            Comparator { a, b -> comparePrefixWorstFirst(a, b) },
        ).also { heaps[slot] = it }
        val byWord = maps[slot] ?: HashMap<String, PrefixHit>().also { maps[slot] = it }
        var j = es
        while (j < ee) {
            val entryOff = entryArrOff + j * 12
            val fr = if (j == es) lead else buf.getInt(entryOff + 8)
            if (top.size >= SHORT_PREFIX_TOP_N) {
                val worst = top.peek() ?: break
                if (fr < worst.freq || (fr == worst.freq && worst.tieRank == 0)) break
            }
            val wo = buf.getInt(entryOff)
            val wl = buf.getInt(entryOff + 4)
            val word = readWord(wo, wl)
            offerPrefixHit(
                top,
                byWord,
                PrefixHit(word, fr, supplementarySingleTieRank(word), order[slot]++, j),
                SHORT_PREFIX_TOP_N,
            )
            j++
        }
    }

    private fun letterUnit(b: Int): Int = if (b in 'a'.code..'z'.code) b - 'a'.code else -1

    private fun digitUnit(b: Int): Int = if (b in '2'.code..'9'.code) b - '2'.code else -1

    private fun prefixUnit(b: Int): Int {
        val letter = letterUnit(b)
        if (letter >= 0) return letter
        val digit = digitUnit(b)
        return if (digit >= 0) PREFIX_LETTERS + digit else -1
    }

    private fun pairSlot(first: Int, second: Int): Int {
        val a = prefixUnit(first)
        val b = prefixUnit(second)
        return if (a < 0 || b < 0) -1 else a * PREFIX_UNITS + b
    }

    private fun tripleSlot(first: Int, second: Int, third: Int): Int {
        val a = digitUnit(first)
        val b = digitUnit(second)
        val c = digitUnit(third)
        return if (a < 0 || b < 0 || c < 0) -1 else PREFIX_UNITS * PREFIX_UNITS + (a * PREFIX_DIGITS + b) * PREFIX_DIGITS + c
    }

    private fun isShortPrefixByte(b: Int): Boolean =
        b in 'a'.code..'z'.code || b in '2'.code..'9'.code

    private fun offerPrefixHit(
        top: PriorityQueue<PrefixHit>,
        byWord: HashMap<String, PrefixHit>,
        hit: PrefixHit,
        limit: Int,
    ) {
        val existing = byWord[hit.word]
        if (existing != null) {
            if (comparePrefixBestFirst(existing, hit) <= 0) return
            top.remove(existing)
            byWord.remove(hit.word)
        }
        if (top.size < limit) {
            top.add(hit)
            byWord[hit.word] = hit
            return
        }
        val worst = top.peek() ?: return
        if (comparePrefixBestFirst(hit, worst) < 0) {
            top.poll()
            byWord.remove(worst.word)
            top.add(hit)
            byWord[hit.word] = hit
        }
    }

    private fun sortedPrefixHits(top: PriorityQueue<PrefixHit>): List<WordFreq> =
        top.toList()
            .sortedWith(Comparator { a, b -> comparePrefixBestFirst(a, b) })
            .map { WordFreq(it.word, it.freq) }

    private fun supplementarySingleTieRank(word: String): Int =
        if (word.isNotEmpty() &&
            word.codePointCount(0, word.length) == 1 &&
            Character.isSupplementaryCodePoint(word.codePointAt(0))
        ) 1 else 0

    private fun comparePrefixWorstFirst(a: PrefixHit, b: PrefixHit): Int {
        val freq = a.freq.compareTo(b.freq)
        if (freq != 0) return freq
        val tie = b.tieRank.compareTo(a.tieRank)
        if (tie != 0) return tie
        return b.order.compareTo(a.order)
    }

    private fun comparePrefixBestFirst(a: PrefixHit, b: PrefixHit): Int {
        val freq = b.freq.compareTo(a.freq)
        if (freq != 0) return freq
        val tie = a.tieRank.compareTo(b.tieRank)
        if (tie != 0) return tie
        return a.order.compareTo(b.order)
    }

    private fun readWord(wordOffset: Int, len: Int): String {
        val bytes = ByteArray(len)
        val base = wordBlobOff + wordOffset
        for (k in 0 until len) bytes[k] = buf.get(base + k)
        return String(bytes, Charsets.UTF_8)
    }

    private fun wordEquals(wordOffset: Int, len: Int, bytes: ByteArray): Boolean {
        if (bytes.size != len) return false
        val base = wordBlobOff + wordOffset
        for (k in 0 until len) {
            if (buf.get(base + k) != bytes[k]) return false
        }
        return true
    }

    companion object {
        private const val FORMAT_VERSION = 2
        private const val HEADER_SIZE = 28
        private const val KEY_RECORD_BYTES = 12

        private const val SHORT_PREFIX_TOP_N = 128
        private const val SHORT_PREFIX_BUCKETS = 128
        private const val PREFIX_LETTERS = 26
        private const val PREFIX_DIGITS = 8
        private const val PREFIX_UNITS = PREFIX_LETTERS + PREFIX_DIGITS
        private const val WIDE_PREFIX_SLOTS = PREFIX_UNITS * PREFIX_UNITS + PREFIX_DIGITS * PREFIX_DIGITS * PREFIX_DIGITS
        private const val WIDE_PREFIX_MIN_KEYS = 4096
        private const val CHECKPOINT_MASK = 0xFF

        fun fromFile(file: File): BinaryDict =
            RandomAccessFile(file, "r").use { raf ->
                val ch = raf.channel
                BinaryDict(ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()))
            }
    }
}
