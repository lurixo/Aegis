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

data class Syllable(val reading: String, val start: Int, val end: Int)

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

    private class Norm(val clean: String, val cuts: Set<Int>, val origLen: IntArray, private val cleanLenAtOrig: IntArray) {
        fun cleanIndexOfOrig(o: Int): Int? = cleanLenAtOrig.getOrNull(o)
    }

    private fun normalizeSeparators(input: String): Norm? {
        if (input.indexOf(SEP) < 0) return null
        val clean = StringBuilder(input.length)
        val cuts = HashSet<Int>()
        val origLen = IntArray(input.length + 1)
        val cleanLenAtOrig = IntArray(input.length + 1)
        var ci = 0
        var oi = 0
        while (oi < input.length) {
            cleanLenAtOrig[oi] = ci
            if (input[oi] == SEP) {
                oi++
                if (ci in 1 until input.length) cuts.add(ci)
                origLen[ci] = oi
            } else {
                clean.append(input[oi]); oi++; ci++
                origLen[ci] = oi
            }
        }
        cleanLenAtOrig[input.length] = ci
        val interiorCuts = cuts.filterTo(HashSet()) { it in 1 until ci }
        return Norm(clean.toString(), interiorCuts, origLen.copyOf(ci + 1), cleanLenAtOrig)
    }

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

    fun syllables(input: String, cuts: Set<Int> = emptySet()): List<Syllable> {
        if (input.isEmpty()) return emptyList()
        val norm = normalizeSeparators(input)
        val clean = norm?.clean ?: input
        if (clean.isEmpty()) return emptyList()
        val spans = atomicSyllables(clean, cleanInterior(norm, clean, cuts))
        return if (norm == null) spans
        else spans.map { Syllable(it.reading, norm.origLen[it.start], norm.origLen[it.end]) }
    }

    private fun cleanInterior(norm: Norm?, clean: String, cuts: Set<Int>): Set<Int> {
        val passedClean = if (norm == null) cuts else cuts.mapNotNull { norm.cleanIndexOfOrig(it) }.toSet()
        return ((norm?.cuts ?: emptySet()) + passedClean).filterTo(HashSet()) { it in 1 until clean.length }
    }

    private fun syllablesClean(input: String): List<Syllable> =
        if (input[0] in '2'..'9') t9Syllables(input) else letterSyllables(input)

    private fun wholeSegmentReading(segment: String): String? =
        if (segment[0] in '2'..'9') T9Pinyin.syllableReading(segment).takeIf { it.isNotEmpty() }
        else segment.takeIf { T9Pinyin.firstSyllableLetters(it) == it }

    private fun atomicSyllables(clean: String, interior: Set<Int>): List<Syllable> {
        val bounds = listOf(0) + interior.filter { it in 1 until clean.length }.sorted() + listOf(clean.length)
        val out = ArrayList<Syllable>()
        for (b in 0 until bounds.size - 1) {
            val lo = bounds[b]; val hi = bounds[b + 1]
            if (lo >= hi) continue
            val segment = clean.substring(lo, hi)
            val whole = wholeSegmentReading(segment)
            if (whole != null) {
                out.add(Syllable(whole, lo, hi))
            } else {
                for (s in syllablesClean(segment)) out.add(Syllable(s.reading, lo + s.start, lo + s.end))
            }
        }
        return out
    }

    private fun letterSyllables(input: String): List<Syllable> {
        val out = ArrayList<Syllable>()
        var pos = 0
        T9Pinyin.segmentLetters(input)?.let { segs ->
            for (s in segs) { out.add(Syllable(s, pos, pos + s.length)); pos += s.length }
            return out
        }
        while (pos < input.length) {
            val syl = T9Pinyin.firstSyllableLetters(input.substring(pos))
            if (syl.isEmpty()) break
            out.add(Syllable(syl, pos, pos + syl.length)); pos += syl.length
        }
        return out
    }

    private fun t9Syllables(input: String): List<Syllable> {
        val out = ArrayList<Syllable>()
        var pos = 0
        T9Pinyin.segment(input)?.let { segs ->
            for (s in segs) { val d = T9Pinyin.toT9(s).length; out.add(Syllable(s, pos, pos + d)); pos += d }
            return out
        }
        while (pos < input.length) {
            val d = T9Pinyin.firstSyllableDigitLen(input.substring(pos))
            if (d == 0) break
            out.add(Syllable(T9Pinyin.syllableReading(input.substring(pos, pos + d)), pos, pos + d)); pos += d
        }
        return out
    }

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
        const val SEP = '\''
        const val BOS = -1
        fun completionCap(limit: Int): Int = maxOf(1, (limit.toLong() * 2 / 3).toInt())
    }
}
