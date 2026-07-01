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

/**
 * Produces candidates for a composing buffer.
 *
 * P2 ships [DictEngine] (exact + prefix dictionary lookup). The decoder arrives incrementally:
 *  - P3: DAG segmentation + Viterbi/beam with unigram scoring over the wanxiang dict trie (26-key).
 *  - P4: T9 digit lattice feeding the same decoder.
 *  - P5: n-gram (LM) context scoring.
 */
interface CandidateEngine {
    /**
     * @param composing raw pinyin (26-key) or digit string (T9).
     * @param t9 true when [composing] is a T9 digit sequence rather than letters.
     * @return ordered candidates, best first; empty when there is nothing to show.
     */
    fun candidates(composing: String, t9: Boolean): List<String>

    /**
     * Candidates tagged with how many leading input units each consumes, longest-prefix words first
     * then leading single chars (★G), so picking one can partially commit and continue (★E). Defaults
     * to assuming each candidate covers the whole buffer.
     */
    fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int> = emptySet(), context: CharSequence = ""): List<Cand> =
        candidates(composing, t9).map { Cand(it, composing.length) }

    /** Candidates for an explicit full-pinyin reading (letters) — used by the 9-key reading column. */
    fun candidatesForReading(letters: String): List<String> = emptyList()

    /**
     * Rich, coverage-tagged candidates for an explicit full-pinyin reading (letters) — the locked
     * left-column path (★E). Like [candidatesCovered] but over committed letters rather than the live
     * digit buffer, so locking a reading keeps the full sentence + completions + per-prefix words
     * instead of collapsing to just the best sentence. coveredLen is in LETTERS of [letters].
     *
     * F6 (debug.12): [cuts] are the user's forced 分词 boundaries (positions in [letters]) still inside the
     * active tail, so a locked reading no longer drops them — no decoded word may span a forced boundary.
     */
    fun candidatesForReadingCovered(letters: String, cuts: Set<Int> = emptySet(), context: CharSequence = ""): List<Cand> =
        candidatesForReading(letters).map { Cand(it, letters.length) }

    /**
     * Coverage-tagged candidates for a reading that the user explicitly locked/chose. Unlike free typing,
     * the chosen reading is atomic: a single locked syllable such as `xiang` must not leak shorter prefix
     * readings such as `xian`/`xia` into the grid. The default keeps test doubles source-compatible; the real
     * dictionary engine overrides this with the boundary-aligned decoder.
     */
    fun candidatesForLockedReadingCovered(letters: String, cuts: Set<Int> = emptySet(), context: CharSequence = ""): List<Cand> =
        candidatesForReadingCovered(letters, cuts, context)

    /**
     * ★单字无损 / per-syllable navigation (debug.13). Segment the live buffer into syllables so the UI can
     * navigate syllable positions (UI-1 9-key trailing column, UI-2 26-key pinyin column). [t9] is true
     * when [composing] is a digit sequence. Empty when there is no decoder.
     */
    fun syllables(composing: String, t9: Boolean): List<Syllable> = emptyList()

    /**
     * The COMPLETE single-char homophone set for syllable [index] of [composing] — every 同音字 of that
     * syllable position, frequency-ordered and NOT subject to any top-N / per-length cap. This is the
     * lossless single-char layer the UI lists for a chosen syllable.
     */
    fun homophonesAt(composing: String, t9: Boolean, index: Int): List<String> = emptyList()

    /** Per-syllable split of the locked full-pinyin reading path (9-key ★E): the syllables of [letters]. */
    fun syllablesForReading(letters: String): List<Syllable> = emptyList()

    /** [homophonesAt] for the locked full-pinyin reading path (letters) — used by the 9-key left column. */
    fun homophonesForReadingAt(letters: String, index: Int): List<String> = emptyList()

    /** Learned next-word predictions to show on an empty buffer after [prevWord]. */
    fun predict(prevWord: String?): List<String> = emptyList()

    /** Record that the user committed [word] after [prevWord] (for adaptation). */
    fun learn(prevWord: String?, word: String) {}

    /** E4 hot-toggle (debug.16): update the active fuzzy rule set at query time (no rebuild). Default no-op. */
    fun setFuzzyRules(rules: Set<String>) {}
}
