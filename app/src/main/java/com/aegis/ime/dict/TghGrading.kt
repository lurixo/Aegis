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

import java.nio.ByteBuffer
import java.nio.ByteOrder

class TghGrading private constructor(
    private val codePoints: IntArray,
    private val levels: ByteArray,
) {

    fun level(codePoint: Int): Int {
        var lo = 0
        var hi = codePoints.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (codePoints[mid] < codePoint) lo = mid + 1 else hi = mid
        }
        if (lo >= codePoints.size || codePoints[lo] != codePoint) return LEVEL_OUT
        return levelAt(lo)
    }

    private fun levelAt(index: Int): Int =
        ((levels[index ushr 2].toInt() ushr (2 * (index and 3))) and 0x3) + 1

    companion object {
        const val LEVEL_OUT = 0
        const val ENTRY_COUNT = 8105
        const val LEVEL1_COUNT = 3500
        const val LEVEL2_COUNT = 3000
        const val LEVEL3_COUNT = 1605
        private const val RESOURCE = "/com/aegis/ime/dict/aegis_tgh.bin"

        val bundled: TghGrading by lazy { parse(readBundledResource()) }

        private fun readBundledResource(): ByteArray {
            val stream = TghGrading::class.java.getResourceAsStream(RESOURCE)
                ?: error("missing grading resource $RESOURCE")
            return stream.use { it.readBytes() }
        }

        fun parse(bytes: ByteArray): TghGrading {
            require(bytes.size > HEADER_LEN) { "grading table too small" }
            val header = ByteBuffer.wrap(bytes, 0, HEADER_LEN).order(ByteOrder.LITTLE_ENDIAN)
            require(
                bytes[0] == 'A'.code.toByte() && bytes[1] == 'E'.code.toByte() &&
                    bytes[2] == 'G'.code.toByte() && bytes[3] == 'T'.code.toByte()
            ) { "bad grading magic" }
            require(header.getInt(4) == VERSION) { "unsupported grading version" }
            val count = header.getInt(8)
            require(count == ENTRY_COUNT) { "grading holds $count entries, expected $ENTRY_COUNT" }
            val codePoints = IntArray(count)
            var pos = HEADER_LEN
            var previous = 0
            for (i in 0 until count) {
                var shift = 0
                var delta = 0
                while (true) {
                    require(pos < bytes.size) { "grading code points run past the end of the table at $i" }
                    require(shift <= MAX_VARINT_SHIFT) { "grading holds an over-wide code point delta at $i" }
                    val b = bytes[pos++].toInt()
                    delta = delta or ((b and 0x7F) shl shift)
                    if (b and 0x80 == 0) break
                    shift += 7
                }
                require(delta > 0) { "grading code points are not ascending at $i" }
                previous += delta
                require(Character.isValidCodePoint(previous)) { "grading holds an invalid code point at $i" }
                codePoints[i] = previous
            }
            val packedLen = (count + 3) / 4
            require(pos + packedLen == bytes.size) { "grading extent is $pos+$packedLen, file is ${bytes.size}" }
            val grading = TghGrading(codePoints, bytes.copyOfRange(pos, pos + packedLen))
            var first = 0
            var second = 0
            var third = 0
            for (i in 0 until count) when (val level = grading.levelAt(i)) {
                1 -> first++
                2 -> second++
                3 -> third++
                else -> throw IllegalArgumentException("grading holds level $level at $i")
            }
            require(first == LEVEL1_COUNT && second == LEVEL2_COUNT && third == LEVEL3_COUNT) {
                "grading level counts $first/$second/$third, expected $LEVEL1_COUNT/$LEVEL2_COUNT/$LEVEL3_COUNT"
            }
            return grading
        }

        private const val VERSION = 1
        private const val HEADER_LEN = 12
        private const val MAX_VARINT_SHIFT = 14
    }
}
