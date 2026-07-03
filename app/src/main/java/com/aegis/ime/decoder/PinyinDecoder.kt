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
) {
    private val lnTotal = ln(dict.totalFreq.coerceAtLeast(1).toDouble())
    private var edgeN = if (lm != null || fuzzyRules.isNotEmpty() || initialsDict != null) EDGE_N else 1

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

    private fun inputAliases(key: String): List<String> = INPUT_ALIASES[key].orEmpty()

    private fun addExactEdges(
        key: String,
        penalty: Double,
        out: MutableList<Edge>,
        seen: MutableSet<String>,
    ): Boolean {
        for (wf in preferredExact(dict, key)) {
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
        return preferredWordFreqs(out)
    }

    /** Lattice edges for a substring, by descending preference: exact, aliases, fuzzy, then jianpin initials. */
    private fun edgesFor(sub: String): List<Edge> {
        val out = ArrayList<Edge>(edgeN)
        val seen = HashSet<String>()
        if (addExactEdges(sub, 0.0, out, seen)) return out
        for (alias in inputAliases(sub)) if (addExactEdges(alias, ALIAS_PENALTY, out, seen)) return out
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
        (ln(freq.toDouble()) - lnTotal) +
            (userModel?.wordBoost(word) ?: 0.0) +
            (octagram?.let { octagramWeight * (it.rawScore(word) ?: 0.0) } ?: 0.0) +
            (if (lm != null && ctxCp != BOS) contextWeight * lm.logCond(ctxCp, word.codePointAt(0)) else 0.0) +
            (if (octagram != null && ctxWord.isNotEmpty()) octagramWeight * (octagram.rawScore(ctxWord + word) ?: 0.0) else 0.0)

    /** Whole-input dict words (exact key = input) ordered by [wordModelScore] — model, not raw freq. */
    private fun rerankedWholeInput(input: String, ctxCp: Int, ctxWord: String): List<String> =
        dict.exact(input)
            .sortedWith(
                compareByDescending<BinaryDict.WordFreq> { wordModelScore(it.word, it.freq, ctxCp, ctxWord) }
                    .thenBy { supplementarySingleTieRank(it.word) },
            )
            .map { it.word }

    private fun rerankedInputAliases(input: String, ctxCp: Int, ctxWord: String): List<String> =
        inputAliasWordFreqs(input)
            .sortedWith(
                compareByDescending<BinaryDict.WordFreq> { wordModelScore(it.word, it.freq, ctxCp, ctxWord) - ALIAS_PENALTY }
                    .thenBy { supplementarySingleTieRank(it.word) },
            )
            .map { it.word }

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
        out.addAll(rerankedWholeInput(clean, ctxCp, ctxWord)) // ★top-N rerank, context-conditioned
        out.addAll(rerankedInputAliases(clean, ctxCp, ctxWord))
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

        // ---- no forced boundary: the original whole-input completion + per-prefix word path (unchanged) ----
        val cover = LinkedHashMap<String, Int>()
        // Reserve part of the budget for leading single-chars/short words so full-input completions
        // (which can alone fill `limit`) never starve the ★G mixed grid.
        val completionCap = maxOf(1, limit * 2 / 3)
        fun addCompletions(words: List<String>) {
            for (w in words) { if (cover.size >= completionCap) return; cover.putIfAbsent(w, input.length) }
        }
        bestSentence(input, emptySet(), ctxCp, ctxWord)?.let { cover[it] = input.length }
        addCompletions(rerankedWholeInput(input, ctxCp, ctxWord)) // ★top-N rerank, context-conditioned
        addCompletions(rerankedInputAliases(input, ctxCp, ctxWord))
        addCompletions(prefixWords(dict, input, completionCap))
        if (fuzzyRules.isNotEmpty()) {
            for (variant in Fuzzy.variants(input, fuzzyRules)) {
                if (variant == input) continue
                addCompletions(prefixWords(dict, variant, completionCap))
                if (cover.size >= completionCap) break
            }
        }
        initialsDict?.let { addCompletions(prefixWords(it, input, completionCap)) }
        // Multi-char prefix WORDS straight from the main dict (freq-ordered), independent of the lattice
        // Chinese IME behavior note.
        // Leading single chars are intentionally NOT emitted here — they are served by the lossless layer
        // Chinese IME behavior note.
        for (q in input.length downTo 1) {
            if (cover.size >= limit) break
            var added = 0
            for (wf in preferredExact(dict, input.substring(0, q))) {
                if (isSingleChar(wf.word)) continue // Chinese IME behavior note.
                if (cover.putIfAbsent(wf.word, q) == null && ++added >= PREFIX_PER_LEN) break
            }
        }
        val out = ArrayList<Cand>(minOf(cover.size, limit) + 20)
        for ((w, len) in cover) { out.add(Cand(w, len)); if (out.size >= limit) break }
        appendLeadingSingles(input, input.length, out)
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
        val cover = LinkedHashMap<String, Int>()
        val sentences = atomicSentences(input, B, interior, ctxCp, ctxWord, singlesCache)
        sentences.firstOrNull()?.let { cover[it] = input.length }            // ① best sentence
        // ③ leading multi-syllable words [0, B[j]] (cover the first j syllables), freq-descending. A user
        // lock is a HARD boundary: a whole dict word fetched by the boundary-less key must not cross a
        // forced cut (locking fang+an must not offer 反感 = fan+gan) — see [admissibleUnderCuts].
        val leadWords = ArrayList<Pair<String, Int>>() // (word, coveredLen) sortable by the carried freq
        val leadFreq = HashMap<String, Int>()
        for (j in 2..nSyl) for (wf in preferredExact(dict, input.substring(0, B[j]))) if (!isSingleChar(wf.word)) {
            if (!admissibleUnderCuts(wf.word, 0, B[j], interior, input, singlesCache)) continue
            if (leadFreq.put(wf.word, wf.freq) == null) leadWords.add(wf.word to B[j])
        }
        leadWords.sortedByDescending { leadFreq[it.first] ?: 0 }.forEach { cover.putIfAbsent(it.first, it.second) }

        val out = ArrayList<Cand>(minOf(cover.size, limit) + 40)
        for ((w, len) in cover) { out.add(Cand(w, len)); if (out.size >= limit) break }
        // Chinese IME behavior note.
        // user-DECLARED unit [0, B[1]] — unlike the unlocked layer it is NOT re-split into sub-prefixes (a locked
        // Chinese IME behavior note.
        val seen = HashSet<String>(out.size * 2); for (c in out) seen.add(c.word)
        for (w in homophonesOf(input.substring(0, B[1]))) if (seen.add(w)) out.add(Cand(w, B[1]))
        for (s in sentences.drop(1)) if (seen.add(s)) out.add(Cand(s, input.length)) // ④ alternative sentences
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
    ): List<String> {
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
        val ordered = LinkedHashSet<String>()
        for (p in dp[nSyl].sortedByDescending { it.score }) { ordered.add(p.text); if (ordered.size >= ATOMIC_BEAM_N) break }
        return ordered.toList()
    }

    /**
      * Chinese IME behavior note.
     * start with (within [span], longest first), on a budget SEPARATE from the word/phrase candidates — so no
      * Chinese IME behavior note.
     * segments. 2nd+ syllables are served per-position by [homophonesAt] for the navigable UI (UI-1/UI-2).
     */
    private fun appendLeadingSingles(input: String, span: Int, out: ArrayList<Cand>) {
        val head = input.substring(0, span)
        val isT9 = input[0] in '2'..'9'
        val lens = if (isT9) T9Pinyin.leadingSyllableDigitLens(head)
        else T9Pinyin.leadingSyllableLetterLens(head)
        if (lens.isEmpty()) return
        val seen = HashSet<String>(out.size * 2)
        for (c in out) seen.add(c.word)
        for (k in lens) for (w in homophonesOf(input.substring(0, k))) if (seen.add(w)) out.add(Cand(w, k))
        // A leading tier can be deduplicated away entirely when its homophone set is a subset of a longer
        // tier's (dict.exact("n") ⊆ dict.exact("ng"): typing "nga" left nothing covering exactly "n", so
        // 嗯-as-n could not be picked before "ga"). Re-emit such a tier at its own coverage — dedup key is
        // (word, coverage), not word text. Three gates keep this from bloating candidate lists: the tier
        // must have NO candidate at its coverage at all; the rest of the buffer must segment into whole
        // syllables; and that segmentation must not START with a bare nasal (n/ng/m) — a bare-nasal rest
        // is the same exotic re-split of a whole syllable that segmentation itself avoids (a lone "liang"
        // must not re-offer its 俩=lia reading with an "ng" rest; a full-dict 多音字 keyed under both "lia"
        // and "liang" would otherwise surface at two coverages).
        for (k in lens) {
            if (k >= input.length) continue
            if (out.any { it.coveredLen == k }) continue
            val rest = input.substring(k)
            val restSeg = if (isT9) T9Pinyin.segment(rest) else T9Pinyin.segmentLetters(rest)
            val first = restSeg?.firstOrNull() ?: continue
            if (first == "n" || first == "ng" || first == "m") continue
            for (w in homophonesOf(input.substring(0, k))) out.add(Cand(w, k))
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
    private fun homophonesOf(key: String): List<String> {
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        for (wf in preferredExact(dict, key)) if (isSingleChar(wf.word) && seen.add(wf.word)) out.add(wf.word) // FIX-1: incl. U+20000+ singles
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

    private companion object {
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
        const val DEFAULT_CONTEXT_WEIGHT = 2.0 // ③ weight of the committed-context boundary bigram (vs λ for
        // internal word boundaries); context is reliable preceding text, so it outvotes raw frequency more
        val INPUT_ALIASES = mapOf("en" to listOf("ng"))
    }
}
