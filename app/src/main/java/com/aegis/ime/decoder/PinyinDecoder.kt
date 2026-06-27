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
import com.aegis.ime.dict.CharBigramLM
import com.aegis.ime.dict.Fuzzy
import com.aegis.ime.user.UserModel
import kotlin.math.ln

/** A candidate word plus how many leading input units (digits/letters) it consumes — for ★E
 *  per-syllable partial commit and the ★G mixed long-word/short-word/single-char grid. */
data class Cand(val word: String, val coveredLen: Int)

/**
 * Word-lattice Viterbi decoder for full-pinyin input (26-key letters or T9 digits — the dict
 * key space differs, the algorithm doesn't).
 *
 * An edge p→q is any dict key matching input[p,q); it expands to the top-[EDGE_N] candidate words
 * for that key. The path score sums each word's unigram log-probability ln(freq/totalFreq) plus,
 * across a word boundary, λ·lnP(firstChar | prevLastChar) from the char bigram [lm]. Because the
 * boundary score depends on the previous word's last char, the DP state is (position, lastChar) —
 * so context can change which homograph wins, not just the segmentation.
 *
 * With [lm] = null it degrades to single-best unigram (P3 behaviour). n-gram context = P5.
 */
class PinyinDecoder(
    private val dict: BinaryDict,
    private val lm: CharBigramLM? = null,
    private val lambda: Double = DEFAULT_LAMBDA,
    private val userModel: UserModel? = null,
    private val fuzzyDict: BinaryDict? = null,
    private val initialsDict: BinaryDict? = null,
    private val octagram: com.aegis.ime.dict.OctagramReader? = null,
    private val octagramWeight: Double = DEFAULT_OCTAGRAM_WEIGHT,
) {
    private val lnTotal = ln(dict.totalFreq.coerceAtLeast(1).toDouble())
    private val edgeN = if (lm != null || fuzzyDict != null || initialsDict != null) EDGE_N else 1

    private class Edge(val word: String, val freq: Int, val penalty: Double)

    /** Lattice edges for a substring, by descending preference: exact, then fuzzy, then 简拼 initials. */
    private fun edgesFor(sub: String): List<Edge> {
        val out = ArrayList<Edge>(edgeN)
        val seen = HashSet<String>()
        for (wf in dict.exact(sub)) {
            if (seen.add(wf.word)) out.add(Edge(wf.word, wf.freq, 0.0))
            if (out.size >= edgeN) return out
        }
        fuzzyDict?.let { fd ->
            for (wf in fd.exact(Fuzzy.normalize(sub))) {
                if (seen.add(wf.word)) out.add(Edge(wf.word, wf.freq, FUZZY_PENALTY))
                if (out.size >= edgeN) return out
            }
        }
        initialsDict?.let { id ->
            for (wf in id.exact(sub)) {
                if (seen.add(wf.word)) out.add(Edge(wf.word, wf.freq, INITIALS_PENALTY))
                if (out.size >= edgeN) break
            }
        }
        return out
    }

    /** Candidates for [input]: best sentence first, then word-by-word dictionary options. */
    fun decode(input: String, limit: Int): List<String> {
        if (input.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        bestSentence(input)?.let { out.add(it) }
        out.addAll(dict.query(input, limit))
        if (out.size < limit) fuzzyDict?.let { out.addAll(it.query(Fuzzy.normalize(input), limit)) }
        if (out.size < limit) initialsDict?.let { out.addAll(it.query(input, limit)) }
        return if (out.size <= limit) out.toList() else out.toList().subList(0, limit)
    }

    /**
     * Candidates tagged with coverage length (★E/★G): best full sentence + full-input completions
     * (all covering the whole input), then per-prefix words enumerated longest-prefix-first so
     * multi-syllable words rank above their leading single chars. Each [Cand.coveredLen] is how many
     * leading input units that word consumes, so picking it can partially commit and continue the rest.
     * [decode] is intentionally left unchanged so the accuracy eval path is unaffected.
     */
    fun decodeCovered(input: String, limit: Int, cuts: Set<Int> = emptySet()): List<Cand> {
        if (input.isEmpty()) return emptyList()
        val cover = LinkedHashMap<String, Int>()
        // Reserve part of the budget for leading single-chars/short words so full-input completions
        // (which can alone fill `limit`) never starve the ★G mixed grid.
        val completionCap = maxOf(1, limit * 2 / 3)
        // ★分词: a forced boundary strictly inside the input means whole-input completions (which would
        // span it) are invalid, and leading prefix words may not extend past it.
        val firstCut = cuts.filter { it in 1 until input.length }.minOrNull()
        fun addCompletions(words: List<String>) {
            for (w in words) { if (cover.size >= completionCap) return; cover.putIfAbsent(w, input.length) }
        }
        bestSentence(input, cuts)?.let { cover[it] = input.length }
        if (firstCut == null) {
            addCompletions(dict.query(input, completionCap))
            fuzzyDict?.let { addCompletions(it.query(Fuzzy.normalize(input), completionCap)) }
            initialsDict?.let { addCompletions(it.query(input, completionCap)) }
        }
        // Prefix words straight from the main dict (freq-ordered), independent of the lattice edge cap,
        // so the leading single chars (你 米 迷 泥 …) surface even without an LM (★G); capped at the cut.
        for (q in (firstCut ?: input.length) downTo 1) {
            if (cover.size >= limit) break
            var added = 0
            for (wf in dict.exact(input.substring(0, q))) {
                if (cover.putIfAbsent(wf.word, q) == null && ++added >= PREFIX_PER_LEN) break
            }
        }
        val out = ArrayList<Cand>(minOf(cover.size, limit))
        for ((w, len) in cover) { out.add(Cand(w, len)); if (out.size >= limit) break }
        return out
    }

    private class Cell(val score: Double, val prevPos: Int, val prevChar: Int, val word: String)

    private fun bestSentence(input: String, cuts: Set<Int> = emptySet()): String? {
        val n = input.length
        val dp = Array(n + 1) { HashMap<Int, Cell>() }
        dp[0][BOS] = Cell(0.0, -1, BOS, "")

        for (q in 1..n) {
            for (p in 0 until q) {
                val from = dp[p]
                if (from.isEmpty()) continue
                if (cuts.any { it > p && it < q }) continue // ★分词: no word may cross a forced syllable boundary
                val edges = edgesFor(input.substring(p, q))
                if (edges.isEmpty()) continue
                for (e in edges) {
                    val w = e.word
                    val uni = ln(e.freq.toDouble()) - lnTotal
                    val boost = userModel?.wordBoost(w) ?: 0.0
                    val firstCp = w.codePointAt(0)
                    val lastCp = w.codePointBefore(w.length)
                    for ((prevChar, cell) in from) {
                        val bi = if (lm == null || prevChar == BOS) 0.0
                        else lambda * lm.logCond(prevChar, firstCp)
                        // Optional top-tier context: wanxiang octagram collocation prevWord+curWord.
                        val og = if (octagram != null && cell.word.isNotEmpty())
                            octagramWeight * (octagram.rawScore(cell.word + w) ?: 0.0) else 0.0
                        val score = cell.score + uni + bi + boost - e.penalty + og
                        val cur = dp[q][lastCp]
                        if (cur == null || score > cur.score) {
                            dp[q][lastCp] = Cell(score, p, prevChar, w)
                        }
                    }
                }
            }
        }

        val end = dp[n]
        if (end.isEmpty()) return null
        var bestChar = BOS
        var bestScore = Double.NEGATIVE_INFINITY
        for ((cp, cell) in end) if (cell.score > bestScore) { bestScore = cell.score; bestChar = cp }

        val parts = ArrayList<String>()
        var q = n
        var cp = bestChar
        while (q > 0) {
            val cell = dp[q][cp]!!
            parts.add(cell.word)
            val pp = cell.prevPos
            cp = cell.prevChar
            q = pp
        }
        parts.reverse()
        return parts.joinToString("")
    }

    private companion object {
        const val BOS = -1            // sentence-start sentinel (real code points are >= 0)
        const val EDGE_N = 8          // candidate words considered per lattice edge when an LM is present
        const val DEFAULT_LAMBDA = 1.0
        const val FUZZY_PENALTY = 3.0     // log-domain cost so exact matches outrank fuzzy ones
        const val INITIALS_PENALTY = 5.0  // 简拼 is the most ambiguous → lowest preference
        const val DEFAULT_OCTAGRAM_WEIGHT = 0.3 // scales the large positive octagram log-weights
        const val PREFIX_PER_LEN = 8  // max leading single-chars/short-words pulled per prefix length (★G)
    }
}
