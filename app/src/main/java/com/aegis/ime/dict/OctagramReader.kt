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

    internal val maximumRawScore: Double by lazy {
        val units = (buf.capacity() - imageStart) / 4
        var maximum = 0
        for (id in 0 until units) {
            val u = unit(id)
            if (!hasLeaf(u)) continue
            val valueId = id xor offset(u)
            if (valueId !in 0 until units) continue
            maximum = maxOf(maximum, value(unit(valueId)))
        }
        maximum / VALUE_SCALE
    }

    private fun unit(id: Int): Int = buf.getInt(imageStart + id * 4)
    private fun offset(u: Int): Int = (u ushr 10) shl ((u and (1 shl 9)) ushr 6)
    private fun hasLeaf(u: Int): Boolean = ((u ushr 8) and 1) == 1
    private fun value(u: Int): Int = u and 0x7FFFFFFF

    private fun lookup(encoded: ByteArray): Int? {
        var id = 0
        var u = unit(0)
        for (b in encoded) {
            val c = b.toInt() and 0xFF
            id = id xor offset(u) xor c
            u = unit(id)
            if ((u and labelMask) != c) return null
        }
        if (!hasLeaf(u)) return null
        return value(unit(id xor offset(u)))
    }

    fun rawScore(text: String): Double? = lookup(encode(text))?.let { it / VALUE_SCALE }

    companion object {
        private const val VALUE_SCALE = 10000.0
        private const val METADATA_SIZE = 44
        private const val FORMAT_SIZE = 32
        private const val FORMAT_PREFIX = "Rime::Grammar/"

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
            val out = ArrayList<Byte>(text.length * 2)
            var i = 0
            while (i < text.length) {
                val u = text.codePointAt(i)
                i += Character.charCount(u)
                when {
                    u < 0x80 -> out.add(if (u == 0) 0xE0.toByte() else u.toByte())
                    u in 0x4000 until 0xA000 -> {
                        if ((u and 0xFF) == 0) {
                            out.add(0xE1.toByte()); out.add(((u shr 8) + 0x40).toByte())
                        } else {
                            out.add(((u shr 8) + 0x40).toByte()); out.add((u and 0xFF).toByte())
                        }
                    }
                    else -> {
                        var uu = u
                        var bits = 32
                        while (bits > 0 && (uu and 0xFE000000.toInt()) == 0) { bits -= 7; uu = uu shl 7 }
                        var n = (bits + 6) / 7
                        out.add((0xE0 or n).toByte())
                        while (n > 0) { n--; out.add((((uu ushr 25) and 0x7F) or 0x80).toByte()) }
                    }
                }
            }
            return out.toByteArray()
        }
    }
}
