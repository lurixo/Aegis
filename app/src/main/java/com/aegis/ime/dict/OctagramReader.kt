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
 * Clean-room reader for the wanxiang "离线大模型" octagram `.gram` (≈401 MB) — the optional
 * top-tier context model. Format reverse-engineered from librime-octagram + darts-clone:
 *
 *   byte 0:  Metadata { char format[32]; u32 checksum; u32 double_array_size; int32 OffsetPtr }
 *   image:   at (40 + OffsetPtr), a darts-clone double-array of `double_array_size` 4-byte units.
 *
 * A collocation score is the leaf value of the encoded key `encode(context + word)`, /10000
 * (log-domain, ≥0; larger = more frequent). Lookup is darts-clone `traverse`; keys use the
 * octagram per-codepoint encoding (`encode`). Not bundled — loaded only if the user downloads it.
 */
class OctagramReader private constructor(
    private val buf: ByteBuffer,
    private val imageStart: Int,
) {
    private val labelMask = (1 shl 31) or 0xFF

    private fun unit(id: Int): Int = buf.getInt(imageStart + id * 4)
    private fun offset(u: Int): Int = (u ushr 10) shl ((u and (1 shl 9)) ushr 6)
    private fun hasLeaf(u: Int): Boolean = ((u ushr 8) and 1) == 1
    private fun value(u: Int): Int = u and 0x7FFFFFFF

    /** darts-clone exact match: leaf value for [encoded], or null if the key is absent. */
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

    /** Collocation log-score for [text] (a context+word concatenation), or null if unseen. */
    fun rawScore(text: String): Double? = lookup(encode(text))?.let { it / VALUE_SCALE }

    companion object {
        private const val VALUE_SCALE = 10000.0

        fun fromFile(file: File): OctagramReader {
            RandomAccessFile(file, "r").use { raf ->
                val ch = raf.channel
                val mapped = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size())
                mapped.order(ByteOrder.LITTLE_ENDIAN)
                val arrayOffset = mapped.getInt(40) // OffsetPtr is relative to its own address (byte 40)
                return OctagramReader(mapped, 40 + arrayOffset)
            }
        }

        /** Present only if the user downloaded the .gram into filesDir/downloaded/. */
        fun fromDownloads(context: Context, name: String): OctagramReader? {
            val f = File(File(context.filesDir, "downloaded"), name)
            return if (f.exists() && f.length() > 1024) fromFile(f) else null
        }

        /**
         * Octagram key encoding (port of librime-octagram `encode`): per Unicode code point.
         * CJK (U+4000..U+9FFF) take the 2-byte middle branch; ASCII a single byte.
         */
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
