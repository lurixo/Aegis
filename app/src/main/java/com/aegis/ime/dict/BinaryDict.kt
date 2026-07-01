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

import android.content.Context
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

    val totalFreq: Long

    init {
        buf.order(ByteOrder.LITTLE_ENDIAN)
        require(buf.get(0) == 'A'.code.toByte() && buf.get(1) == 'E'.code.toByte() &&
            buf.get(2) == 'G'.code.toByte() && buf.get(3) == 'D'.code.toByte()) { "bad magic" }
        numKeys = buf.getInt(8)
        numEntries = buf.getInt(12)
        totalFreq = buf.getLong(16)
        val keyBlobLen = buf.getInt(24)
        keyBlobOff = 28
        val wordBlobLenPos = 28 + keyBlobLen
        val wordBlobLen = buf.getInt(wordBlobLenPos)
        wordBlobOff = wordBlobLenPos + 4
        keyArrOff = wordBlobOff + wordBlobLen
        entryArrOff = keyArrOff + numKeys * 12
    }

    data class WordFreq(val word: String, val freq: Int)

    private data class PrefixHit(val word: String, val freq: Int, val tieRank: Int, val order: Int)

    fun exact(key: String): List<WordFreq> {
        if (key.isEmpty() || numKeys == 0) return emptyList()
        val q = key.toByteArray(Charsets.US_ASCII)
        val i = lowerBound(q)
        if (i >= numKeys || compareKey(i, q) != 0) return emptyList()
        val es = entryStart(i)
        val ee = if (i + 1 < numKeys) entryStart(i + 1) else numEntries
        val out = ArrayList<WordFreq>(ee - es)
        var j = es
        while (j < ee) {
            val wo = buf.getInt(entryArrOff + j * 12)
            val wl = buf.getInt(entryArrOff + j * 12 + 4)
            val fr = buf.getInt(entryArrOff + j * 12 + 8)
            out.add(WordFreq(readWord(wo, wl), fr))
            j++
        }
        return out
    }

    fun prefixByFreq(prefix: String, limit: Int): List<WordFreq> {
        if (prefix.isEmpty() || limit <= 0 || numKeys == 0) return emptyList()
        val q = prefix.toByteArray(Charsets.US_ASCII)
        val top = PriorityQueue<PrefixHit>(limit, Comparator { a, b -> comparePrefixWorstFirst(a, b) })
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
                if (top.size < limit) {
                    top.add(hit)
                } else {
                    val worst = top.peek() ?: break
                    if (comparePrefixBestFirst(hit, worst) < 0) {
                        top.poll()
                        top.add(hit)
                    }
                }
                j++
            }
            i++
        }
        return top.toList()
            .sortedWith(Comparator { a, b -> comparePrefixBestFirst(a, b) })
            .map { WordFreq(it.word, it.freq) }
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

    companion object {
        fun fromFile(file: File): BinaryDict =
            RandomAccessFile(file, "r").use { raf ->
                val ch = raf.channel
                BinaryDict(ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()))
            }

        fun fromAssets(context: Context, assetName: String): BinaryDict {
            val outFile = File(context.filesDir, assetName)
            if (!outFile.exists() || outFile.length() == 0L) {
                context.assets.open(assetName).use { input ->
                    outFile.outputStream().use { input.copyTo(it) }
                }
            }
            return fromFile(outFile)
        }
    }
}
