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
import com.aegis.ime.dict.Fuzzy
import com.aegis.ime.user.UserModel

internal class StoredReadingJudge(
    private val dict: BinaryDict,
    private val readsAs: (word: String, reading: String) -> Boolean,
    private val spell: (word: String, reading: String) -> String,
) {
    private val table: CharReadings by lazy(LazyThreadSafetyMode.NONE) { charReadings() }
    private val keys = HashMap<String, String>()
    private val looseSpellings = HashMap<String, List<String>>()
    private var budget = 0

    fun repairs(rows: List<Pair<String, String>>): List<UserModel.ReadingRepair> {
        val out = ArrayList<UserModel.ReadingRepair>()
        for ((reading, word) in rows) verdict(reading, word)?.let(out::add)
        return out
    }

    private fun verdict(reading: String, word: String): UserModel.ReadingRepair? {
        if (!assessable(reading, word)) return null
        val chars = word.codePoints().toArray()
        val letters = reach(chars, reading) { it }
        if (letters[reading.length * (chars.size + 1) + chars.size]) return null
        if (T9Pinyin.segmentLetters(reading) == null) return null
        if (readsAs(word, reading) || dict.containsExactWord(reading, word)) return null
        val digits = T9Pinyin.toT9(reading)
        val keyed = reach(chars, digits, ::keysOf)
        if (keyed[digits.length * (chars.size + 1) + chars.size]) {
            val spelled = spell(word, reading)
            if (spelled.isNotEmpty() && spelled != reading) return UserModel.ReadingRepair(reading, word, spelled)
        }
        if (truncated(word, chars, reading, letters) { it }) return UserModel.ReadingRepair(reading, word, null)
        if (readsLeadingCharsLoosely(chars, reading)) return null
        if (truncated(word, chars, digits, keyed, ::keysOf)) return UserModel.ReadingRepair(reading, word, null)
        return null
    }

    private fun assessable(reading: String, word: String): Boolean {
        if (reading.length < 2 || reading.any { it !in 'a'..'z' }) return false
        if (word.codePointCount(0, word.length) < 2) return false
        var i = 0
        while (i < word.length) {
            val cp = word.codePointAt(i)
            if (!Character.isIdeographic(cp)) return false
            i += Character.charCount(cp)
        }
        return true
    }

    private class CharReadings(val codePoints: IntArray, val syllables: Array<List<String>>)

    private fun charReadings(): CharReadings {
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

    private fun readings(codePoint: Int): List<String> {
        val index = table.codePoints.binarySearch(codePoint)
        return if (index < 0) emptyList() else table.syllables[index]
    }

    private fun keysOf(syllable: String): String = keys.getOrPut(syllable) { T9Pinyin.toT9(syllable) }

    private fun reach(chars: IntArray, input: String, unit: (String) -> String): BooleanArray {
        val width = chars.size + 1
        val reach = BooleanArray((input.length + 1) * width)
        reach[0] = true
        for (p in 0 until input.length) for (i in chars.indices) {
            if (!reach[p * width + i]) continue
            for (syllable in readings(chars[i])) {
                val u = unit(syllable)
                if (input.startsWith(u, p)) reach[(p + u.length) * width + i + 1] = true
            }
        }
        return reach
    }

    private fun truncated(
        word: String,
        chars: IntArray,
        input: String,
        reach: BooleanArray,
        unit: (String) -> String,
    ): Boolean {
        val width = chars.size + 1
        budget = MAX_TAIL_READINGS
        var offset = 0
        for (i in chars.indices) {
            val tail = word.substring(offset)
            for (p in 0 until input.length) {
                if (reach[p * width + i] && tailTruncated(tail, chars, i, input, p, unit)) return true
            }
            offset += Character.charCount(chars[i])
        }
        return false
    }

    private fun tailTruncated(
        tail: String,
        chars: IntArray,
        from: Int,
        input: String,
        start: Int,
        unit: (String) -> String,
    ): Boolean {
        val spelled = StringBuilder()
        fun walk(i: Int, pos: Int, longer: Boolean): Boolean {
            if (i == chars.size) {
                if (pos < input.length || !longer || budget <= 0) return false
                budget--
                return dict.containsExactWord(spelled.toString(), tail)
            }
            val mark = spelled.length
            val rest = input.length - pos
            for (syllable in readings(chars[i])) {
                if (budget <= 0) return false
                val u = unit(syllable)
                val advance = when {
                    rest == 0 -> 0
                    input.startsWith(u, pos) -> u.length
                    u.length > rest && u.regionMatches(0, input, pos, rest) -> rest
                    else -> continue
                }
                spelled.append(syllable)
                val found = walk(i + 1, pos + advance, longer || advance != u.length)
                spelled.setLength(mark)
                if (found) return true
            }
            return false
        }
        return walk(from, start, false)
    }

    private fun readsLeadingCharsLoosely(chars: IntArray, reading: String): Boolean {
        val width = chars.size + 1
        val reach = BooleanArray((reading.length + 1) * width)
        reach[0] = true
        for (p in 0 until reading.length) for (i in chars.indices) {
            if (!reach[p * width + i]) continue
            for (syllable in readings(chars[i])) {
                for (spelling in loose(syllable)) {
                    if (reading.startsWith(spelling, p)) reach[(p + spelling.length) * width + i + 1] = true
                }
            }
        }
        return (1..chars.size).any { reach[reading.length * width + it] }
    }

    private fun loose(syllable: String): List<String> =
        looseSpellings.getOrPut(syllable) { Fuzzy.syllableVariants(syllable, Fuzzy.DEFAULT_RULE_KEYS) }

    private companion object {
        const val MAX_TAIL_READINGS = 64
    }
}
