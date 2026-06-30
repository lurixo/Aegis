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
import com.aegis.ime.dict.DecodeCancellation
import com.aegis.ime.dict.TghGrading
import kotlin.math.exp
import kotlin.math.ln

data class Cand(
    val word: String,
    val coveredLen: Int,
)

data class Syllable(val reading: String, val start: Int, val end: Int)

class PinyinDecoder(
    private val dict: BinaryDict,
    private val lm: CharBigramLM? = null,
    private val lambda: Double = DEFAULT_LAMBDA,
    private val octagram: com.aegis.ime.dict.OctagramReader? = null,
    private val octagramWeight: Double = DEFAULT_OCTAGRAM_WEIGHT,
    private val contextWeight: Double = DEFAULT_CONTEXT_WEIGHT,
) {
    private val grading = TghGrading.bundled
    private val lnTotal = ln(dict.totalFreq.coerceAtLeast(1).toDouble())

    @Volatile private var edgeN =
        if (lm != null || octagram != null) EDGE_N else 1

    private class Edge(val word: String, val freq: Int, val penalty: Double)

    private fun addExactEdges(
        source: BinaryDict,
        key: String,
        penalty: Double,
        out: MutableList<Edge>,
        seen: MutableSet<String>,
    ): Boolean {
        for (wf in preferredExact(source, key, edgeN + seen.size)) {
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

    private fun resolveCtxId(ctxCp: Int): Int =
        if (ctxCp == BOS) NO_CTX else lm?.charId(ctxCp) ?: NO_CTX

    private fun activeLambda(ctx: Ctx): Double = if (ctx.cp == BOS) lambda else 0.0

    private fun logCondMemo(model: CharBigramLM, id1: Int, id2: Int): Double {
            return model.logCondById(id1, id2)
    }

    private fun internalBigramScore(word: String, model: CharBigramLM): Double {
        if (word.isEmpty()) return 0.0
        var offset = 0
        var previous = word.codePointAt(offset)
        offset += Character.charCount(previous)
        var score = 0.0
        while (offset < word.length) {
            val next = word.codePointAt(offset)
            score += logCondMemo(model, model.charId(previous), model.charId(next))
            previous = next
            offset += Character.charCount(next)
        }
        return score
    }

    private fun assemblyFrequency(
        word: String,
        headFrequency: Double,
        readingMass: Double,
        classMass: Double,
    ): Double {
        val model = lm ?: return headFrequency
        var offset = 0
        var previous = word.codePointAt(offset)
        offset += Character.charCount(previous)
        var total = 0.0
        var pairs = 0
        while (offset < word.length) {
            val next = word.codePointAt(offset)
            total += model.logCond(previous, next)
            previous = next
            offset += Character.charCount(next)
            pairs++
        }
        if (pairs == 0) return headFrequency
        val estimate = exp(ln(headFrequency.coerceAtLeast(1.0)) + total / pairs)
        if (classMass <= 0.0 || readingMass <= 0.0) return estimate
        return estimate * minOf(1.0, classMass / readingMass)
    }

    private fun wordModelScore(word: String, freq: Int, ctxId: Int, ctx: Ctx): Double =
        wordModelScore(word, freq.toDouble(), ctxId, ctx)

    private fun wordModelScore(word: String, freq: Double, ctxId: Int, ctx: Ctx): Double {
        return (ln(freq) - lnTotal) +
            (octagram?.let { octagramWeight * (it.rawScore(word) ?: 0.0) } ?: 0.0) +
            (lm?.let {
                val lam = activeLambda(ctx)
                (if (lam == 0.0) 0.0 else lam * internalBigramScore(word, it)) +
                    if (ctxId != NO_CTX) contextWeight * logCondMemo(it, ctxId, it.charId(word.codePointAt(0))) else 0.0
            } ?: 0.0) +
            octagramWeight * contextArm(ctx.tail, word)
    }

    internal data class Ctx(val cp: Int, val tail: String) {
        companion object {
            val EMPTY = Ctx(BOS, "")
        }
    }

    internal fun parseContext(context: CharSequence): Ctx {
        val tail = rollingHanTail("", context.toString())
        return if (tail.isEmpty()) Ctx.EMPTY else Ctx(tail.codePointBefore(tail.length), tail)
    }

    private fun rollingHanTail(tail: String, word: String): String = hanTail(tail + word)

    private fun hanTail(combined: String): String {
        var start = combined.length
        var chars = 0
        while (start > 0 && chars < CTX_WORD_MAX) {
            val cp = combined.codePointBefore(start)
            if (!isHan(cp)) break
            start -= Character.charCount(cp)
            chars++
        }
        return combined.substring(start)
    }

    private fun bestSuffixGram(text: String, startLimit: Int): Double =
        octagram?.bestSuffixScore(text, startLimit) ?: 0.0

    private fun contextArm(contextTail: String, word: String): Double =
        if (contextTail.isEmpty()) 0.0
        else bestSuffixGram(contextTail + word, contextTail.length)

    private fun joinedArm(contextTail: String, joined: String): Double =
        if (contextTail.isEmpty()) 0.0
        else bestSuffixGram(joined, contextTail.length)

    private fun joinTail(contextTail: String, word: String): String =
        if (contextTail.isEmpty()) word else contextTail + word

    private fun advanceJoinedTail(joined: String): String =
        if (octagram == null) "" else hanTail(joined)

    internal fun wholeSentenceArm(contextTail: String, text: String): Double {
        if (text.isEmpty()) return 0.0
        val combined = contextTail + text
        return bestSuffixGram(combined, combined.length)
    }

    private fun isHan(cp: Int): Boolean {
            return Character.isIdeographic(cp)
    }

    private fun isSingleChar(w: String): Boolean = w.codePointCount(0, w.length) == 1

    private fun supplementarySingleTieRank(word: String): Int =
        if (isSingleChar(word) && Character.isSupplementaryCodePoint(word.codePointAt(0))) 1 else 0

    private fun preferredWordFreqs(words: List<BinaryDict.WordFreq>): List<BinaryDict.WordFreq> =
        words.sortedWith(compareByDescending<BinaryDict.WordFreq> { it.freq }.thenBy { supplementarySingleTieRank(it.word) })

    private fun cachedExact(source: BinaryDict, key: String): List<BinaryDict.WordFreq> =
        source.exact(key)

    private fun cachedPrefix(source: BinaryDict, prefix: String, limit: Int): List<BinaryDict.WordFreq> =
        source.prefixByFreq(prefix, limit)

    private fun preferredExact(source: BinaryDict, key: String, limit: Int = Int.MAX_VALUE): List<BinaryDict.WordFreq> {
        if (limit <= 0) return emptyList()
            return lookupPreferredExact(source, key, limit)
    }

    private fun lookupPreferredExact(source: BinaryDict, key: String, limit: Int): List<BinaryDict.WordFreq> {
        val scanLimit = if (limit == Int.MAX_VALUE) limit else limit + EXACT_TIE_LOOKAHEAD
        val preferred = preferredWordFreqs(source.exact(key, scanLimit))
        return if (preferred.size <= limit) preferred else preferred.subList(0, limit).toList()
    }

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

    fun decodeCovered(input: String, limit: Int, cuts: Set<Int> = emptySet(), context: CharSequence = ""): List<Cand> =
        decodeCoveredLayered(input, limit, cuts, context).first

    internal fun decodeCoveredLayered(
        input: String,
        limit: Int,
        cuts: Set<Int> = emptySet(),
        context: CharSequence = "",
    ): Pair<List<Cand>, Int> {
        if (input.isEmpty() || limit <= 0) return emptyList<Cand>() to 0
        val norm = normalizeSeparators(input) ?: return decodeCoveredClean(input, limit, cuts, context)
        if (norm.clean.isEmpty()) return emptyList<Cand>() to 0
        val passedClean = cuts.mapNotNull { norm.cleanIndexOfOrig(it) }.toSet()
        val (cands, remainderStart) =
            decodeCoveredClean(norm.clean, limit, norm.cuts + passedClean, context)
        return cands.map {
            Cand(it.word, norm.origLen.getOrElse(it.coveredLen) { input.length })
        } to remainderStart
    }

    fun decodeCoveredAtomic(input: String, limit: Int, cuts: Set<Int> = emptySet(), context: CharSequence = ""): List<Cand> {
        if (input.isEmpty() || limit <= 0) return emptyList()
        val ctx = parseContext(context)
        val norm = normalizeSeparators(input)
        val clean = norm?.clean ?: input
        if (clean.isEmpty()) return emptyList()
        val passedClean = if (norm == null) cuts else cuts.mapNotNull { norm.cleanIndexOfOrig(it) }.toSet()
        val interior = ((norm?.cuts ?: emptySet()) + passedClean).filter { it in 1 until clean.length }.toSet()
        val decoded = decodeAtomic(clean, interior, ctx)
        return if (norm == null) {
            decoded
        } else {
            decoded.map {
                Cand(it.word, norm.origLen.getOrElse(it.coveredLen) { input.length })
            }
        }
    }

    private fun decodeCoveredClean(
        input: String,
        limit: Int,
        cuts: Set<Int>,
        context: CharSequence,
    ): Pair<List<Cand>, Int> {
        val ctx = parseContext(context)

        val ctxId = resolveCtxId(ctx.cp)
        val interior = cuts.filter { it in 1 until input.length }.toSortedSet()
        if (interior.isNotEmpty()) return decodeAtomic(input, interior, ctx).let { it to it.size }
        val cover = LinkedHashMap<String, Int>()
        val completionCap = completionCap(limit)
        val sentence = bestSentence(input, ctx)?.also { cover[it] = input.length }
        DecodeCancellation.checkpoint()
        val pool = ArrayList<RankedWord>()
        val offered = HashSet<String>()
        fun offer(wf: BinaryDict.WordFreq, penalty: Double): Boolean {
            if (!offered.add(wf.word)) return false
            pool.add(
                RankedWord(
                    wf,
                    wordModelScore(wf.word, wf.freq, ctxId, ctx) - penalty,
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
            compareByDescending<RankedWord> { it.score }
                .thenBy { supplementarySingleTieRank(it.wordFreq.word) },
        )
        enforceRareAfterCommon(
            pool,
            word = { it.wordFreq.word },
            frequency = { it.wordFreq.freq.toDouble() },
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
        appendLeadingSingles(input, input.length, out, ctx)
        DecodeCancellation.checkpoint()
        closeWithRareSingles(input, out)
        var remainderStart = 0
        while (remainderStart < out.size && out[remainderStart].word in covered) remainderStart++
        return out to remainderStart
    }

    private fun closeWithRareSingles(input: String, out: MutableList<Cand>) {
        val heads = HashMap<Int, Map<String, Double>>()
        val rare = ArrayList<Cand>()
        var write = 0
        for (c in out) {
            val len = c.coveredLen.coerceIn(1, input.length)
            val closing = isSingleChar(c.word) &&
                rareSingle(c.word, heads.getOrPut(len) { homophoneFreqMap(input.substring(0, len)) })
            if (closing) rare.add(c) else out[write++] = c
        }
        for (c in rare) out[write++] = c
    }

    private fun decodeAtomic(input: String, interior: Set<Int>, ctx: Ctx): List<Cand> {
        val ctxId = resolveCtxId(ctx.cp)
        val B = atomicBounds(input, interior)
        val nSyl = B.size - 1

        val singlesCache = HashMap<String, Set<String>>()
        val sentences = atomicSentences(input, B, interior, ctx, singlesCache)
        DecodeCancellation.checkpoint()

        val best = sentences.firstOrNull()?.text

        val leadFreq = LinkedHashMap<String, Double>()
        val leadCov = HashMap<String, Int>()
        for (j in 2..nSyl) {
            for (wf in preferredExact(dict, input.substring(0, B[j]))) if (!isSingleChar(wf.word)) {
                if (!admissibleUnderCuts(wf.word, 0, B[j], interior, input, singlesCache)) continue
                if (leadFreq.put(wf.word, wf.freq.toDouble()) == null) leadCov[wf.word] = B[j]
            }
        }

        val sylCharFreq = Array(nSyl) { i ->
            val m = HashMap<String, Double>()
            for ((w, f) in segmentSingleFreqs(input.substring(B[i], B[i + 1]))) m.putIfAbsent(w, f)
            m
        }
        fun commonnessFreq(word: String, coveredSyls: Int, carried: Double): Double {
            var mn = Double.MAX_VALUE
            var ci = 0
            var si = 0
            while (ci < word.length && si < coveredSyls) {
                val cp = word.codePointAt(ci)
                val f = sylCharFreq.getOrNull(si)?.get(String(Character.toChars(cp))) ?: carried
                if (f < mn) mn = f
                ci += Character.charCount(cp)
                si++
            }
            return if (mn == Double.MAX_VALUE) carried else mn
        }
        val tailScore = HashMap<String, Double>()
        val tailFreq = HashMap<String, Double>()
        val tailCand = LinkedHashMap<String, Cand>()
        val measuredMass = HashMap<Int, Double>()
        for ((w, f) in leadFreq) {
            val cov = leadCov[w] ?: input.length
            if (dict.exactWordFreq(input.substring(0, cov), w) != null) {
                measuredMass[cov] = (measuredMass[cov] ?: 0.0) + f.toDouble()
            }
        }
        var readingMass = 0.0
        for (f in sylCharFreq[0].values) readingMass += f
        fun tailFrequency(word: String, coveredSyls: Int, carried: Double): Double {
            val plain = commonnessFreq(word, coveredSyls, carried)
            if (isSingleChar(word)) return plain
            val head = sylCharFreq[0][String(Character.toChars(word.codePointAt(0)))] ?: plain
            val cov = if (coveredSyls <= 0) input.length else B[coveredSyls.coerceAtMost(nSyl)]
            return assemblyFrequency(word, head, readingMass, measuredMass[cov] ?: 0.0)
        }
        fun offerTail(word: String, coveredLen: Int, coveredSyls: Int, carried: Double) {
            if (word == best || word in leadFreq) return
            val frequency = tailFrequency(word, coveredSyls, carried)
            val score = wordModelScore(word, frequency, ctxId, ctx)
            val prev = tailScore[word]
            if (prev == null || score > prev) {
                tailScore[word] = score
                tailFreq[word] = frequency
                tailCand[word] = Cand(word, coveredLen)
            }
        }
        for (sentence in sentences) offerTail(sentence.text, input.length, nSyl, 1.0)
        for ((w, _) in segmentSingleFreqs(input.substring(0, B[1]))) offerTail(w, B[1], 1, 0.0)
        DecodeCancellation.checkpoint()
        val tailRanked = tailCand.values.sortedWith(
            compareBy<Cand> { isSingleChar(it.word) }
                .thenByDescending { tailScore[it.word] ?: Double.NEGATIVE_INFINITY }
                .thenBy { it.word.codePointCount(0, it.word.length) }
                .thenBy { supplementarySingleTieRank(it.word) },
        )

        val out = ArrayList<Cand>(1 + leadFreq.size + tailRanked.size)
        val seen = HashSet<String>()
        fun emit(words: List<String>) {
            for (w in words) {
                if (seen.add(w)) out.add(Cand(w, leadCov[w] ?: input.length))
            }
        }
        val rest = ArrayList<String>(leadFreq.size + 1)
        best?.let { rest.add(it) }
        val leadRank = HashMap<String, Double>(leadFreq.size * 2)
        for ((w, f) in leadFreq) leadRank[w] = wordModelScore(w, f, ctxId, ctx)
        for (w in leadFreq.keys.sortedByDescending { leadRank.getValue(it) }) {
            if (w !in rest) rest.add(w)
        }
        emit(rest)
        best?.let { if (seen.add(it)) out.add(Cand(it, input.length)) }
        val merged = ArrayList<Cand>(leadFreq.size + tailRanked.size)
        for (w in rest) if (w !in seen) merged.add(Cand(w, leadCov[w] ?: input.length))
        for (c in tailRanked) if (c.word !in seen) merged.add(c)
        fun candFrequency(c: Cand): Double =
            leadFreq[c.word]?.toDouble() ?: tailFreq[c.word] ?: tailFrequency(c.word, nSyl, 1.0)
        val classTotal = HashMap<Int, Double>()
        for (c in out) classTotal[c.coveredLen] = (classTotal[c.coveredLen] ?: 0.0) + candFrequency(c)
        for (c in merged) classTotal[c.coveredLen] = (classTotal[c.coveredLen] ?: 0.0) + candFrequency(c)
        val mergedRank = HashMap<String, Double>(merged.size * 2)
        for (c in merged) {
            val raw = tailScore[c.word] ?: wordModelScore(c.word, candFrequency(c), ctxId, ctx)
            mergedRank[c.word] = raw + lnTotal - ln((classTotal[c.coveredLen] ?: 1.0).coerceAtLeast(1.0))
        }
        merged.sortWith(
            compareBy<Cand> { if (rareSingle(it.word, sylCharFreq[0])) 1 else 0 }
                .thenByDescending { mergedRank.getValue(it.word) }
                .thenBy { supplementarySingleTieRank(it.word) },
        )
        for (c in merged) if (seen.add(c.word)) out.add(c)
        return out
    }

    private fun admissibleUnderCuts(
        word: String,
        spanStart: Int,
        spanEnd: Int,
        cuts: Set<Int>,
        input: String,
        singlesCache: HashMap<String, Set<String>>,
    ): Boolean {
        var hasInner = false
        for (c in cuts) if (c > spanStart && c < spanEnd) { hasInner = true; break }
        if (!hasInner) return true
        val key = input.substring(spanStart, spanEnd)
        val n = key.length
        val cps = ArrayList<String>(4)
        var ci = 0
        while (ci < word.length) {
            val cp = word.codePointAt(ci)
            cps.add(String(Character.toChars(cp)))
            ci += Character.charCount(cp)
        }
        val m = cps.size
        fun singles(k: String): Set<String> = singlesCache.getOrPut(k) {
            val out = HashSet<String>()
            for (wf in cachedExact(dict, k)) if (isSingleChar(wf.word)) out.add(wf.word)
            out
        }
        fun parses(respectCuts: Boolean): Boolean {
            val dp = Array(n + 1) { BooleanArray(m + 1) }
            dp[0][0] = true
            for (p in 0 until n) for (i in 0 until m) {
                if (!dp[p][i]) continue
                var q = p + 1
                while (q <= n && q - p <= MAX_SYLLABLE_KEY_LEN) {
                    var straddles = false
                    if (respectCuts) {
                        for (c in cuts) if (c > spanStart + p && c < spanStart + q) { straddles = true; break }
                    }
                    if (!straddles && cps[i] in singles(key.substring(p, q))) dp[q][i + 1] = true
                    q++
                }
            }
            return dp[n][m]
        }
        if (parses(respectCuts = true)) return true
        return !parses(respectCuts = false)
    }

    private class APath(val text: String, val lastCp: Int, val tail: String, val score: Double)

    internal data class SentencePath(val text: String, val score: Double)

    internal fun rerankSentencePaths(paths: List<SentencePath>, contextTail: String): List<SentencePath> {
        if (octagram == null || paths.size < 2) return paths
        val headSize = minOf(SENTENCE_RERANK_N, paths.size)
        val scores = DoubleArray(headSize) {
            paths[it].score + octagramWeight * wholeSentenceArm(contextTail, paths[it].text)
        }
        val order = (0 until headSize).sortedWith(
            compareByDescending<Int> { scores[it] }.thenBy { it },
        )
        val out = ArrayList<SentencePath>(paths.size)
        for (index in order) out.add(paths[index])
        for (index in headSize until paths.size) out.add(paths[index])
        return out
    }

    private fun atomicSentences(
        input: String,
        B: List<Int>,
        interior: Set<Int>,
        ctx: Ctx,
        singlesCache: HashMap<String, Set<String>>,
    ): List<SentencePath> {
        val model = lm
        val lam = activeLambda(ctx)
        val nSyl = B.size - 1
        val dp = Array(B.size) { ArrayList<APath>() }
        dp[0].add(APath("", ctx.cp, if (octagram == null) "" else ctx.tail, 0.0))
        for (i in 0 until nSyl) {
            DecodeCancellation.checkpoint()
            if (dp[i].isEmpty()) continue
            val src = dp[i].sortedByDescending { it.score }.take(BEAM_W)
            for (j in i + 1..nSyl) {
                val seg = input.substring(B[i], B[j])
                val raw = preferredExact(dict, seg)
                val eligible = if (j == i + 1) raw.filter { isSingleChar(it.word) }
                else raw.filterNot { isSingleChar(it.word) }
                    .filter { admissibleUnderCuts(it.word, B[i], B[j], interior, input, singlesCache) }
                val edges = eligible.take(SENTENCE_EDGE_N).toMutableList()
                for (wf in edges) {
                    val w = wf.word
                    val firstCp = w.codePointAt(0)
                    val idFirst = model?.charId(firstCp) ?: -1
                    val lastCp = w.codePointBefore(w.length)
                    val uni = ln(wf.freq.toDouble()) - lnTotal
                    val inner = if (model == null || lam == 0.0) 0.0 else lam * internalBigramScore(w, model)
                    for (p in src) {
                        val bw = if (p.text.isEmpty() && p.lastCp != BOS) contextWeight else lam
                        val bi = if (model == null || p.lastCp == BOS || bw == 0.0) 0.0 else bw * logCondMemo(model, model.charId(p.lastCp), idFirst)
                        val joined = joinTail(p.tail, w)
                        val og = octagramWeight * joinedArm(p.tail, joined)
                        dp[j].add(
                            APath(
                                p.text + w,
                                lastCp,
                                advanceJoinedTail(joined),
                                p.score + uni + bi + inner + og
                            ),
                        )
                    }
                }
            }
        }
        if (dp[nSyl].isEmpty()) return emptyList()
        val emit = ATOMIC_BEAM_N + ATOMIC_BEAM_PER_SYL * (nSyl - 2).coerceAtLeast(0)
        val ordered = ArrayList<SentencePath>(emit)
        val seen = HashSet<String>()
        for (p in dp[nSyl].sortedByDescending { it.score }) {
            if (seen.add(p.text)) { ordered.add(SentencePath(p.text, p.score)); if (ordered.size >= emit) break }
        }
        return rerankSentencePaths(ordered, ctx.tail)
    }

    private fun appendLeadingSingles(
        input: String,
        span: Int,
        out: ArrayList<Cand>,
        ctx: Ctx,
    ) {
        val ctxId = resolveCtxId(ctx.cp)
        val head = input.substring(0, span)
        val isT9 = input[0] in '2'..'9'
        val lens = if (isT9) T9Pinyin.leadingSyllableDigitLens(head)
        else T9Pinyin.leadingSyllableLetterLens(head)
        val lensSet = lens.toSet()
        val seen = HashSet<String>(out.size * 2)
        for (c in out) seen.add(c.word)
        val entries = ArrayList<Entry>()
        val entryAt = HashMap<String, Int>()
        fun record(word: String, cov: Int, frequency: Double) {
            entryAt[word] = entries.size
            entries.add(Entry(word, cov, wordModelScore(word, frequency, ctxId, ctx), frequency))
        }
        for (q in span downTo 1) {
            DecodeCancellation.checkpoint()
            for (wf in preferredExact(dict, input.substring(0, q))) {
                if (isSingleChar(wf.word) || !seen.add(wf.word)) continue
                record(wf.word, q, wf.freq.toDouble())
            }
            if (q in lensSet) {
                for ((w, f) in homophoneFreqs(input.substring(0, q))) if (seen.add(w)) record(w, q, f)
            }
        }
        if (lens.firstOrNull() != input.length) for (k in lens) {
            if (k >= input.length) continue
            val rest = input.substring(k)
            val restSeg = if (isT9) T9Pinyin.segment(rest) else T9Pinyin.segmentLetters(rest)
            val first = restSeg?.firstOrNull() ?: continue
            if (first == "n" || first == "ng" || first == "m") continue
            val present = HashSet<String>()
            for (c in out) if (c.coveredLen == k) present.add(c.word)
            for (e in entries) if (e.cov == k) present.add(e.word)
            for ((w, f) in homophoneFreqs(input.substring(0, k))) {
                if (!present.add(w)) continue
                val at = entryAt[w]
                if (at == null) {
                    record(w, k, f)
                } else if (f > entries[at].frequency) {
                    entries[at] = Entry(w, k, wordModelScore(w, f, ctxId, ctx), f)
                }
            }
        }
        val classTotal = HashMap<Int, Double>()
        for (e in entries) classTotal[e.cov] = (classTotal[e.cov] ?: 0.0) + e.frequency
        for (e in entries) e.rank = e.score + lnTotal - ln((classTotal[e.cov] ?: 1.0).coerceAtLeast(1.0))
        entries.sortWith(
            compareByDescending<Entry> { it.rank }
                .thenBy { supplementarySingleTieRank(it.word) },
        )
        enforceRareAfterCommon(entries, word = { it.word }, frequency = { it.frequency })
        val emitted = out.mapTo(HashSet<String>((out.size + entries.size) * 2)) { it.word }
        for (e in entries) if (emitted.add(e.word)) out.add(Cand(e.word, e.cov))
    }

    private fun frequencyClass(frequency: Double): Int = when {
        frequency >= ORDERING_COMMON_FREQ -> 1
        frequency <= ORDERING_RARE_FREQ -> -1
        else -> 0
    }

    private fun <T> enforceRareAfterCommon(
        entries: MutableList<T>,
        word: (T) -> String,
        frequency: (T) -> Double,
    ) {
        fun classification(entry: T): Int {
            return frequencyClass(frequency(entry))
        }
        val lastCommon = entries.indexOfLast { classification(it) > 0 }
        if (lastCommon <= 0 || entries.subList(0, lastCommon).none { classification(it) < 0 }) return
        val ordered = ArrayList<T>(entries.size)
        val delayed = ArrayList<T>()
        for ((index, entry) in entries.withIndex()) {
            if (index < lastCommon && classification(entry) < 0) {
                delayed.add(entry)
            } else {
                ordered.add(entry)
                if (index == lastCommon) ordered.addAll(delayed)
            }
        }
        entries.clear()
        entries.addAll(ordered)
    }

    internal fun rareSingle(word: String, frequencies: Map<String, Double>): Boolean {
        if (lm == null || !isSingleChar(word)) return false
        val frequency = frequencies[word] ?: return false
        return homophoneLayer(word, frequency) >= LAYER_RARE
    }

    private data class RankedWord(
        val wordFreq: BinaryDict.WordFreq,
        val score: Double,
    )

    private class Entry(
        val word: String,
        val cov: Int,
        val score: Double,
        val frequency: Double,
    ) {
        var rank = 0.0
    }

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

    private fun atomicBounds(clean: String, interior: Set<Int>): List<Int> {
        val bset = sortedSetOf(0, clean.length)
        bset.addAll(interior)
        for (s in atomicSyllables(clean, interior)) bset.add(s.end)
        return bset.toList()
    }

    fun homophonesAt(input: String, index: Int, cuts: Set<Int> = emptySet()): List<String> {
        val norm = normalizeSeparators(input)
        val clean = norm?.clean ?: input
        if (clean.isEmpty()) return emptyList()
        val syls = atomicSyllables(clean, cleanInterior(norm, clean, cuts))
        if (index !in syls.indices) return emptyList()
        val s = syls[index]
        return homophonesOf(clean.substring(s.start, s.end))
    }

    private class Homophone(val word: String, val layer: Int, val band: Int)

    internal fun homophonesOf(key: String): List<String> =
        segmentSingleFreqs(key)
            .map { Homophone(it.first, homophoneLayer(it.first, it.second), corpusBand(it.first)) }
            .sortedWith(compareBy<Homophone> { it.layer }.thenBy { it.band })
            .map { it.word }

    internal fun corpusBand(word: String): Int {
        if (!isSingleChar(word)) return CORPUS_BAND_OUT
        val rank = lm?.unigramRank(word.codePointAt(0)) ?: return CORPUS_BAND_OUT
        return when {
            rank <= TghGrading.LEVEL1_COUNT -> 0
            rank <= GENERAL_USE_CARDINALITY -> 1
            rank <= TghGrading.ENTRY_COUNT -> 2
            else -> CORPUS_BAND_OUT
        }
    }

    internal fun homophoneLayer(word: String, frequency: Double): Int {
        if (frequency <= ORDERING_INJECTED_FREQ) return LAYER_INJECTED
        if (!isSingleChar(word)) return LAYER_UNCOMMON
        return characterLayer(word.codePointAt(0))
    }

    private fun characterLayer(codePoint: Int): Int {
        val band = when (grading.level(codePoint)) {
            1 -> LAYER_COMMON
            2 -> LAYER_UNCOMMON
            3 -> LAYER_SPECIALIZED
            else -> if (codePoint >= EXTENSION_B_FLOOR) LAYER_RARE_EXTENSION else LAYER_RARE
        }
        if (band < LAYER_RARE) return band
        val rank = lm?.unigramRank(codePoint) ?: return band
        return if (rank <= GENERAL_USE_CARDINALITY) band - 1 else band
    }

    internal fun homophoneFreqs(key: String): List<Pair<String, Double>> =
        lookupHomophoneFreqs(key)

    private fun homophoneFreqMap(key: String): Map<String, Double> =
        homophoneFreqs(key).toMap()

    private fun lookupHomophoneFreqs(key: String): List<Pair<String, Double>> {
        val out = ArrayList<Pair<String, Double>>()
        val seen = HashSet<String>()
        for (wf in preferredExact(dict, key)) {
            if (isSingleChar(wf.word) && seen.add(wf.word)) out.add(wf.word to wf.freq.toDouble())
        }
        out.sortWith(
            compareByDescending<Pair<String, Double>> { it.second }
                .thenBy { supplementarySingleTieRank(it.first) },
        )
        return out
    }

    private fun segmentSingleFreqs(segment: String): List<Pair<String, Double>> =
        homophoneFreqs(segment)

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

    private data class SentenceState(val lastCp: Int, val tail: String)

    private class Cell(val score: Double, val prevPos: Int, val prevState: SentenceState?, val word: String)

    private fun bestSentence(input: String, ctx: Ctx): String? {
        val text = bestSentencePath(input, ctx)?.text
        return text
    }

    private fun bestSentencePath(
        input: String,
        ctx: Ctx,
    ): SentencePath? {
        val model = lm
        val lam = activeLambda(ctx)
        val n = input.length
        val dp = Array<MutableMap<SentenceState, Cell>>(n + 1) {
                        if (octagram == null) HashMap() else LinkedHashMap(SENTENCE_STATE_CAPACITY)
        }
        val initial = SentenceState(ctx.cp, if (octagram == null) "" else ctx.tail)
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
                    val firstCp = w.codePointAt(0)
                    val idFirst = model?.charId(firstCp) ?: -1
                    val lastCp = w.codePointBefore(w.length)
                    val inner = if (model == null || lam == 0.0) 0.0 else lam * internalBigramScore(w, model)
                    for ((state, cell) in from) {
                        val bw = if (cell.prevPos < 0 && state.lastCp != BOS) contextWeight else lam
                        val bi = if (model == null || state.lastCp == BOS || bw == 0.0) 0.0
                        else bw * logCondMemo(model, model.charId(state.lastCp), idFirst)
                        val joined = joinTail(state.tail, w)
                        val og = octagramWeight * joinedArm(state.tail, joined)
                        val score = cell.score + uni + bi + inner - e.penalty + og
                        val nextState = SentenceState(lastCp, advanceJoinedTail(joined))
                        val cur = dp[q][nextState]
                        if (cur == null || score > cur.score) {
                            dp[q][nextState] = Cell(score, p, state, w)
                        }
                    }
                }
            }
            if (octagram != null && dp[q].size > BEAM_W) {
                val keep = dp[q].entries
                    .sortedByDescending { it.value.score }
                    .take(BEAM_W)
                    .map { it.key to it.value }
                dp[q].clear()
                for ((state, cell) in keep) dp[q][state] = cell
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
        const val NO_CTX = Int.MIN_VALUE
        const val EDGE_N = 20
        const val DEFAULT_LAMBDA = 0.5
        const val DEFAULT_OCTAGRAM_WEIGHT = 0.1
        const val BEAM_W = 12
        const val SENTENCE_EDGE_N = 6
        const val ATOMIC_BEAM_N = 8
        const val ATOMIC_BEAM_PER_SYL = 40
        const val SENTENCE_RERANK_N = 128
        const val CTX_WORD_MAX = 4
        const val MAX_SYLLABLE_KEY_LEN = 6
        const val EXACT_TIE_LOOKAHEAD = 16
        const val SENTENCE_STATE_CAPACITY = 256
        const val ORDERING_RARE_FREQ = 100.0
        const val ORDERING_COMMON_FREQ = 1000.0
        const val ORDERING_INJECTED_FREQ = 1.0
        const val LAYER_COMMON = 0
        const val LAYER_UNCOMMON = 1
        const val LAYER_SPECIALIZED = 2
        const val LAYER_RARE = 3
        const val LAYER_RARE_EXTENSION = 4
        const val LAYER_INJECTED = 5
        const val EXTENSION_B_FLOOR = 0x20000
        const val GENERAL_USE_CARDINALITY = TghGrading.LEVEL1_COUNT + TghGrading.LEVEL2_COUNT
        const val CORPUS_BAND_OUT = 3
        const val DEFAULT_CONTEXT_WEIGHT = 1.0
        fun completionCap(limit: Int): Int = maxOf(1, (limit.toLong() * 2 / 3).toInt())
    }
}
