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

import com.aegis.ime.decoder.Cand
import com.aegis.ime.decoder.PinyinDecoder
import com.aegis.ime.decoder.Syllable
import com.aegis.ime.dict.BinaryDict

class DictEngine(
    pinyinDict: BinaryDict?,
    t9Dict: BinaryDict?,
) : CandidateEngine {
    private val decoder = pinyinDict?.let {
        PinyinDecoder(
            it,
        )
    }
    private val t9Decoder = t9Dict?.let {
        PinyinDecoder(
            it,
        )
    }

    override val supportsChinese: Boolean = decoder != null || t9Decoder != null

    override fun candidates(composing: String, t9: Boolean): List<String> =
        candidatesCovered(composing, t9).map { it.word }

    override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>): List<Cand> {
        if (composing.isEmpty()) return emptyList()
        val d = if (t9) t9Decoder else decoder
        val out = d?.decodeCovered(composing, MAX_CANDIDATES, cuts) ?: emptyList()
        return if (t9) out.filterNot { c -> c.word.all { it.code < 128 } } else out
    }

    override fun candidatesForLockedReadingCovered(letters: String, cuts: Set<Int>): List<Cand> {
        if (letters.isEmpty()) return emptyList()
        return decoder?.decodeCoveredAtomic(letters, MAX_CANDIDATES, cuts) ?: emptyList()
    }

    override fun syllables(composing: String, t9: Boolean): List<Syllable> =
        syllables(composing, t9, emptySet())

    override fun syllables(composing: String, t9: Boolean, cuts: Set<Int>): List<Syllable> {
        if (composing.isEmpty()) return emptyList()
        return (if (t9) t9Decoder else decoder)?.syllables(composing, cuts) ?: emptyList()
    }

    override fun syllablesForReading(letters: String): List<Syllable> =
        syllablesForReading(letters, emptySet())

    override fun syllablesForReading(letters: String, cuts: Set<Int>): List<Syllable> =
        if (letters.isEmpty()) emptyList() else decoder?.syllables(letters, cuts) ?: emptyList()

    private companion object {
        const val MAX_CANDIDATES = 30
    }
}
