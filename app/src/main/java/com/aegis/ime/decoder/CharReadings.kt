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

package com.aegis.ime.decoder

import com.aegis.ime.dict.BinaryDict

internal class CharReadings private constructor(
    private val codePoints: IntArray,
    private val syllables: Array<List<String>>,
) {
    fun of(codePoint: Int): List<String> {
        val index = codePoints.binarySearch(codePoint)
        return if (index < 0) emptyList() else syllables[index]
    }

    companion object {
        fun from(dict: BinaryDict): CharReadings {
            val weighted = HashMap<Int, MutableList<Pair<String, Int>>>()
            for (syllable in T9Pinyin.SYLLABLES) {
                for (wf in dict.exact(syllable)) {
                    val cp = wf.word.codePointAt(0)
                    if (Character.charCount(cp) != wf.word.length) continue
                    weighted.getOrPut(cp) { ArrayList(2) }.add(syllable to wf.freq)
                }
            }
            val codePoints = weighted.keys.toIntArray().apply { sort() }
            val syllables = Array(codePoints.size) { index ->
                weighted.getValue(codePoints[index]).sortedByDescending { it.second }.map { it.first }.distinct()
            }
            return CharReadings(codePoints, syllables)
        }
    }
}
