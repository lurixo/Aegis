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

/**
 * Read-only, memory-mapped pinyin dictionary. Keys are toneless concatenated syllables
 * (e.g. "nihao"); values are candidate words ordered by descending frequency.
 *
 * Format produced by `tools/DictBuilder` (little-endian):
 *   'A''E''G''D' | i32 version=2 | i32 numKeys | i32 numEntries | i64 totalFreq
 *   i32 keyBlobLen  | keyBlob  (ascii, distinct keys concatenated, sorted asc)
 *   i32 wordBlobLen | wordBlob (utf-8, one slice per entry)
 *   keyArr  : numKeys   × (i32 keyOffset, i32 keyLen, i32 entryStart)
 *   entryArr: numEntries× (i32 wordOffset, i32 wordLen, i32 freq)
 */
class BinaryDict private constructor(private val buf: ByteBuffer) {

    private val numKeys: Int
    private val numEntries: Int
    private val keyBlobOff: Int
    private val wordBlobOff: Int
    private val keyArrOff: Int
    private val entryArrOff: Int

    /** Sum of all entry frequencies — denominator for unigram probabilities. */
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

    /** A candidate word and its corpus frequency. */
    data class WordFreq(val word: String, val freq: Int)

    /** Entries for an exact key (its full toneless pinyin), frequency-descending. Empty if absent. */
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

    /** All entries whose key starts with [prefix], ranked by frequency (for English completion). */
    fun prefixByFreq(prefix: String, limit: Int): List<WordFreq> {
        if (prefix.isEmpty() || numKeys == 0) return emptyList()
        val q = prefix.toByteArray(Charsets.US_ASCII)
        val all = ArrayList<WordFreq>()
        var i = lowerBound(q)
        while (i < numKeys && startsWith(i, q)) {
            val es = entryStart(i)
            val ee = if (i + 1 < numKeys) entryStart(i + 1) else numEntries
            var j = es
            while (j < ee) {
                val wo = buf.getInt(entryArrOff + j * 12)
                val wl = buf.getInt(entryArrOff + j * 12 + 4)
                val fr = buf.getInt(entryArrOff + j * 12 + 8)
                all.add(WordFreq(readWord(wo, wl), fr))
                j++
            }
            i++
        }
        all.sortWith(compareByDescending<WordFreq> { it.freq }.thenBy { supplementarySingleTieRank(it.word) })
        return if (all.size <= limit) all else all.subList(0, limit)
    }

    /** Exact match for [input] (its full toneless pinyin), then prefix matches; freq-ordered, deduped. */
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

    /** first index whose key >= q (byte-lex) */
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

    private fun readWord(wordOffset: Int, len: Int): String {
        val bytes = ByteArray(len)
        val base = wordBlobOff + wordOffset
        for (k in 0 until len) bytes[k] = buf.get(base + k)
        return String(bytes, Charsets.UTF_8)
    }

    companion object {
        /** Map a dict file directly (mmap survives channel close). */
        fun fromFile(file: File): BinaryDict =
            RandomAccessFile(file, "r").use { raf ->
                val ch = raf.channel
                BinaryDict(ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()))
            }

        /** Copy the asset into filesDir once, then mmap it (assets can't be mmapped while compressed). */
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
