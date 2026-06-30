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
 *  list every 同音字 of each (UI-1 9-key trailing column / UI-2 26-key pinyin column). */
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
     * are not crowded out by the single exact match (matters only when there is no lm / 简拼 dict).
     */
    fun setFuzzyRules(rules: Set<String>) {
        fuzzyRules = rules
        edgeN = if (lm != null || rules.isNotEmpty() || initialsDict != null) EDGE_N else 1
    }

    private class Edge(val word: String, val freq: Int, val penalty: Double)

    /** Lattice edges for a substring, by descending preference: exact, then fuzzy, then 简拼 initials. */
    private fun edgesFor(sub: String): List<Edge> {
        val out = ArrayList<Edge>(edgeN)
        val seen = HashSet<String>()
        for (wf in dict.exact(sub)) {
            if (seen.add(wf.word)) out.add(Edge(wf.word, wf.freq, 0.0))
            if (out.size >= edgeN) return out
        }
        if (fuzzyRules.isNotEmpty()) {
            // Per-rule fuzzy: enumerate the confusion class of `sub` and match each against the exact
            // dict (no monolithic fuzzy index, so individual rules can be turned off — E4).
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
        dict.exact(input).sortedByDescending { wordModelScore(it.word, it.freq, ctxCp, ctxWord) }.map { it.word }

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

    private fun isHan(cp: Int): Boolean = cp in 0x3400..0x9FFF || cp in 0xF900..0xFAFF || cp in 0x20000..0x2FFFF

    /**
     * debug.18 (FIX-1): a "single character" by CODE POINTS, not UTF-16 units. A CJK extension char
     * (U+20000+) is a surrogate pair so its [String.length] is 2 — the old `length == 1` test misclassified
     * it as a multi-char WORD, flooding the front of the grid (PREFIX_PER_LEN) AND dropping it from the
     * single-char homophone layer. Shared by 26-key and 9-key (both decode through here).
     */
    private fun isSingleChar(w: String): Boolean = w.codePointCount(0, w.length) == 1

    /**
     * debug.17 隔音符 normalisation. A [SEP] ' in the buffer is a user-forced syllable boundary, never a key
     * character — but the dict keyspace is pure a-z/2-9, so a stray ' silently broke segmentation, dict lookup
     * AND the lattice (the post-' tail was dropped → 丢字). [normalizeSeparators] strips every ' to a clean
     * buffer and records:
     *  - [Norm.cuts]: clean-index forced boundaries (one per interior ');
     *  - [Norm.origLen]: clean-coverage-length → ORIGINAL coverage length, eating a trailing ' so a partial
     *    commit consumes the separator (the remaining buffer is a clean syllable, never "'ci");
     *  - [Norm.cleanIndexOfOrig]: original-index → clean-index, to remap any caller-supplied 分词 cuts.
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
        // debug.17: a 隔音符 ' is a hard syllable boundary, never a buffer character — strip it to pure pinyin
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

    private fun decodeCoveredClean(input: String, limit: Int, cuts: Set<Int>, context: CharSequence): List<Cand> {
        val (ctxCp, ctxWord) = parseContext(context)
        val interior = cuts.filter { it in 1 until input.length }.toSortedSet()
        // debug.18 (FIX-2): a buffer carrying FORCED syllable boundaries — 9-key locked readings OR a 26-key
        // 隔音符/分词 (both arrive here as interior `cuts`) — decodes BOUNDARY-ALIGNED & atomic. Shared by both
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
        addCompletions(dict.query(input, completionCap))
        if (fuzzyRules.isNotEmpty()) {
            for (variant in Fuzzy.variants(input, fuzzyRules)) {
                if (variant == input) continue
                addCompletions(dict.query(variant, completionCap))
                if (cover.size >= completionCap) break
            }
        }
        initialsDict?.let { addCompletions(it.query(input, completionCap)) }
        // Multi-char prefix WORDS straight from the main dict (freq-ordered), independent of the lattice
        // edge cap, so leading short words (你说 你们 …) surface even without an LM (★G).
        // Leading single chars are intentionally NOT emitted here — they are served by the lossless layer
        // below, so the PREFIX_PER_LEN cap can never truncate a syllable's 同音字 (the debug.13 loss bug).
        for (q in input.length downTo 1) {
            if (cover.size >= limit) break
            var added = 0
            for (wf in dict.exact(input.substring(0, q))) {
                if (isSingleChar(wf.word)) continue // FIX-1: ★单字 (incl. U+20000+) → lossless layer, never capped here
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
     * (9-key locks / 26-key 隔音符). The boundary set B = the syllable ends honouring the cuts (each
     * cut-segment is segmented independently, so a declared syllable like `xian` stays ONE syllable and is
     * NEVER internally re-split into `xi|an` → no spurious 西安). Every lattice edge [p,q] has p,q ∈ B; an
     * edge spanning exactly one syllable yields only single chars (so the 2-char 西安 keyed by the single
     * syllable `xian` is DROPPED), an edge spanning ≥2 syllables yields multi-char words (实现 / 九键 / 词库).
     * Order: ① best sentence, ③ leading multi-syllable words (freq), ② the leading-syllable 同音字 (lossless,
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

        val cover = LinkedHashMap<String, Int>()
        val sentences = atomicSentences(input, B, ctxCp, ctxWord)
        sentences.firstOrNull()?.let { cover[it] = input.length }            // ① best sentence
        // ③ leading multi-syllable words [0, B[j]] (cover the first j syllables), freq-descending.
        val leadWords = ArrayList<Pair<String, Int>>() // (word, coveredLen) sortable by the carried freq
        val leadFreq = HashMap<String, Int>()
        for (j in 2..nSyl) for (wf in dict.exact(input.substring(0, B[j]))) if (!isSingleChar(wf.word)) {
            if (leadFreq.put(wf.word, wf.freq) == null) leadWords.add(wf.word to B[j])
        }
        leadWords.sortedByDescending { leadFreq[it.first] ?: 0 }.forEach { cover.putIfAbsent(it.first, it.second) }

        val out = ArrayList<Cand>(minOf(cover.size, limit) + 40)
        for ((w, len) in cover) { out.add(Cand(w, len)); if (out.size >= limit) break }
        // ② lossless first-syllable 同音字 (freq-ordered, U+20000+ rares at the tail). The first syllable IS the
        // user-DECLARED unit [0, B[1]] — unlike the unlocked layer it is NOT re-split into sub-prefixes (a locked
        // `xian` lists 现… only, never the `xi`→西 / `xia`→下 sub-readings the user did not ask for).
        val seen = HashSet<String>(out.size * 2); for (c in out) seen.add(c.word)
        for (w in homophonesOf(input.substring(0, B[1]))) if (seen.add(w)) out.add(Cand(w, B[1]))
        for (s in sentences.drop(1)) if (seen.add(s)) out.add(Cand(s, input.length)) // ④ alternative sentences
        return out
    }

    private class APath(val text: String, val lastCp: Int, val lastWord: String, val score: Double)

    /**
     * Top-[ATOMIC_BEAM_N] whole-buffer sentences over the boundary-aligned lattice [B], best score first.
     * Single-syllable spans contribute the syllable's single chars (top [SENTENCE_EDGE_N] by freq);
     * multi-syllable spans contribute multi-char words. Scored like [bestSentence] (unigram + λ·char-bigram +
     * octagram + user boost), with the committed context conditioning the first word. Every returned sentence
     * covers all [B] syllables, so its codePointCount equals the syllable count.
     */
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

    /**
     * ★单字无损层 (debug.13): append the COMPLETE homophone set of every leading syllable the buffer could
     * start with (within [span], longest first), on a budget SEPARATE from the word/phrase candidates — so no
     * number of words crowds a 同音字 out and no per-length cap can trim it, regardless of how the buffer
     * segments. 2nd+ syllables are served per-position by [homophonesAt] for the navigable UI (UI-1/UI-2).
     */
    private fun appendLeadingSingles(input: String, span: Int, out: ArrayList<Cand>) {
        val head = input.substring(0, span)
        val lens = if (input[0] in '2'..'9') T9Pinyin.leadingSyllableDigitLens(head)
        else T9Pinyin.leadingSyllableLetterLens(head)
        if (lens.isEmpty()) return
        val seen = HashSet<String>(out.size * 2)
        for (c in out) seen.add(c.word)
        for (k in lens) for (w in homophonesOf(input.substring(0, k))) if (seen.add(w)) out.add(Cand(w, k))
    }

    /**
     * Segment [input] into its syllables (best-cost split; letters on the 26-key decoder, digits on the
     * T9 decoder). Best-effort: if the tail doesn't fully segment yet (user mid-syllable) the already
     * complete leading syllables are still returned. Exposed for per-syllable UI navigation (UI-1/UI-2).
     */
    fun syllables(input: String): List<Syllable> {
        if (input.isEmpty()) return emptyList()
        // debug.17: a 隔音符 ' was breaking segmentation outright (syllables("chai'ci") returned only [chai],
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
     * UNCAPPED — the ★单字无损 layer for per-syllable UI navigation. Empty when [index] is out of range or
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
        for (wf in dict.exact(key)) if (isSingleChar(wf.word)) out.add(wf.word) // FIX-1: incl. U+20000+ singles
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
                if (cuts.any { it > p && it < q }) continue // ★分词: no word may cross a forced syllable boundary
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
        const val SEP = '\''          // 隔音符: a user-forced hard syllable boundary in the buffer (debug.17)
        const val BOS = -1            // sentence-start sentinel (real code points are >= 0)
        const val EDGE_N = 20         // candidate words considered per lattice edge when an LM is present (C3)
        const val DEFAULT_LAMBDA = 1.0
        const val FUZZY_PENALTY = 3.0     // log-domain cost so exact matches outrank fuzzy ones
        const val INITIALS_PENALTY = 5.0  // 简拼 is the most ambiguous → lowest preference
        const val DEFAULT_OCTAGRAM_WEIGHT = 0.3 // scales the large positive octagram log-weights
        const val PREFIX_PER_LEN = 16 // max multi-char short words pulled per prefix length (★G; singles are lossless, separate) (C3)
        const val BEAM_W = 12         // debug.18: atomic sentence beam width per syllable node
        const val SENTENCE_EDGE_N = 6 // debug.18: single chars / words considered per atomic lattice cell (the FULL homophone set still reaches the grid via the lossless layer)
        const val ATOMIC_BEAM_N = 8   // debug.18: alternative whole sentences kept from the atomic beam
        const val CTX_WORD_MAX = 4    // trailing Han chars of context used as the octagram prev-word proxy
        const val DEFAULT_CONTEXT_WEIGHT = 2.0 // ③ weight of the committed-context boundary bigram (vs λ for
        // internal word boundaries); context is reliable preceding text, so it outvotes raw frequency more
    }
}
