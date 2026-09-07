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

internal object PinyinCorrection {

    enum class Edit(val penalty: Double) {
        TRANSPOSE(1.0),
        DELETE(1.5),
        INSERT(1.5),
        SUBSTITUTE_NEAR(1.5),
        SUBSTITUTE_FAR(3.0),
    }

    data class Split(val prefix: String, val tail: String)

    data class Variant(val input: String, val edit: Edit, val partial: Split? = null)

    const val MIN_LEN = 2
    const val MAX_LEN = 12
    const val DEFAULT_LIMIT = 32

    private enum class Mode(val alphabet: String) {
        LETTERS("abcdefghijklmnopqrstuvwxyz"),
        DIGITS("23456789"),
    }

    private val LETTER_ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

    private val letterNeighbours: Map<Char, Set<Char>> = buildMap {
        for ((r, row) in LETTER_ROWS.withIndex()) {
            for ((i, c) in row.withIndex()) {
                val near = HashSet<Char>()
                row.getOrNull(i - 1)?.let(near::add)
                row.getOrNull(i + 1)?.let(near::add)
                LETTER_ROWS.getOrNull(r - 1)?.let { above ->
                    above.getOrNull(i)?.let(near::add)
                    above.getOrNull(i + 1)?.let(near::add)
                }
                LETTER_ROWS.getOrNull(r + 1)?.let { below ->
                    below.getOrNull(i - 1)?.let(near::add)
                    below.getOrNull(i)?.let(near::add)
                }
                put(c, near)
            }
        }
    }

    private val digitNeighbours: Map<Char, Set<Char>> = mapOf(
        '2' to "35", '3' to "26", '4' to "57", '5' to "2468",
        '6' to "359", '7' to "48", '8' to "579", '9' to "68",
    ).mapValues { (_, v) -> v.toSet() }

    private fun modeOf(input: String): Mode? = when {
        input.isEmpty() -> null
        input.all { it in 'a'..'z' } -> Mode.LETTERS
        input.all { it in '2'..'9' } -> Mode.DIGITS
        else -> null
    }

    fun classifies(input: String): Boolean = modeOf(input) != null

    fun isNeighbour(a: Char, b: Char): Boolean =
        letterNeighbours[a]?.contains(b) == true || digitNeighbours[a]?.contains(b) == true

    fun fullySegmentable(input: String): Boolean = when (modeOf(input)) {
        Mode.LETTERS -> T9Pinyin.segmentLetters(input) != null
        Mode.DIGITS -> T9Pinyin.segment(input) != null
        null -> false
    }

    fun syllableCount(input: String): Int? = when (modeOf(input)) {
        Mode.LETTERS -> T9Pinyin.segmentLetters(input)?.size
        Mode.DIGITS -> T9Pinyin.segment(input)?.size
        null -> null
    }

    fun partialSplit(input: String): Split? {
        val mode = modeOf(input) ?: return null
        for (cut in input.length - 1 downTo 0) {
            val tail = input.substring(cut)
            val isPrefix = when (mode) {
                Mode.LETTERS -> T9Pinyin.isSyllablePrefix(tail)
                Mode.DIGITS -> T9Pinyin.isSyllableDigitPrefix(tail)
            }
            if (!isPrefix) continue
            val prefix = input.substring(0, cut)
            if (prefix.isEmpty() || fullySegmentable(prefix)) return Split(prefix, tail)
        }
        return null
    }

    fun acceptable(input: String): Boolean = fullySegmentable(input) || partialSplit(input) != null

    private class Ranked(val variant: Variant, val cost: Double)

    fun variants(
        input: String,
        limit: Int = DEFAULT_LIMIT,
        includeFarSubstitutions: Boolean = false,
    ): List<Variant> {
        if (limit <= 0 || input.length !in MIN_LEN..MAX_LEN) return emptyList()
        val mode = modeOf(input) ?: return emptyList()
        val neighbours = if (mode == Mode.LETTERS) letterNeighbours else digitNeighbours
        val best = HashMap<String, Edit>()
        fun offer(candidate: String, edit: Edit) {
            if (candidate == input) return
            val prev = best[candidate]
            if (prev != null && prev.penalty <= edit.penalty) return
            best[candidate] = edit
        }
        val n = input.length
        for (i in 0 until n - 1) {
            if (input[i] == input[i + 1]) continue
            offer(input.substring(0, i) + input[i + 1] + input[i] + input.substring(i + 2), Edit.TRANSPOSE)
        }
        for (i in 0 until n) offer(input.substring(0, i) + input.substring(i + 1), Edit.DELETE)
        for (i in 0..n) {
            val head = input.substring(0, i)
            val rest = input.substring(i)
            for (c in mode.alphabet) offer(head + c + rest, Edit.INSERT)
        }
        for (i in 0 until n) {
            val head = input.substring(0, i)
            val rest = input.substring(i + 1)
            val near = neighbours[input[i]].orEmpty()
            val substitutions = if (includeFarSubstitutions) mode.alphabet.asIterable() else near
            for (c in substitutions) {
                if (c == input[i]) continue
                offer(head + c + rest, if (c in near) Edit.SUBSTITUTE_NEAR else Edit.SUBSTITUTE_FAR)
            }
        }
        val ranked = ArrayList<Ranked>()
        for ((candidate, edit) in best) {
            val fullCost = T9Pinyin.segmentCost(candidate)
            if (fullCost != null) {
                ranked.add(Ranked(Variant(candidate, edit), fullCost))
                continue
            }
            val split = partialSplit(candidate) ?: continue
            val cost = if (split.prefix.isEmpty()) 0.0 else T9Pinyin.segmentCost(split.prefix) ?: 0.0
            ranked.add(Ranked(Variant(candidate, edit, split), cost))
        }
        ranked.sortWith(
            compareBy<Ranked> { it.variant.edit.penalty }
                .thenBy { it.variant.partial != null }
                .thenBy { it.cost }
                .thenBy { it.variant.input },
        )
        val out = ArrayList<Variant>(minOf(limit, ranked.size))
        for (r in ranked) {
            if (out.size >= limit) break
            out.add(r.variant)
        }
        return out
    }
}
