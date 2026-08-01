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
import com.aegis.ime.user.UserLearning
import com.aegis.ime.user.UserModel
import java.util.PriorityQueue
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
    private val userLearning: UserLearning? = null,
) {
    private val lnTotal = ln(dict.totalFreq.coerceAtLeast(1).toDouble())

    private var userIndexVersion = Long.MIN_VALUE
    private var learnIndexVersion = Long.MIN_VALUE
    private var userLetterIndex: Map<String, List<String>> = emptyMap()
    private var userDigitIndex: Map<String, List<String>> = emptyMap()

    fun setFuzzyRules(rules: Set<String>) {
        fuzzyRules = rules
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
    ) {
        for (wf in preferredExact(source, key)) {
            if (seen.add(wf.word)) out.add(Edge(wf.word, wf.freq, penalty))
        }
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
        if (userModel == null && userLearning == null) return
        val userVersion = userModel?.version ?: Long.MIN_VALUE
        val learnVersion = userLearning?.version ?: Long.MIN_VALUE
        if (userVersion == userIndexVersion && learnVersion == learnIndexVersion) return
        val userSnapshot = userModel?.readingSnapshot().orEmpty()
        val learnSnapshot = userLearning?.readingSnapshot().orEmpty()
        val letter = HashMap<String, MutableList<String>>()
        val digit = HashMap<String, MutableList<String>>()
        for (snapshot in listOf(userSnapshot, learnSnapshot)) {
            for ((reading, words) in snapshot) {
                if (reading.isEmpty()) continue
                val dk = T9Pinyin.toT9(reading)
                for (w in words) {
                    letter.getOrPut(reading) { ArrayList() }.let { if (w !in it) it.add(w) }
                    digit.getOrPut(dk) { ArrayList() }.let { if (w !in it) it.add(w) }
                }
            }
        }
        userLetterIndex = letter
        userDigitIndex = digit
        userIndexVersion = userVersion
        learnIndexVersion = learnVersion
    }

    private fun userWordsFor(key: String): List<String> {
        if ((userModel == null && userLearning == null) || key.isEmpty()) return emptyList()
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
        val out = ArrayList<Edge>()
        val seen = HashSet<String>()
        addExactEdges(dict, sub, 0.0, out, seen)
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
            addExactEdges(aliasSource, alias, ALIAS_PENALTY, out, seen)
        }
        if (fuzzyRules.isNotEmpty()) {
            for (variant in Fuzzy.variants(sub, fuzzyRules)) {
                if (variant == sub) continue
                addExactEdges(dict, variant, FUZZY_PENALTY, out, seen)
            }
        }
        initialsDict?.let { addExactEdges(it, sub, INITIALS_PENALTY, out, seen) }
        return out
    }

    private fun preferredExactLegacy(source: BinaryDict, key: String, limit: Int): List<BinaryDict.WordFreq> {
        if (limit <= 0) return emptyList()
        val scanLimit = minOf(Int.MAX_VALUE.toLong(), limit.toLong() + LEGACY_EXACT_TIE_LOOKAHEAD).toInt()
        val preferred = preferredWordFreqs(source.exact(key, scanLimit))
        return if (preferred.size <= limit) preferred else preferred.subList(0, limit)
    }

    private fun edgesForLegacy(sub: String): List<Edge> {
        val edgeLimit = if (
            lm != null || fuzzyRules.isNotEmpty() || initialsDict != null || octagram != null ||
            userModel != null || userLearning != null
        ) {
            LEGACY_SENTENCE_EDGE_LIMIT
        } else {
            1
        }
        val out = ArrayList<Edge>(edgeLimit)
        val seen = HashSet<String>()
        fun addExact(source: BinaryDict, key: String, penalty: Double): Boolean {
            for (wordFreq in preferredExactLegacy(source, key, edgeLimit + seen.size)) {
                if (seen.add(wordFreq.word)) out.add(Edge(wordFreq.word, wordFreq.freq, penalty))
                if (out.size >= edgeLimit) return true
            }
            return false
        }
        val exactFull = addExact(dict, sub, 0.0)
        for ((word, frequency) in learnedExactWordFreqs(sub)) {
            if (seen.add(word)) out.add(Edge(word, frequency, 0.0))
        }
        for (word in userWordsFor(sub)) {
            if (seen.add(word)) {
                val codePoints = word.codePointCount(0, word.length)
                out.add(
                    Edge(
                        word,
                        userWordFreq(word, sub).toInt().coerceAtLeast(1),
                        (codePoints - 1).coerceAtLeast(0) * lnTotal,
                    ),
                )
            }
        }
        for (alias in inputAliases(sub)) {
            var added = 0
            for (wordFreq in preferredExactLegacy(aliasSource, alias, edgeLimit + seen.size)) {
                if (seen.add(wordFreq.word)) {
                    out.add(Edge(wordFreq.word, wordFreq.freq, ALIAS_PENALTY))
                    added++
                }
                if (added >= edgeLimit) break
            }
        }
        if (exactFull || out.size >= edgeLimit) return out
        if (fuzzyRules.isNotEmpty()) {
            for (variant in Fuzzy.variants(sub, fuzzyRules)) {
                if (variant == sub) continue
                for (wordFreq in preferredExactLegacy(dict, variant, edgeLimit + seen.size)) {
                    if (seen.add(wordFreq.word)) out.add(Edge(wordFreq.word, wordFreq.freq, FUZZY_PENALTY))
                    if (out.size >= edgeLimit) return out
                }
            }
        }
        initialsDict?.let { initials ->
            for (wordFreq in preferredExactLegacy(initials, sub, edgeLimit + seen.size)) {
                if (seen.add(wordFreq.word)) out.add(Edge(wordFreq.word, wordFreq.freq, INITIALS_PENALTY))
                if (out.size >= edgeLimit) break
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

    private fun wordModelScore(word: String, freq: Int, ctxId: Int, ctx: Ctx, condMemo: HashMap<Long, Double>): Double =
        wordModelScore(word, freq.toDouble(), ctxId, ctx, condMemo)

    private fun wordModelScore(word: String, freq: Double, ctxId: Int, ctx: Ctx, condMemo: HashMap<Long, Double>): Double =
        (ln(freq) - lnTotal) +
            (userModel?.wordBoost(word) ?: 0.0) +
            userLearningScore(ctx.tail, word) +
            (octagram?.let { octagramWeight * (it.rawScore(word) ?: 0.0) } ?: 0.0) +
            (lm?.let {
                lambda * internalBigramScore(word, it, condMemo) +
                    if (ctxId != NO_CTX) contextWeight * logCondMemo(condMemo, it, ctxId, it.charId(word.codePointAt(0))) else 0.0
            } ?: 0.0) +
            octagramWeight * contextArm(ctx.tail, word)

    internal data class Ctx(val cp: Int, val tail: String) {
        companion object {
            val EMPTY = Ctx(BOS, "")
        }
    }

    internal fun requiredContextCodePoints(): Int = maxOf(
        1,
        if (octagram == null) 0 else OCTAGRAM_PRECEDING_CODE_POINTS,
        userLearning?.maximumFollowContextCodePoints() ?: 0,
    )

    internal fun parseContext(context: CharSequence): Ctx {
        val tail = rollingHanTail("", context.toString())
        return if (tail.isEmpty()) Ctx.EMPTY else Ctx(tail.codePointBefore(tail.length), tail)
    }

    private fun rollingHanTail(tail: String, word: String): String {
        val combined = tail + word
        var start = combined.length
        var chars = 0
        val limit = requiredContextCodePoints()
        while (start > 0 && chars < limit) {
            val cp = combined.codePointBefore(start)
            if (!isHan(cp)) break
            start -= Character.charCount(cp)
            chars++
        }
        return combined.substring(start)
    }

    private fun bestSuffixGram(text: String, startLimit: Int): Double {
        val gram = octagram ?: return 0.0
        var best = 0.0
        var start = 0
        while (start < startLimit && start < text.length) {
            gram.rawScore(text.substring(start))?.let { if (it > best) best = it }
            start += Character.charCount(text.codePointAt(start))
        }
        return best
    }

    private fun contextArm(contextTail: String, word: String): Double =
        if (contextTail.isEmpty()) 0.0
        else bestSuffixGram(contextTail + word, contextTail.length)

    private fun userLearningScore(contextTail: String, word: String): Double =
        userLearning?.let { it.formedWeight(word) + it.followBoost(contextTail, word) } ?: 0.0

    private fun advanceRankingTail(contextTail: String, word: String): String =
        if (octagram == null && userLearning == null) "" else rollingHanTail(contextTail, word)

    internal fun wholeSentenceArm(contextTail: String, text: String): Double {
        if (text.isEmpty()) return 0.0
        val combined = contextTail + text
        return bestSuffixGram(combined, combined.length)
    }

    private fun isHan(cp: Int): Boolean = Character.isIdeographic(cp)

    private fun isSingleChar(w: String): Boolean = w.codePointCount(0, w.length) == 1

    private fun supplementarySingleTieRank(word: String): Int =
        if (isSingleChar(word) && Character.isSupplementaryCodePoint(word.codePointAt(0))) 1 else 0

    private fun preferredWordFreqs(words: List<BinaryDict.WordFreq>): List<BinaryDict.WordFreq> =
        words.sortedWith(compareByDescending<BinaryDict.WordFreq> { it.freq }.thenBy { supplementarySingleTieRank(it.word) })

    private fun preferredExact(source: BinaryDict, key: String, limit: Int = Int.MAX_VALUE): List<BinaryDict.WordFreq> {
        if (limit <= 0) return emptyList()
        val preferred = preferredWordFreqs(source.exact(key))
        return if (preferred.size <= limit) preferred else preferred.subList(0, limit)
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

    private data class LayeredCandidates(val items: List<Cand>, val remainderStart: Int)

    fun decodeCovered(input: String, limit: Int, cuts: Set<Int> = emptySet(), context: CharSequence = ""): List<Cand> =
        decodeCoveredLayered(input, limit, cuts, context).first

    internal fun decodeCoveredLayered(
        input: String,
        limit: Int,
        cuts: Set<Int> = emptySet(),
        context: CharSequence = "",
    ): Pair<List<Cand>, Int> {
        if (input.isEmpty() || limit <= 0) return emptyList<Cand>() to 0
        val norm = normalizeSeparators(input) ?: return decodeCoveredCleanLegacy(input, limit, cuts, context)
        if (norm.clean.isEmpty()) return emptyList<Cand>() to 0
        val passedClean = cuts.mapNotNull { norm.cleanIndexOfOrig(it) }.toSet()
        val (candidates, remainderStart) = decodeCoveredCleanLegacy(
            norm.clean,
            limit,
            norm.cuts + passedClean,
            context,
        )
        return candidates.map { candidate ->
            Cand(candidate.word, norm.origLen.getOrElse(candidate.coveredLen) { input.length })
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
        val decoded = decodeAtomicLegacy(clean, interior, ctx)
        return if (norm == null) decoded else decoded.map { candidate ->
            Cand(candidate.word, norm.origLen.getOrElse(candidate.coveredLen) { input.length })
        }
    }

    internal fun coveredCandidateSource(
        input: String,
        cuts: Set<Int> = emptySet(),
        context: CharSequence = "",
        atomic: Boolean = false,
    ): CandidatePageSource<Cand> {
        if (input.isEmpty()) return ListCandidatePageSource(emptyList())
        val norm = normalizeSeparators(input)
        val clean = norm?.clean ?: input
        if (clean.isEmpty()) return ListCandidatePageSource(emptyList())
        val passedClean = if (norm == null) cuts else cuts.mapNotNull { norm.cleanIndexOfOrig(it) }.toSet()
        val interior = ((norm?.cuts ?: emptySet()) + passedClean).filterTo(sortedSetOf()) { it in 1 until clean.length }
        val source = if (atomic || interior.isNotEmpty()) {
            atomicCandidateSource(clean, interior, parseContext(context))
        } else {
            incrementalCoveredCandidateSource(clean, context)
        }
        if (norm == null) return source
        return object : CandidatePageSource<Cand> {
            override fun next(pageSize: Int): CandidateSlice<Cand> {
                val slice = source.next(pageSize)
                return CandidateSlice(
                    slice.items.map { Cand(it.word, norm.origLen.getOrElse(it.coveredLen) { input.length }) },
                    slice.hasMore,
                )
            }
        }
    }

    private data class RawPoolSource(
        val cursor: BinaryDict.WordFreqCursor,
        val penalty: Double,
        val order: Int,
    )

    private data class RankedPoolItem(
        val wordFreq: BinaryDict.WordFreq,
        val score: Double,
        val userBoost: Double,
        val serial: Long,
    )

    private inner class IncrementalPoolCursor(
        private val input: String,
        private val ctx: Ctx,
        blockedWords: Set<String>,
    ) {
        private val ctxId = resolveCtxId(ctx.cp)
        private val condMemo = HashMap<Long, Double>()
        private val blocked = HashSet(blockedWords)
        private val emitted = HashSet<String>()
        private val active = HashMap<String, RankedPoolItem>()
        private val ranked = PriorityQueue<RankedPoolItem>(::compareRankedPoolItems)
        private val sources = ArrayList<RawPoolSource>()
        private val delayedRare = ArrayDeque<RankedPoolItem>()
        private val fuzzyVariants = if (fuzzyRules.isEmpty()) {
            null
        } else {
            Fuzzy.variantCursor(input, fuzzyRules, dict.maximumKeyLength)
        }
        private val maximumAdjustment =
                (userModel?.maximumWordBoost() ?: 0.0) +
                (userLearning?.maximumFormedBoost() ?: 0.0) +
                (userLearning?.maximumFollowBoost() ?: 0.0) +
                (octagram?.let { 2.0 * octagramWeight * maxOf(0.0, it.maximumRawScore) } ?: 0.0)
        private var serial = 0L
        private var commonPrepared = false
        private var lastCommon: RankedPoolItem? = null
        private var afterLastCommon = false
        private var delayedDrained = false
        private var sourceOrder = 0
        private var fuzzyExhausted = fuzzyVariants == null
        private var fuzzyBatchAvailable = false

        init {
            sources.add(RawPoolSource(dict.exactCursor(input), 0.0, sourceOrder++))
            for (alias in inputAliases(input)) {
                sources.add(RawPoolSource(aliasSource.exactCursor(alias), ALIAS_PENALTY, sourceOrder++))
            }
            sources.add(RawPoolSource(dict.prefixByFreqCursor(input), 0.0, sourceOrder++))
            initialsDict?.let {
                sources.add(RawPoolSource(it.prefixByFreqCursor(input), INITIALS_PENALTY, sourceOrder++))
            }
            for (word in userWordsFor(input)) {
                val frequency = userWordFreq(word, input).toInt().coerceAtLeast(1)
                offer(BinaryDict.WordFreq(word, frequency), 0.0)
            }
        }

        fun beginPage() {
            fuzzyBatchAvailable = true
        }

        fun next(): RankedPoolItem? {
            if (afterLastCommon && !delayedDrained) {
                val delayed = delayedRare.removeFirstOrNull()
                if (delayed != null) return delayed
                delayedDrained = true
            }
            while (true) {
                val item = nextRanked() ?: return null
                if (!commonPrepared && classification(item) < 0) prepareCommonBoundary()
                val boundary = lastCommon
                if (!afterLastCommon && boundary != null) {
                    if (classification(item) < 0 && compareRankedPoolItems(item, boundary) < 0) {
                        delayedRare.addLast(item)
                        continue
                    }
                    if (item.wordFreq.word == boundary.wordFreq.word) afterLastCommon = true
                }
                return item
            }
        }

        fun hasMore(): Boolean {
            cleanupRanked()
            return delayedRare.isNotEmpty() || active.isNotEmpty() ||
                sources.any { it.cursor.peek() != null } || !fuzzyExhausted
        }

        private fun activateFuzzyBatch(): Boolean {
            val variants = fuzzyVariants ?: return false
            var activated = 0
            while (activated < Fuzzy.VARIANT_BATCH_SIZE) {
                val variant = variants.next()
                if (variant == null) {
                    fuzzyExhausted = true
                    break
                }
                if (variant == input) continue
                sources.add(RawPoolSource(dict.prefixByFreqCursor(variant), FUZZY_PENALTY, sourceOrder++))
                activated++
            }
            return activated > 0
        }

        private fun prepareCommonBoundary() {
            if (commonPrepared) return
            commonPrepared = true
            for (source in sources) {
                while ((source.cursor.peek()?.freq ?: Int.MIN_VALUE) >= ORDERING_COMMON_FREQ.toInt()) {
                    pull(source)
                }
            }
            cleanupRanked()
            lastCommon = active.values
                .filter { classification(it) > 0 }
                .maxWithOrNull(::compareRankedPoolItems)
        }

        private fun nextRanked(): RankedPoolItem? {
            while (true) {
                cleanupRanked()
                val best = ranked.peek()
                val source = sources
                    .asSequence()
                    .filter { it.cursor.peek() != null }
                    .maxWithOrNull(
                        compareBy<RawPoolSource> { unseenUpperBound(it) }
                            .thenBy { -it.order },
                    )
                if (best != null && (source == null || best.score > unseenUpperBound(source))) {
                    ranked.remove()
                    active.remove(best.wordFreq.word)
                    emitted.add(best.wordFreq.word)
                    return best
                }
                if (source != null) {
                    pull(source)
                    continue
                }
                if (!fuzzyExhausted && fuzzyBatchAvailable) {
                    fuzzyBatchAvailable = false
                    if (activateFuzzyBatch()) continue
                }
                if (best != null) {
                    ranked.remove()
                    active.remove(best.wordFreq.word)
                    emitted.add(best.wordFreq.word)
                }
                return best
            }
        }

        private fun unseenUpperBound(source: RawPoolSource): Double {
            val frequency = source.cursor.peek()?.freq ?: return Double.NEGATIVE_INFINITY
            return ln(frequency.coerceAtLeast(1).toDouble()) - lnTotal - source.penalty + maximumAdjustment
        }

        private fun pull(source: RawPoolSource) {
            val wordFreq = source.cursor.next() ?: return
            offer(wordFreq, source.penalty)
        }

        private fun offer(wordFreq: BinaryDict.WordFreq, penalty: Double) {
            if (wordFreq.word in blocked || wordFreq.word in emitted) return
            val userBoost = userModel?.wordBoost(wordFreq.word) ?: 0.0
            val item = RankedPoolItem(
                wordFreq,
                wordModelScore(wordFreq.word, wordFreq.freq, ctxId, ctx, condMemo) - penalty,
                userBoost,
                serial++,
            )
            val previous = active[wordFreq.word]
            if (previous == null || compareRankedPoolItems(item, previous) < 0) {
                active[wordFreq.word] = item
                ranked.add(item)
            }
        }

        private fun cleanupRanked() {
            while (ranked.isNotEmpty()) {
                val item = ranked.peek() ?: break
                if (active[item.wordFreq.word] === item) break
                ranked.remove()
            }
        }

        private fun classification(item: RankedPoolItem): Int {
            if (item.userBoost > 0.0) return 0
            return when {
                item.wordFreq.freq.toDouble() >= ORDERING_COMMON_FREQ -> 1
                item.wordFreq.freq.toDouble() <= ORDERING_RARE_FREQ -> -1
                else -> 0
            }
        }
    }

    private fun compareRankedPoolItems(left: RankedPoolItem, right: RankedPoolItem): Int {
        val score = right.score.compareTo(left.score)
        if (score != 0) return score
        val tie = supplementarySingleTieRank(left.wordFreq.word)
            .compareTo(supplementarySingleTieRank(right.wordFreq.word))
        if (tie != 0) return tie
        return left.serial.compareTo(right.serial)
    }

    private fun incrementalCoveredCandidateSource(input: String, context: CharSequence): CandidatePageSource<Cand> {
        val ctx = parseContext(context)
        val sentences = AtomicSentenceCursor(buildCompleteSentenceGraph(input), ctx)
        val best = sentences.next()?.text
        val seen = HashSet<String>()
        if (best != null) seen.add(best)
        val pool = IncrementalPoolCursor(input, ctx, seen)
        return object : CandidatePageSource<Cand> {
            private var bestPending = best != null
            private var sentenceExhausted = false
            private var leading: List<Cand>? = null
            private var leadingOffset = 0
            private var exhausted = false
            private var headResultCount = if (best != null) 1 else 0
            private var headResultBudget = -1

            override fun next(pageSize: Int): CandidateSlice<Cand> {
                if (headResultBudget < 0) headResultBudget = completionCap(pageSize)
                pool.beginPage()
                val items = ArrayList<Cand>(pageSize)
                var poolPaused = false
                while (items.size < pageSize && !exhausted) {
                    when {
                        bestPending -> {
                            bestPending = false
                            items.add(Cand(requireNotNull(best), input.length))
                        }
                        leading == null && headResultCount < headResultBudget && pool.hasMore() && !poolPaused -> {
                            val item = pool.next()
                            if (item != null && seen.add(item.wordFreq.word)) {
                                items.add(Cand(item.wordFreq.word, input.length))
                                headResultCount++
                            } else if (item == null) {
                                headResultCount = headResultBudget
                                poolPaused = true
                            }
                        }
                        leading == null -> {
                            leading = leadingSingles(input, input.length, ctx, seen)
                        }
                        leadingOffset < leading.orEmpty().size -> {
                            items.add(leading.orEmpty()[leadingOffset++])
                        }
                        pool.hasMore() && !poolPaused -> {
                            val item = pool.next()
                            if (item != null && seen.add(item.wordFreq.word)) {
                                items.add(Cand(item.wordFreq.word, input.length))
                            } else if (item == null) {
                                poolPaused = true
                            }
                        }
                        !sentenceExhausted -> {
                            val sentence = sentences.next()
                            if (sentence == null) {
                                sentenceExhausted = true
                            } else if (seen.add(sentence.text)) {
                                items.add(Cand(sentence.text, input.length))
                            }
                        }
                        pool.hasMore() -> break
                        else -> {
                            exhausted = true
                        }
                    }
                }
                return CandidateSlice(items, !exhausted)
            }
        }
    }

    internal fun isAtomicCandidateReachable(
        input: String,
        word: String,
        cuts: Set<Int> = emptySet(),
    ): Boolean {
        if (input.isEmpty() || word.isEmpty()) return false
        val norm = normalizeSeparators(input)
        val clean = norm?.clean ?: input
        if (clean.isEmpty()) return false
        val passedClean = if (norm == null) cuts else cuts.mapNotNull { norm.cleanIndexOfOrig(it) }.toSet()
        val interior = ((norm?.cuts ?: emptySet()) + passedClean).filterTo(sortedSetOf()) { it in 1 until clean.length }
        val bounds = atomicBounds(clean, interior)
        val graph = buildAtomicGraph(clean, bounds, interior, HashMap())
        val offsets = Array(graph.size + 1) { HashSet<Int>() }
        offsets[0].add(0)
        for (position in graph.indices) for (offset in offsets[position]) {
            for (edge in graph[position]) {
                if (word.startsWith(edge.word, offset)) offsets[edge.end].add(offset + edge.word.length)
            }
        }
        return word.length in offsets[graph.size]
    }

    private fun decodeCoveredCleanLegacy(
        input: String,
        limit: Int,
        cuts: Set<Int>,
        context: CharSequence,
    ): Pair<List<Cand>, Int> {
        val ctx = parseContext(context)
        val interior = cuts.filter { it in 1 until input.length }.toSortedSet()
        if (interior.isNotEmpty()) return decodeAtomicLegacy(input, interior, ctx).let { it to it.size }
        val ctxId = resolveCtxId(ctx.cp)
        val condMemo = HashMap<Long, Double>()
        val cover = LinkedHashMap<String, Int>()
        val completionCap = completionCap(limit)
        bestSentence(input, emptySet(), ctx, legacyBounds = true)?.let { cover[it] = input.length }
        val pool = ArrayList<RankedWord>()
        val offered = HashSet<String>()
        fun offer(wordFreq: BinaryDict.WordFreq, penalty: Double): Boolean {
            if (!offered.add(wordFreq.word)) return false
            pool.add(
                RankedWord(
                    wordFreq,
                    wordModelScore(wordFreq.word, wordFreq.freq, ctxId, ctx, condMemo) - penalty,
                ),
            )
            return true
        }
        val exactWords = HashSet<String>()
        for (wordFreq in dict.exact(input)) {
            if (!isSingleChar(wordFreq.word)) exactWords.add(wordFreq.word)
            offer(wordFreq, 0.0)
        }
        inputAliasWordFreqs(input).forEach { offer(it, ALIAS_PENALTY) }
        dict.prefixByFreq(input, completionCap).forEach { offer(it, 0.0) }
        for (word in userWordsFor(input)) {
            offer(BinaryDict.WordFreq(word, userWordFreq(word, input).toInt().coerceAtLeast(1)), 0.0)
        }
        if (fuzzyRules.isNotEmpty()) {
            for (variant in Fuzzy.variants(input, fuzzyRules)) {
                if (variant == input) continue
                dict.prefixByFreq(variant, completionCap).forEach { offer(it, FUZZY_PENALTY) }
            }
        }
        val reservedInitials = HashSet<String>()
        initialsDict?.let { initials ->
            if (input.length >= INITIALS_RESERVE_MIN_LEN) {
                for (wordFreq in preferredExact(initials, input, INITIALS_RESERVE)) {
                    if (wordFreq.freq <= ORDERING_RARE_FREQ) continue
                    if (offer(wordFreq, INITIALS_PENALTY)) reservedInitials.add(wordFreq.word)
                }
            }
            initials.prefixByFreq(input, completionCap).forEach { offer(it, INITIALS_PENALTY) }
        }
        pool.sortWith(
            compareByDescending<RankedWord> { it.score }
                .thenBy { supplementarySingleTieRank(it.wordFreq.word) },
        )
        enforceRareAfterCommon(
            pool,
            word = { it.wordFreq.word },
            frequency = { it.wordFreq.freq.toDouble() },
        )
        var pendingInitials = reservedInitials.count { it !in cover }
        for ((wordFreq, _) in pool) {
            val reserved = wordFreq.word in reservedInitials
            if (!reserved && cover.size >= completionCap - pendingInitials && wordFreq.word !in exactWords) continue
            if (cover.putIfAbsent(wordFreq.word, input.length) == null && reserved) pendingInitials--
        }
        val out = ArrayList<Cand>(cover.size + 20)
        for ((word, coveredLen) in cover) out.add(Cand(word, coveredLen))
        if (userModel != null) {
            val present = out.mapTo(HashSet()) { it.word }
            for (word in userWordsFor(input)) if (present.add(word)) out.add(Cand(word, input.length))
        }
        val remainderStart = out.size
        appendLeadingSingles(input, input.length, out, ctx)
        return out to remainderStart
    }

    private fun decodeCoveredClean(input: String, context: CharSequence): LayeredCandidates {
        val ctx = parseContext(context)
        val ctxId = resolveCtxId(ctx.cp)
        val condMemo = HashMap<Long, Double>()
        val cover = LinkedHashMap<String, Int>()
        bestSentence(input, emptySet(), ctx)?.let { cover[it] = input.length }
        val pool = ArrayList<RankedWord>()
        val offered = HashSet<String>()
        fun offer(wf: BinaryDict.WordFreq, penalty: Double): Boolean {
            if (!offered.add(wf.word)) return false
            pool.add(
                RankedWord(
                    wf,
                    wordModelScore(wf.word, wf.freq, ctxId, ctx, condMemo) - penalty,
                ),
            )
            return true
        }
        for (wf in dict.exact(input)) {
            offer(wf, 0.0)
        }
        inputAliasWordFreqs(input).forEach {
            offer(it, ALIAS_PENALTY)
        }
        dict.prefixByFreq(input, Int.MAX_VALUE).forEach { offer(it, 0.0) }
        for (uw in userWordsFor(input)) {
            offer(BinaryDict.WordFreq(uw, userWordFreq(uw, input).toInt().coerceAtLeast(1)), 0.0)
        }
        if (fuzzyRules.isNotEmpty()) {
            for (variant in Fuzzy.variants(input, fuzzyRules)) {
                if (variant == input) continue
                dict.prefixByFreq(variant, Int.MAX_VALUE).forEach { offer(it, FUZZY_PENALTY) }
            }
        }
        initialsDict?.let { id ->
            id.prefixByFreq(input, Int.MAX_VALUE).forEach { offer(it, INITIALS_PENALTY) }
        }
        pool.sortWith(
            compareByDescending<RankedWord> { it.score }
                .thenBy { supplementarySingleTieRank(it.wordFreq.word) },
        )
        enforceRareAfterCommon(
            pool,
            word = { it.wordFreq.word },
            frequency = { it.wordFreq.freq.toDouble() },
        )
        for ((wf, _) in pool) cover.putIfAbsent(wf.word, input.length)
        val out = ArrayList<Cand>(cover.size + 20)
        for ((w, len) in cover) out.add(Cand(w, len))
        val remainderStart = out.size
        appendLeadingSingles(input, input.length, out, ctx)
        return LayeredCandidates(out, remainderStart)
    }

    private fun decodeAtomicLegacy(input: String, interior: Set<Int>, ctx: Ctx): List<Cand> {
        val ctxId = resolveCtxId(ctx.cp)
        val condMemo = HashMap<Long, Double>()
        val bounds = atomicBounds(input, interior)
        val nSyl = bounds.size - 1
        val singlesCache = HashMap<String, Set<String>>()
        val sentences = atomicSentencesLegacy(input, bounds, interior, ctx, singlesCache)
        val best = sentences.firstOrNull()?.text
        val leadFreq = LinkedHashMap<String, Int>()
        val leadCov = HashMap<String, Int>()
        for (end in 2..nSyl) {
            for (wordFreq in preferredExact(dict, input.substring(0, bounds[end]))) {
                if (isSingleChar(wordFreq.word)) continue
                if (!admissibleUnderCuts(wordFreq.word, 0, bounds[end], interior, input, singlesCache)) continue
                if (leadFreq.put(wordFreq.word, wordFreq.freq) == null) leadCov[wordFreq.word] = bounds[end]
            }
        }
        for (word in userWordsFor(input)) {
            if (word == best || word in leadFreq || word.codePointCount(0, word.length) < 2) continue
            if (!admissibleUnderCuts(word, 0, input.length, interior, input, singlesCache)) continue
            val frequency = userWordFreq(word, input).toInt().coerceAtLeast(1)
            if (leadFreq.put(word, frequency) == null) leadCov[word] = input.length
        }
        val syllableCharFrequency = Array(nSyl) { index ->
            val frequencies = HashMap<String, Double>()
            for ((word, frequency) in homophoneFreqs(input.substring(bounds[index], bounds[index + 1]))) {
                frequencies.putIfAbsent(word, frequency)
            }
            frequencies
        }
        fun commonnessFrequency(word: String, coveredSyllables: Int, carried: Double): Double {
            var minimum = Double.MAX_VALUE
            var offset = 0
            var syllable = 0
            while (offset < word.length && syllable < coveredSyllables) {
                val cp = word.codePointAt(offset)
                val frequency = syllableCharFrequency.getOrNull(syllable)
                    ?.get(String(Character.toChars(cp))) ?: carried
                minimum = minOf(minimum, frequency)
                offset += Character.charCount(cp)
                syllable++
            }
            return if (minimum == Double.MAX_VALUE) carried else minimum
        }
        val tailScore = HashMap<String, Double>()
        val tailCandidate = LinkedHashMap<String, Cand>()
        fun offerTail(word: String, coveredLen: Int, coveredSyllables: Int, carried: Double) {
            if (word == best || word in leadFreq) return
            val score = wordModelScore(
                word,
                commonnessFrequency(word, coveredSyllables, carried),
                ctxId,
                ctx,
                condMemo,
            )
            val previous = tailScore[word]
            if (previous == null || score > previous) {
                tailScore[word] = score
                tailCandidate[word] = Cand(word, coveredLen)
            }
        }
        for (sentence in sentences) offerTail(sentence.text, input.length, nSyl, 1.0)
        for ((word, _) in homophoneFreqs(input.substring(0, bounds[1]))) {
            offerTail(word, bounds[1], 1, 0.0)
        }
        val tailRanked = tailCandidate.values.sortedWith(
            compareBy<Cand> { isSingleChar(it.word) }
                .thenByDescending { tailScore[it.word] ?: Double.NEGATIVE_INFINITY }
                .thenBy { it.word.codePointCount(0, it.word.length) }
                .thenBy { supplementarySingleTieRank(it.word) },
        )
        val out = ArrayList<Cand>(1 + leadFreq.size + tailRanked.size)
        val seen = HashSet<String>()
        best?.let { if (seen.add(it)) out.add(Cand(it, input.length)) }
        for ((word, _) in leadFreq.entries.sortedByDescending {
            wordModelScore(it.key, it.value, ctxId, ctx, condMemo)
        }) {
            if (seen.add(word)) out.add(Cand(word, leadCov.getValue(word)))
        }
        for (candidate in tailRanked) if (seen.add(candidate.word)) out.add(candidate)
        return out
    }

    private fun atomicCandidateSource(input: String, interior: Set<Int>, ctx: Ctx): CandidatePageSource<Cand> {
        val ctxId = resolveCtxId(ctx.cp)
        val condMemo = HashMap<Long, Double>()
        val bounds = atomicBounds(input, interior)
        val nSyl = bounds.size - 1
        val singlesCache = HashMap<String, Set<String>>()
        val graph = buildAtomicGraph(input, bounds, interior, singlesCache)
        val sentences = AtomicSentenceCursor(graph, ctx)
        val best = sentences.next()?.text
        val leadFreq = LinkedHashMap<String, Int>()
        val leadCov = HashMap<String, Int>()
        for (j in 2..nSyl) for (wf in preferredExact(dict, input.substring(0, bounds[j]))) if (!isSingleChar(wf.word)) {
            if (!admissibleUnderCuts(wf.word, 0, bounds[j], interior, input, singlesCache)) continue
            if (leadFreq.put(wf.word, wf.freq) == null) leadCov[wf.word] = bounds[j]
        }
        for (uw in userWordsFor(input)) {
            if (uw == best || uw in leadFreq || uw.codePointCount(0, uw.length) < 2) continue
            if (!admissibleUnderCuts(uw, 0, input.length, interior, input, singlesCache)) continue
            val f = userWordFreq(uw, input).toInt().coerceAtLeast(1)
            if (leadFreq.put(uw, f) == null) leadCov[uw] = input.length
        }
        val leading = leadFreq.entries.sortedWith(
            compareByDescending<Map.Entry<String, Int>> {
                wordModelScore(it.key, it.value, ctxId, ctx, condMemo)
            }.thenBy { supplementarySingleTieRank(it.key) },
        )
        val singles = if (nSyl == 0) emptyList() else homophoneFreqs(input.substring(0, bounds[1])).map { it.first }
        return object : CandidatePageSource<Cand> {
            private val pending = ArrayDeque<Cand>()
            private val seen = HashSet<String>()
            private var sentenceExhausted = false
            private var singlesAdded = false

            init {
                best?.let { if (seen.add(it)) pending.addLast(Cand(it, input.length)) }
                for ((word, _) in leading) {
                    if (seen.add(word)) pending.addLast(Cand(word, leadCov.getValue(word)))
                }
            }

            private fun fill(targetSize: Int) {
                while (pending.size < targetSize && !sentenceExhausted) {
                    val sentence = sentences.next()
                    if (sentence == null) {
                        sentenceExhausted = true
                    } else if (seen.add(sentence.text)) {
                        pending.addLast(Cand(sentence.text, input.length))
                    }
                }
                if (pending.size < targetSize && sentenceExhausted && !singlesAdded) {
                    for (word in singles) if (seen.add(word)) pending.addLast(Cand(word, bounds[1]))
                    singlesAdded = true
                }
            }

            override fun next(pageSize: Int): CandidateSlice<Cand> {
                fill(pageSize + 1)
                val count = minOf(pageSize, pending.size)
                val items = ArrayList<Cand>(count)
                repeat(count) { items.add(pending.removeFirst()) }
                return CandidateSlice(items, pending.isNotEmpty())
            }
        }
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

    private data class LegacyAtomicPath(
        val text: String,
        val lastCp: Int,
        val tail: String,
        val score: Double,
    )

    internal data class SentencePath(val text: String, val score: Double)

    internal fun rerankSentencePaths(paths: List<SentencePath>, contextTail: String): List<SentencePath> {
        if (octagram == null || paths.size < 2) return paths
        val scores = DoubleArray(paths.size) {
            paths[it].score + octagramWeight * wholeSentenceArm(contextTail, paths[it].text)
        }
        val order = paths.indices.sortedWith(
            compareByDescending<Int> { scores[it] }.thenBy { it },
        )
        val out = ArrayList<SentencePath>(paths.size)
        for (index in order) out.add(paths[index])
        return out
    }

    private fun atomicSentencesLegacy(
        input: String,
        bounds: List<Int>,
        interior: Set<Int>,
        ctx: Ctx,
        singlesCache: HashMap<String, Set<String>>,
    ): List<SentencePath> {
        val model = lm
        val condMemo = HashMap<Long, Double>()
        val nSyl = bounds.size - 1
        val paths = Array(bounds.size) { ArrayList<LegacyAtomicPath>() }
        paths[0].add(
            LegacyAtomicPath(
                "",
                ctx.cp,
                if (octagram == null && userLearning == null) "" else ctx.tail,
                0.0,
            ),
        )
        for (start in 0 until nSyl) {
            if (paths[start].isEmpty()) continue
            val source = paths[start].sortedByDescending { it.score }.take(LEGACY_ATOMIC_BEAM_WIDTH)
            for (end in start + 1..nSyl) {
                val segment = input.substring(bounds[start], bounds[end])
                val exact = preferredExact(dict, segment)
                val eligible = if (end == start + 1) {
                    exact.filter { isSingleChar(it.word) }
                } else {
                    exact.filterNot { isSingleChar(it.word) }
                        .filter {
                            admissibleUnderCuts(
                                it.word,
                                bounds[start],
                                bounds[end],
                                interior,
                                input,
                                singlesCache,
                            )
                        }
                }
                val entries = eligible.take(LEGACY_ATOMIC_EDGE_WIDTH).toMutableList()
                val present = entries.mapTo(HashSet()) { it.word }
                for ((word, frequency) in learnedExactWordFreqs(segment)) {
                    if (!present.add(word)) continue
                    if (end == start + 1 && !isSingleChar(word)) continue
                    if (end > start + 1 && isSingleChar(word)) continue
                    if (end > start + 1 && !admissibleUnderCuts(
                            word,
                            bounds[start],
                            bounds[end],
                            interior,
                            input,
                            singlesCache,
                        )
                    ) continue
                    entries.add(BinaryDict.WordFreq(word, frequency))
                }
                for (entry in entries) {
                    val word = entry.word
                    val firstCp = word.codePointAt(0)
                    val firstId = model?.charId(firstCp) ?: -1
                    val lastCp = word.codePointBefore(word.length)
                    val unigram = ln(entry.freq.toDouble()) - lnTotal
                    val boost = (userModel?.wordBoost(word) ?: 0.0) +
                        (userLearning?.formedWeight(word) ?: 0.0)
                    val inner = if (model == null) 0.0 else lambda * internalBigramScore(word, model, condMemo)
                    for (path in source) {
                        val bigramWeight = if (path.text.isEmpty() && path.lastCp != BOS) contextWeight else lambda
                        val bigram = if (model == null || path.lastCp == BOS) {
                            0.0
                        } else {
                            bigramWeight * logCondMemo(
                                condMemo,
                                model,
                                model.charId(path.lastCp),
                                firstId,
                            )
                        }
                        val grammar = octagramWeight * contextArm(path.tail, word)
                        val follow = userLearning?.followBoost(path.tail, word) ?: 0.0
                        paths[end].add(
                            LegacyAtomicPath(
                                path.text + word,
                                lastCp,
                                advanceRankingTail(path.tail, word),
                                path.score + unigram + bigram + inner + boost + follow + grammar,
                            ),
                        )
                    }
                }
            }
        }
        if (paths[nSyl].isEmpty()) return emptyList()
        val emit = LEGACY_ATOMIC_BASE_RESULTS +
            LEGACY_ATOMIC_RESULTS_PER_SYLLABLE * (nSyl - 2).coerceAtLeast(0)
        val ordered = ArrayList<SentencePath>(emit)
        val seen = HashSet<String>()
        for (path in paths[nSyl].sortedByDescending { it.score }) {
            if (!seen.add(path.text)) continue
            ordered.add(SentencePath(path.text, path.score))
            if (ordered.size >= emit) break
        }
        if (octagram == null || ordered.size < 2) return ordered
        val headSize = minOf(LEGACY_SENTENCE_RERANK_RESULTS, ordered.size)
        val scores = DoubleArray(headSize) { index ->
            ordered[index].score + octagramWeight * wholeSentenceArm(ctx.tail, ordered[index].text)
        }
        val order = (0 until headSize).sortedWith(
            compareByDescending<Int> { scores[it] }.thenBy { it },
        )
        val reranked = ArrayList<SentencePath>(ordered.size)
        for (index in order) reranked.add(ordered[index])
        for (index in headSize until ordered.size) reranked.add(ordered[index])
        return reranked
    }

    private data class AtomicGraphEdge(
        val end: Int,
        val word: String,
        val firstCp: Int,
        val lastCp: Int,
        val baseScore: Double,
    )

    private fun buildCompleteSentenceGraph(input: String): Array<ArrayList<AtomicGraphEdge>> {
        val model = lm
        val condMemo = HashMap<Long, Double>()
        val graph = Array(input.length) { ArrayList<AtomicGraphEdge>() }
        for (start in input.indices) for (end in start + 1..input.length) {
            for (edge in edgesFor(input.substring(start, end))) {
                val word = edge.word
                val firstCp = word.codePointAt(0)
                val lastCp = word.codePointBefore(word.length)
                val unigram = ln(edge.freq.toDouble()) - lnTotal
                val boost = (userModel?.wordBoost(word) ?: 0.0) +
                    (userLearning?.formedWeight(word) ?: 0.0)
                val inner = if (model == null) 0.0 else lambda * internalBigramScore(word, model, condMemo)
                graph[start].add(
                    AtomicGraphEdge(end, word, firstCp, lastCp, unigram + boost + inner - edge.penalty),
                )
            }
        }
        return graph
    }

    private fun buildAtomicGraph(
        input: String,
        bounds: List<Int>,
        interior: Set<Int>,
        singlesCache: HashMap<String, Set<String>>,
    ): Array<ArrayList<AtomicGraphEdge>> {
        val model = lm
        val condMemo = HashMap<Long, Double>()
        val nSyl = bounds.size - 1
        val graph = Array(nSyl) { ArrayList<AtomicGraphEdge>() }
        for (i in 0 until nSyl) for (j in i + 1..nSyl) {
            val segment = input.substring(bounds[i], bounds[j])
            val exact = preferredExact(dict, segment)
            val eligible = if (j == i + 1) {
                exact.filter { isSingleChar(it.word) }
            } else {
                exact.filterNot { isSingleChar(it.word) }
                    .filter { admissibleUnderCuts(it.word, bounds[i], bounds[j], interior, input, singlesCache) }
            }
            val entries = eligible.toMutableList()
            val present = entries.mapTo(HashSet()) { it.word }
            for ((word, freq) in learnedExactWordFreqs(segment)) {
                if (!present.add(word)) continue
                if (j == i + 1 && !isSingleChar(word)) continue
                if (j > i + 1 && isSingleChar(word)) continue
                if (j > i + 1 && !admissibleUnderCuts(word, bounds[i], bounds[j], interior, input, singlesCache)) continue
                entries.add(BinaryDict.WordFreq(word, freq))
            }
            for (entry in entries) {
                val word = entry.word
                val firstCp = word.codePointAt(0)
                val lastCp = word.codePointBefore(word.length)
                val unigram = ln(entry.freq.toDouble()) - lnTotal
                val boost = (userModel?.wordBoost(word) ?: 0.0) +
                    (userLearning?.formedWeight(word) ?: 0.0)
                val inner = if (model == null) 0.0 else lambda * internalBigramScore(word, model, condMemo)
                graph[i].add(AtomicGraphEdge(j, word, firstCp, lastCp, unigram + boost + inner))
            }
        }
        return graph
    }

    private data class AtomicGraphState(val position: Int, val lastCp: Int, val tail: String)

    private data class AtomicTransition(val state: AtomicGraphState, val score: Double)

    private data class AtomicQueuePath(
        val state: AtomicGraphState,
        val text: String,
        val baseScore: Double,
        val priority: Double,
        val serial: Long,
    )

    private inner class AtomicSentenceCursor(
        private val graph: Array<ArrayList<AtomicGraphEdge>>,
        private val ctx: Ctx,
    ) {
        private val model = lm
        private val condMemo = HashMap<Long, Double>()
        private val nSyl = graph.size
        private val suffixUpperBound = DoubleArray(nSyl + 1) { Double.NEGATIVE_INFINITY }
        private val yielded = HashSet<String>()
        private val queue = PriorityQueue<AtomicQueuePath> { a, b ->
            val priority = b.priority.compareTo(a.priority)
            if (priority != 0) priority
            else {
                val base = b.baseScore.compareTo(a.baseScore)
                if (base != 0) base else a.serial.compareTo(b.serial)
            }
        }
        private val grammarUpperBound = octagram?.let {
            maxOf(0.0, octagramWeight * it.maximumRawScore)
        } ?: 0.0
        private val transitionUpperBound = grammarUpperBound + (userLearning?.maximumFollowBoost() ?: 0.0)
        private var serial = 0L

        init {
            suffixUpperBound[nSyl] = 0.0
            for (position in nSyl - 1 downTo 0) {
                var best = Double.NEGATIVE_INFINITY
                for (edge in graph[position]) {
                    val suffix = suffixUpperBound[edge.end]
                    if (!suffix.isFinite()) continue
                    best = maxOf(best, edge.baseScore + transitionUpperBound + suffix)
                }
                suffixUpperBound[position] = best
            }
            val initial = AtomicGraphState(
                0,
                ctx.cp,
                if (octagram == null && userLearning == null) "" else ctx.tail,
            )
            val remaining = suffixUpperBound[0]
            if (remaining.isFinite()) {
                queue.add(AtomicQueuePath(initial, "", 0.0, remaining + grammarUpperBound, serial++))
            }
        }

        private fun transition(state: AtomicGraphState, edge: AtomicGraphEdge): AtomicTransition {
            val bw = if (state.position == 0 && state.lastCp != BOS) contextWeight else lambda
            val bi = if (model == null || state.lastCp == BOS) {
                0.0
            } else {
                bw * logCondMemo(
                    condMemo,
                    model,
                    model.charId(state.lastCp),
                    model.charId(edge.firstCp),
                )
            }
            val score = edge.baseScore + bi +
                octagramWeight * contextArm(state.tail, edge.word) +
                (userLearning?.followBoost(state.tail, edge.word) ?: 0.0)
            return AtomicTransition(
                AtomicGraphState(edge.end, edge.lastCp, advanceRankingTail(state.tail, edge.word)),
                score,
            )
        }

        private fun terminalScore(baseScore: Double, text: String): Double =
            baseScore + octagramWeight * wholeSentenceArm(ctx.tail, text)

        fun next(): SentencePath? {
            while (queue.isNotEmpty()) {
                val path = queue.remove()
                if (path.state.position == nSyl) {
                    if (yielded.add(path.text)) return SentencePath(path.text, terminalScore(path.baseScore, path.text))
                    continue
                }
                for (edge in graph[path.state.position]) {
                    val transition = transition(path.state, edge)
                    val nextScore = path.baseScore + transition.score
                    val nextText = path.text + edge.word
                    val priority = if (transition.state.position == nSyl) {
                        terminalScore(nextScore, nextText)
                    } else {
                        val remaining = suffixUpperBound[transition.state.position]
                        if (!remaining.isFinite()) continue
                        nextScore + remaining + grammarUpperBound
                    }
                    queue.add(
                        AtomicQueuePath(
                            transition.state,
                            nextText,
                            nextScore,
                            priority,
                            serial++,
                        ),
                    )
                }
            }
            return null
        }
    }

    private fun appendLeadingSingles(input: String, span: Int, out: ArrayList<Cand>, ctx: Ctx) {
        val seen = out.mapTo(HashSet(out.size * 2)) { it.word }
        val existingByCoverage = out.groupBy({ it.coveredLen }, { it.word })
        out.addAll(leadingSingles(input, span, ctx, seen, existingByCoverage))
    }

    private fun leadingSingles(
        input: String,
        span: Int,
        ctx: Ctx,
        seen: MutableSet<String>,
        existingByCoverage: Map<Int, List<String>> = emptyMap(),
    ): List<Cand> {
        val ctxId = resolveCtxId(ctx.cp)
        val condMemo = HashMap<Long, Double>()
        val head = input.substring(0, span)
        val isT9 = input[0] in '2'..'9'
        val lens = if (isT9) T9Pinyin.leadingSyllableDigitLens(head)
        else T9Pinyin.leadingSyllableLetterLens(head)
        val lensSet = lens.toSet()
        val entries = ArrayList<Entry>()
        for (q in span downTo 1) {
            for (wf in preferredExact(dict, input.substring(0, q))) {
                if (isSingleChar(wf.word) || !seen.add(wf.word)) continue
                entries.add(
                    Entry(
                        wf.word,
                        q,
                        wordModelScore(wf.word, wf.freq, ctxId, ctx, condMemo),
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
                            wordModelScore(w, f, ctxId, ctx, condMemo),
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
            present.addAll(existingByCoverage[k].orEmpty())
            for (e in entries) if (e.cov == k) present.add(e.word)
            for ((w, f) in homophoneFreqs(input.substring(0, k))) if (present.add(w))
                entries.add(
                    Entry(
                        w,
                        k,
                        wordModelScore(w, f, ctxId, ctx, condMemo),
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
        return entries.map { Cand(it.word, it.cov) }
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

    private fun homophonesOf(key: String): List<String> = homophoneFreqs(key).map { it.first }

    internal fun homophoneFreqs(key: String): List<Pair<String, Double>> {
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

    private data class SentenceState(val lastCp: Int, val tail: String) {
        override fun hashCode(): Int = if (tail.isEmpty()) lastCp else 31 * lastCp + tail.hashCode()
    }

    private class Cell(val score: Double, val prevPos: Int, val prevState: SentenceState?, val word: String)

    private fun bestSentence(
        input: String,
        cuts: Set<Int> = emptySet(),
        ctx: Ctx = Ctx.EMPTY,
        legacyBounds: Boolean = false,
    ): String? {
        val model = lm
        val condMemo = HashMap<Long, Double>()
        val n = input.length
        val dp = Array<MutableMap<SentenceState, Cell>>(n + 1) {
            if (octagram == null && userLearning == null) HashMap() else LinkedHashMap()
        }
        val initial = SentenceState(ctx.cp, if (octagram == null && userLearning == null) "" else ctx.tail)
        dp[0][initial] = Cell(0.0, -1, null, "")

        for (q in 1..n) {
            for (p in 0 until q) {
                val from = dp[p]
                if (from.isEmpty()) continue
                if (cuts.any { it > p && it < q }) continue
                val edges = if (legacyBounds) {
                    edgesForLegacy(input.substring(p, q))
                } else {
                    edgesFor(input.substring(p, q))
                }
                if (edges.isEmpty()) continue
                for (e in edges) {
                    val w = e.word
                    val uni = ln(e.freq.toDouble()) - lnTotal
                    val boost = (userModel?.wordBoost(w) ?: 0.0) +
                        (userLearning?.formedWeight(w) ?: 0.0)
                    val firstCp = w.codePointAt(0)
                    val idFirst = model?.charId(firstCp) ?: -1
                    val lastCp = w.codePointBefore(w.length)
                    val inner = if (model == null) 0.0 else lambda * internalBigramScore(w, model, condMemo)
                    for ((state, cell) in from) {
                        val bw = if (cell.prevPos < 0 && state.lastCp != BOS) contextWeight else lambda
                        val bi = if (model == null || state.lastCp == BOS) 0.0
                        else bw * logCondMemo(condMemo, model, model.charId(state.lastCp), idFirst)
                        val og = octagramWeight * contextArm(state.tail, w)
                        val follow = userLearning?.followBoost(state.tail, w) ?: 0.0
                        val score = cell.score + uni + bi + inner + boost + follow - e.penalty + og
                        val nextState = SentenceState(lastCp, advanceRankingTail(state.tail, w))
                        val cur = dp[q][nextState]
                        if (cur == null || score > cur.score) {
                            dp[q][nextState] = Cell(score, p, state, w)
                        }
                    }
                }
            }
            if (legacyBounds && (octagram != null || userLearning != null) && dp[q].size > LEGACY_ATOMIC_BEAM_WIDTH) {
                val keep = dp[q].entries
                    .sortedByDescending { it.value.score }
                    .take(LEGACY_ATOMIC_BEAM_WIDTH)
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
        return parts.joinToString("")
    }

    internal companion object {
        const val SEP = '\''
        const val BOS = -1
        const val NO_CTX = Int.MIN_VALUE
        const val DEFAULT_LAMBDA = 0.5
        const val FUZZY_PENALTY = 3.0
        const val ALIAS_PENALTY = 3.5
        const val INITIALS_PENALTY = 5.0
        const val INITIALS_RESERVE = 1
        const val INITIALS_RESERVE_MIN_LEN = 2
        const val DEFAULT_OCTAGRAM_WEIGHT = 0.1
        const val LEGACY_SENTENCE_EDGE_LIMIT = 20
        const val LEGACY_EXACT_TIE_LOOKAHEAD = 16
        const val LEGACY_ATOMIC_BEAM_WIDTH = 12
        const val LEGACY_ATOMIC_EDGE_WIDTH = 6
        const val LEGACY_ATOMIC_BASE_RESULTS = 8
        const val LEGACY_ATOMIC_RESULTS_PER_SYLLABLE = 40
        const val LEGACY_SENTENCE_RERANK_RESULTS = 128
        const val OCTAGRAM_PRECEDING_CODE_POINTS = 7
        const val MAX_SYLLABLE_KEY_LEN = 6
        const val ORDERING_RARE_FREQ = 100.0
        const val ORDERING_COMMON_FREQ = 1000.0
        val ALIAS_FREQ_DISCOUNT = exp(-ALIAS_PENALTY)
        const val DEFAULT_CONTEXT_WEIGHT = 1.0
        fun completionCap(limit: Int): Int = maxOf(1, (limit.toLong() * 2 / 3).toInt())
        val INPUT_ALIASES = mapOf("en" to listOf("ng"))
        val T9_INPUT_ALIASES: Map<String, List<String>> =
            INPUT_ALIASES.entries.associate { (k, v) -> T9Pinyin.toT9(k) to v }
    }
}
