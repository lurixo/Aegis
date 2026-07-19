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
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.ln

class CharBigramLM private constructor(private val buf: ByteBuffer) {

    private val numChars: Int
    private val totalUni: Long
    private val charCodesOff: Int
    private val uniCountOff: Int
    private val rowTotalOff: Int
    private val rowStartOff: Int
    private val biC2Off: Int
    private val biCountOff: Int
    private val hasBigrams: Boolean

    init {
        buf.order(ByteOrder.LITTLE_ENDIAN)
        val cap = buf.capacity().toLong()
        require(cap >= 20L) { "lm too small" }
        require(buf.get(0) == 'A'.code.toByte() && buf.get(1) == 'E'.code.toByte() &&
            buf.get(2) == 'G'.code.toByte() && buf.get(3) == 'L'.code.toByte()) { "bad lm magic" }
        require(buf.getInt(4) == 1) { "unsupported lm version" }
        val nc = buf.getInt(8)
        require(nc >= 0) { "bad lm char count" }
        totalUni = buf.getLong(12)
        val charCodes = 20L
        val uniCount = charCodes + nc.toLong() * 4
        val rowTotal = uniCount + nc.toLong() * 8
        val rowStart = rowTotal + nc.toLong() * 8
        val numBigramsOff = rowStart + (nc.toLong() + 1) * 4
        require(numBigramsOff + 4 <= cap) { "lm header exceeds file" }
        val nb = buf.getInt(numBigramsOff.toInt())
        require(nb >= 0) { "bad lm bigram count" }
        val biC2 = numBigramsOff + 4
        val biCount = biC2 + nb.toLong() * 4
        val fullExtent = biCount + nb.toLong() * 8
        numChars = nc
        charCodesOff = charCodes.toInt()
        uniCountOff = uniCount.toInt()
        rowTotalOff = rowTotal.toInt()
        rowStartOff = rowStart.toInt()
        biC2Off = biC2.toInt()
        biCountOff = biCount.toInt()
        hasBigrams = nb > 0 && fullExtent <= cap
    }

    private val lnTotalUni = ln(totalUni.coerceAtLeast(1).toDouble())

    fun charId(cp: Int): Int {
        var lo = 0
        var hi = numChars
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            val v = buf.getInt(charCodesOff + mid * 4)
            if (v < cp) lo = mid + 1 else hi = mid
        }
        return if (lo < numChars && buf.getInt(charCodesOff + lo * 4) == cp) lo else -1
    }

    private fun uniLogP(id: Int): Double =
        if (id < 0) -lnTotalUni else ln(buf.getLong(uniCountOff + id * 8).toDouble()) - lnTotalUni

    fun logCond(prevCp: Int, curCp: Int): Double = logCondById(charId(prevCp), charId(curCp))

    fun logCondById(id1: Int, id2: Int): Double {
        if (hasBigrams && id1 >= 0 && id2 >= 0) {
            val start = buf.getInt(rowStartOff + id1 * 4)
            val end = buf.getInt(rowStartOff + (id1 + 1) * 4)
            val cnt = bigramCount(start, end, id2)
            if (cnt > 0) {
                val rowTotal = buf.getLong(rowTotalOff + id1 * 8)
                return ln(cnt.toDouble()) - ln(rowTotal.toDouble())
            }
        }
        return BACKOFF_LN + uniLogP(id2)
    }

    private fun bigramCount(start: Int, end: Int, c2Id: Int): Long {
        var lo = start
        var hi = end
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            val v = buf.getInt(biC2Off + mid * 4)
            if (v < c2Id) lo = mid + 1 else hi = mid
        }
        return if (lo < end && buf.getInt(biC2Off + lo * 4) == c2Id) buf.getLong(biCountOff + lo * 8) else 0L
    }

    companion object {
        private val BACKOFF_LN = ln(0.4)

        fun fromFile(file: File): CharBigramLM =
            RandomAccessFile(file, "r").use { raf ->
                val ch = raf.channel
                CharBigramLM(ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()))
            }

        fun fromAssets(context: Context, assetName: String): CharBigramLM {
            val outFile = File(context.filesDir, assetName)
            if (!outFile.exists() || outFile.length() == 0L) {
                val tmp = File(context.filesDir, "$assetName.part")
                context.assets.open(assetName).use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                }
                if (!tmp.renameTo(outFile)) {
                    tmp.delete()
                    throw IOException("failed to install $assetName")
                }
            }
            return fromFile(outFile)
        }
    }
}
