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
import com.aegis.ime.decoder.CANDIDATE_PAGE_SIZE
import com.aegis.ime.decoder.CandidateContinuation
import com.aegis.ime.decoder.CandidatePage
import com.aegis.ime.decoder.ListCandidatePageSource
import com.aegis.ime.decoder.Syllable
import com.aegis.ime.decoder.continueCandidatePage
import com.aegis.ime.decoder.firstCandidatePage

interface CandidateEngine {
    val supportsChinese: Boolean get() = false

    fun candidates(composing: String, t9: Boolean): List<String>

    fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int> = emptySet(), context: CharSequence = ""): List<Cand> =
        candidates(composing, t9).map { Cand(it, composing.length) }

    fun candidatesCoveredPage(
        composing: String,
        t9: Boolean,
        inputEpoch: Long,
        cuts: Set<Int> = emptySet(),
        context: CharSequence = "",
        pageSize: Int = CANDIDATE_PAGE_SIZE,
    ): CandidatePage<Cand> = firstCandidatePage(
        ListCandidatePageSource(candidatesCovered(composing, t9, cuts, context)),
        inputEpoch,
        pageSize,
    )

    fun candidatesForLockedReadingCovered(letters: String, cuts: Set<Int> = emptySet(), context: CharSequence = ""): List<Cand> =
        emptyList()

    fun candidatesForLockedReadingCoveredPage(
        letters: String,
        inputEpoch: Long,
        cuts: Set<Int> = emptySet(),
        context: CharSequence = "",
        pageSize: Int = CANDIDATE_PAGE_SIZE,
    ): CandidatePage<Cand> = firstCandidatePage(
        ListCandidatePageSource(candidatesForLockedReadingCovered(letters, cuts, context)),
        inputEpoch,
        pageSize,
    )

    fun syllables(composing: String, t9: Boolean): List<Syllable> = emptyList()

    fun syllables(composing: String, t9: Boolean, cuts: Set<Int>): List<Syllable> = syllables(composing, t9)

    fun homophonesAt(composing: String, t9: Boolean, index: Int): List<String> = emptyList()

    fun homophonesAt(composing: String, t9: Boolean, index: Int, cuts: Set<Int>): List<String> =
        homophonesAt(composing, t9, index)

    fun syllablesForReading(letters: String): List<Syllable> = emptyList()

    fun syllablesForReading(letters: String, cuts: Set<Int>): List<Syllable> = syllablesForReading(letters)

    fun homophonesForReadingAt(letters: String, index: Int): List<String> = emptyList()

    fun homophonesForReadingAt(letters: String, index: Int, cuts: Set<Int>): List<String> =
        homophonesForReadingAt(letters, index)

    fun homophonesForReadingAtPage(
        letters: String,
        index: Int,
        inputEpoch: Long,
        cuts: Set<Int> = emptySet(),
        pageSize: Int = CANDIDATE_PAGE_SIZE,
    ): CandidatePage<String> = firstCandidatePage(
        ListCandidatePageSource(homophonesForReadingAt(letters, index, cuts)),
        inputEpoch,
        pageSize,
    )

    fun predict(prevWord: String?): List<String> = emptyList()

    fun predictPage(
        prevWord: String?,
        inputEpoch: Long,
        pageSize: Int = CANDIDATE_PAGE_SIZE,
    ): CandidatePage<String> = firstCandidatePage(
        ListCandidatePageSource(predict(prevWord)),
        inputEpoch,
        pageSize,
    )

    fun <T> continuePage(
        continuation: CandidateContinuation<T>,
        inputEpoch: Long,
        pageSize: Int = CANDIDATE_PAGE_SIZE,
    ): CandidatePage<T> = continueCandidatePage(continuation, inputEpoch, pageSize)

    fun learn(prevWord: String?, word: String) {}

    fun learnWord(reading: String, word: String, assembled: Boolean) {}

    fun setFuzzyRules(rules: Set<String>) {}
}
