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
import com.aegis.ime.decoder.Syllable

interface CandidateEngine {
    fun candidates(composing: String, t9: Boolean): List<String>

    fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int> = emptySet(), context: CharSequence = ""): List<Cand> =
        candidates(composing, t9).map { Cand(it, composing.length) }

    fun candidatesForReading(letters: String): List<String> = emptyList()

    fun candidatesForReadingCovered(letters: String, cuts: Set<Int> = emptySet(), context: CharSequence = ""): List<Cand> =
        candidatesForReading(letters).map { Cand(it, letters.length) }

    fun candidatesForLockedReadingCovered(letters: String, cuts: Set<Int> = emptySet(), context: CharSequence = ""): List<Cand> =
        candidatesForReadingCovered(letters, cuts, context)

    fun syllables(composing: String, t9: Boolean): List<Syllable> = emptyList()

    fun homophonesAt(composing: String, t9: Boolean, index: Int): List<String> = emptyList()

    fun syllablesForReading(letters: String): List<Syllable> = emptyList()

    fun homophonesForReadingAt(letters: String, index: Int): List<String> = emptyList()

    fun predict(prevWord: String?): List<String> = emptyList()

    fun learn(prevWord: String?, word: String) {}

    fun learnWord(reading: String, word: String, assembled: Boolean) {}

    fun setFuzzyRules(rules: Set<String>) {}
}
