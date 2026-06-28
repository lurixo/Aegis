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

data class Cand(val word: String, val coveredLen: Int)

class PinyinDecoder(
    private val dict: BinaryDict,
    private val lm: CharBigramLM? = null,
    private val lambda: Double = DEFAULT_LAMBDA,
    private val userModel: UserModel? = null,
    private val fuzzyRules: Set<String> = emptySet(),
    private val initialsDict: BinaryDict? = null,
    private val octagram: com.aegis.ime.dict.OctagramReader? = null,
    private val octagramWeight: Double = DEFAULT_OCTAGRAM_WEIGHT,
    private val contextWeight: Double = DEFAULT_CONTEXT_WEIGHT,
) {
    private val lnTotal = ln(dict.totalFreq.coerceAtLeast(1).toDouble())
    private val edgeN = if (lm != null || fuzzyRules.isNotEmpty() || initialsDict != null) EDGE_N else 1

    private class Edge(val word: String, val freq: Int, val penalty: Double)

    private fun edgesFor(sub: String): List<Edge> {
        val out = ArrayList<Edge>(edgeN)
        val seen = HashSet<String>()
        for (wf in dict.exact(sub)) {
            if (seen.add(wf.word)) out.add(Edge(wf.word, wf.freq, 0.0))
            if (out.size >= edgeN) return out
        }
        if (fuzzyRules.isNotEmpty()) {
            for (variant in Fuzzy.variants(sub, fuzzyRules)) {
                if (variant == sub) continue
                for (wf in dict.exact(variant)) {
                    if (seen.add(wf.word)) out.add(Edge(wf.word, wf.freq, FUZZY_PENALTY))
                    if (out.size >= edgeN) return out
                }
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

    private fun wordModelScore(word: String, freq: Int, ctxCp: Int, ctxWord: String): Double =
        (ln(freq.toDouble()) - lnTotal) +
            (userModel?.wordBoost(word) ?: 0.0) +
            (octagram?.let { octagramWeight * (it.rawScore(word) ?: 0.0) } ?: 0.0) +
            (if (lm != null && ctxCp != BOS) contextWeight * lm.logCond(ctxCp, word.codePointAt(0)) else 0.0) +
            (if (octagram != null && ctxWord.isNotEmpty()) octagramWeight * (octagram.rawScore(ctxWord + word) ?: 0.0) else 0.0)

    private fun rerankedWholeInput(input: String, ctxCp: Int, ctxWord: String): List<String> =
        dict.exact(input).sortedByDescending { wordModelScore(it.word, it.freq, ctxCp, ctxWord) }.map { it.word }

    private fun parseContext(context: CharSequence): Pair<Int, String> {
        val s = context.toString()
        if (s.isEmpty()) return BOS to ""
        val lastCp = s.codePointBefore(s.length)
        if (!isHan(lastCp)) return BOS to ""
        var start = s.length
        var chars = 0
        while (start > 0 && chars < CTX_WORD_MAX) {
            val cp = s.codePointBefore(start)
            if (!isHan(cp)) break
            start -= Character.charCount(cp)
            chars++
        }
        return lastCp to s.substring(start)
    }

    private fun isHan(cp: Int): Boolean = cp in 0x3400..0x9FFF || cp in 0xF900..0xFAFF

    fun decode(input: String, limit: Int, context: CharSequence = ""): List<String> {
        if (input.isEmpty()) return emptyList()
        val (ctxCp, ctxWord) = parseContext(context)
        val out = LinkedHashSet<String>()
        bestSentence(input, ctxCp = ctxCp, ctxWord = ctxWord)?.let { out.add(it) }
        out.addAll(rerankedWholeInput(input, ctxCp, ctxWord))
        out.addAll(dict.query(input, limit))
        if (out.size < limit && fuzzyRules.isNotEmpty()) {
            for (variant in Fuzzy.variants(input, fuzzyRules)) {
                if (variant == input) continue
                out.addAll(dict.query(variant, limit))
                if (out.size >= limit) break
            }
        }
        if (out.size < limit) initialsDict?.let { out.addAll(it.query(input, limit)) }
        return if (out.size <= limit) out.toList() else out.toList().subList(0, limit)
    }

    fun decodeCovered(input: String, limit: Int, cuts: Set<Int> = emptySet(), context: CharSequence = ""): List<Cand> {
        if (input.isEmpty()) return emptyList()
        val (ctxCp, ctxWord) = parseContext(context)
        val cover = LinkedHashMap<String, Int>()
        val completionCap = maxOf(1, limit * 2 / 3)
        val firstCut = cuts.filter { it in 1 until input.length }.minOrNull()
        fun addCompletions(words: List<String>) {
            for (w in words) { if (cover.size >= completionCap) return; cover.putIfAbsent(w, input.length) }
        }
        bestSentence(input, cuts, ctxCp, ctxWord)?.let { cover[it] = input.length }
        if (firstCut == null) {
            addCompletions(rerankedWholeInput(input, ctxCp, ctxWord))
            addCompletions(dict.query(input, completionCap))
            if (fuzzyRules.isNotEmpty()) {
                for (variant in Fuzzy.variants(input, fuzzyRules)) {
                    if (variant == input) continue
                    addCompletions(dict.query(variant, completionCap))
                    if (cover.size >= completionCap) break
                }
            }
            initialsDict?.let { addCompletions(it.query(input, completionCap)) }
        }
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

    private fun bestSentence(input: String, cuts: Set<Int> = emptySet(), ctxCp: Int = BOS, ctxWord: String = ""): String? {
        val n = input.length
        val dp = Array(n + 1) { HashMap<Int, Cell>() }
        dp[0][ctxCp] = Cell(0.0, -1, ctxCp, ctxWord)

        for (q in 1..n) {
            for (p in 0 until q) {
                val from = dp[p]
                if (from.isEmpty()) continue
                if (cuts.any { it > p && it < q }) continue
                val edges = edgesFor(input.substring(p, q))
                if (edges.isEmpty()) continue
                for (e in edges) {
                    val w = e.word
                    val uni = ln(e.freq.toDouble()) - lnTotal
                    val boost = userModel?.wordBoost(w) ?: 0.0
                    val firstCp = w.codePointAt(0)
                    val lastCp = w.codePointBefore(w.length)
                    for ((prevChar, cell) in from) {
                        val bw = if (cell.prevPos < 0 && prevChar != BOS) contextWeight else lambda
                        val bi = if (lm == null || prevChar == BOS) 0.0
                        else bw * lm.logCond(prevChar, firstCp)
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
        const val BOS = -1
        const val EDGE_N = 8
        const val DEFAULT_LAMBDA = 1.0
        const val FUZZY_PENALTY = 3.0
        const val INITIALS_PENALTY = 5.0
        const val DEFAULT_OCTAGRAM_WEIGHT = 0.3
        const val PREFIX_PER_LEN = 8
        const val CTX_WORD_MAX = 4
        const val DEFAULT_CONTEXT_WEIGHT = 2.0
    }
}
