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

data class Syllable(val reading: String, val start: Int, val end: Int)

class PinyinDecoder(
    private val dict: BinaryDict,
    private val lm: CharBigramLM? = null,
    private val lambda: Double = DEFAULT_LAMBDA,
    private val userModel: UserModel? = null,
    private var fuzzyRules: Set<String> = emptySet(),
    private val initialsDict: BinaryDict? = null,
    private val octagram: com.aegis.ime.dict.OctagramReader? = null,
    private val octagramWeight: Double = DEFAULT_OCTAGRAM_WEIGHT,
    private val contextWeight: Double = DEFAULT_CONTEXT_WEIGHT,
) {
    private val lnTotal = ln(dict.totalFreq.coerceAtLeast(1).toDouble())
    private var edgeN = if (lm != null || fuzzyRules.isNotEmpty() || initialsDict != null) EDGE_N else 1

    fun setFuzzyRules(rules: Set<String>) {
        fuzzyRules = rules
        edgeN = if (lm != null || rules.isNotEmpty() || initialsDict != null) EDGE_N else 1
    }

    private class Edge(val word: String, val freq: Int, val penalty: Double)

    private fun inputAliases(key: String): List<String> = INPUT_ALIASES[key].orEmpty()

    private fun addExactEdges(
        key: String,
        penalty: Double,
        out: MutableList<Edge>,
        seen: MutableSet<String>,
    ): Boolean {
        for (wf in dict.exact(key)) {
            if (seen.add(wf.word)) out.add(Edge(wf.word, wf.freq, penalty))
            if (out.size >= edgeN) return true
        }
        return false
    }

    private fun inputAliasWordFreqs(input: String): List<BinaryDict.WordFreq> {
        val out = ArrayList<BinaryDict.WordFreq>()
        val seen = HashSet<String>()
        for (alias in inputAliases(input)) {
            for (wf in dict.exact(alias)) if (seen.add(wf.word)) out.add(wf)
        }
        return out
    }

    private fun edgesFor(sub: String): List<Edge> {
        val out = ArrayList<Edge>(edgeN)
        val seen = HashSet<String>()
        if (addExactEdges(sub, 0.0, out, seen)) return out
        for (alias in inputAliases(sub)) if (addExactEdges(alias, ALIAS_PENALTY, out, seen)) return out
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

    private fun rerankedInputAliases(input: String, ctxCp: Int, ctxWord: String): List<String> =
        inputAliasWordFreqs(input)
            .sortedByDescending { wordModelScore(it.word, it.freq, ctxCp, ctxWord) - ALIAS_PENALTY }
            .map { it.word }

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

    private fun isHan(cp: Int): Boolean = Character.isIdeographic(cp)

    private fun isSingleChar(w: String): Boolean = w.codePointCount(0, w.length) == 1

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

    fun decode(input: String, limit: Int, context: CharSequence = ""): List<String> {
        if (input.isEmpty()) return emptyList()
        val norm = normalizeSeparators(input)
        val clean = norm?.clean ?: input
        if (clean.isEmpty()) return emptyList()
        val cuts = norm?.cuts ?: emptySet()
        val (ctxCp, ctxWord) = parseContext(context)
        val out = LinkedHashSet<String>()
        bestSentence(clean, cuts, ctxCp = ctxCp, ctxWord = ctxWord)?.let { out.add(it) }
        out.addAll(rerankedWholeInput(clean, ctxCp, ctxWord))
        out.addAll(rerankedInputAliases(clean, ctxCp, ctxWord))
        out.addAll(dict.query(clean, limit))
        if (out.size < limit && fuzzyRules.isNotEmpty()) {
            for (variant in Fuzzy.variants(clean, fuzzyRules)) {
                if (variant == clean) continue
                out.addAll(dict.query(variant, limit))
                if (out.size >= limit) break
            }
        }
        if (out.size < limit) initialsDict?.let { out.addAll(it.query(clean, limit)) }
        return if (out.size <= limit) out.toList() else out.toList().subList(0, limit)
    }

    fun decodeCovered(input: String, limit: Int, cuts: Set<Int> = emptySet(), context: CharSequence = ""): List<Cand> {
        if (input.isEmpty()) return emptyList()
        val norm = normalizeSeparators(input) ?: return decodeCoveredClean(input, limit, cuts, context)
        if (norm.clean.isEmpty()) return emptyList()
        val passedClean = cuts.mapNotNull { norm.cleanIndexOfOrig(it) }.toSet()
        return decodeCoveredClean(norm.clean, limit, norm.cuts + passedClean, context)
            .map { Cand(it.word, norm.origLen.getOrElse(it.coveredLen) { input.length }) }
    }

    fun decodeCoveredAtomic(input: String, limit: Int, cuts: Set<Int> = emptySet(), context: CharSequence = ""): List<Cand> {
        if (input.isEmpty()) return emptyList()
        val (ctxCp, ctxWord) = parseContext(context)
        val norm = normalizeSeparators(input)
        val clean = norm?.clean ?: input
        if (clean.isEmpty()) return emptyList()
        val passedClean = if (norm == null) cuts else cuts.mapNotNull { norm.cleanIndexOfOrig(it) }.toSet()
        val interior = ((norm?.cuts ?: emptySet()) + passedClean).filter { it in 1 until clean.length }.toSet()
        val decoded = decodeAtomic(clean, limit, interior, ctxCp, ctxWord)
        return if (norm == null) {
            decoded
        } else {
            decoded.map { Cand(it.word, norm.origLen.getOrElse(it.coveredLen) { input.length }) }
        }
    }

    private fun decodeCoveredClean(input: String, limit: Int, cuts: Set<Int>, context: CharSequence): List<Cand> {
        val (ctxCp, ctxWord) = parseContext(context)
        val interior = cuts.filter { it in 1 until input.length }.toSortedSet()
        if (interior.isNotEmpty()) return decodeAtomic(input, limit, interior, ctxCp, ctxWord)

        val cover = LinkedHashMap<String, Int>()
        val completionCap = maxOf(1, limit * 2 / 3)
        fun addCompletions(words: List<String>) {
            for (w in words) { if (cover.size >= completionCap) return; cover.putIfAbsent(w, input.length) }
        }
        bestSentence(input, emptySet(), ctxCp, ctxWord)?.let { cover[it] = input.length }
        addCompletions(rerankedWholeInput(input, ctxCp, ctxWord))
        addCompletions(rerankedInputAliases(input, ctxCp, ctxWord))
        addCompletions(dict.query(input, completionCap))
        if (fuzzyRules.isNotEmpty()) {
            for (variant in Fuzzy.variants(input, fuzzyRules)) {
                if (variant == input) continue
                addCompletions(dict.query(variant, completionCap))
                if (cover.size >= completionCap) break
            }
        }
        initialsDict?.let { addCompletions(it.query(input, completionCap)) }
        for (q in input.length downTo 1) {
            if (cover.size >= limit) break
            var added = 0
            for (wf in dict.exact(input.substring(0, q))) {
                if (isSingleChar(wf.word)) continue
                if (cover.putIfAbsent(wf.word, q) == null && ++added >= PREFIX_PER_LEN) break
            }
        }
        val out = ArrayList<Cand>(minOf(cover.size, limit) + 20)
        for ((w, len) in cover) { out.add(Cand(w, len)); if (out.size >= limit) break }
        appendLeadingSingles(input, input.length, out)
        return out
    }

    private fun decodeAtomic(input: String, limit: Int, interior: Set<Int>, ctxCp: Int, ctxWord: String): List<Cand> {
        val bset = sortedSetOf(0, input.length)
        bset.addAll(interior)
        for (s in syllablesCleanCut(input, interior)) bset.add(s.end)
        val B = bset.toList()
        val nSyl = B.size - 1

        val cover = LinkedHashMap<String, Int>()
        val sentences = atomicSentences(input, B, ctxCp, ctxWord)
        sentences.firstOrNull()?.let { cover[it] = input.length }
        val leadWords = ArrayList<Pair<String, Int>>()
        val leadFreq = HashMap<String, Int>()
        for (j in 2..nSyl) for (wf in dict.exact(input.substring(0, B[j]))) if (!isSingleChar(wf.word)) {
            if (leadFreq.put(wf.word, wf.freq) == null) leadWords.add(wf.word to B[j])
        }
        leadWords.sortedByDescending { leadFreq[it.first] ?: 0 }.forEach { cover.putIfAbsent(it.first, it.second) }

        val out = ArrayList<Cand>(minOf(cover.size, limit) + 40)
        for ((w, len) in cover) { out.add(Cand(w, len)); if (out.size >= limit) break }
        val seen = HashSet<String>(out.size * 2); for (c in out) seen.add(c.word)
        for (w in homophonesOf(input.substring(0, B[1]))) if (seen.add(w)) out.add(Cand(w, B[1]))
        for (s in sentences.drop(1)) if (seen.add(s)) out.add(Cand(s, input.length))
        return out
    }

    private class APath(val text: String, val lastCp: Int, val lastWord: String, val score: Double)

    private fun atomicSentences(input: String, B: List<Int>, ctxCp: Int, ctxWord: String): List<String> {
        val nSyl = B.size - 1
        val dp = Array(B.size) { ArrayList<APath>() }
        dp[0].add(APath("", ctxCp, ctxWord, 0.0))
        for (i in 0 until nSyl) {
            if (dp[i].isEmpty()) continue
            val src = dp[i].sortedByDescending { it.score }.take(BEAM_W)
            for (j in i + 1..nSyl) {
                val seg = input.substring(B[i], B[j])
                val raw = dict.exact(seg)
                val edges = (if (j == i + 1) raw.filter { isSingleChar(it.word) } else raw.filterNot { isSingleChar(it.word) })
                    .take(SENTENCE_EDGE_N)
                for (wf in edges) {
                    val w = wf.word
                    val firstCp = w.codePointAt(0)
                    val lastCp = w.codePointBefore(w.length)
                    val uni = ln(wf.freq.toDouble()) - lnTotal
                    val boost = userModel?.wordBoost(w) ?: 0.0
                    for (p in src) {
                        val bw = if (p.text.isEmpty() && p.lastCp != BOS) contextWeight else lambda
                        val bi = if (lm == null || p.lastCp == BOS) 0.0 else bw * lm.logCond(p.lastCp, firstCp)
                        val og = if (octagram != null && p.lastWord.isNotEmpty())
                            octagramWeight * (octagram.rawScore(p.lastWord + w) ?: 0.0) else 0.0
                        dp[j].add(APath(p.text + w, lastCp, w, p.score + uni + bi + boost + og))
                    }
                }
            }
        }
        if (dp[nSyl].isEmpty()) return emptyList()
        val ordered = LinkedHashSet<String>()
        for (p in dp[nSyl].sortedByDescending { it.score }) { ordered.add(p.text); if (ordered.size >= ATOMIC_BEAM_N) break }
        return ordered.toList()
    }

    private fun appendLeadingSingles(input: String, span: Int, out: ArrayList<Cand>) {
        val head = input.substring(0, span)
        val lens = if (input[0] in '2'..'9') T9Pinyin.leadingSyllableDigitLens(head)
        else T9Pinyin.leadingSyllableLetterLens(head)
        if (lens.isEmpty()) return
        val seen = HashSet<String>(out.size * 2)
        for (c in out) seen.add(c.word)
        for (k in lens) for (w in homophonesOf(input.substring(0, k))) if (seen.add(w)) out.add(Cand(w, k))
    }

    fun syllables(input: String): List<Syllable> {
        if (input.isEmpty()) return emptyList()
        normalizeSeparators(input)?.let { n ->
            if (n.clean.isEmpty()) return emptyList()
            return syllablesCleanCut(n.clean, n.cuts).map { Syllable(it.reading, n.origLen[it.start], n.origLen[it.end]) }
        }
        return syllablesClean(input)
    }

    private fun syllablesClean(input: String): List<Syllable> =
        if (input[0] in '2'..'9') t9Syllables(input) else letterSyllables(input)

    private fun syllablesCleanCut(clean: String, cuts: Set<Int>): List<Syllable> {
        val interior = cuts.filter { it in 1 until clean.length }.sorted()
        if (interior.isEmpty()) return syllablesClean(clean)
        val out = ArrayList<Syllable>()
        val bounds = listOf(0) + interior + listOf(clean.length)
        for (b in 0 until bounds.size - 1) {
            val lo = bounds[b]; val hi = bounds[b + 1]
            if (lo >= hi) continue
            for (s in syllablesClean(clean.substring(lo, hi))) out.add(Syllable(s.reading, lo + s.start, lo + s.end))
        }
        return out
    }

    fun homophonesAt(input: String, index: Int): List<String> {
        val norm = normalizeSeparators(input)
        val clean = norm?.clean ?: input
        if (clean.isEmpty()) return emptyList()
        val syls = syllablesCleanCut(clean, norm?.cuts ?: emptySet())
        if (index !in syls.indices) return emptyList()
        val s = syls[index]
        return homophonesOf(clean.substring(s.start, s.end))
    }

    private fun homophonesOf(key: String): List<String> {
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        for (wf in dict.exact(key)) if (isSingleChar(wf.word) && seen.add(wf.word)) out.add(wf.word)
        for (wf in inputAliasWordFreqs(key)) if (isSingleChar(wf.word) && seen.add(wf.word)) out.add(wf.word)
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
        const val SEP = '\''
        const val BOS = -1
        const val EDGE_N = 20
        const val DEFAULT_LAMBDA = 1.0
        const val FUZZY_PENALTY = 3.0
        const val ALIAS_PENALTY = 3.5
        const val INITIALS_PENALTY = 5.0
        const val DEFAULT_OCTAGRAM_WEIGHT = 0.3
        const val PREFIX_PER_LEN = 16
        const val BEAM_W = 12
        const val SENTENCE_EDGE_N = 6
        const val ATOMIC_BEAM_N = 8
        const val CTX_WORD_MAX = 4
        const val DEFAULT_CONTEXT_WEIGHT = 2.0
        val INPUT_ALIASES = mapOf("en" to listOf("ng"))
    }
}
