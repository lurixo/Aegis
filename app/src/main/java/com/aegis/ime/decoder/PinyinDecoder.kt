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
import kotlin.math.exp
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
    private val aliasDict: BinaryDict? = null,
) {
    private val lnTotal = ln(dict.totalFreq.coerceAtLeast(1).toDouble())
    private var edgeN = if (lm != null || fuzzyRules.isNotEmpty() || initialsDict != null) EDGE_N else 1

    private var userIndexVersion = Long.MIN_VALUE
    private var userLetterIndex: Map<String, List<String>> = emptyMap()
    private var userDigitIndex: Map<String, List<String>> = emptyMap()

    fun setFuzzyRules(rules: Set<String>) {
        fuzzyRules = rules
        edgeN = if (lm != null || rules.isNotEmpty() || initialsDict != null) EDGE_N else 1
    }

    private class Edge(val word: String, val freq: Int, val penalty: Double)

    private fun inputAliases(key: String): List<String> =
        if (key.isNotEmpty() && key[0] in '2'..'9') T9_INPUT_ALIASES[key].orEmpty()
        else INPUT_ALIASES[key].orEmpty()

    private val aliasSource: BinaryDict get() = aliasDict ?: dict

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

    private fun inputAliasWordFreqs(input: String): List<BinaryDict.WordFreq> {
        val out = ArrayList<BinaryDict.WordFreq>()
        val seen = HashSet<String>()
        for (alias in inputAliases(input)) {
            for (wf in aliasSource.exact(alias)) if (seen.add(wf.word)) out.add(wf)
        }
        return preferredWordFreqs(out)
    }

    private fun refreshUserIndex() {
        val um = userModel ?: return
        val v = um.version
        if (v == userIndexVersion) return
        val letter = HashMap<String, MutableList<String>>()
        val digit = HashMap<String, MutableList<String>>()
        for ((reading, words) in um.readingSnapshot()) {
            if (reading.isEmpty()) continue
            val dk = T9Pinyin.toT9(reading)
            for (w in words) {
                letter.getOrPut(reading) { ArrayList() }.let { if (w !in it) it.add(w) }
                digit.getOrPut(dk) { ArrayList() }.let { if (w !in it) it.add(w) }
            }
        }
        userLetterIndex = letter
        userDigitIndex = digit
        userIndexVersion = v
    }

    private fun userWordsFor(key: String): List<String> {
        if (userModel == null || key.isEmpty()) return emptyList()
        refreshUserIndex()
        return (if (key[0] in '2'..'9') userDigitIndex[key] else userLetterIndex[key]) ?: emptyList()
    }

    private fun learnedExactWordFreqs(key: String): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        for (word in userWordsFor(key)) {
            val freq = dict.exactWordFreq(key, word) ?: continue
            out[word] = freq
        }
        return out
    }

    private fun userWordFreq(word: String, readingKey: String): Double {
        if (readingKey.isEmpty()) return 1.0
        val cps = ArrayList<String>(4)
        var ci = 0
        while (ci < word.length) { val cp = word.codePointAt(ci); cps.add(String(Character.toChars(cp))); ci += Character.charCount(cp) }
        val n = readingKey.length
        val m = cps.size
        if (m == 0) return 1.0
        val cache = HashMap<String, Map<String, Int>>()
        fun singleFreqs(key: String): Map<String, Int> = cache.getOrPut(key) {
            val map = HashMap<String, Int>()
            for (wf in preferredExact(dict, key)) if (isSingleChar(wf.word)) map.putIfAbsent(wf.word, wf.freq)
            map
        }
        val dp = Array(n + 1) { DoubleArray(m + 1) { Double.NEGATIVE_INFINITY } }
        dp[0][0] = Double.MAX_VALUE
        for (p in 0 until n) for (i in 0 until m) {
            if (dp[p][i] == Double.NEGATIVE_INFINITY) continue
            var q = p + 1
            while (q <= n && q - p <= MAX_SYLLABLE_KEY_LEN) {
                val f = singleFreqs(readingKey.substring(p, q))[cps[i]]
                if (f != null) {
                    val v = minOf(dp[p][i], f.toDouble())
                    if (v > dp[q][i + 1]) dp[q][i + 1] = v
                }
                q++
            }
        }
        val best = dp[n][m]
        return if (best == Double.NEGATIVE_INFINITY || best == Double.MAX_VALUE) 1.0 else best.coerceAtLeast(1.0)
    }

    private fun edgesFor(sub: String): List<Edge> {
        val out = ArrayList<Edge>(edgeN)
        val seen = HashSet<String>()
        val exactFull = addExactEdges(dict, sub, 0.0, out, seen)
        for ((word, freq) in learnedExactWordFreqs(sub)) {
            if (seen.add(word)) out.add(Edge(word, freq, 0.0))
        }
        for (uw in userWordsFor(sub)) {
            if (seen.add(uw)) {
                val n = uw.codePointCount(0, uw.length)
                out.add(Edge(uw, userWordFreq(uw, sub).toInt().coerceAtLeast(1), (n - 1).coerceAtLeast(0) * lnTotal))
            }
        }
        for (alias in inputAliases(sub)) {
            var added = 0
            for (wf in preferredExact(aliasSource, alias, edgeN + seen.size)) {
                if (seen.add(wf.word)) { out.add(Edge(wf.word, wf.freq, ALIAS_PENALTY)); if (++added >= edgeN) break }
            }
        }
        if (exactFull || out.size >= edgeN) return out
        if (fuzzyRules.isNotEmpty()) {
            for (variant in Fuzzy.variants(sub, fuzzyRules)) {
                if (variant == sub) continue
                for (wf in preferredExact(dict, variant, edgeN + seen.size)) {
                    if (seen.add(wf.word)) out.add(Edge(wf.word, wf.freq, FUZZY_PENALTY))
                    if (out.size >= edgeN) return out
                }
            }
        }
        initialsDict?.let { id ->
            for (wf in preferredExact(id, sub, edgeN + seen.size)) {
                if (seen.add(wf.word)) out.add(Edge(wf.word, wf.freq, INITIALS_PENALTY))
                if (out.size >= edgeN) break
            }
        }
        return out
    }

    private fun resolveCtxId(ctxCp: Int): Int =
        if (ctxCp == BOS) NO_CTX else lm?.charId(ctxCp) ?: NO_CTX

    private fun logCondMemo(memo: HashMap<Long, Double>, model: CharBigramLM, id1: Int, id2: Int): Double {
        val key = (id1.toLong() shl 32) or (id2.toLong() and 0xFFFFFFFFL)
        memo[key]?.let { return it }
        val v = model.logCondById(id1, id2)
        memo[key] = v
        return v
    }

    private fun internalBigramScore(word: String, model: CharBigramLM, memo: HashMap<Long, Double>): Double {
        if (word.isEmpty()) return 0.0
        var offset = 0
        var previous = word.codePointAt(offset)
        offset += Character.charCount(previous)
        var score = 0.0
        while (offset < word.length) {
            val next = word.codePointAt(offset)
            score += logCondMemo(memo, model, model.charId(previous), model.charId(next))
            previous = next
            offset += Character.charCount(next)
        }
        return score
    }

    private fun wordModelScore(word: String, freq: Int, ctxId: Int, ctxWord: String, condMemo: HashMap<Long, Double>): Double =
        wordModelScore(word, freq.toDouble(), ctxId, ctxWord, condMemo)

    private fun wordModelScore(word: String, freq: Double, ctxId: Int, ctxWord: String, condMemo: HashMap<Long, Double>): Double =
        (ln(freq) - lnTotal) +
            (userModel?.wordBoost(word) ?: 0.0) +
            (octagram?.let { octagramWeight * (it.rawScore(word) ?: 0.0) } ?: 0.0) +
            (lm?.let {
                lambda * internalBigramScore(word, it, condMemo) +
                    if (ctxId != NO_CTX) contextWeight * logCondMemo(condMemo, it, ctxId, it.charId(word.codePointAt(0))) else 0.0
            } ?: 0.0) +
            (if (octagram != null && ctxWord.isNotEmpty()) octagramWeight * (octagram.rawScore(ctxWord + word) ?: 0.0) else 0.0)

    private fun rerankedWholeInputAndAliases(input: String, ctxCp: Int, ctxWord: String): List<String> {
        val ctxId = resolveCtxId(ctxCp)
        val condMemo = HashMap<Long, Double>()
        val own = dict.exact(input)
        val alias = inputAliasWordFreqs(input)
        val scored = ArrayList<Pair<BinaryDict.WordFreq, Double>>(own.size + alias.size)
        for (wf in own) scored.add(wf to wordModelScore(wf.word, wf.freq, ctxId, ctxWord, condMemo))
        if (alias.isNotEmpty()) {
            val ownWords = own.mapTo(HashSet()) { it.word }
            for (wf in alias) {
                if (wf.word !in ownWords) scored.add(wf to (wordModelScore(wf.word, wf.freq, ctxId, ctxWord, condMemo) - ALIAS_PENALTY))
            }
        }
        return scored
            .sortedWith(
                compareByDescending<Pair<BinaryDict.WordFreq, Double>> { it.second }
                    .thenBy { supplementarySingleTieRank(it.first.word) },
            )
            .map { it.first.word }
    }

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

    private fun supplementarySingleTieRank(word: String): Int =
        if (isSingleChar(word) && Character.isSupplementaryCodePoint(word.codePointAt(0))) 1 else 0

    private fun preferredWordFreqs(words: List<BinaryDict.WordFreq>): List<BinaryDict.WordFreq> =
        words.sortedWith(compareByDescending<BinaryDict.WordFreq> { it.freq }.thenBy { supplementarySingleTieRank(it.word) })

    private fun preferredExact(source: BinaryDict, key: String, limit: Int = Int.MAX_VALUE): List<BinaryDict.WordFreq> {
        if (limit <= 0) return emptyList()
        val scanLimit = if (limit == Int.MAX_VALUE) limit else limit + EXACT_TIE_LOOKAHEAD
        val preferred = preferredWordFreqs(source.exact(key, scanLimit))
        return if (preferred.size <= limit) preferred else preferred.subList(0, limit)
    }

    private fun prefixWords(source: BinaryDict, input: String, limit: Int): List<String> =
        source.prefixByFreq(input, limit).map { it.word }

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
        if (input.isEmpty() || limit <= 0) return emptyList()
        val norm = normalizeSeparators(input)
        val clean = norm?.clean ?: input
        if (clean.isEmpty()) return emptyList()
        val cuts = norm?.cuts ?: emptySet()
        val (ctxCp, ctxWord) = parseContext(context)
        val out = LinkedHashSet<String>()
        bestSentence(clean, cuts, ctxCp = ctxCp, ctxWord = ctxWord)?.let { out.add(it) }
        out.addAll(rerankedWholeInputAndAliases(clean, ctxCp, ctxWord))
        out.addAll(userWordsFor(clean))
        out.addAll(prefixWords(dict, clean, limit))
        if (out.size < limit && fuzzyRules.isNotEmpty()) {
            for (variant in Fuzzy.variants(clean, fuzzyRules)) {
                if (variant == clean) continue
                out.addAll(prefixWords(dict, variant, limit))
                if (out.size >= limit) break
            }
        }
        if (out.size < limit) initialsDict?.let { out.addAll(prefixWords(it, clean, limit)) }
        return if (out.size <= limit) out.toList() else out.toList().subList(0, limit)
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
        val (cands, remainderStart) = decodeCoveredClean(norm.clean, limit, norm.cuts + passedClean, context)
        return cands.map { Cand(it.word, norm.origLen.getOrElse(it.coveredLen) { input.length }) } to remainderStart
    }

    fun decodeCoveredAtomic(input: String, limit: Int, cuts: Set<Int> = emptySet(), context: CharSequence = ""): List<Cand> {
        if (input.isEmpty() || limit <= 0) return emptyList()
        val (ctxCp, ctxWord) = parseContext(context)
        val norm = normalizeSeparators(input)
        val clean = norm?.clean ?: input
        if (clean.isEmpty()) return emptyList()
        val passedClean = if (norm == null) cuts else cuts.mapNotNull { norm.cleanIndexOfOrig(it) }.toSet()
        val interior = ((norm?.cuts ?: emptySet()) + passedClean).filter { it in 1 until clean.length }.toSet()
        val decoded = decodeAtomic(clean, interior, ctxCp, ctxWord)
        return if (norm == null) {
            decoded
        } else {
            decoded.map { Cand(it.word, norm.origLen.getOrElse(it.coveredLen) { input.length }) }
        }
    }

    private fun decodeCoveredClean(input: String, limit: Int, cuts: Set<Int>, context: CharSequence): Pair<List<Cand>, Int> {
        val (ctxCp, ctxWord) = parseContext(context)
        val interior = cuts.filter { it in 1 until input.length }.toSortedSet()
        if (interior.isNotEmpty()) return decodeAtomic(input, interior, ctxCp, ctxWord).let { it to it.size }

        val ctxId = resolveCtxId(ctxCp)
        val condMemo = HashMap<Long, Double>()
        val cover = LinkedHashMap<String, Int>()
        val completionCap = maxOf(1, limit * 2 / 3)
        bestSentence(input, emptySet(), ctxCp, ctxWord)?.let { cover[it] = input.length }
        val pool = ArrayList<RankedWord>()
        val offered = HashSet<String>()
        fun offer(wf: BinaryDict.WordFreq, penalty: Double) {
            if (offered.add(wf.word)) {
                pool.add(
                    RankedWord(
                        wf,
                        wordModelScore(wf.word, wf.freq, ctxId, ctxWord, condMemo) - penalty,
                    ),
                )
            }
        }
        val exactWords = HashSet<String>()
        for (wf in dict.exact(input)) {
            if (!isSingleChar(wf.word)) exactWords.add(wf.word)
            offer(wf, 0.0)
        }
        inputAliasWordFreqs(input).forEach { offer(it, ALIAS_PENALTY) }
        dict.prefixByFreq(input, completionCap).forEach { offer(it, 0.0) }
        for (uw in userWordsFor(input)) offer(BinaryDict.WordFreq(uw, userWordFreq(uw, input).toInt().coerceAtLeast(1)), 0.0)
        if (fuzzyRules.isNotEmpty()) {
            for (variant in Fuzzy.variants(input, fuzzyRules)) {
                if (variant == input) continue
                dict.prefixByFreq(variant, completionCap).forEach { offer(it, FUZZY_PENALTY) }
            }
        }
        initialsDict?.let { id -> id.prefixByFreq(input, completionCap).forEach { offer(it, INITIALS_PENALTY) } }
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
        for ((w, len) in cover) out.add(Cand(w, len))
        if (userModel != null) {
            val present = out.mapTo(HashSet()) { it.word }
            for (uw in userWordsFor(input)) if (present.add(uw)) out.add(Cand(uw, input.length))
        }
        val remainderStart = out.size
        appendLeadingSingles(input, input.length, out, ctxCp, ctxWord)
        return out to remainderStart
    }

    private fun decodeAtomic(input: String, interior: Set<Int>, ctxCp: Int, ctxWord: String): List<Cand> {
        val ctxId = resolveCtxId(ctxCp)
        val condMemo = HashMap<Long, Double>()
        val bset = sortedSetOf(0, input.length)
        bset.addAll(interior)
        for (s in syllablesCleanCut(input, interior)) bset.add(s.end)
        val B = bset.toList()
        val nSyl = B.size - 1

        val singlesCache = HashMap<String, Set<String>>()
        val sentences = atomicSentences(input, B, interior, ctxCp, ctxWord, singlesCache)

        val best = sentences.firstOrNull()?.first

        val leadFreq = LinkedHashMap<String, Int>()
        val leadCov = HashMap<String, Int>()
        for (j in 2..nSyl) for (wf in preferredExact(dict, input.substring(0, B[j]))) if (!isSingleChar(wf.word)) {
            if (!admissibleUnderCuts(wf.word, 0, B[j], interior, input, singlesCache)) continue
            if (leadFreq.put(wf.word, wf.freq) == null) leadCov[wf.word] = B[j]
        }
        for (uw in userWordsFor(input)) {
            if (uw == best || uw in leadFreq || uw.codePointCount(0, uw.length) < 2) continue
            if (!admissibleUnderCuts(uw, 0, input.length, interior, input, singlesCache)) continue
            val f = userWordFreq(uw, input).toInt().coerceAtLeast(1)
            if (leadFreq.put(uw, f) == null) leadCov[uw] = input.length
        }

        val sylCharFreq = Array(nSyl) { i ->
            val m = HashMap<String, Double>()
            for ((w, f) in homophoneFreqs(input.substring(B[i], B[i + 1]))) m.putIfAbsent(w, f)
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
        val tailCand = LinkedHashMap<String, Cand>()
        fun offerTail(word: String, coveredLen: Int, coveredSyls: Int, carried: Double) {
            if (word == best || word in leadFreq) return
            val score = wordModelScore(word, commonnessFreq(word, coveredSyls, carried), ctxId, ctxWord, condMemo)
            val prev = tailScore[word]
            if (prev == null || score > prev) { tailScore[word] = score; tailCand[word] = Cand(word, coveredLen) }
        }
        for ((text, _) in sentences) offerTail(text, input.length, nSyl, 1.0)
        for ((w, _) in homophoneFreqs(input.substring(0, B[1]))) offerTail(w, B[1], 1, 0.0)
        val tailRanked = tailCand.values.sortedWith(
            compareBy<Cand> { isSingleChar(it.word) }
                .thenByDescending { tailScore[it.word] ?: Double.NEGATIVE_INFINITY }
                .thenBy { it.word.codePointCount(0, it.word.length) }
                .thenBy { supplementarySingleTieRank(it.word) },
        )

        val out = ArrayList<Cand>(1 + leadFreq.size + tailRanked.size)
        val seen = HashSet<String>()
        best?.let { if (seen.add(it)) out.add(Cand(it, input.length)) }
        for ((w, _) in leadFreq.entries.sortedByDescending { wordModelScore(it.key, it.value, ctxId, ctxWord, condMemo) }) {
            if (seen.add(w)) out.add(Cand(w, leadCov.getValue(w)))
        }
        for (c in tailRanked) if (seen.add(c.word)) out.add(c)
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
            for (wf in dict.exact(k)) if (isSingleChar(wf.word)) out.add(wf.word)
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

    private class APath(val text: String, val lastCp: Int, val lastWord: String, val score: Double)

    private fun atomicSentences(
        input: String,
        B: List<Int>,
        interior: Set<Int>,
        ctxCp: Int,
        ctxWord: String,
        singlesCache: HashMap<String, Set<String>>,
    ): List<Pair<String, Double>> {
        val model = lm
        val condMemo = HashMap<Long, Double>()
        val nSyl = B.size - 1
        val dp = Array(B.size) { ArrayList<APath>() }
        dp[0].add(APath("", ctxCp, ctxWord, 0.0))
        for (i in 0 until nSyl) {
            if (dp[i].isEmpty()) continue
            val src = dp[i].sortedByDescending { it.score }.take(BEAM_W)
            for (j in i + 1..nSyl) {
                val seg = input.substring(B[i], B[j])
                val raw = preferredExact(dict, seg)
                val eligible = if (j == i + 1) raw.filter { isSingleChar(it.word) }
                else raw.filterNot { isSingleChar(it.word) }
                    .filter { admissibleUnderCuts(it.word, B[i], B[j], interior, input, singlesCache) }
                val edges = eligible.take(SENTENCE_EDGE_N).toMutableList()
                val present = edges.mapTo(HashSet()) { it.word }
                for ((word, freq) in learnedExactWordFreqs(seg)) {
                    if (!present.add(word)) continue
                    if (j == i + 1 && !isSingleChar(word)) continue
                    if (j > i + 1 && isSingleChar(word)) continue
                    if (j > i + 1 && !admissibleUnderCuts(word, B[i], B[j], interior, input, singlesCache)) continue
                    edges.add(BinaryDict.WordFreq(word, freq))
                }
                for (wf in edges) {
                    val w = wf.word
                    val firstCp = w.codePointAt(0)
                    val idFirst = model?.charId(firstCp) ?: -1
                    val lastCp = w.codePointBefore(w.length)
                    val uni = ln(wf.freq.toDouble()) - lnTotal
                    val boost = userModel?.wordBoost(w) ?: 0.0
                    val inner = if (model == null) 0.0 else lambda * internalBigramScore(w, model, condMemo)
                    for (p in src) {
                        val bw = if (p.text.isEmpty() && p.lastCp != BOS) contextWeight else lambda
                        val bi = if (model == null || p.lastCp == BOS) 0.0 else bw * logCondMemo(condMemo, model, model.charId(p.lastCp), idFirst)
                        val og = if (octagram != null && p.lastWord.isNotEmpty())
                            octagramWeight * (octagram.rawScore(p.lastWord + w) ?: 0.0) else 0.0
                        dp[j].add(APath(p.text + w, lastCp, w, p.score + uni + bi + inner + boost + og))
                    }
                }
            }
        }
        if (dp[nSyl].isEmpty()) return emptyList()
        val ordered = ArrayList<Pair<String, Double>>(ATOMIC_BEAM_N)
        val seen = HashSet<String>()
        for (p in dp[nSyl].sortedByDescending { it.score }) {
            if (seen.add(p.text)) { ordered.add(p.text to p.score); if (ordered.size >= ATOMIC_BEAM_N) break }
        }
        return ordered
    }

    private fun appendLeadingSingles(input: String, span: Int, out: ArrayList<Cand>, ctxCp: Int, ctxWord: String) {
        val ctxId = resolveCtxId(ctxCp)
        val condMemo = HashMap<Long, Double>()
        val head = input.substring(0, span)
        val isT9 = input[0] in '2'..'9'
        val lens = if (isT9) T9Pinyin.leadingSyllableDigitLens(head)
        else T9Pinyin.leadingSyllableLetterLens(head)
        val lensSet = lens.toSet()
        val seen = HashSet<String>(out.size * 2)
        for (c in out) seen.add(c.word)
        val entries = ArrayList<Entry>()
        for (q in span downTo 1) {
            for (wf in preferredExact(dict, input.substring(0, q))) {
                if (isSingleChar(wf.word) || !seen.add(wf.word)) continue
                entries.add(
                    Entry(
                        wf.word,
                        q,
                        wordModelScore(wf.word, wf.freq, ctxId, ctxWord, condMemo),
                        single = false,
                        frequency = wf.freq.toDouble(),
                    ),
                )
            }
            if (q in lensSet) {
                for ((w, f) in homophoneFreqs(input.substring(0, q))) if (seen.add(w))
                    entries.add(
                        Entry(
                            w,
                            q,
                            wordModelScore(w, f, ctxId, ctxWord, condMemo),
                            single = true,
                            frequency = f,
                        ),
                    )
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
            for ((w, f) in homophoneFreqs(input.substring(0, k))) if (present.add(w))
                entries.add(
                    Entry(
                        w,
                        k,
                        wordModelScore(w, f, ctxId, ctxWord, condMemo),
                        single = true,
                        frequency = f,
                    ),
                )
        }
        entries.sortWith(
            compareByDescending<Entry> { it.score }
                .thenBy { supplementarySingleTieRank(it.word) },
        )
        enforceRareAfterCommon(entries, word = { it.word }, frequency = { it.frequency })
        for (e in entries) out.add(Cand(e.word, e.cov))
    }

    private fun <T> enforceRareAfterCommon(
        entries: MutableList<T>,
        word: (T) -> String,
        frequency: (T) -> Double,
    ) {
        fun classification(entry: T): Int {
            if ((userModel?.wordBoost(word(entry)) ?: 0.0) > 0.0) return 0
            val freq = frequency(entry)
            return when {
                freq >= ORDERING_COMMON_FREQ -> 1
                freq <= ORDERING_RARE_FREQ -> -1
                else -> 0
            }
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

    private data class RankedWord(
        val wordFreq: BinaryDict.WordFreq,
        val score: Double,
    )

    private class Entry(
        val word: String,
        val cov: Int,
        val score: Double,
        val single: Boolean,
        val frequency: Double,
    )

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

    private fun homophonesOf(key: String): List<String> = homophoneFreqs(key).map { it.first }

    private fun homophoneFreqs(key: String): List<Pair<String, Double>> {
        val out = ArrayList<Pair<String, Double>>()
        val seen = HashSet<String>()
        for (wf in preferredExact(dict, key)) {
            if (isSingleChar(wf.word) && seen.add(wf.word)) out.add(wf.word to wf.freq.toDouble())
        }
        for (wf in inputAliasWordFreqs(key)) {
            if (isSingleChar(wf.word) && seen.add(wf.word)) out.add(wf.word to wf.freq * ALIAS_FREQ_DISCOUNT)
        }
        out.sortWith(
            compareByDescending<Pair<String, Double>> { it.second }
                .thenBy { supplementarySingleTieRank(it.first) },
        )
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
        val model = lm
        val condMemo = HashMap<Long, Double>()
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
                    val idFirst = model?.charId(firstCp) ?: -1
                    val lastCp = w.codePointBefore(w.length)
                    val inner = if (model == null) 0.0 else lambda * internalBigramScore(w, model, condMemo)
                    for ((prevChar, cell) in from) {
                        val bw = if (cell.prevPos < 0 && prevChar != BOS) contextWeight else lambda
                        val bi = if (model == null || prevChar == BOS) 0.0
                        else bw * logCondMemo(condMemo, model, model.charId(prevChar), idFirst)
                        val og = if (octagram != null && cell.word.isNotEmpty())
                            octagramWeight * (octagram.rawScore(cell.word + w) ?: 0.0) else 0.0
                        val score = cell.score + uni + bi + inner + boost - e.penalty + og
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

    internal companion object {
        const val SEP = '\''
        const val BOS = -1
        const val NO_CTX = Int.MIN_VALUE
        const val EDGE_N = 20
        const val DEFAULT_LAMBDA = 0.5
        const val FUZZY_PENALTY = 3.0
        const val ALIAS_PENALTY = 3.5
        const val INITIALS_PENALTY = 5.0
        const val DEFAULT_OCTAGRAM_WEIGHT = 0.1
        const val BEAM_W = 12
        const val SENTENCE_EDGE_N = 6
        const val ATOMIC_BEAM_N = 8
        const val CTX_WORD_MAX = 4
        const val MAX_SYLLABLE_KEY_LEN = 6
        const val EXACT_TIE_LOOKAHEAD = 16
        const val ORDERING_RARE_FREQ = 100.0
        const val ORDERING_COMMON_FREQ = 1000.0
        val ALIAS_FREQ_DISCOUNT = exp(-ALIAS_PENALTY)
        const val DEFAULT_CONTEXT_WEIGHT = 1.0
        val INPUT_ALIASES = mapOf("en" to listOf("ng"))
        val T9_INPUT_ALIASES: Map<String, List<String>> =
            INPUT_ALIASES.entries.associate { (k, v) -> T9Pinyin.toT9(k) to v }
    }
}
