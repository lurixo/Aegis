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

/** A candidate word plus how many leading input units (digits/letters) it consumes — for ★E
 *  per-syllable partial commit and the ★G mixed long-word/short-word/single-char grid. */
data class Cand(val word: String, val coveredLen: Int)

/** A segmented syllable of the input: its [reading] (pinyin letters, for display) and the half-open
 *  input-unit span [[start], [end]) it consumes — letters on the 26-key decoder, digits on the T9
 *  decoder. Exposed (with [PinyinDecoder.homophonesAt]) so the UI can navigate syllable positions and
  * Chinese IME behavior note.
  */
data class Syllable(val reading: String, val start: Int, val end: Int)

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
    private var fuzzyRules: Set<String> = emptySet(),
    private val initialsDict: BinaryDict? = null,
    private val octagram: com.aegis.ime.dict.OctagramReader? = null,
    private val octagramWeight: Double = DEFAULT_OCTAGRAM_WEIGHT,
    private val contextWeight: Double = DEFAULT_CONTEXT_WEIGHT,
    private val aliasDict: BinaryDict? = null,
) {
    private val lnTotal = ln(dict.totalFreq.coerceAtLeast(1).toDouble())
    private var edgeN = if (lm != null || fuzzyRules.isNotEmpty() || initialsDict != null) EDGE_N else 1

    // --- self-created (user) word recall -------------------------------------------------------------
    // A snapshot of the user model's reading -> words map, indexed in BOTH the letter and the T9-digit
    // keyspaces so either decoder matches its own input key. Rebuilt only when [UserModel.version] moves,
    // so free typing pays a single hash lookup per key and nothing at all on an un-adapted install.
    private var userIndexVersion = Long.MIN_VALUE
    private var userLetterIndex: Map<String, List<String>> = emptyMap()
    private var userDigitIndex: Map<String, List<String>> = emptyMap()

    /**
     * E4 hot-toggle (debug.16): swap the active fuzzy rule set without rebuilding the decoder — fuzzy is pure
     * query-time variant expansion ([edgesFor] → [Fuzzy.variants]), not a prebuilt index. [edgeN] is widened
     * iff there is now a reason to keep more than one edge per cell, so a non-empty rule set's fuzzy variants
      * Chinese IME behavior note.
     */
    fun setFuzzyRules(rules: Set<String>) {
        fuzzyRules = rules
        edgeN = if (lm != null || rules.isNotEmpty() || initialsDict != null) EDGE_N else 1
    }

    private class Edge(val word: String, val freq: Int, val penalty: Double)

    /** INPUT_ALIASES keyed by the decoder's own key space: the T9 decoder sees digit substrings, so the
     *  alias source reading is matched by its digit group (en -> "36"); targets stay LETTER keys. */
    private fun inputAliases(key: String): List<String> =
        if (key.isNotEmpty() && key[0] in '2'..'9') T9_INPUT_ALIASES[key].orEmpty()
        else INPUT_ALIASES[key].orEmpty()

    /** Where alias target keys are looked up: the T9 decoder's own dict is digit-keyed, so it resolves the
     *  letter-keyed alias targets against [aliasDict] (the 26-key dict) — dict.exact("ng") there is exactly
     *  the ng-reading words, never the whole digit-64 group (米…). The letter decoder resolves in itself. */
    private val aliasSource: BinaryDict get() = aliasDict ?: dict

    private fun addExactEdges(
        source: BinaryDict,
        key: String,
        penalty: Double,
        out: MutableList<Edge>,
        seen: MutableSet<String>,
    ): Boolean {
        for (wf in preferredExact(source, key)) {
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

    /** Whether the main dictionary already carries [word] under exact key [reading] — lets the engine skip
     *  storing a self-created word the dictionary can already recall. Readings are stored as canonical
     *  letters, so only this (letter) decoder's dict is meaningful; the T9 decoder never records words. */
    fun hasDictWord(reading: String, word: String): Boolean =
        reading.isNotEmpty() && dict.exact(reading).any { it.word == word }

    /** Rebuild the per-keyspace recall index iff the user model changed since it was last read. */
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

    /** Self-created words whose stored reading is exactly the whole input key [key] (letters or T9 digits,
     *  chosen by the first character, mirroring [inputAliases]), most-used first; empty when there is no user
     *  model or no match — so every caller is a no-op on an un-adapted install and behaves exactly as before. */
    private fun userWordsFor(key: String): List<String> {
        if (userModel == null || key.isEmpty()) return emptyList()
        refreshUserIndex()
        return (if (key[0] in '2'..'9') userDigitIndex[key] else userLetterIndex[key]) ?: emptyList()
    }

    /** Synthetic ranking frequency for a self-created word under reading key [readingKey]: the frequency of
     *  its RAREST covered reading-character, floored at 1 (the same length-neutral commonness metric the locked
     *  decode ranks by, so a word of common characters ranks among the common words and a rare-charactered one
     *  sinks — never ordered as a rare candidate ahead of a common one, nor as a common one behind a rarer
     *  word). The character↔syllable alignment is recovered by a maximin DP over EVERY syllabification of the
     *  reading (each codepoint a single of its span), NOT a single greedy split — so a concatenation-ambiguity
     *  reading (xi+an → "xian", which greedily segments into one syllable) still credits each character its own
     *  reading-frequency instead of mis-crediting a merged key's common single. No recoverable alignment (a
     *  heteronym reading with no standalone single) falls back to the floor. */
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
        // dp[p][i] = best achievable (max over parses of the min covered single-freq) parsing readingKey[0,p)
        // into the first i codepoints; NEGATIVE_INFINITY = unreachable.
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

    /** Lattice edges for a substring, by descending preference: user words, exact, aliases, fuzzy, jianpin. */
    private fun edgesFor(sub: String): List<Edge> {
        val out = ArrayList<Edge>(edgeN)
        val seen = HashSet<String>()
        // Self-created words are floor-guaranteed edges (like alias words): a word the user built for exactly
        // this reading must reach the sentence lattice even when the exact dict layer would fill every slot by
        // itself. Its edge frequency is its characters' shared commonness; the path score adds the usage boost
        // in [bestSentence], so a repeatedly used self-created word can win the sentence.
        //
        // WORD-NORMALISE the whole-input edge: a single edge spanning N syllables otherwise pays only ONE
        // lnTotal normalisation (in [bestSentence]'s ln(freq) - lnTotal term) while the natural N-word sentence
        // pays N — one per word edge — so even a fresh (count == 1, boost-negligible) common-character user word
        // would STRUCTURALLY out-score the best sentence and seize the commit default (#0). Charging (N-1) extra
        // lnTotal makes the edge cost ln(freq) - N·lnTotal, competing length-fairly with an N-word sentence; a
        // user word then reaches #0 only once accumulated usage boost outweighs the sentence, which is the
        // intended "used a lot ⇒ rises" behaviour rather than a one-off assembly hijacking the default.
        for (uw in userWordsFor(sub)) {
            if (seen.add(uw)) {
                val n = uw.codePointCount(0, uw.length)
                out.add(Edge(uw, userWordFreq(uw, sub).toInt().coerceAtLeast(1), (n - 1).coerceAtLeast(0) * lnTotal))
            }
        }
        val exactFull = addExactEdges(dict, sub, 0.0, out, seen)
        // Alias words are floor-guaranteed edges: an exact layer that fills all [edgeN] slots by itself
        // (the full dict's exact("en") can) must not starve them out of the lattice — they are the only
        // way 嗯 reaches an "en" edge at all. Each alias key contributes at most edgeN words and only
        // en->ng exists, so an edge list stays O(edgeN); the path score carries ALIAS_PENALTY as before.
        for (alias in inputAliases(sub)) {
            var added = 0
            for (wf in preferredExact(aliasSource, alias)) {
                if (seen.add(wf.word)) { out.add(Edge(wf.word, wf.freq, ALIAS_PENALTY)); if (++added >= edgeN) break }
            }
        }
        if (exactFull || out.size >= edgeN) return out
        if (fuzzyRules.isNotEmpty()) {
            // Per-rule fuzzy: enumerate the confusion class of `sub` and match each against the exact
            // dict (no monolithic fuzzy index, so individual rules can be turned off — E4).
            for (variant in Fuzzy.variants(sub, fuzzyRules)) {
                if (variant == sub) continue
                for (wf in preferredExact(dict, variant)) {
                    if (seen.add(wf.word)) out.add(Edge(wf.word, wf.freq, FUZZY_PENALTY))
                    if (out.size >= edgeN) return out
                }
            }
        }
        initialsDict?.let { id ->
            for (wf in preferredExact(id, sub)) {
                if (seen.add(wf.word)) out.add(Edge(wf.word, wf.freq, INITIALS_PENALTY))
                if (out.size >= edgeN) break
            }
        }
        return out
    }

    /**
     * Model score for a whole-input candidate word, used to rerank the candidate **list** (★top-N):
     * global unigram + learned user boost + optional octagram word weight, plus — when the preceding
     * committed text is supplied — the cross-boundary char-bigram P(word₀ | ctxLastChar) and octagram
     * collocation(ctxWord + word). The context terms vanish when there is no preceding Han context, so
     * a fresh buffer behaves exactly as before. This is the ③ context-aware same-code disambiguation.
     */
    private fun wordModelScore(word: String, freq: Int, ctxCp: Int, ctxWord: String): Double =
        wordModelScore(word, freq.toDouble(), ctxCp, ctxWord)

    /** [wordModelScore] over a (possibly penalty-discounted) frequency — the log-domain unigram term
     *  ln(freq)−lnTotal already folds a source penalty in when the caller passes f·e^−penalty (e.g. the
     *  alias-discounted single frequencies from [homophoneFreqs]). Same boosts/context terms as the
     *  Int overload; single-syllable candidates score on the same scale as the whole-buffer sentences. */
    private fun wordModelScore(word: String, freq: Double, ctxCp: Int, ctxWord: String): Double =
        (ln(freq) - lnTotal) +
            (userModel?.wordBoost(word) ?: 0.0) +
            (octagram?.let { octagramWeight * (it.rawScore(word) ?: 0.0) } ?: 0.0) +
            (if (lm != null && ctxCp != BOS) contextWeight * lm.logCond(ctxCp, word.codePointAt(0)) else 0.0) +
            (if (octagram != null && ctxWord.isNotEmpty()) octagramWeight * (octagram.rawScore(ctxWord + word) ?: 0.0) else 0.0)

    /**
     * Whole-input dict words (exact key = input) plus the input's alias-key words in ONE
     * [wordModelScore] order — model, not raw freq. Alias entries score with ALIAS_PENALTY, which in
     * the log domain is a ÷e^3.5 frequency discount: 嗯@434,765 lands under en's common natives
     * (恩摁) but above the rare tail — and a long exact list can no longer starve alias words out of
     * [decode]'s limit or [decodeCoveredClean]'s completion budget (they used to be appended AFTER
     * every exact word). A no-alias input takes the exact-only path, same elements, same comparator.
     */
    private fun rerankedWholeInputAndAliases(input: String, ctxCp: Int, ctxWord: String): List<String> {
        val own = dict.exact(input)
        val alias = inputAliasWordFreqs(input)
        val scored = ArrayList<Pair<BinaryDict.WordFreq, Double>>(own.size + alias.size)
        for (wf in own) scored.add(wf to wordModelScore(wf.word, wf.freq, ctxCp, ctxWord))
        if (alias.isNotEmpty()) {
            val ownWords = own.mapTo(HashSet()) { it.word }
            for (wf in alias) {
                if (wf.word !in ownWords) scored.add(wf to (wordModelScore(wf.word, wf.freq, ctxCp, ctxWord) - ALIAS_PENALTY))
            }
        }
        return scored
            .sortedWith(
                compareByDescending<Pair<BinaryDict.WordFreq, Double>> { it.second }
                    .thenBy { supplementarySingleTieRank(it.first.word) },
            )
            .map { it.first.word }
    }

    /**
     * Parse the editor text before the cursor into (last Han code point, trailing Han run) for
     * conditioning the first decoded word. A non-Han char immediately before the cursor (space,
     * punctuation, latin, digit) breaks the context → (BOS, "") = no conditioning. The Han run is
     * capped at [CTX_WORD_MAX] chars as the octagram previous-word proxy.
     */
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

    /**
     * debug.18 (FIX-1): a "single character" by CODE POINTS, not UTF-16 units. A CJK extension char
     * (U+20000+) is a surrogate pair so its [String.length] is 2 — the old `length == 1` test misclassified
     * it as a multi-char WORD, flooding the front of the grid (PREFIX_PER_LEN) AND dropping it from the
     * single-char homophone layer. Shared by 26-key and 9-key (both decode through here).
     */
    private fun isSingleChar(w: String): Boolean = w.codePointCount(0, w.length) == 1

    private fun supplementarySingleTieRank(word: String): Int =
        if (isSingleChar(word) && Character.isSupplementaryCodePoint(word.codePointAt(0))) 1 else 0

    private fun preferredWordFreqs(words: List<BinaryDict.WordFreq>): List<BinaryDict.WordFreq> =
        words.sortedWith(compareByDescending<BinaryDict.WordFreq> { it.freq }.thenBy { supplementarySingleTieRank(it.word) })

    private fun preferredExact(source: BinaryDict, key: String): List<BinaryDict.WordFreq> =
        preferredWordFreqs(source.exact(key))

    private fun prefixWords(source: BinaryDict, input: String, limit: Int): List<String> =
        source.prefixByFreq(input, limit).map { it.word }

    /**
      * Chinese IME behavior note.
     * character — but the dict keyspace is pure a-z/2-9, so a stray ' silently broke segmentation, dict lookup
      * Chinese IME behavior note.
     * buffer and records:
     *  - [Norm.cuts]: clean-index forced boundaries (one per interior ');
     *  - [Norm.origLen]: clean-coverage-length → ORIGINAL coverage length, eating a trailing ' so a partial
     *    commit consumes the separator (the remaining buffer is a clean syllable, never "'ci");
      * Chinese IME behavior note.
     * Returns null when there is no ' at all — callers then take the original (zero-overhead) path unchanged.
     */
    private class Norm(val clean: String, val cuts: Set<Int>, val origLen: IntArray, private val cleanLenAtOrig: IntArray) {
        fun cleanIndexOfOrig(o: Int): Int? = cleanLenAtOrig.getOrNull(o)
    }

    private fun normalizeSeparators(input: String): Norm? {
        if (input.indexOf(SEP) < 0) return null
        val clean = StringBuilder(input.length)
        val cuts = HashSet<Int>()
        val origLen = IntArray(input.length + 1) // [cleanLen] -> original index (incl. an eaten trailing ')
        val cleanLenAtOrig = IntArray(input.length + 1)
        var ci = 0
        var oi = 0
        while (oi < input.length) {
            cleanLenAtOrig[oi] = ci
            if (input[oi] == SEP) {
                oi++
                if (ci in 1 until input.length) cuts.add(ci) // interior boundary only (drop leading/trailing/doubled)
                origLen[ci] = oi                              // coverage of `ci` clean chars also eats this '
            } else {
                clean.append(input[oi]); oi++; ci++
                origLen[ci] = oi
            }
        }
        cleanLenAtOrig[input.length] = ci
        // Keep only INTERIOR boundaries (a trailing ' lands a candidate cut at ci == clean.length): callers all
        // re-filter, but a clean Norm.cuts is safe for any future direct user — no spurious 0/length boundary.
        val interiorCuts = cuts.filterTo(HashSet()) { it in 1 until ci }
        return Norm(clean.toString(), interiorCuts, origLen.copyOf(ci + 1), cleanLenAtOrig)
    }

    /** Candidates for [input]: best sentence first, then word-by-word dictionary options. [context] is
     *  the committed text before the cursor, conditioning the first word (③ context-aware). */
    fun decode(input: String, limit: Int, context: CharSequence = ""): List<String> {
        if (input.isEmpty()) return emptyList()
        // Chinese IME behavior note.
        // and turn its position into a forced cut, so the lattice / dict see valid keys. Without this the whole
        // post-' tail was silently dropped (decode("chai'ci") returned []). No separator → unchanged fast path.
        val norm = normalizeSeparators(input)
        val clean = norm?.clean ?: input
        if (clean.isEmpty()) return emptyList()
        val cuts = norm?.cuts ?: emptySet()
        val (ctxCp, ctxWord) = parseContext(context)
        val out = LinkedHashSet<String>()
        bestSentence(clean, cuts, ctxCp = ctxCp, ctxWord = ctxWord)?.let { out.add(it) }
        out.addAll(rerankedWholeInputAndAliases(clean, ctxCp, ctxWord)) // ★top-N rerank, context-conditioned
        out.addAll(userWordsFor(clean)) // self-created words recalled for their exact reading
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

    /**
     * Candidates tagged with coverage length (★E/★G): best full sentence + full-input completions
     * (all covering the whole input), then per-prefix words enumerated longest-prefix-first so
     * multi-syllable words rank above their leading single chars. Each [Cand.coveredLen] is how many
     * leading input units that word consumes, so picking it can partially commit and continue the rest.
     * [decode] is intentionally left unchanged so the accuracy eval path is unaffected.
     */
    fun decodeCovered(input: String, limit: Int, cuts: Set<Int> = emptySet(), context: CharSequence = ""): List<Cand> {
        if (input.isEmpty()) return emptyList()
        // debug.17: ' = hard syllable boundary → strip to pure pinyin + forced cut, then remap each candidate's
        // coverage back to the original ' -inclusive index (so a partial commit eats the separator too, leaving
        // a clean tail). No separator → the original clean path runs verbatim (zero behaviour change).
        val norm = normalizeSeparators(input) ?: return decodeCoveredClean(input, limit, cuts, context)
        if (norm.clean.isEmpty()) return emptyList()
        val passedClean = cuts.mapNotNull { norm.cleanIndexOfOrig(it) }.toSet()
        return decodeCoveredClean(norm.clean, limit, norm.cuts + passedClean, context)
            .map { Cand(it.word, norm.origLen.getOrElse(it.coveredLen) { input.length }) }
    }

    /**
     * Boundary-aligned decode for readings the user explicitly selected. This is stricter than normal
     * [decodeCovered]: even with a single syllable and no interior cut, the selected reading is atomic, so
     * shorter prefix readings remain available only through the normal free-typing path.
     */
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
        // debug.18 (FIX-2): a buffer carrying FORCED syllable boundaries — 9-key locked readings OR a 26-key
        // Chinese IME behavior note.
        // keyboards (26-key chai'ci and 9-key locked chai|ci funnel through the SAME decodeCovered → here).
        if (interior.isNotEmpty()) return decodeAtomic(input, limit, interior, ctxCp, ctxWord)

        // ---- no forced boundary: whole-input completions + per-prefix word path ----
        val cover = LinkedHashMap<String, Int>()
        // Reserve part of the budget for leading single-chars/short words so full-input completions
        // (which can alone fill `limit`) never starve the ★G mixed grid.
        val completionCap = maxOf(1, limit * 2 / 3)
        bestSentence(input, emptySet(), ctxCp, ctxWord)?.let { cover[it] = input.length }
        // O2 ordering invariant (生僻不得先于常用): completions from ALL sources rank in ONE
        // cold-start model-score order — wordModelScore minus the source's documented penalty (exact
        // and main-dict prefix 0, alias ALIAS_PENALTY, fuzzy FUZZY_PENALTY, jianpin INITIALS_PENALTY).
        // The old source-layered append (whole exact bucket first, then prefix words, …) let a rare
        // exact single precede common prefix expansions inside one coverage bucket (欻@16 before 传统;
        // the full pack's freq-1 tails before 恩怨). One score order sinks the rare tail below every
        // common word, while equal-penalty candidates keep their previous relative (frequency) order.
        // Nothing becomes unreachable: singles pushed past the cap re-enter through the lossless
        // homophone tier, multi-char exact-key words through the per-prefix word layer below.
        val pool = ArrayList<Pair<BinaryDict.WordFreq, Double>>()
        val offered = HashSet<String>()
        fun offer(wf: BinaryDict.WordFreq, penalty: Double) {
            if (offered.add(wf.word)) pool.add(wf to (wordModelScore(wf.word, wf.freq, ctxCp, ctxWord) - penalty))
        }
        dict.exact(input).forEach { offer(it, 0.0) }
        inputAliasWordFreqs(input).forEach { offer(it, ALIAS_PENALTY) }
        dict.prefixByFreq(input, completionCap).forEach { offer(it, 0.0) }
        // Self-created words for this exact reading rank with the common completions by their characters'
        // shared commonness (+ usage boost inside the model score), so they surface without preceding a
        // common word; a genuine self-created word is not in the dict, so [offer]'s dedup never doubles it.
        for (uw in userWordsFor(input)) offer(BinaryDict.WordFreq(uw, userWordFreq(uw, input).toInt().coerceAtLeast(1)), 0.0)
        if (fuzzyRules.isNotEmpty()) {
            for (variant in Fuzzy.variants(input, fuzzyRules)) {
                if (variant == input) continue
                dict.prefixByFreq(variant, completionCap).forEach { offer(it, FUZZY_PENALTY) }
            }
        }
        initialsDict?.let { id -> id.prefixByFreq(input, completionCap).forEach { offer(it, INITIALS_PENALTY) } }
        pool.sortWith(
            compareByDescending<Pair<BinaryDict.WordFreq, Double>> { it.second }
                .thenBy { supplementarySingleTieRank(it.first.word) },
        )
        for ((wf, _) in pool) {
            if (cover.size >= completionCap) break
            cover.putIfAbsent(wf.word, input.length)
        }
        val out = ArrayList<Cand>(minOf(cover.size, limit) + 20)
        for ((w, len) in cover) { out.add(Cand(w, len)); if (out.size >= limit) break }
        // Recall floor: a self-created word for this exact reading must appear even if the completion cap
        // spilled it; add any still-missing one here — above the leading-singles tier, so it never precedes
        // a common word yet is always reachable.
        if (userModel != null) {
            val present = out.mapTo(HashSet()) { it.word }
            for (uw in userWordsFor(input)) if (present.add(uw)) out.add(Cand(uw, input.length))
        }
        // The per-prefix multi-char words and the lossless singles tiers are ONE remainder layer now
        // (merged per coverage inside appendLeadingSingles) — see the ordering note there.
        appendLeadingSingles(input, input.length, out, limit)
        return out
    }

    /**
     * debug.18 (FIX-2) BOUNDARY-ALIGNED ATOMIC decode for a buffer whose syllable boundaries are FORCED
      * Chinese IME behavior note.
     * cut-segment is segmented independently, so a declared syllable like `xian` stays ONE syllable and is
      * Chinese IME behavior note.
      * Chinese IME behavior note.
      * Chinese IME behavior note.
      * Chinese IME behavior note.
     * incl. U+20000+ rares at the freq tail), ④ the remaining top-N alternative whole sentences.
     */
    private fun decodeAtomic(input: String, limit: Int, interior: Set<Int>, ctxCp: Int, ctxWord: String): List<Cand> {
        // B = the forced boundaries (always honoured) PLUS the syllable boundaries WITHIN each cut-segment. The
        // forced cuts are added unconditionally so a chunk that fails to segment (garbage mid-typing) can never
        // silently drop a boundary the user declared.
        val bset = sortedSetOf(0, input.length)
        bset.addAll(interior)
        for (s in syllablesCleanCut(input, interior)) bset.add(s.end)
        val B = bset.toList()
        val nSyl = B.size - 1

        val singlesCache = HashMap<String, Set<String>>()
        val sentences = atomicSentences(input, B, interior, ctxCp, ctxWord, singlesCache)

        val best = sentences.firstOrNull()?.first                  // ① pinned best interpretation (commit default)

        // ② leading / whole-buffer exact dictionary WORDS for [0, B[j]] (the whole-key exact multi-syllable
        // words that read as the normal top of the strip), frequency-descending. A user lock is a HARD
        // boundary: a whole dict word fetched by the boundary-less key must not cross a forced cut (locking
        // fang+an must not offer 反感 = fan+gan) — see [admissibleUnderCuts].
        val leadFreq = LinkedHashMap<String, Int>()
        val leadCov = HashMap<String, Int>()
        for (j in 2..nSyl) for (wf in preferredExact(dict, input.substring(0, B[j]))) if (!isSingleChar(wf.word)) {
            if (!admissibleUnderCuts(wf.word, 0, B[j], interior, input, singlesCache)) continue
            if (leadFreq.put(wf.word, wf.freq) == null) leadCov[wf.word] = B[j]
        }
        // A self-created word whose reading is exactly this locked buffer joins the prominent leading-word
        // tier (above the single/sentence tail), ranked by its characters' shared commonness — recall on the
        // locked path without disturbing the rare-before-common order or the lossless single layer below.
        for (uw in userWordsFor(input)) {
            if (uw == best || uw in leadFreq || uw.codePointCount(0, uw.length) < 2) continue
            if (!admissibleUnderCuts(uw, 0, input.length, interior, input, singlesCache)) continue
            val f = userWordFreq(uw, input).toInt().coerceAtLeast(1)
            if (leadFreq.put(uw, f) == null) leadCov[uw] = input.length
        }

        // ③ first-syllable single homophones + the composed alternative sentences, merged into ONE cold-start
        // [wordModelScore] order (the O2 invariant on the locked path). Before, these were two source layers —
        // EVERY first-syllable single (rare tail and all), THEN the alternative sentences appended dead last —
        // so a rare single could precede a common multi-character candidate. They now share ONE frequency: a
        // candidate is as common as its RAREST covered reading-character. Per-syllable reading frequencies (the
        // same [homophoneFreqs] the singles use, alias discount folded in) make the score length-neutral, so a
        // candidate of common characters ranks alongside its common single while a rare single sinks below the
        // common words and sentences. Length-neutrality is the point: a raw covered-log-probability adds a
        // ln-total penalty per character, so a two-character candidate scores below a single of the same leading
        // frequency and the common two-char candidates stay stuck beneath the mid-rare singles. Equal-reading
        // singles keep frequency-descending order (O1). The rare single long tail sinks to the back but stays
        // LOSSLESS — the first-syllable single layer is emitted whole, exactly as the non-atomic
        // [appendLeadingSingles] keeps it (a redundant lock then decodes identically to free typing).
        val sylCharFreq = Array(nSyl) { i ->
            val m = HashMap<String, Double>()
            for ((w, f) in homophoneFreqs(input.substring(B[i], B[i + 1]))) m.putIfAbsent(w, f)
            m
        }
        // Rarest covered reading-character frequency: codepoint i reads covered syllable i (a returned sentence
        // covers all syllables with one codepoint each). A codepoint absent from its syllable's single set (a
        // heteronym reading) falls back to [carried].
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
            if (word == best || word in leadFreq) return          // pinned or already a prominent exact word
            val score = wordModelScore(word, commonnessFreq(word, coveredSyls, carried), ctxCp, ctxWord)
            val prev = tailScore[word]
            if (prev == null || score > prev) { tailScore[word] = score; tailCand[word] = Cand(word, coveredLen) }
        }
        for ((text, _) in sentences) offerTail(text, input.length, nSyl, 1.0)   // composed alternatives
        for ((w, _) in homophoneFreqs(input.substring(0, B[1]))) offerTail(w, B[1], 1, 0.0) // first-syllable singles
        val tailRanked = tailCand.values.sortedWith(
            compareByDescending<Cand> { tailScore[it.word] ?: Double.NEGATIVE_INFINITY }
                .thenBy { it.word.codePointCount(0, it.word.length) }       // a single before its equal-score composition
                .thenBy { supplementarySingleTieRank(it.word) },
        )

        val out = ArrayList<Cand>(1 + leadFreq.size + tailRanked.size)
        val seen = HashSet<String>()
        best?.let { if (seen.add(it)) out.add(Cand(it, input.length)) }
        // The exact-word tier is bounded by [limit] (as the non-atomic completion tier is); the first-syllable
        // single layer below stays LOSSLESS (uncapped), matching [appendLeadingSingles] — the composed
        // alternatives it is merged with are already bounded by the [ATOMIC_BEAM_N] beam.
        for ((w, _) in leadFreq.entries.sortedByDescending { it.value }) {
            if (out.size >= limit) break
            if (seen.add(w)) out.add(Cand(w, leadCov.getValue(w)))
        }
        for (c in tailRanked) if (seen.add(c.word)) out.add(c)
        return out
    }

    /**
     * Lock-boundary admissibility of a multi-char dict word occupying [spanStart, spanEnd) of [input]
     * against the user's forced [cuts] (a lock is a HARD boundary). The dict stores no per-word
     * syllabification, so it is recovered by reverse lookup — a DP over (key position, word codepoint)
     * where one step consumes a key substring `s` with the word's codepoint in `dict.exact(s)`'s singles:
     *  - some syllabification exists that straddles NO cut inside the span → admissible (方案 under
     *    fang|an: 方∈exact(fang), 案∈exact(an), boundary on the cut);
     *  - syllabifications exist but ALL straddle a cut → provably cross-parse → filtered (反感 under
     *    fang|an: its only recoverable split fan+gan puts a boundary at 3 ≠ cut 4);
     *  - NO syllabification is recoverable at all (a char without any standalone single entry, 猩 of
     *    猩猩, or a heteronym reading absent from the singles bucket, 石=dàn of 百石) → the crossing is
     *    unprovable offline → kept.
     * With no cut inside the span this is a constant-time no-op, so single-syllable locks and the free
     * typing path are untouched. Spans are short (≤ a few syllables) and lookups are cached per decode.
     */
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
        return !parses(respectCuts = false) // no recoverable syllabification at all: unprovable, keep
    }

    private class APath(val text: String, val lastCp: Int, val lastWord: String, val score: Double)

    /**
     * Top-[ATOMIC_BEAM_N] whole-buffer sentences over the boundary-aligned lattice [B], best score first.
     * Single-syllable spans contribute the syllable's single chars (top [SENTENCE_EDGE_N] by freq);
     * multi-syllable spans contribute multi-char words. Scored like [bestSentence] (unigram + λ·char-bigram +
     * octagram + user boost), with the committed context conditioning the first word. Every returned sentence
     * covers all [B] syllables, so its codePointCount equals the syllable count.
     */
    private fun atomicSentences(
        input: String,
        B: List<Int>,
        interior: Set<Int>,
        ctxCp: Int,
        ctxWord: String,
        singlesCache: HashMap<String, Set<String>>,
    ): List<Pair<String, Double>> {
        val nSyl = B.size - 1
        val dp = Array(B.size) { ArrayList<APath>() }
        dp[0].add(APath("", ctxCp, ctxWord, 0.0))
        for (i in 0 until nSyl) {
            if (dp[i].isEmpty()) continue
            val src = dp[i].sortedByDescending { it.score }.take(BEAM_W)
            for (j in i + 1..nSyl) {
                val seg = input.substring(B[i], B[j])
                val raw = preferredExact(dict, seg)
                // multi-syllable word edges spanning a forced cut must respect the lock boundary too
                val edges = (if (j == i + 1) raw.filter { isSingleChar(it.word) }
                else raw.filterNot { isSingleChar(it.word) }
                    .filter { admissibleUnderCuts(it.word, B[i], B[j], interior, input, singlesCache) })
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
        val ordered = ArrayList<Pair<String, Double>>(ATOMIC_BEAM_N)
        val seen = HashSet<String>()
        for (p in dp[nSyl].sortedByDescending { it.score }) {
            if (seen.add(p.text)) { ordered.add(p.text to p.score); if (ordered.size >= ATOMIC_BEAM_N) break }
        }
        return ordered
    }

    /**
      * Chinese IME behavior note.
     * start with (within [span], longest first), on a budget SEPARATE from the word/phrase candidates — so no
      * Chinese IME behavior note.
     * segments. 2nd+ syllables are served per-position by [homophonesAt] for the navigable UI (UI-1/UI-2).
     */
    private fun appendLeadingSingles(input: String, span: Int, out: ArrayList<Cand>, limit: Int) {
        val head = input.substring(0, span)
        val isT9 = input[0] in '2'..'9'
        val lens = if (isT9) T9Pinyin.leadingSyllableDigitLens(head)
        else T9Pinyin.leadingSyllableLetterLens(head)
        val lensSet = lens.toSet()
        val seen = HashSet<String>(out.size * 2)
        for (c in out) seen.add(c.word)
        // Ordering (O2): per-prefix multi-char WORDS and the lossless singles tier are merged into ONE
        // frequency order per coverage (longest prefix first). They used to be two layers — all
        // per-prefix words, then all singles tiers — which let a rare whole-key cross-parse word
        // (帝鳄@50 under "die") precede the common singles the completion cap had spilled over
        // (蝶/爹), and left the alias singles (嗯 for en, at the discounted frequency f·e^-ALIAS_PENALTY
        // set by [homophoneFreqs]) stranded behind the frequency-1 tail. Words keep the old budget
        // (PREFIX_PER_LEN per coverage, [limit] overall); the singles tiers stay uncapped (lossless).
        var wordBudget = maxOf(0, limit - out.size)
        for (q in span downTo 1) {
            val merged = ArrayList<Pair<String, Double>>()
            if (wordBudget > 0) {
                var added = 0
                for (wf in preferredExact(dict, input.substring(0, q))) {
                    if (isSingleChar(wf.word) || wf.word in seen) continue
                    merged.add(wf.word to wf.freq.toDouble())
                    if (++added >= PREFIX_PER_LEN) break
                }
            }
            if (q in lensSet) {
                for ((w, f) in homophoneFreqs(input.substring(0, q))) if (w !in seen) merged.add(w to f)
            }
            if (merged.isEmpty()) continue
            merged.sortWith(
                compareByDescending<Pair<String, Double>> { it.second }
                    .thenBy { supplementarySingleTieRank(it.first) },
            )
            for ((w, _) in merged) {
                if (!seen.add(w)) continue
                if (!isSingleChar(w)) { if (wordBudget <= 0) continue; wordBudget-- }
                out.add(Cand(w, q))
            }
        }
        // A leading tier's words can be swallowed by a longer tier's word-text dedup (dict.exact("n") ⊆
        // dict.exact("ng"): typing "nga" left 嗯 only at coverage 2, so 嗯-as-n could not be picked before
        // "ga"; on the full pack the tier PARTIALLY survives — 𠮾 stays at coverage 1 while 嗯 is swallowed).
        // Re-emit every swallowed word at its own coverage — the dedup key is (word, coverage), not word
        // text. Three gates keep this from bloating candidate lists:
        //  ①′ the whole buffer is not itself a single syllable — a lone typed syllable keeps its exact
        //     list (its prefix readings, e.g. 数=shu inside a lone "shuo", are served by the free-typing
        //     layers, not re-emitted);
        //  ②  the rest of the buffer segments into whole syllables (mid-syllable fragments never trigger);
        //  ③  that segmentation does not START with a bare nasal (n/ng/m) — a bare-nasal rest is the same
        //     exotic re-split of a whole syllable that segmentation itself avoids (a "liang" buffer must
        //     not re-offer its 俩=lia reading with an "ng" rest).
        if (lens.firstOrNull() == input.length) return
        for (k in lens) {
            if (k >= input.length) continue
            val rest = input.substring(k)
            val restSeg = if (isT9) T9Pinyin.segment(rest) else T9Pinyin.segmentLetters(rest)
            val first = restSeg?.firstOrNull() ?: continue
            if (first == "n" || first == "ng" || first == "m") continue
            val present = HashSet<String>()
            for (c in out) if (c.coveredLen == k) present.add(c.word)
            for (w in homophonesOf(input.substring(0, k))) if (present.add(w)) out.add(Cand(w, k))
        }
    }

    /**
     * Segment [input] into its syllables (best-cost split; letters on the 26-key decoder, digits on the
     * T9 decoder). Best-effort: if the tail doesn't fully segment yet (user mid-syllable) the already
     * complete leading syllables are still returned. Exposed for per-syllable UI navigation (UI-1/UI-2).
     */
    fun syllables(input: String): List<Syllable> {
        if (input.isEmpty()) return emptyList()
        // Chinese IME behavior note.
        // dropping "ci"). Strip separators, segment the clean buffer, then remap each span back to the original
        // ' -inclusive index — the syllable END eats a trailing separator so its coverage is contiguous.
        normalizeSeparators(input)?.let { n ->
            if (n.clean.isEmpty()) return emptyList()
            return syllablesCleanCut(n.clean, n.cuts).map { Syllable(it.reading, n.origLen[it.start], n.origLen[it.end]) }
        }
        return syllablesClean(input)
    }

    // The dict keyspace mirrors the input: a T9 buffer is digits 2-9, a 26-key buffer is a-z, with no overlap —
    // so the input itself tells us which segmenter (and which exact-key space) applies.
    private fun syllablesClean(input: String): List<Syllable> =
        if (input[0] in '2'..'9') t9Syllables(input) else letterSyllables(input)

    /** Segment a separator-free buffer while HONOURING forced boundaries: each ' -delimited chunk is segmented
     *  independently (so xi|an survives even though "xian" would greedily be one syllable), spans on clean indices. */
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

    /**
     * The COMPLETE single-char homophone set of syllable [index] of [input], frequency-ordered and
      * Chinese IME behavior note.
     * the syllable is unknown. On the T9 decoder the key is the syllable's digit group, so the set spans
     * every reading of that group (T9 is inherently ambiguous).
     */
    fun homophonesAt(input: String, index: Int): List<String> {
        // debug.17: resolve the syllable on the separator-stripped buffer so the reading key is a real pinyin
        // syllable (never a "chai'" with a stray '), keeping the post-' positions fully navigable.
        val norm = normalizeSeparators(input)
        val clean = norm?.clean ?: input
        if (clean.isEmpty()) return emptyList()
        val syls = syllablesCleanCut(clean, norm?.cuts ?: emptySet())
        if (index !in syls.indices) return emptyList()
        val s = syls[index]
        return homophonesOf(clean.substring(s.start, s.end))
    }

    /** Every single-char entry for an exact syllable key, frequency-ordered, uncapped. */
    private fun homophonesOf(key: String): List<String> = homophoneFreqs(key).map { it.first }

    /**
     * Every single-char entry for an exact syllable key with its ranking frequency: own-key entries at
     * their dict frequency (FIX-1: incl. U+20000+ singles), alias-key entries (嗯 for en/36) at the
     * penalty-DISCOUNTED frequency f·e^-ALIAS_PENALTY, all in one descending order. Ordering (O2): the old
     * append-alias-at-the-tail left the common 嗯 behind the native frequency-1 tail; the discounted
     * merge puts it after the common natives (恩/摁) but above the rares — the same relative position
     * the completion pool's score order gives it.
     */
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
        val n = input.length
        val dp = Array(n + 1) { HashMap<Int, Cell>() }
        // Seed the lattice with the preceding context (③): the first decoded word then pays the real
        // boundary bigram P(word₀ | ctxCp) and octagram(ctxWord + word₀). Empty context → BOS = no-op.
        dp[0][ctxCp] = Cell(0.0, -1, ctxCp, ctxWord)

        for (q in 1..n) {
            for (p in 0 until q) {
                val from = dp[p]
                if (from.isEmpty()) continue
                if (cuts.any { it > p && it < q }) continue // Chinese IME behavior note.
                val edges = edgesFor(input.substring(p, q))
                if (edges.isEmpty()) continue
                for (e in edges) {
                    val w = e.word
                    val uni = ln(e.freq.toDouble()) - lnTotal
                    val boost = userModel?.wordBoost(w) ?: 0.0
                    val firstCp = w.codePointAt(0)
                    val lastCp = w.codePointBefore(w.length)
                    for ((prevChar, cell) in from) {
                        // ③ the boundary out of the seeded context cell (prevPos < 0) is the committed
                        // preceding text → weight it by contextWeight; internal word boundaries keep λ.
                        val bw = if (cell.prevPos < 0 && prevChar != BOS) contextWeight else lambda
                        val bi = if (lm == null || prevChar == BOS) 0.0
                        else bw * lm.logCond(prevChar, firstCp)
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

    // internal (not private) so tests can assert the alias tables' exact shape (only en→ng exists)
    internal companion object {
        const val SEP = '\'' // Chinese IME behavior note.
        const val BOS = -1            // sentence-start sentinel (real code points are >= 0)
        const val EDGE_N = 20         // candidate words considered per lattice edge when an LM is present (C3)
        const val DEFAULT_LAMBDA = 1.0
        const val FUZZY_PENALTY = 3.0     // log-domain cost so exact matches outrank fuzzy ones
        const val ALIAS_PENALTY = 3.5
        const val INITIALS_PENALTY = 5.0 // Chinese IME behavior note.
        const val DEFAULT_OCTAGRAM_WEIGHT = 0.3 // scales the large positive octagram log-weights
        const val PREFIX_PER_LEN = 16 // max multi-char short words pulled per prefix length (★G; singles are lossless, separate) (C3)
        const val BEAM_W = 12         // debug.18: atomic sentence beam width per syllable node
        const val SENTENCE_EDGE_N = 6 // debug.18: single chars / words considered per atomic lattice cell (the FULL homophone set still reaches the grid via the lossless layer)
        const val ATOMIC_BEAM_N = 8   // debug.18: alternative whole sentences kept from the atomic beam
        const val CTX_WORD_MAX = 4    // trailing Han chars of context used as the octagram prev-word proxy
        const val MAX_SYLLABLE_KEY_LEN = 6 // longest single-syllable dict key (letters and T9 digits alike)
        val ALIAS_FREQ_DISCOUNT = exp(-ALIAS_PENALTY) // ALIAS_PENALTY expressed as a frequency factor (÷e^3.5)
        const val DEFAULT_CONTEXT_WEIGHT = 2.0 // ③ weight of the committed-context boundary bigram (vs λ for
        // internal word boundaries); context is reliable preceding text, so it outvotes raw frequency more
        val INPUT_ALIASES = mapOf("en" to listOf("ng"))
        /** The same aliases as the T9 decoder sees them: source reading keyed by its digit group
         *  (toT9("en") = "36"); targets stay letter keys, resolved against [aliasDict]. */
        val T9_INPUT_ALIASES: Map<String, List<String>> =
            INPUT_ALIASES.entries.associate { (k, v) -> T9Pinyin.toT9(k) to v }
    }
}
