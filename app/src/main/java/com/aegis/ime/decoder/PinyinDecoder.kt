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
import com.aegis.ime.dict.DecodeCancellation
import kotlin.math.ln

data class Cand(
    val word: String,
    val coveredLen: Int,
)

class PinyinDecoder(
    private val dict: BinaryDict,
) {
    private val lnTotal = ln(dict.totalFreq.coerceAtLeast(1).toDouble())

    @Volatile private var edgeN =
        1

    private class Edge(val word: String, val freq: Int, val penalty: Double)

    private fun addExactEdges(
        source: BinaryDict,
        key: String,
        penalty: Double,
        out: MutableList<Edge>,
        seen: MutableSet<String>,
    ): Boolean {
        for (wf in source.exact(key, edgeN + seen.size)) {
            if (seen.add(wf.word)) out.add(Edge(wf.word, wf.freq, penalty))
            if (out.size >= edgeN) return true
        }
        return false
    }

    private fun edgesFor(sub: String): List<Edge> {
        val out = ArrayList<Edge>(edgeN)
        val seen = HashSet<String>()
        val exactFull = addExactEdges(dict, sub, 0.0, out, seen)
        if (exactFull || out.size >= edgeN) return out
        return out
    }

    private fun wordModelScore(word: String, freq: Int): Double =
        wordModelScore(word, freq.toDouble())

    private fun wordModelScore(word: String, freq: Double): Double {
        return (ln(freq) - lnTotal)
    }

    private fun isSingleChar(w: String): Boolean = w.codePointCount(0, w.length) == 1

    private fun cachedExact(source: BinaryDict, key: String): List<BinaryDict.WordFreq> =
        source.exact(key)

    private fun cachedPrefix(source: BinaryDict, prefix: String, limit: Int): List<BinaryDict.WordFreq> =
        source.prefixByFreq(prefix, limit)

    fun decodeCovered(input: String, limit: Int): List<Cand> =
        decodeCoveredLayered(input, limit).first

    internal fun decodeCoveredLayered(
        input: String,
        limit: Int,
    ): Pair<List<Cand>, Int> {
        if (input.isEmpty() || limit <= 0) return emptyList<Cand>() to 0
        return decodeCoveredClean(input, limit)
    }

    private fun decodeCoveredClean(
        input: String,
        limit: Int,
    ): Pair<List<Cand>, Int> {
        val cover = LinkedHashMap<String, Int>()
        val completionCap = completionCap(limit)
        val sentence = bestSentence(input)?.also { cover[it] = input.length }
        DecodeCancellation.checkpoint()
        val pool = ArrayList<RankedWord>()
        val offered = HashSet<String>()
        fun offer(wf: BinaryDict.WordFreq, penalty: Double): Boolean {
            if (!offered.add(wf.word)) return false
            pool.add(
                RankedWord(
                    wf,
                    wordModelScore(wf.word, wf.freq) - penalty,
                ),
            )
            return true
        }
        val exactWords = HashSet<String>()
        for (wf in cachedExact(dict, input)) {
            if (!isSingleChar(wf.word)) exactWords.add(wf.word)
            offer(wf, 0.0)
        }
        cachedPrefix(dict, input, completionCap).forEach { offer(it, 0.0) }
        DecodeCancellation.checkpoint()
        pool.sortWith(
            compareByDescending<RankedWord> { it.score },
        )
        for ((wf, _) in pool) {
            if (cover.size >= completionCap && wf.word !in exactWords) continue
            cover.putIfAbsent(wf.word, input.length)
        }
        val out = ArrayList<Cand>(cover.size + 20)
        val ordered = cover.keys.toList()
        for ((i, w) in ordered.withIndex()) {
            out.add(Cand(w, cover.getValue(w)))
        }
        val covered = out.mapTo(HashSet<String>(out.size * 2)) { it.word }
        var remainderStart = 0
        while (remainderStart < out.size && out[remainderStart].word in covered) remainderStart++
        return out to remainderStart
    }

    internal data class SentencePath(val text: String, val score: Double)

    private data class RankedWord(
        val wordFreq: BinaryDict.WordFreq,
        val score: Double,
    )

    private data class SentenceState(val lastCp: Int)

    private class Cell(val score: Double, val prevPos: Int, val prevState: SentenceState?, val word: String)

    private fun bestSentence(input: String): String? {
        val text = bestSentencePath(input)?.text
        return text
    }

    private fun bestSentencePath(
        input: String,
    ): SentencePath? {
        val n = input.length
        val dp = Array<MutableMap<SentenceState, Cell>>(n + 1) {
            HashMap()
        }
        val initial = SentenceState(BOS)
        dp[0][initial] = Cell(0.0, -1, null, "")

        for (q in 1..n) {
            DecodeCancellation.checkpoint()
            for (p in 0 until q) {
                val from = dp[p]
                if (from.isEmpty()) continue
                val sub = input.substring(p, q)
                val edges = edgesFor(sub)
                if (edges.isEmpty()) continue
                for (e in edges) {
                    val w = e.word
                    val uni = ln(e.freq.toDouble()) - lnTotal
                    val lastCp = w.codePointBefore(w.length)
                    for ((state, cell) in from) {
                        val score = cell.score + uni - e.penalty
                        val nextState = SentenceState(lastCp)
                        val cur = dp[q][nextState]
                        if (cur == null || score > cur.score) {
                            dp[q][nextState] = Cell(score, p, state, w)
                        }
                    }
                }
            }
        }

        val end = dp[n]
        if (end.isEmpty()) return null
        var bestState = initial
        var bestScore = Double.NEGATIVE_INFINITY
        for ((state, cell) in end) if (cell.score > bestScore) {
            bestScore = cell.score
            bestState = state
        }

        val parts = ArrayList<String>()
        var q = n
        var state = bestState
        while (q > 0) {
            val cell = dp[q][state]!!
            parts.add(cell.word)
            state = cell.prevState!!
            q = cell.prevPos
        }
        parts.reverse()
        return SentencePath(parts.joinToString(""), bestScore)
    }

    internal companion object {
        const val BOS = -1
        fun completionCap(limit: Int): Int = maxOf(1, (limit.toLong() * 2 / 3).toInt())
    }
}
