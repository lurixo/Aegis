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

data class WordReading(val syllables: List<String>, val choices: List<List<String>>, val ambiguous: Boolean) {
    val reading: String get() = syllables.joinToString("")
}

internal class WordAnnotator(private val dict: BinaryDict) {
    private val table: CharReadings by lazy { CharReadings.from(dict) }

    fun annotate(word: String): WordReading? {
        if (word.isEmpty()) return null
        val chars = word.codePoints().toArray()
        if (chars.any { !Character.isIdeographic(it) }) return null
        val options = chars.map { cp -> table.of(cp).ifEmpty { return null } }
        val chosen = arrayOfNulls<String>(chars.size)
        for (i in chars.indices) if (options[i].size == 1) chosen[i] = options[i][0]
        var budget = MAX_LOOKUPS
        for (len in chars.size downTo 2) {
            for (start in 0..chars.size - len) {
                if ((start until start + len).all { chosen[it] != null }) continue
                if (budget <= 0) break
                val piece = String(chars, start, len)
                var best: List<String>? = null
                var bestFreq = Int.MIN_VALUE
                var combos = MAX_COMBOS
                val picked = ArrayList<String>(len)
                fun walk(k: Int) {
                    if (budget <= 0 || combos <= 0) return
                    if (k == len) {
                        budget--
                        combos--
                        val freq = dict.exactWordFreq(picked.joinToString(""), piece) ?: return
                        if (freq > bestFreq) {
                            bestFreq = freq
                            best = ArrayList(picked)
                        }
                        return
                    }
                    for (syllable in chosen[start + k]?.let(::listOf) ?: options[start + k]) {
                        picked.add(syllable)
                        walk(k + 1)
                        picked.removeAt(picked.lastIndex)
                    }
                }
                walk(0)
                best?.forEachIndexed { k, syllable -> chosen[start + k] = syllable }
            }
        }
        val settled = List(chars.size) { chosen[it] ?: options[it][0] }
        val typed = options.map { listed ->
            PinyinDecoder.INPUT_ALIASES.entries.firstOrNull { (_, targets) -> targets.any(listed::contains) }?.key
        }
        val entry = typed.any { it != null } && chosen.none { it == null } &&
            dict.containsExactWord(settled.joinToString(""), word)
        val syllables = List(chars.size) { typed[it]?.takeUnless { entry } ?: settled[it] }
        return WordReading(
            syllables = syllables,
            choices = List(chars.size) { i ->
                val listed = (listOfNotNull(typed[i]) + options[i]).distinct().take(MAX_CHOICES)
                if (syllables[i] in listed) listed else listed.dropLast(1) + syllables[i]
            },
            ambiguous = chosen.any { it == null },
        )
    }

    private companion object {
        const val MAX_LOOKUPS = 4096
        const val MAX_COMBOS = 64
        const val MAX_CHOICES = 6
    }
}
