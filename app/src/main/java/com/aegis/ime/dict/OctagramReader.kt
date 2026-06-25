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

class OctagramReader private constructor(
    private val buf: ByteBuffer,
    private val imageStart: Int,
) {
    private val labelMask = (1 shl 31) or 0xFF

    private val unitCount = (buf.limit() - imageStart) / 4

    private fun inImage(id: Int): Boolean = id >= 0 && id < unitCount

    private fun unit(id: Int): Int = buf.getInt(imageStart + id * 4)
    private fun offset(u: Int): Int = (u ushr 10) shl ((u and (1 shl 9)) ushr 6)
    private fun hasLeaf(u: Int): Boolean = ((u ushr 8) and 1) == 1
    private fun value(u: Int): Int = u and 0x7FFFFFFF

    private fun lookup(encoded: ByteArray, from: Int = 0, to: Int = encoded.size): Int? {
        var id = 0
        var u = unit(0)
        for (i in from until to) {
            val c = encoded[i].toInt() and 0xFF
            id = id xor offset(u) xor c
            if (!inImage(id)) return null
            u = unit(id)
            if ((u and labelMask) != c) return null
        }
        if (!hasLeaf(u)) return null
        val leaf = id xor offset(u)
        if (!inImage(leaf)) return null
        return value(unit(leaf))
    }

    fun rawScore(text: String): Double? = lookup(encode(text))?.let { it / VALUE_SCALE }

    fun bestSuffixScore(text: String, startLimit: Int): Double {
        var best = 0.0
        var start = 0
        while (start < startLimit && start < text.length) {
            val found = lookupFrom(text, start)
            if (found != ABSENT) {
                val score = found / VALUE_SCALE
                if (score > best) best = score
            }
            start += Character.charCount(text.codePointAt(start))
        }
        return best
    }

    private fun lookupFrom(text: String, from: Int): Int {
        var id = 0
        var u = unit(0)
        var i = from
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val packed = pack(cp)
            val count = (packed ushr PACKED_COUNT_SHIFT).toInt()
            for (k in 0 until count) {
                val c = ((packed ushr (8 * k)) and 0xFF).toInt()
                id = id xor offset(u) xor c
                if (!inImage(id)) return ABSENT
                u = unit(id)
                if ((u and labelMask) != c) return ABSENT
            }
            i += Character.charCount(cp)
        }
        if (!hasLeaf(u)) return ABSENT
        val leaf = id xor offset(u)
        if (!inImage(leaf)) return ABSENT
        return value(unit(leaf))
    }

    companion object {
        private const val VALUE_SCALE = 10000.0
        private const val METADATA_SIZE = 44
        private const val FORMAT_SIZE = 32
        private const val FORMAT_PREFIX = "Rime::Grammar/"
        private const val MAX_BYTES_PER_CHAR = 6
        private const val PACKED_COUNT_SHIFT = 56
        private const val ABSENT = -1

        fun fromFile(file: File): OctagramReader {
            RandomAccessFile(file, "r").use { raf ->
                val ch = raf.channel
                require(ch.size() >= METADATA_SIZE)
                val mapped = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size())
                mapped.order(ByteOrder.LITTLE_ENDIAN)
                val formatBytes = ByteArray(FORMAT_SIZE)
                mapped.get(formatBytes)
                val formatEnd = formatBytes.indexOf(0.toByte()).let { if (it < 0) FORMAT_SIZE else it }
                require(String(formatBytes, 0, formatEnd, Charsets.US_ASCII).startsWith(FORMAT_PREFIX))
                val arraySize = mapped.getInt(36).toLong() and 0xffffffffL
                val arrayOffset = mapped.getInt(40)
                val imageStart = 40L + arrayOffset.toLong()
                val imageBytes = arraySize * 4L
                require(
                    arrayOffset != 0 &&
                        arraySize > 0L &&
                        imageBytes <= ch.size() &&
                        imageStart in METADATA_SIZE.toLong()..(ch.size() - imageBytes) &&
                        imageStart + imageBytes == ch.size() &&
                        ch.size() <= Int.MAX_VALUE.toLong()
                )
                return OctagramReader(mapped, imageStart.toInt())
            }
        }

        fun fromDownloads(context: Context, name: String): OctagramReader? {
            val f = EngineAssets.downloadedOverride(File(context.filesDir, "downloaded"), name, minBytes = 1025L)
            return if (f != null) fromFile(f) else null
        }

        fun encode(text: String): ByteArray {
            val out = ByteArray(text.length * MAX_BYTES_PER_CHAR)
            var size = 0
            var i = 0
            while (i < text.length) {
                val u = text.codePointAt(i)
                i += Character.charCount(u)
                size = encodeInto(u, out, size)
            }
            return out.copyOf(size)
        }

        private fun encodeInto(u: Int, out: ByteArray, at: Int): Int {
            val packed = pack(u)
            val count = (packed ushr PACKED_COUNT_SHIFT).toInt()
            for (k in 0 until count) out[at + k] = (packed ushr (8 * k)).toByte()
            return at + count
        }

        private fun pack(u: Int): Long {
            var packed: Long
            var size: Int
            when {
                u < 0x80 -> {
                    packed = if (u == 0) 0xE0L else u.toLong()
                    size = 1
                }
                u in 0x4000 until 0xA000 -> {
                    packed = if ((u and 0xFF) == 0) {
                        0xE1L or (((u shr 8) + 0x40).toLong() shl 8)
                    } else {
                        ((u shr 8) + 0x40).toLong() or ((u and 0xFF).toLong() shl 8)
                    }
                    size = 2
                }
                else -> {
                    var uu = u
                    var bits = 32
                    while (bits > 0 && (uu and 0xFE000000.toInt()) == 0) { bits -= 7; uu = uu shl 7 }
                    var n = (bits + 6) / 7
                    packed = (0xE0 or n).toLong()
                    size = 1
                    while (n > 0) { n--; packed = packed or ((((uu ushr 25) and 0x7F) or 0x80).toLong() shl (8 * size)); size++ }
                }
            }
            return packed or (size.toLong() shl PACKED_COUNT_SHIFT)
        }
    }
}
