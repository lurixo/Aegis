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

package com.aegis.ime.ime

class PreeditModel(
    val text: String,
    val rawIndexAt: IntArray,
    val lockedRanges: List<IntRange>,
    val caret: Int,
    val editableFrom: Int,
) {
    val rawLength: Int get() = rawIndexAt.last()

    fun displayCaret(): Int {
        var best = editableFrom
        for (p in editableFrom..text.length) if (rawIndexAt[p] <= caret) best = p
        return best
    }

    fun rawIndexForDisplay(position: Int): Int = rawIndexAt[position.coerceIn(0, text.length)]

    companion object {
        fun align(
            text: String,
            prefixLength: Int,
            raw: String,
            lockedDisplayLengths: List<Int>,
            lockedInputLengths: List<Int>,
            caret: Int,
        ): PreeditModel {
            val prefix = prefixLength.coerceIn(0, text.length)
            val rawAt = IntArray(text.length + 1)
            val ranges = ArrayList<IntRange>(lockedDisplayLengths.size)
            var p = prefix
            var r = 0
            var lockStart = 0
            for (i in lockedDisplayLengths.indices) {
                val displayLen = lockedDisplayLengths[i]
                val inputLen = lockedInputLengths.getOrElse(i) { displayLen }
                r = (lockStart + (inputLen - displayLen).coerceAtLeast(0)).coerceAtMost(raw.length)
                val rangeStart = p
                var k = 0
                while (k < displayLen && p < text.length && text[p] != '\'') {
                    r = (r + 1).coerceAtMost(raw.length)
                    p++
                    rawAt[p] = r
                    k++
                }
                ranges.add(rangeStart until p)
                lockStart = (lockStart + inputLen).coerceAtMost(raw.length)
                r = lockStart
                if (p < text.length && text[p] == '\'') {
                    p++
                    rawAt[p] = r
                }
            }
            while (p < text.length) {
                val c = text[p]
                if (c == '\'') {
                    if (r < raw.length && raw[r] == '\'') r++
                } else {
                    while (r < raw.length && raw[r] == '\'') r++
                    if (r < raw.length) r++
                }
                p++
                rawAt[p] = r
            }
            rawAt[text.length] = raw.length
            for (i in 1..text.length) if (rawAt[i] < rawAt[i - 1]) rawAt[i] = rawAt[i - 1]
            return PreeditModel(text, rawAt, ranges, caret.coerceIn(0, raw.length), prefix)
        }
    }
}
