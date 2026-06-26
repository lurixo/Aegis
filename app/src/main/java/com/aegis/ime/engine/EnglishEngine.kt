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

package com.aegis.ime.engine

import com.aegis.ime.dict.BinaryDict

class EnglishEngine(private val dict: BinaryDict) {

    fun suggest(typed: String, limit: Int): List<String> {
        if (typed.isEmpty()) return emptyList()
        val lower = typed.lowercase()
        val out = LinkedHashSet<String>()
        for (wf in dict.prefixByFreq(lower, limit)) out.add(applyCase(typed, wf.word))
        if (out.size < limit) {
            for (w in corrections(lower)) {
                out.add(applyCase(typed, w))
                if (out.size >= limit) break
            }
        }
        return out.toList()
    }

    private fun corrections(word: String): List<String> {
        val results = ArrayList<Pair<String, Int>>()
        val seen = HashSet<String>()
        for (v in edits1(word)) {
            val e = dict.exact(v).firstOrNull() ?: continue
            if (seen.add(e.word)) results.add(e.word to e.freq)
        }
        results.sortByDescending { it.second }
        return results.map { it.first }
    }

    private fun edits1(w: String): Sequence<String> = sequence {
        val n = w.length
        for (i in 0 until n) yield(w.substring(0, i) + w.substring(i + 1))
        for (i in 0 until n - 1) yield(w.substring(0, i) + w[i + 1] + w[i] + w.substring(i + 2))
        for (i in 0 until n) for (c in 'a'..'z') if (c != w[i]) yield(w.substring(0, i) + c + w.substring(i + 1))
        for (i in 0..n) for (c in 'a'..'z') yield(w.substring(0, i) + c + w.substring(i))
    }

    private fun applyCase(typed: String, suggestion: String): String = when {
        typed.length > 1 && typed.all { it.isUpperCase() } -> suggestion.uppercase()
        typed[0].isUpperCase() -> suggestion.replaceFirstChar { it.uppercaseChar() }
        else -> suggestion
    }
}
