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
import com.aegis.ime.dict.TghGrading
import com.aegis.ime.user.UserLearning
import com.aegis.ime.user.UserModel
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.exp
import kotlin.math.ln

data class Cand(
    val word: String,
    val coveredLen: Int,
    val correctedReading: String? = null,
)

data class Syllable(val reading: String, val start: Int, val end: Int)

class PinyinDecoder(
    private val dict: BinaryDict,
    private val lm: CharBigramLM? = null,
    private val lambda: Double = DEFAULT_LAMBDA,
    private val userModel: UserModel? = null,
    fuzzyRules: Set<String> = emptySet(),
    private val initialsDict: BinaryDict? = null,
    private val octagram: com.aegis.ime.dict.OctagramReader? = null,
    private val octagramWeight: Double = DEFAULT_OCTAGRAM_WEIGHT,
    private val contextWeight: Double = DEFAULT_CONTEXT_WEIGHT,
    private val aliasDict: BinaryDict? = null,
    private val userLearning: UserLearning? = null,
    private val fuzzyVariants: (String, Set<String>) -> List<String> = { s, rules -> Fuzzy.variants(s, rules).filter { it != s } },
    private val fuzzyPenalty: Double = FUZZY_PENALTY,
) {
    private val grading = TghGrading.bundled
    private val lnTotal = ln(dict.totalFreq.coerceAtLeast(1).toDouble())

    @Volatile private var fuzzyRules: Set<String> = fuzzyRules

    @Volatile private var edgeN =
        if (lm != null || fuzzyRules.isNotEmpty() || initialsDict != null || octagram != null) EDGE_N else 1

    private var userIndexVersion = Long.MIN_VALUE
    private var learnIndexVersion = Long.MIN_VALUE
    private var userLetterIndex: Map<String, UserWords> = emptyMap()
    private var userDigitIndex: Map<String, UserWords> = emptyMap()
    private var manualLetterIndex: Map<String, Set<String>> = emptyMap()
    private var manualDigitIndex: Map<String, Set<String>> = emptyMap()

    fun setFuzzyRules(rules: Set<String>) {
        fuzzyRules = rules
        edgeN = if (lm != null || rules.isNotEmpty() || initialsDict != null || octagram != null) EDGE_N else 1
    }

    private class Edge(val word: String, val freq: Int, val penalty: Double)

    private class RankedWords(val userVersion: Long, val learnVersion: Long, val words: List<String>)

    private class UserWords {
        val used = ArrayList<List<String>>()
        val formed = ArrayList<Pair<String, List<String>>>()
        @Volatile var ranked: RankedWords? = null
    }

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
        if (userModel == null && userLearning == null) return
        val userVersion = userModel?.readingsVersion ?: Long.MIN_VALUE
        val learnVersion = userLearning?.formedVersion ?: Long.MIN_VALUE
        if (userVersion == userIndexVersion && learnVersion == learnIndexVersion) return
        val userSnapshot = userModel?.readingSnapshot().orEmpty()
        val learnSnapshot = userLearning?.readingSnapshot().orEmpty()
        val letter = HashMap<String, UserWords>()
        val digit = HashMap<String, UserWords>()
        val singles = HashMap<String, Set<String>>()
        for ((reading, words) in userSnapshot) {
            if (reading.isEmpty() || words.isEmpty()) continue
            letter.getOrPut(reading) { UserWords() }.used.add(words)
            digit.getOrPut(T9Pinyin.toT9(reading)) { UserWords() }.used.add(words)
        }
        for ((reading, words) in learnSnapshot) {
            if (reading.isEmpty()) continue
            val readable = words.filter { readsAs(it, reading, singles) }
            if (readable.isEmpty()) continue
            letter.getOrPut(reading) { UserWords() }.formed.add(reading to readable)
            digit.getOrPut(T9Pinyin.toT9(reading)) { UserWords() }.formed.add(reading to readable)
        }
        val manualLetter = HashMap<String, MutableSet<String>>()
        val manualDigit = HashMap<String, MutableSet<String>>()
        for ((reading, words) in userModel?.manualSnapshot().orEmpty()) {
            if (reading.isEmpty() || words.isEmpty()) continue
            val dk = T9Pinyin.toT9(reading)
            manualLetter.getOrPut(reading) { HashSet() }.addAll(words)
            manualDigit.getOrPut(dk) { HashSet() }.addAll(words)
        }
        userLetterIndex = letter
        userDigitIndex = digit
        manualLetterIndex = manualLetter
        manualDigitIndex = manualDigit
        userIndexVersion = userVersion
        learnIndexVersion = learnVersion
    }

    private fun readsAs(word: String, reading: String, cache: HashMap<String, Set<String>>): Boolean {
        if (reading.isEmpty() || word.isEmpty()) return false
        val cps = ArrayList<String>(4)
        var ci = 0
        while (ci < word.length) {
            val cp = word.codePointAt(ci)
            cps.add(String(Character.toChars(cp)))
            ci += Character.charCount(cp)
        }
        val n = reading.length
        val m = cps.size
        if (m > n) return false
        val ref = aliasDict ?: dict
        fun singles(key: String): Set<String> = cache.getOrPut(key) {
            val out = HashSet<String>()
            for (wf in ref.exact(key)) if (isSingleChar(wf.word)) out.add(wf.word)
            out
        }
        val dp = Array(n + 1) { BooleanArray(m + 1) }
        dp[0][0] = true
        for (p in 0 until n) for (i in 0 until m) {
            if (!dp[p][i]) continue
            var q = p + 1
            while (q <= n && q - p <= MAX_SYLLABLE_KEY_LEN) {
                val key = reading.substring(p, q)
                val known = singles(key)
                if (cps[i] in known || (known.isEmpty() && key in T9Pinyin.SYLLABLES)) dp[q][i + 1] = true
                q++
            }
        }
        return dp[n][m]
    }

    private fun userWordsFor(key: String): List<String> {
        if ((userModel == null && userLearning == null) || key.isEmpty()) return emptyList()
        refreshUserIndex()
        val words = (if (key[0] in '2'..'9') userDigitIndex[key] else userLetterIndex[key]) ?: return emptyList()
        val userVersion = userModel?.version ?: Long.MIN_VALUE
        val learnVersion = userLearning?.version ?: Long.MIN_VALUE
        words.ranked?.let { if (it.userVersion == userVersion && it.learnVersion == learnVersion) return it.words }
        val out = ArrayList<String>()
        for (group in words.used) {
            for (w in userModel?.rankedByUsage(group).orEmpty()) if (w !in out) out.add(w)
        }
        for ((reading, group) in words.formed) {
            for (w in userLearning?.rankedFormed(reading, group).orEmpty()) if (w !in out) out.add(w)
        }
        words.ranked = RankedWords(userVersion, learnVersion, out)
        return out
    }

    private fun manualWordsFor(key: String): Set<String> {
        if (userModel == null || key.isEmpty()) return emptySet()
        refreshUserIndex()
        return (if (key[0] in '2'..'9') manualDigitIndex[key] else manualLetterIndex[key]) ?: emptySet()
    }

    private fun assembledWordsFor(key: String, best: String?): Set<String> {
        val kept = manualWordsFor(key)
        val out = HashSet<String>()
        for (word in userWordsFor(key)) {
            if (word !in kept && dict.exactWordFreq(key, word) == null) out.add(word)
        }
        if (best != null && best !in kept && dict.exactWordFreq(key, best) == null) out.add(best)
        return out
    }

    private fun demoteBelowExact(
        words: List<String>,
        assembled: Set<String>,
        exact: Set<String>,
        held: Set<String>,
    ): List<String> {
        if (assembled.isEmpty() || exact.isEmpty()) return words
        val firstExact = words.indexOfFirst { it in exact }
        if (firstExact <= 0) return words
        val ahead = words.subList(0, firstExact)
        val demoted = ahead.filter { it in assembled }
        if (demoted.isEmpty()) return words
        val cut = ahead.indexOfFirst { it in assembled }
        val kept = ahead.filterNot { it in assembled }
        val trailing = kept.subList(cut, kept.size)
        return kept.subList(0, cut) +
            trailing.filter { it in held } +
            words[firstExact] +
            demoted +
            trailing.filterNot { it in held } +
            words.subList(firstExact + 1, words.size)
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
            for (variant in fuzzyVariants(sub, fuzzyRules)) {
                for (wf in preferredExact(dict, variant, edgeN + seen.size)) {
                    if (seen.add(wf.word)) out.add(Edge(wf.word, wf.freq, fuzzyPenalty))
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

    private fun activeLambda(ctx: Ctx): Double = if (ctx.cp == BOS) lambda else 0.0

    private class CondMemo {
        private var keys = LongArray(COND_MEMO_CAPACITY)
        private var values = DoubleArray(COND_MEMO_CAPACITY)
        private var filled = BooleanArray(COND_MEMO_CAPACITY)
        private var size = 0

        fun slot(key: Long): Int {
            val mask = keys.size - 1
            val mixed = key * COND_MEMO_MIX
            var at = (mixed xor (mixed ushr 32)).toInt() and mask
            while (filled[at] && keys[at] != key) at = (at + 1) and mask
            return at
        }

        fun has(slot: Int): Boolean = filled[slot]

        fun value(slot: Int): Double = values[slot]

        fun put(slot: Int, key: Long, value: Double) {
            keys[slot] = key
            values[slot] = value
            filled[slot] = true
            if (++size * 2 <= keys.size) return
            if (keys.size < COND_MEMO_MAX_CAPACITY) {
                grow()
            } else {
                filled.fill(false)
                size = 0
            }
        }

        private fun grow() {
            val oldKeys = keys
            val oldValues = values
            val oldFilled = filled
            keys = LongArray(oldKeys.size * 2)
            values = DoubleArray(oldKeys.size * 2)
            filled = BooleanArray(oldKeys.size * 2)
            for (i in oldKeys.indices) {
                if (!oldFilled[i]) continue
                val at = slot(oldKeys[i])
                keys[at] = oldKeys[i]
                values[at] = oldValues[i]
                filled[at] = true
            }
        }
    }

    private val spareCondMemo = AtomicReference<CondMemo?>()

    private fun logCondMemo(memo: CondMemo, model: CharBigramLM, id1: Int, id2: Int): Double {
        val key = (id1.toLong() shl 32) or (id2.toLong() and 0xFFFFFFFFL)
        val slot = memo.slot(key)
        if (memo.has(slot)) return memo.value(slot)
        val v = model.logCondById(id1, id2)
        memo.put(slot, key, v)
        return v
    }

    private fun internalBigramScore(word: String, model: CharBigramLM, memo: CondMemo): Double {
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

    private fun wordModelScore(word: String, freq: Int, ctxId: Int, ctx: Ctx, condMemo: CondMemo): Double =
        wordModelScore(word, freq.toDouble(), ctxId, ctx, condMemo)

    private fun wordModelScore(word: String, freq: Double, ctxId: Int, ctx: Ctx, condMemo: CondMemo): Double =
        (ln(freq) - lnTotal) +
            (userModel?.wordBoost(word) ?: 0.0) +
            userLearningScore(ctx.tail, word) +
            (octagram?.let { octagramWeight * (it.rawScore(word) ?: 0.0) } ?: 0.0) +
            (lm?.let {
                val lam = activeLambda(ctx)
                (if (lam == 0.0) 0.0 else lam * internalBigramScore(word, it, condMemo)) +
                    if (ctxId != NO_CTX) contextWeight * logCondMemo(condMemo, it, ctxId, it.charId(word.codePointAt(0))) else 0.0
            } ?: 0.0) +
            octagramWeight * contextArm(ctx.tail, word)

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

    private val activeLearning: UserLearning? get() = userLearning?.takeIf { it.enabled }

    private fun userLearningScore(contextTail: String, word: String): Double =
        activeLearning?.let { it.formedWeight(word) + it.followBoost(contextTail, word) } ?: 0.0

    private fun joinTail(contextTail: String, word: String): String =
        if (contextTail.isEmpty()) word else contextTail + word

    private fun advanceJoinedTail(joined: String): String =
        if (octagram == null && userLearning == null) "" else hanTail(joined)

    internal fun wholeSentenceArm(contextTail: String, text: String): Double {
        if (text.isEmpty()) return 0.0
        val combined = contextTail + text
        return bestSuffixGram(combined, combined.length)
    }

    private fun isHan(cp: Int): Boolean {
        if (cp !in 0 until HAN_TABLE_SIZE) return Character.isIdeographic(cp)
        val known = HAN_TABLE[cp]
        if (known != HAN_UNKNOWN) return known == HAN_YES
        val han = Character.isIdeographic(cp)
        HAN_TABLE[cp] = if (han) HAN_YES else HAN_NO
        return han
    }

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
        withFuzzyReadings(input, decodeCoveredLayered(input, limit, cuts, context).first)

    internal fun decodeCoveredLayered(
        input: String,
        limit: Int,
        cuts: Set<Int> = emptySet(),
        context: CharSequence = "",
        guessSink: MutableList<String>? = null,
    ): Pair<List<Cand>, Int> {
        if (input.isEmpty() || limit <= 0) return emptyList<Cand>() to 0
        val norm = normalizeSeparators(input) ?: return decodeCoveredClean(input, limit, cuts, context, false, guessSink)
        if (norm.clean.isEmpty()) return emptyList<Cand>() to 0
        val passedClean = cuts.mapNotNull { norm.cleanIndexOfOrig(it) }.toSet()
        val (cands, remainderStart) =
            decodeCoveredClean(norm.clean, limit, norm.cuts + passedClean, context, norm.cuts.isNotEmpty(), guessSink)
        return cands.map {
            Cand(it.word, norm.origLen.getOrElse(it.coveredLen) { input.length }, it.correctedReading)
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
        val decoded = withFuzzyReadings(clean, decodeAtomic(clean, interior, ctx, interior.isNotEmpty()))
        return if (norm == null) {
            decoded
        } else {
            decoded.map {
                Cand(it.word, norm.origLen.getOrElse(it.coveredLen) { input.length }, it.correctedReading)
            }
        }
    }

    private fun withFuzzyReadings(input: String, candidates: List<Cand>): List<Cand> {
        val rules = fuzzyRules
        if (rules.isEmpty()) return candidates
        val exact = HashMap<String, Set<String>>()
        val targets = HashMap<String, Map<String, String>>()
        val readings = ReadingLookup(aliasDict ?: dict)
        return candidates.map { candidate ->
            if (candidate.correctedReading != null) return@map candidate
            val source = input.take(candidate.coveredLen).replace("'", "")
            if (candidate.word in exact.getOrPut(source) { dict.exact(source).mapTo(HashSet()) { it.word } }) return@map candidate
            val target = targets.getOrPut(source) {
                buildMap {
                    for (variant in fuzzyVariants(source, rules)) {
                        for (wf in dict.exact(variant)) putIfAbsent(wf.word, variant)
                    }
                }
            }[candidate.word] ?: return@map candidate
            val reading = guessReading(candidate.word, target, false, readings)
            candidate.copy(correctedReading = reading)
        }
    }

    private fun decodeCoveredClean(
        input: String,
        limit: Int,
        cuts: Set<Int>,
        context: CharSequence,
        staged: Boolean,
        guessSink: MutableList<String>? = null,
    ): Pair<List<Cand>, Int> {
        val ctx = parseContext(context)
        val interior = cuts.filter { it in 1 until input.length }.toSortedSet()
        if (interior.isNotEmpty()) return decodeAtomic(input, interior, ctx, staged).let { it to it.size }

        val ctxId = resolveCtxId(ctx.cp)
        val condMemo = CondMemo()
        val cover = LinkedHashMap<String, Int>()
        val completionCap = completionCap(limit)
        val sentence = bestSentence(input, ctx)?.also { cover[it] = input.length }
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
        val exactWords = HashSet<String>()
        for (wf in dict.exact(input)) {
            if (!isSingleChar(wf.word)) exactWords.add(wf.word)
            offer(wf, 0.0)
        }
        inputAliasWordFreqs(input).forEach { offer(it, ALIAS_PENALTY) }
        dict.prefixByFreq(input, completionCap).forEach { offer(it, 0.0) }
        for (uw in userWordsFor(input)) offer(BinaryDict.WordFreq(uw, userWordFreq(uw, input).toInt().coerceAtLeast(1)), 0.0)
        val fuzzyExactWords = HashSet<String>()
        if (fuzzyRules.isNotEmpty()) {
            for (variant in fuzzyVariants(input, fuzzyRules)) {
                for (wf in dict.exact(variant)) {
                    fuzzyExactWords.add(wf.word)
                    offer(wf, fuzzyPenalty)
                }
                dict.prefixByFreq(variant, completionCap).forEach { offer(it, fuzzyPenalty) }
            }
        }
        val reservedInitials = HashSet<String>()
        val initialsOnly = HashSet<String>()
        initialsDict?.let { id ->
            if (input.length >= INITIALS_RESERVE_MIN_LEN) {
                for (wf in preferredExact(id, input, INITIALS_RESERVE)) {
                    if (wf.freq <= ORDERING_RARE_FREQ) continue
                    if (offer(wf, INITIALS_PENALTY)) {
                        reservedInitials.add(wf.word)
                        initialsOnly.add(wf.word)
                    }
                }
            }
            id.prefixByFreq(input, completionCap).forEach { if (offer(it, INITIALS_PENALTY)) initialsOnly.add(it.word) }
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
        for ((wf, _) in pool) {
            val reserved = wf.word in reservedInitials
            if (!reserved && cover.size >= completionCap - pendingInitials && wf.word !in exactWords && wf.word !in fuzzyExactWords) continue
            if (cover.putIfAbsent(wf.word, input.length) == null && reserved) pendingInitials--
        }
        val guesses = guessCorrections(input, ctx, GUESS_LIMIT)
        guessSink?.addAll(guesses.map { it.word })
        val out = ArrayList<Cand>(cover.size + guesses.size + 20)
        val assembled = assembledWordsFor(input, cover.keys.firstOrNull())
        val primaryGuess = guesses.firstOrNull { it.source == GuessSource.EXACT }
        val primaryWord = primaryGuess?.word
        val correctionByWord = guesses.associateBy { it.word }
        val ordered = demoteBelowExact(cover.keys.toList(), assembled, exactWords, manualWordsFor(input))
            .filterNot { it == primaryWord }
        val remainingGuesses = guesses.filter { it.word != primaryWord && it.word !in cover }
        val guessAt = when {
            remainingGuesses.isEmpty() -> -1
            else -> ordered.indexOfFirst { it in initialsOnly && it != sentence }.let { if (it < 0) ordered.size else it }
        }
        primaryGuess?.let { out.add(Cand(it.word, input.length, it.correctedReading)) }
        for ((i, w) in ordered.withIndex()) {
            if (i == guessAt) for (g in remainingGuesses) {
                out.add(Cand(g.word, input.length, g.correctedReading))
            }
            out.add(Cand(w, cover.getValue(w), correctionByWord[w]?.correctedReading))
        }
        if (guessAt == ordered.size) for (g in remainingGuesses) {
            out.add(Cand(g.word, input.length, g.correctedReading))
        }
        if (userModel != null) {
            val present = out.mapTo(HashSet()) { it.word }
            for (uw in userWordsFor(input).sortedByDescending { userModel.wordBoost(it) }) {
                if (present.add(uw)) out.add(Cand(uw, input.length))
            }
        }
        val covered = out.mapTo(HashSet<String>(out.size * 2)) { it.word }
        appendLeadingSingles(input, input.length, out, ctx, condMemo)
        closeWithRareSingles(input, out)
        var remainderStart = 0
        while (remainderStart < out.size && out[remainderStart].word in covered) remainderStart++
        return out to remainderStart
    }

    private fun closeWithRareSingles(input: String, out: MutableList<Cand>) {
        val heads = HashMap<String, Map<String, Double>>()
        val rare = ArrayList<Cand>()
        var write = 0
        for (c in out) {
            val key = input.substring(0, c.coveredLen.coerceIn(1, input.length))
            val closing = isSingleChar(c.word) &&
                rareSingle(c.word, heads.getOrPut(key) { homophoneFreqs(key).toMap() })
            if (closing) rare.add(c) else out[write++] = c
        }
        for (c in rare) out[write++] = c
    }

    private fun decodeAtomic(input: String, interior: Set<Int>, ctx: Ctx, staged: Boolean): List<Cand> {
        val ctxId = resolveCtxId(ctx.cp)
        val condMemo = CondMemo()
        val B = atomicBounds(input, interior)
        val nSyl = B.size - 1

        val singlesCache = HashMap<String, Set<String>>()
        val sentences = atomicSentences(input, B, interior, ctx, singlesCache)

        val best = sentences.firstOrNull()?.text

        val leadFreq = LinkedHashMap<String, Int>()
        val leadCov = HashMap<String, Int>()
        for (j in 2..nSyl) for (wf in preferredExact(dict, input.substring(0, B[j]))) if (!isSingleChar(wf.word)) {
            if (!admissibleUnderCuts(wf.word, 0, B[j], interior, input, singlesCache)) continue
            if (leadFreq.put(wf.word, wf.freq) == null) leadCov[wf.word] = B[j]
        }
        for (uw in userWordsFor(input)) {
            if (uw in leadFreq || uw.codePointCount(0, uw.length) < 2) continue
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
            val score = wordModelScore(word, frequency, ctxId, ctx, condMemo)
            val prev = tailScore[word]
            if (prev == null || score > prev) {
                tailScore[word] = score
                tailFreq[word] = frequency
                tailCand[word] = Cand(word, coveredLen)
            }
        }
        for (sentence in sentences) offerTail(sentence.text, input.length, nSyl, 1.0)
        for ((w, _) in homophoneFreqs(input.substring(0, B[1]))) offerTail(w, B[1], 1, 0.0)
        val tailRanked = tailCand.values.sortedWith(
            compareBy<Cand> { isSingleChar(it.word) }
                .thenByDescending { tailScore[it.word] ?: Double.NEGATIVE_INFINITY }
                .thenBy { it.word.codePointCount(0, it.word.length) }
                .thenBy { supplementarySingleTieRank(it.word) },
        )

        val assembled = assembledWordsFor(input, best)
        val fullCoverExact = leadFreq.keys.filterTo(HashSet()) {
            leadCov.getValue(it) == input.length && dict.exactWordFreq(input, it) != null
        }
        val out = ArrayList<Cand>(1 + leadFreq.size + tailRanked.size)
        val seen = HashSet<String>()
        fun emit(words: List<String>) {
            for (w in demoteBelowExact(words, assembled, fullCoverExact, manualWordsFor(input))) {
                if (seen.add(w)) out.add(Cand(w, leadCov[w] ?: input.length))
            }
        }
        if (staged) {
            val leadScore = HashMap<String, Double>(leadFreq.size * 2)
            for ((w, f) in leadFreq) leadScore[w] = wordModelScore(w, f, ctxId, ctx, condMemo)
            val stagedRealWords = leadFreq.keys.sortedWith(
                compareByDescending<String> { leadCov.getValue(it) }
                    .thenByDescending { leadScore.getValue(it) }
                    .thenBy { supplementarySingleTieRank(it) },
            )
            val head = ArrayList<String>(STAGED_REAL_WORD_SLOTS)
            best?.let { head.add(it) }
            for (w in stagedRealWords) {
                if (head.size >= STAGED_REAL_WORD_SLOTS) break
                if (w !in head) head.add(w)
            }
            emit(head)
        }
        val rest = ArrayList<String>(leadFreq.size + 1)
        best?.let { rest.add(it) }
        val leadRank = HashMap<String, Double>(leadFreq.size * 2)
        for ((w, f) in leadFreq) leadRank[w] = wordModelScore(w, f, ctxId, ctx, condMemo)
        for (w in leadFreq.keys.sortedByDescending { leadRank.getValue(it) }) {
            if (w !in rest) rest.add(w)
        }
        if (!staged) emit(rest)
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
            val raw = tailScore[c.word] ?: wordModelScore(c.word, candFrequency(c), ctxId, ctx, condMemo)
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

    private class APath(val text: String, val lastCp: Int, val tail: String, val score: Double) {
        private var lastId = UNRESOLVED_CHAR_ID

        fun charId(model: CharBigramLM): Int {
            if (lastId == UNRESOLVED_CHAR_ID) lastId = model.charId(lastCp)
            return lastId
        }
    }

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
        val condMemo = CondMemo()
        val lam = activeLambda(ctx)
        val nSyl = B.size - 1
        val learn = activeLearning
        val dp = Array(B.size) { ArrayList<APath>() }
        dp[0].add(APath("", ctx.cp, if (octagram == null && userLearning == null) "" else ctx.tail, 0.0))
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
                    val boost = (userModel?.wordBoost(w) ?: 0.0) +
                        (learn?.formedWeight(w) ?: 0.0)
                    val inner = if (model == null || lam == 0.0) 0.0 else lam * internalBigramScore(w, model, condMemo)
                    for (p in src) {
                        val bw = if (p.text.isEmpty() && p.lastCp != BOS) contextWeight else lam
                        val bi = if (model == null || p.lastCp == BOS || bw == 0.0) 0.0 else bw * logCondMemo(condMemo, model, p.charId(model), idFirst)
                        val joined = joinTail(p.tail, w)
                        val og = octagramWeight * joinedArm(p.tail, joined)
                        val follow = learn?.followBoost(p.tail, w) ?: 0.0
                        dp[j].add(
                            APath(
                                p.text + w,
                                lastCp,
                                advanceJoinedTail(joined),
                                p.score + uni + bi + inner + boost + follow + og,
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
        condMemo: CondMemo,
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
            entries.add(Entry(word, cov, wordModelScore(word, frequency, ctxId, ctx, condMemo), frequency))
        }
        for (q in span downTo 1) {
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
                    entries[at] = Entry(w, k, wordModelScore(w, f, ctxId, ctx, condMemo), f)
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
            if ((userModel?.wordBoost(word(entry)) ?: 0.0) > 0.0) return 0
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
        homophoneFreqs(key)
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
        if ((userModel?.wordBoost(word) ?: 0.0) > 0.0) return LAYER_COMMON
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
        private var lastId = UNRESOLVED_CHAR_ID

        fun charId(model: CharBigramLM): Int {
            if (lastId == UNRESOLVED_CHAR_ID) lastId = model.charId(lastCp)
            return lastId
        }

        override fun hashCode(): Int = if (tail.isEmpty()) lastCp else 31 * lastCp + tail.hashCode()
    }

    private class Cell(val score: Double, val prevPos: Int, val prevState: SentenceState?, val word: String)

    private fun bestSentence(input: String, ctx: Ctx): String? {
        val condMemo = spareCondMemo.getAndSet(null) ?: CondMemo()
        val text = bestSentencePath(input, ctx, condMemo = condMemo)?.text
        spareCondMemo.set(condMemo)
        return text
    }

    private fun bestSentencePath(
        input: String,
        ctx: Ctx,
        edgeMemo: MutableMap<String, List<Edge>>? = null,
        condMemo: CondMemo = CondMemo(),
        pruned: Boolean = false,
    ): SentencePath? {
        val model = lm
        val learn = activeLearning
        val lam = activeLambda(ctx)
        val n = input.length
        val dp = Array<MutableMap<SentenceState, Cell>>(n + 1) {
            if (octagram == null && userLearning == null) HashMap() else LinkedHashMap(SENTENCE_STATE_CAPACITY)
        }
        val initial = SentenceState(ctx.cp, if (octagram == null && userLearning == null) "" else ctx.tail)
        dp[0][initial] = Cell(0.0, -1, null, "")

        for (q in 1..n) {
            for (p in 0 until q) {
                val from = dp[p]
                if (from.isEmpty()) continue
                val sub = input.substring(p, q)
                val edges = if (edgeMemo == null) edgesFor(sub) else edgeMemo.getOrPut(sub) { edgesFor(sub) }
                if (edges.isEmpty()) continue
                for (e in edges) {
                    val w = e.word
                    val uni = ln(e.freq.toDouble()) - lnTotal
                    val boost = (userModel?.wordBoost(w) ?: 0.0) +
                        (learn?.formedWeight(w) ?: 0.0)
                    val firstCp = w.codePointAt(0)
                    val idFirst = model?.charId(firstCp) ?: -1
                    val lastCp = w.codePointBefore(w.length)
                    val inner = if (model == null || lam == 0.0) 0.0 else lam * internalBigramScore(w, model, condMemo)
                    for ((state, cell) in from) {
                        val bw = if (cell.prevPos < 0 && state.lastCp != BOS) contextWeight else lam
                        val bi = if (model == null || state.lastCp == BOS || bw == 0.0) 0.0
                        else bw * logCondMemo(condMemo, model, state.charId(model), idFirst)
                        val joined = joinTail(state.tail, w)
                        val og = octagramWeight * joinedArm(state.tail, joined)
                        val follow = learn?.followBoost(state.tail, w) ?: 0.0
                        val score = cell.score + uni + bi + inner + boost + follow - e.penalty + og
                        val nextState = SentenceState(lastCp, advanceJoinedTail(joined))
                        val cur = dp[q][nextState]
                        if (cur == null || score > cur.score) {
                            dp[q][nextState] = Cell(score, p, state, w)
                        }
                    }
                }
            }
            if ((pruned || octagram != null || userLearning != null) && dp[q].size > BEAM_W) {
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

    private class GuessVariant(val input: String, val letters: String, val prefix: String?, val sentence: String?, val score: Double)

    private enum class GuessSource { EXACT, SENTENCE, PREFIX }

    private data class GuessCandidate(
        val word: String,
        val correctedReading: String,
        val source: GuessSource,
        val score: Double,
    )

    private fun scoreGuessVariants(
        variants: List<PinyinCorrection.Variant>,
        ctx: Ctx,
        includePartial: Boolean,
        toLetters: (String) -> String,
    ): List<GuessVariant> {
        val scored = ArrayList<GuessVariant>()
        val edgeMemo = HashMap<String, List<Edge>>()
        val condMemo = CondMemo()
        for (v in variants) {
            val split = v.partial
            if (split == null) {
                val letters = toLetters(v.input)
                val path = bestSentencePath(letters, ctx, edgeMemo, condMemo, pruned = true) ?: continue
                val syllables = path.text.codePointCount(0, path.text.length)
                val score = path.score + GUESS_SYLLABLE_BONUS * syllables - v.edit.penalty
                scored.add(GuessVariant(v.input, letters, null, path.text, score))
                continue
            }
            if (!includePartial) continue
            val letters = toLetters(v.input)
            val completion = dict.prefixByFreq(letters, 1).firstOrNull() ?: continue
            val syllables = PinyinCorrection.syllableCount(split.prefix) ?: 0
            val score = ln(completion.freq.toDouble()) - lnTotal + GUESS_SYLLABLE_BONUS * syllables - v.edit.penalty
            scored.add(GuessVariant(v.input, letters, split.prefix, null, score))
        }
        scored.sortByDescending { it.score }
        return if (scored.size <= GUESS_VARIANTS) scored else scored.subList(0, GUESS_VARIANTS)
    }

    private class ReadingLookup(private val source: BinaryDict) {
        private data class Query(val remaining: String, val t9: Boolean, val prefix: Boolean)

        private val matches = HashMap<Query, List<Pair<String, String>>>()
        private val frequencies = HashMap<String, Map<String, Int>>()

        fun matching(remaining: String, t9: Boolean, prefix: Boolean): List<Pair<String, String>> =
            matches.getOrPut(Query(remaining, t9, prefix)) {
                READING_KEYS.mapNotNull { (reading, digits) ->
                    val key = if (t9) digits else reading
                    if (remaining.startsWith(key) || (prefix && key.startsWith(remaining))) reading to key else null
                }
            }

        fun frequency(reading: String, word: String): Int? =
            frequencies.getOrPut(reading) {
                buildMap {
                    for (wf in source.exact(reading)) {
                        if (wf.word.codePointCount(0, wf.word.length) == 1) putIfAbsent(wf.word, wf.freq)
                    }
                }
            }[word]
    }

    private fun guessReading(word: String, input: String, prefix: Boolean, cache: ReadingLookup): String {
        val ref = aliasDict ?: dict
        val t9 = input.all { it in '2'..'9' }
        if (!t9 && ref.containsExactWord(input, word)) return input
        val chars = word.codePoints().toArray().map { String(Character.toChars(it)) }
        data class Path(val reading: String, val covered: Int, val score: Double)
        var paths = listOf(Path("", 0, 0.0))
        for (char in chars) {
            val next = ArrayList<Path>()
            for (path in paths) {
                val remaining = input.substring(path.covered)
                for ((reading, key) in cache.matching(remaining, t9, prefix)) {
                    val frequency = cache.frequency(reading, char) ?: continue
                    next.add(Path(path.reading + reading, (path.covered + key.length).coerceAtMost(input.length),
                        path.score + ln(frequency.toDouble().coerceAtLeast(1.0))))
                }
            }
            paths = next.groupBy { it.covered }.values.flatMap { group -> group.sortedByDescending { it.score }.take(8) }
            if (paths.isEmpty()) return ""
        }
        return paths.filter { it.covered == input.length }
            .sortedWith(compareByDescending<Path> { ref.containsExactWord(it.reading, word) }.thenByDescending { it.score })
            .firstOrNull()?.reading.orEmpty()
    }

    private fun guessCorrections(input: String, ctx: Ctx, limit: Int): List<GuessCandidate> {
        if (limit <= 0 || input.length !in PinyinCorrection.MIN_LEN..PinyinCorrection.MAX_LEN) return emptyList()
        if (!PinyinCorrection.classifies(input) || PinyinCorrection.acceptable(input)) return emptyList()
        val out = LinkedHashMap<String, GuessCandidate>()
        val readingCache = ReadingLookup(aliasDict ?: dict)
        fun add(word: String, variant: GuessVariant, source: GuessSource): Boolean {
            if (word.isNotEmpty() && word !in out) {
                val reading = guessReading(word, variant.input, source == GuessSource.PREFIX, readingCache)
                out[word] = GuessCandidate(word, reading, source, variant.score)
            }
            return out.size >= limit
        }
        val variants = scoreGuessVariants(PinyinCorrection.variants(input), ctx, includePartial = true) { it }
        for (gv in variants) {
            if (gv.prefix != null) continue
            for (wf in preferredExact(dict, gv.input, GUESS_EXACT_WORDS)) {
                if (add(wf.word, gv, GuessSource.EXACT)) return out.values.toList()
            }
        }
        for (gv in variants) {
            gv.sentence?.let { if (add(it, gv, GuessSource.SENTENCE)) return out.values.toList() }
            val count = if (gv.prefix == null) GUESS_PREFIX_WORDS else GUESS_PARTIAL_WORDS
            for (wf in dict.prefixByFreq(gv.input, count)) {
                if (add(wf.word, gv, GuessSource.PREFIX)) return out.values.toList()
            }
        }
        return out.values.toList()
    }

    internal fun guessWords(input: String, limit: Int, context: CharSequence = ""): List<String> =
        ArrayList<String>().also { decodeCoveredLayered(input, limit, emptySet(), context, it) }

    internal fun guessLockedWords(
        lockedLetters: String,
        active: String,
        t9Active: Boolean,
        cuts: Set<Int>,
        context: CharSequence,
        limit: Int,
    ): List<Cand> {
        if (limit <= 0 || active.isEmpty()) return emptyList()
        if (!PinyinCorrection.classifies(active) || PinyinCorrection.acceptable(active)) return emptyList()
        val ctx = parseContext(context)
        val norm = normalizeSeparators(lockedLetters)
        val locked = norm?.clean ?: lockedLetters
        val passedClean = if (norm == null) cuts else cuts.mapNotNull { norm.cleanIndexOfOrig(it) }.toSet()
        val lockedCuts = (norm?.cuts ?: emptySet()) + passedClean
        val toLetters: (String) -> String =
            if (t9Active) { v -> T9Pinyin.preedit(v).replace(SEP.toString(), "") } else { v -> v }
        val out = LinkedHashMap<String, String>()
        for (gv in scoreGuessVariants(PinyinCorrection.variants(active), ctx, includePartial = false, toLetters)) {
            val full = locked + gv.letters
            if (full.isEmpty() || gv.letters.any { it !in 'a'..'z' }) continue
            val interior = lockedCuts.filter { it in 1 until full.length }.toSet()
            for (c in decodeAtomic(full, interior, ctx, interior.isNotEmpty())) {
                if (c.coveredLen != full.length) continue
                out.putIfAbsent(c.word, full)
                if (out.size >= limit) return out.map { (word, reading) -> Cand(word, active.length, reading) }
            }
        }
        return out.map { (word, reading) -> Cand(word, active.length, reading) }
    }

    internal companion object {
        private val READING_KEYS = T9Pinyin.SYLLABLES.map { it to T9Pinyin.toT9(it) }
        const val SEP = '\''
        const val BOS = -1
        const val NO_CTX = Int.MIN_VALUE
        const val EDGE_N = 20
        const val DEFAULT_LAMBDA = 0.5
        const val FUZZY_PENALTY = 6.0
        const val ALIAS_PENALTY = 3.5
        const val INITIALS_PENALTY = 5.0
        const val INITIALS_RESERVE = 1
        const val INITIALS_RESERVE_MIN_LEN = 2
        const val GUESS_LIMIT = 3
        const val GUESS_VARIANTS = 3
        const val GUESS_EXACT_WORDS = 3
        const val GUESS_PREFIX_WORDS = 2
        const val GUESS_PARTIAL_WORDS = 3
        const val GUESS_SYLLABLE_BONUS = 5.0
        const val DEFAULT_OCTAGRAM_WEIGHT = 0.1
        const val BEAM_W = 12
        const val SENTENCE_EDGE_N = 6
        const val ATOMIC_BEAM_N = 8
        const val ATOMIC_BEAM_PER_SYL = 40
        const val STAGED_REAL_WORD_SLOTS = 8
        const val SENTENCE_RERANK_N = 128
        const val CTX_WORD_MAX = 4
        const val MAX_SYLLABLE_KEY_LEN = 6
        const val EXACT_TIE_LOOKAHEAD = 16
        const val COND_MEMO_CAPACITY = 256
        const val COND_MEMO_MAX_CAPACITY = 1 shl 13
        const val SENTENCE_STATE_CAPACITY = 256
        const val COND_MEMO_MIX = -7046029254386353131L
        const val UNRESOLVED_CHAR_ID = Int.MIN_VALUE
        const val HAN_TABLE_SIZE = 0x10000
        const val HAN_UNKNOWN: Byte = 0
        const val HAN_YES: Byte = 1
        const val HAN_NO: Byte = 2
        private val HAN_TABLE = ByteArray(HAN_TABLE_SIZE)
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
        val ALIAS_FREQ_DISCOUNT = exp(-ALIAS_PENALTY)
        const val DEFAULT_CONTEXT_WEIGHT = 1.0
        fun completionCap(limit: Int): Int = maxOf(1, (limit.toLong() * 2 / 3).toInt())
        val INPUT_ALIASES = mapOf("en" to listOf("ng"))
        val T9_INPUT_ALIASES: Map<String, List<String>> =
            INPUT_ALIASES.entries.associate { (k, v) -> T9Pinyin.toT9(k) to v }
    }
}
