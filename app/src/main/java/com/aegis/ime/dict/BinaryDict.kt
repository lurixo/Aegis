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
        wordBlobOff = wordBlobLenPos + 4
        keyArrOff = keyArrStart.toInt()
        entryArrOff = entryArrStart.toInt()
        shortPrefixTop = buildShortPrefixTop()
    }

    data class WordFreq(val word: String, val freq: Int)

    private data class PrefixHit(val word: String, val freq: Int, val tieRank: Int, val order: Int)

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
        val q = prefix.toByteArray(Charsets.US_ASCII)
        val top = PriorityQueue<PrefixHit>(Comparator { a, b -> comparePrefixWorstFirst(a, b) })
        val byWord = HashMap<String, PrefixHit>()
        var order = 0
        var i = lowerBound(q)
        while (i < numKeys && startsWith(i, q)) {
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
                val hit = PrefixHit(word, fr, supplementarySingleTieRank(word), order++)
                offerPrefixHit(top, byWord, hit, limit)
                j++
            }
            i++
        }
        return sortedPrefixHits(top)
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

    private fun buildShortPrefixTop(): Array<List<WordFreq>> {
        val heaps = arrayOfNulls<PriorityQueue<PrefixHit>>(SHORT_PREFIX_BUCKETS)
        val maps = arrayOfNulls<HashMap<String, PrefixHit>>(SHORT_PREFIX_BUCKETS)
        val order = IntArray(SHORT_PREFIX_BUCKETS)
        var i = 0
        while (i < numKeys) {
            val first = firstKeyByte(i)
            if (isShortPrefixByte(first)) {
                val top = heaps[first] ?: PriorityQueue<PrefixHit>(
                    SHORT_PREFIX_TOP_N,
                    Comparator { a, b -> comparePrefixWorstFirst(a, b) },
                ).also { heaps[first] = it }
                val byWord = maps[first] ?: HashMap<String, PrefixHit>().also { maps[first] = it }
                val es = entryStart(i)
                val ee = if (i + 1 < numKeys) entryStart(i + 1) else numEntries
                var j = es
                while (j < ee) {
                    val entryOff = entryArrOff + j * 12
                    val fr = buf.getInt(entryOff + 8)
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
                        PrefixHit(word, fr, supplementarySingleTieRank(word), order[first]++),
                        SHORT_PREFIX_TOP_N,
                    )
                    j++
                }
            }
            i++
        }
        return Array(SHORT_PREFIX_BUCKETS) { b -> heaps[b]?.let { sortedPrefixHits(it) } ?: emptyList() }
    }

    private fun firstKeyByte(i: Int): Int {
        if (keyLen(i) <= 0) return -1
        return buf.get(keyBlobOff + keyOffset(i)).toInt() and 0xFF
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

        fun fromFile(file: File): BinaryDict =
            RandomAccessFile(file, "r").use { raf ->
                val ch = raf.channel
                BinaryDict(ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()))
            }
    }
}
