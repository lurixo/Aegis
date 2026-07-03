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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Blind-spot EXPANSION of the exhaustive decode audit (Level A, pure JVM, decoder direct).
 *
 * The audit ([ExhaustiveDecodeAuditTest]) only ever locked/checked the FIRST syllable and ran
 * with fuzzy OFF. This sibling extends coverage into the untested surfaces:
 *
 *  E1  9-key sequential locking — both syllables of every ordered pair locked; mirrors
 *      `KeyboardController.baseCandidates()`: locked readings become `fullLetters` + cumulative
 *      letter-offset `lockCuts` fed to `decodeCoveredAtomic`. Chars per segment must read that
 *      segment's locked syllable.
 *  E2  later-syllable homophones — `homophonesAt(pair, i)` for EVERY position i: the chars at a
 *      position must read that position's DISPLAYED syllable (label↔chars cross-wiring hunt beyond
 *      position 0).
 *  E3  partial commit then continue — a candidate covering exactly S1 must exist as the partial-commit
 *      entry point (letters + T9), and the remaining buffer (== S2) must relabel/redecode cleanly.
 *  E4  fuzzy ON — with all rules enabled the shown label must still be EXACTLY the typed syllable, and
 *      chars must stay inside the fuzzy variant class; plus per-rule isolation on the 25 sentinel
 *      syllables.
 *  E5  candidate-order sanity — advisory only: #1 candidate not in the dict's top-5 singles for the
 *      syllable is reported, never failed.
 *
 * Same conventions as the audit: runtime `T9Pinyin.SYLLABLES` via reflection (415), oracle =
 * the app's own `dict.exact(S)` singles, `COLLOQUIAL_WHITELIST` (en→嗯) honoured wherever chars are
 * checked, TSVs to `AEGIS_AUDIT_DIR`, heavy sweeps gated by `AEGIS_AUDIT_FULL=1`.
 * REPORT-ONLY: nothing here fixes the decoder.
 */
class ExhaustiveDecodeAuditExtTest {

    private val dictFile = File("src/main/assets/aegis_dict.bin")
    private val t9File = File("src/main/assets/aegis_t9.bin")
    private val lmFile = File("src/main/assets/aegis_lm.bin")
    private val jianpinFile = File("src/main/assets/aegis_jianpin.bin")

    private fun letterDecoder(fuzzy: Set<String> = emptySet()): PinyinDecoder {
        // jianpin is part of the decoder configuration under audit: skip loudly when absent instead of
        // silently sweeping with a different configuration.
        assumeTrue("jianpin asset present", jianpinFile.exists())
        return PinyinDecoder(
            BinaryDict.fromFile(dictFile), CharBigramLM.fromFile(lmFile),
            fuzzyRules = fuzzy, initialsDict = BinaryDict.fromFile(jianpinFile),
        )
    }
    private fun t9Decoder(): PinyinDecoder =
        PinyinDecoder(BinaryDict.fromFile(t9File), CharBigramLM.fromFile(lmFile))

    private val dict: BinaryDict by lazy { BinaryDict.fromFile(dictFile) }
    private val t9Dict: BinaryDict by lazy { BinaryDict.fromFile(t9File) }

    private fun isSingleChar(w: String): Boolean = w.codePointCount(0, w.length) == 1
    private fun dictSingles(key: String): Set<String> =
        dict.exact(key).filter { isSingleChar(it.word) }.map { it.word }.toSet()
    private fun t9Singles(key: String): Set<String> =
        t9Dict.exact(key).filter { isSingleChar(it.word) }.map { it.word }.toSet()
    private fun sample(s: Collection<String>, n: Int = 8): String =
        s.take(n).joinToString(" ") + if (s.size > n) " …(${s.size})" else ""

    /** Same allowlist as the audit: known colloquial cross-readings. */
    /** The deliberate `en -> ng` input alias forwards the WHOLE `ng` key: whatever single chars the
     *  dict carries under `ng` (嗯, and any completeness top-ups like 㕶) legitimately surface for `en`.
     *  The allowlist therefore mirrors the alias target's live single set instead of pinning one char. */
    private val COLLOQUIAL_WHITELIST: Map<String, Set<String>> by lazy { mapOf("en" to dictSingles("ng")) }
    private fun allowed(reading: String): Set<String> = COLLOQUIAL_WHITELIST[reading] ?: emptySet()

    @Suppress("UNCHECKED_CAST")
    private fun runtimeSyllables(): List<String> {
        val f = T9Pinyin::class.java.getDeclaredField("SYLLABLES")
        f.isAccessible = true
        val syls = (f.get(T9Pinyin) as Set<String>).toList().sorted()
        // drift guard (aligned with the audit): the runtime universe must stay ~415
        assertTrue("runtime SYLLABLES set looks like ~415 (drift guard): ${syls.size}", syls.size in 400..430)
        return syls
    }

    /** Reverse single-char index: char -> the syllables it has a STANDALONE dict.exact entry under.
     *  Distinguishes a true cross-parse char (standalone entry under a different syllable, e.g. 反=fan)
     *  from a char the seed dict only carries inside words (no standalone entry anywhere, e.g. 猩). */
    private val reverseSingles: Map<String, Set<String>> by lazy {
        val m = HashMap<String, MutableSet<String>>()
        for (s in runtimeSyllables()) for (ch in dictSingles(s)) m.getOrPut(ch) { HashSet() }.add(s)
        m
    }

    /**
     * Lock-boundary word-level admissibility (independent reimplementation of the decoder's lock-boundary rule,
     * used to AUDIT it): recover the word's syllabification over [key] by reverse lookup. Verdicts:
     * "aligned" (a syllabification straddles no cut), "crossing" (syllabifications exist but ALL straddle
     * a cut — the decoder must filter these under a lock), "unverifiable" (no syllabification recoverable
     * offline — kept; crossing cannot be proven).
     */
    private fun lockVerdict(word: String, key: String, cuts: Set<Int>): String {
        val cps = ArrayList<String>(4)
        var ci = 0
        while (ci < word.length) {
            val cp = word.codePointAt(ci); cps.add(String(Character.toChars(cp))); ci += Character.charCount(cp)
        }
        val n = key.length
        val m = cps.size
        fun parses(respect: Boolean): Boolean {
            val dp = Array(n + 1) { BooleanArray(m + 1) }
            dp[0][0] = true
            for (p in 0 until n) for (i in 0 until m) {
                if (!dp[p][i]) continue
                for (q in p + 1..minOf(n, p + 6)) {
                    if (respect && cuts.any { it in (p + 1) until q }) continue
                    if (cps[i] in dictSingles(key.substring(p, q))) dp[q][i + 1] = true
                }
            }
            return dp[n][m]
        }
        return when {
            parses(true) -> "aligned"
            parses(false) -> "crossing"
            else -> "unverifiable"
        }
    }

    /** Run provenance embedded in every TSV/summary so a stale-artifact mixup is detectable. */
    private val runStamp: String by lazy {
        val rev = runCatching {
            ProcessBuilder("git", "rev-parse", "--short", "HEAD").redirectErrorStream(true)
                .start().inputStream.bufferedReader().readText().trim()
        }.getOrDefault("unknown")
        "run=${System.currentTimeMillis()} git=$rev"
    }

    private data class Fail(
        val input: String, val layout: String, val check: String,
        val expected: String, val shown: String, val detail: String,
    )

    private fun outDir(): File {
        val p = System.getenv("AEGIS_AUDIT_DIR") ?: System.getProperty("aegis.audit.dir") ?: "build/decode-audit"
        val d = File(p); d.mkdirs(); return d
    }
    private fun fullEnabled(): Boolean =
        (System.getenv("AEGIS_AUDIT_FULL") ?: System.getProperty("aegis.audit.full")) == "1"

    private fun writeTsv(file: File, fails: List<Fail>) {
        file.bufferedWriter().use { w ->
            w.write("# $runStamp\n")
            w.write("input\tlayout\tcheck\texpected\tshown\tdetail\n")
            for (f in fails) w.write("${f.input}\t${f.layout}\t${f.check}\t${f.expected}\t${f.shown}\t${f.detail}\n")
        }
    }

    private fun summary(file: File, title: String, covered: String, fails: List<Fail>) {
        val byCheck = fails.groupingBy { it.check }.eachCount().toSortedMap()
        File(outDir(), file.name).writeText(buildString {
            appendLine("# $runStamp")
            appendLine(title)
            appendLine(covered)
            appendLine("total rows (violations + classified findings): ${fails.size}; distinct inputs: ${fails.map { it.input }.toSet().size}")
            byCheck.forEach { (k, v) -> appendLine("  $k: $v") }
        })
    }

    // ================= E1 · 9-key sequential locking (ALL 415² ordered pairs) =================
    // Mirrors KeyboardController.baseCandidates(): lockedReadings=[S1,S2] -> fullLetters=S1+S2,
    // lockCuts={len(S1)} (cumulative offsets < full.length) -> engine.candidatesForLockedReadingCovered
    // -> decoder.decodeCoveredAtomic(letters, cuts). Preedit joins the locked readings with ' -> the
    // segment view is syllables("S1'S2").
    @Test fun e1_sequentialLock_allPairs() {
        assumeTrue("heavy sweep gated: set AEGIS_AUDIT_FULL=1", fullEnabled())
        assumeTrue(dictFile.exists() && lmFile.exists())
        val syls = runtimeSyllables()
        val d = letterDecoder()
        val fails = ArrayList<Fail>()
        var done = 0
        for (s1 in syls) {
            val o1 = dictSingles(s1) + allowed(s1)
            for (s2 in syls) {
                val input = s1 + s2
                val cut = s1.length
                val o2 = dictSingles(s2) + allowed(s2)

                // label: the locked preedit "S1'S2" must segment back to exactly [S1, S2]
                val seg = d.syllables("$s1'$s2").map { it.reading }
                if (seg != listOf(s1, s2)) {
                    fails += Fail(input, "9key", "E1-label", "$s1+$s2", seg.joinToString("+"),
                        "syllables(S1'S2) != locked readings")
                }

                val cands = d.decodeCoveredAtomic(input, 30, setOf(cut))
                // first-unit singles must read S1
                val firstLeak = cands.filter { it.coveredLen == cut && isSingleChar(it.word) }
                    .map { it.word }.filter { it !in o1 }
                if (firstLeak.isNotEmpty()) {
                    fails += Fail(input, "9key", "E1-chars-S1", sample(o1), sample(firstLeak),
                        "locked-decode first-unit singles not reading S1")
                }
                // full-coverage 2-codepoint candidates: each codepoint must read its locked syllable.
                // A candidate that violates that but IS a dict word keyed under the boundary-less string
                // S1+S2 is NOT exempted — it is counted as its own class: the dict-word channel surfaces
                // the whole key regardless of the lock cut (locking fang+an still offers 反感 = fan+gan),
                // which is real, user-visible behaviour reported in its own class. Within that class, a failing
                // char with a STANDALONE entry under another syllable (反=fan) is hard cross-parse
                // evidence; a char with no standalone entry anywhere (猩) is an oracle gap of the seed
                // dict (its true reading may well match). Non-dict-word (beam-assembled) candidates that
                // fail remain TRUE violations.
                val dictWords = dict.exact(input).map { it.word }.toSet()
                for (c in cands) {
                    if (c.coveredLen != input.length || c.word.codePointCount(0, c.word.length) != 2) continue
                    val cp0 = String(Character.toChars(c.word.codePointAt(0)))
                    val cp1 = String(Character.toChars(c.word.codePointBefore(c.word.length)))
                    val bad = ArrayList<Pair<String, String>>() // (cp, which)
                    if (cp0 !in o1) bad.add(cp0 to "S1=$s1")
                    if (cp1 !in o2) bad.add(cp1 to "S2=$s2")
                    if (bad.isEmpty()) continue
                    if (c.word in dictWords) {
                        // Lock boundary: an explicit lock is a hard boundary — a dict word whose every recoverable
                        // syllabification crosses the cut must have been FILTERED by the decoder; seeing
                        // one here is a hard failure ("E1-crossing-escaped", asserted 0). Words whose
                        // syllabification is unrecoverable offline stay a disclosed class.
                        val det = bad.joinToString("; ") { (cp, which) ->
                            "$cp vs $which (standalone readings: ${reverseSingles[cp]?.sorted()?.joinToString(",") ?: "none"})"
                        }
                        when (lockVerdict(c.word, input, setOf(cut))) {
                            "crossing" -> fails += Fail(input, "9key", "E1-crossing-escaped", "$s1+$s2", c.word,
                                "boundary-crossing dict word escaped the lock filter: $det")
                            "unverifiable" -> fails += Fail(input, "9key", "E1-dictword-unverifiable", "$s1+$s2", c.word,
                                "dict word with no offline-recoverable syllabification (kept by design): $det")
                            else -> fails += Fail(input, "9key", "E1-aligned-but-mismatch", "$s1+$s2", c.word,
                                "aligned dict word with a char outside the locked oracle (unexpected): $det")
                        }
                    } else {
                        if (cp0 !in o1) fails += Fail(input, "9key", "E1-chars-S1",
                            sample(o1), "${c.word}[0]=$cp0", "sentence char 0 not reading S1")
                        if (cp1 !in o2) fails += Fail(input, "9key", "E1-chars-S2",
                            sample(o2), "${c.word}[1]=$cp1", "sentence char 1 not reading S2")
                    }
                }
                // the newly locked segment's homophone drill must read S2
                val homo2 = d.homophonesAt("$s1'$s2", 1).toSet()
                val leak2 = homo2 - dictSingles(s2) - allowed(s2)
                if (leak2.isNotEmpty()) {
                    fails += Fail(input, "9key", "E1-drill-S2", sample(o2), sample(leak2),
                        "homophonesAt(S1'S2, 1) not reading S2")
                }
                if (dictSingles(s2).isNotEmpty() && homo2.isEmpty()) {
                    fails += Fail(input, "9key", "E1-drill-S2", sample(o2), "<empty>",
                        "homophonesAt(S1'S2, 1) empty though dict.exact(S2) non-empty")
                }
            }
            done += syls.size
            if (done % (syls.size * 100) == 0) println("[E1] ~$done/${syls.size * syls.size}")
        }
        writeTsv(File(outDir(), "ext_e1.tsv"), fails)
        summary(File(outDir(), "ext_e1_summary.txt"), "E1 — 9-key sequential locking",
            "pairs covered: ${syls.size.toLong() * syls.size} (all ordered 415²)", fails)
        // Lock-boundary hard gate: no boundary-crossing dict word may escape the lock filter anywhere in 415².
        val escaped = fails.filter { it.check == "E1-crossing-escaped" || it.check == "E1-aligned-but-mismatch" }
        assertTrue("lock-boundary filter escaped on ${escaped.size} pairs: ${escaped.take(5)}", escaped.isEmpty())
        assertTrue("E1 report written", File(outDir(), "ext_e1.tsv").exists())
    }

    // ================= E2 · later-syllable homophones (ALL 415² ordered pairs) =================
    // For EVERY displayed position i of the un-cut pair: chars must read the DISPLAYED reading at i
    // (cross-wiring hunt). Where segmentation == [S1,S2] this pins position 1 to dict.exact(S2).
    // A pair that segments differently is inherent reflow (Part-1 n=2 I1 territory), tracked separately.
    @Test fun e2_laterSyllableHomophones_allPairs() {
        assumeTrue("heavy sweep gated: set AEGIS_AUDIT_FULL=1", fullEnabled())
        assumeTrue(dictFile.exists() && lmFile.exists())
        val syls = runtimeSyllables()
        val d = letterDecoder()
        val fails = ArrayList<Fail>()
        var exactSeg = 0L
        var done = 0
        for (s1 in syls) {
            for (s2 in syls) {
                val input = s1 + s2
                val seg = d.syllables(input)
                if (seg.map { it.reading } == listOf(s1, s2)) exactSeg++
                for ((i, s) in seg.withIndex()) {
                    val oracle = dictSingles(s.reading)
                    val homo = d.homophonesAt(input, i).toSet()
                    val leak = homo - oracle - allowed(s.reading)
                    if (leak.isNotEmpty()) {
                        fails += Fail(input, "26key", "E2-pos$i-leak", sample(oracle), sample(leak),
                            "homophonesAt(pair,$i) has chars not reading displayed '${s.reading}'")
                    }
                    if (oracle.isNotEmpty() && homo.isEmpty()) {
                        fails += Fail(input, "26key", "E2-pos$i-empty", sample(oracle), "<empty>",
                            "homophonesAt(pair,$i) empty though dict.exact('${s.reading}') non-empty")
                    }
                }
            }
            done += syls.size
            if (done % (syls.size * 100) == 0) println("[E2] ~$done/${syls.size * syls.size}")
        }
        writeTsv(File(outDir(), "ext_e2.tsv"), fails)
        summary(File(outDir(), "ext_e2_summary.txt"), "E2 — later-syllable homophones vs displayed reading",
            "pairs covered: ${syls.size.toLong() * syls.size}; pairs segmenting exactly [S1,S2]: $exactSeg", fails)
        assertTrue("E2 report written", File(outDir(), "ext_e2.tsv").exists())
    }

    // ================= E3 · partial commit then continue (ALL 415² ordered pairs) =================
    // Letters + T9: a candidate covering exactly S1 must exist (the partial-commit entry point).
    // The remaining buffer is literally S2's units; its label/char integrity is checked once per
    // distinct S2 (415 distinct remainders).
    @Test fun e3_partialCommitContinue_allPairs() {
        assumeTrue("heavy sweep gated: set AEGIS_AUDIT_FULL=1", fullEnabled())
        assumeTrue(dictFile.exists() && lmFile.exists() && t9File.exists())
        val syls = runtimeSyllables()
        val d = letterDecoder()
        val t9 = t9Decoder()
        val fails = ArrayList<Fail>()

        // remainder integrity per distinct S2 (letters + 9-key reading options)
        for (s2 in syls) {
            val seg = d.syllables(s2).map { it.reading }
            if (seg != listOf(s2)) {
                fails += Fail(s2, "26key", "E3-remainder-label", s2, seg.joinToString("+"),
                    "remaining buffer S2 mis-segments")
            }
            val homo = d.homophonesAt(s2, 0).toSet()
            val leak = homo - dictSingles(s2) - allowed(s2)
            if (leak.isNotEmpty()) {
                fails += Fail(s2, "26key", "E3-remainder-chars", sample(dictSingles(s2)), sample(leak),
                    "remaining buffer chars not reading S2")
            }
            val col = T9Pinyin.leftColumnReadings(T9Pinyin.toT9(s2), 26)
            if (s2 !in col) {
                fails += Fail(s2, "9key", "E3-remainder-option", s2, sample(col),
                    "9-key reading options for the remaining digits omit S2")
            }
        }

        var done = 0
        for (s1 in syls) {
            val letterLen = s1.length
            val digitLen = T9Pinyin.toT9(s1).length
            val o1Letters = dictSingles(s1)
            val o1T9 = t9Singles(T9Pinyin.toT9(s1))
            for (s2 in syls) {
                // letters path
                val lc = d.decodeCovered(s1 + s2, 30)
                val hitL = lc.firstOrNull { it.coveredLen == letterLen }
                if (hitL == null) {
                    val cls = if (o1Letters.isEmpty()) "E3-noentry-dictless" else "E3-noentry-letters"
                    fails += Fail(s1 + s2, "26key", cls, "cand covering ${letterLen}", "<none>",
                        "no decodeCovered candidate covers exactly S1")
                } else if (isSingleChar(hitL.word) && hitL.word !in o1Letters + allowed(s1)) {
                    fails += Fail(s1 + s2, "26key", "E3-entry-char", sample(o1Letters), hitL.word,
                        "top S1-covering single does not read S1")
                }
                // T9 path
                val digits = T9Pinyin.toT9(s1 + s2)
                val tc = t9.decodeCovered(digits, 30)
                val hitT = tc.firstOrNull { it.coveredLen == digitLen }
                if (hitT == null) {
                    val cls = if (o1T9.isEmpty()) "E3-noentry-dictless" else "E3-noentry-t9"
                    fails += Fail(s1 + s2, "9key", cls, "cand covering $digitLen digits", "<none>",
                        "no T9 decodeCovered candidate covers exactly S1's digits")
                } else if (isSingleChar(hitT.word) && hitT.word !in o1T9) {
                    fails += Fail(s1 + s2, "9key", "E3-entry-char", sample(o1T9), hitT.word,
                        "top S1-digit-covering single not in the digit group's dict set")
                }
            }
            done += syls.size
            if (done % (syls.size * 50) == 0) println("[E3] ~$done/${syls.size * syls.size}")
        }
        writeTsv(File(outDir(), "ext_e3.tsv"), fails)
        summary(File(outDir(), "ext_e3_summary.txt"), "E3 — partial commit then continue",
            "pairs covered: ${syls.size.toLong() * syls.size} on letters AND T9; remainder integrity per distinct S2 (415)", fails)
        assertTrue("E3 report written", File(outDir(), "ext_e3.tsv").exists())
    }

    // ================= E4 · fuzzy ON (all 415; label fixed, chars within the variant class) =================
    // DIFFERENTIAL char check: fuzzy may only ADD single-char candidates that read a fuzzy VARIANT of the
    // exact span they cover. Chars already produced with fuzzy OFF (prefix completions like 按 covering the
    // typed "a", alias-vouched chars, leading-prefix singles) are the established non-fuzzy behaviour and
    // are vouched for by the baseline run + the merged Part-1 invariants.
    @Test fun e4_fuzzyOn_allSyllables_andRuleIsolation() {
        assumeTrue(dictFile.exists() && lmFile.exists())
        val syls = runtimeSyllables()
        val allKeys = Fuzzy.RULES.map { it.key }.toSet()
        val fails = ArrayList<Fail>()

        val dBase = letterDecoder()
        val baseline = HashMap<String, Set<Pair<String, Int>>>() // input -> fuzzy-off (word, coveredLen) singles
        fun baseSingles(s: String): Set<Pair<String, Int>> = baseline.getOrPut(s) {
            dBase.decodeCovered(s, 30).filter { isSingleChar(it.word) }.mapTo(HashSet()) { it.word to it.coveredLen }
        }

        fun checkOne(d: PinyinDecoder, s: String, rules: Set<String>, tag: String) {
            val seg = d.syllables(s).map { it.reading }
            if (seg != listOf(s)) {
                fails += Fail(s, "26key", "$tag-label", s, seg.joinToString("+"),
                    "fuzzy rewrote the shown reading (rules=${rules.joinToString(",")})")
            }
            val base = baseSingles(s)
            for (c in d.decodeCovered(s, 30)) {
                if (!isSingleChar(c.word) || (c.word to c.coveredLen) in base) continue
                val key = s.substring(0, c.coveredLen.coerceIn(1, s.length))
                val okSet = Fuzzy.variants(key, rules).flatMapTo(HashSet()) { dictSingles(it) } + allowed(key)
                if (c.word !in okSet) {
                    fails += Fail(s, "26key", "$tag-chars", "variants('$key')", "${c.word}@${c.coveredLen}",
                        "fuzzy ADDED a single outside the variant class of its covered span (rules=${rules.joinToString(",")})")
                }
            }
            // the homophone drill must stay exact-reading even with fuzzy on
            val drillLeak = d.homophonesAt(s, 0).toSet() - dBase.homophonesAt(s, 0).toSet()
            if (drillLeak.isNotEmpty()) {
                fails += Fail(s, "26key", "$tag-drill", sample(dictSingles(s)), sample(drillLeak),
                    "fuzzy leaked into homophonesAt (must stay identical to fuzzy-off)")
            }
        }

        val dAll = letterDecoder(fuzzy = allKeys)
        for (s in syls) checkOne(dAll, s, allKeys, "E4-all")

        // rule isolation on the 25 sentinels: the 12 former offenders + 13 controls
        val sentinels = listOf(
            "dang", "deng", "geng", "heng", "keng", "leng", "nang", "ning", "tang", "xing", "ying", "en",
            "ding", "dong", "gang", "hang", "tong", "ping", "qing", "ling", "zheng", "fang", "feng", "bing", "ming",
        )
        for (rule in allKeys) {
            val dOne = letterDecoder(fuzzy = setOf(rule))
            for (s in sentinels) checkOne(dOne, s, setOf(rule), "E4-$rule")
        }

        writeTsv(File(outDir(), "ext_e4.tsv"), fails)
        summary(File(outDir(), "ext_e4_summary.txt"), "E4 — fuzzy ON",
            "all-rules: ${syls.size} syllables; isolation: ${allKeys.size} rules x ${sentinels.size} sentinels", fails)
        assertTrue("E4 must be clean (label fixed under fuzzy, chars within variant class): ${fails.take(6)}",
            fails.isEmpty())
    }

    // ================= E5 · candidate-order sanity (advisory only — never fails) =================
    @Test fun e5_orderAdvisory_allSyllables() {
        assumeTrue(dictFile.exists() && lmFile.exists())
        val syls = runtimeSyllables()
        val d = letterDecoder()
        val rows = ArrayList<String>()
        for (s in syls) {
            val top5 = dict.exact(s).filter { isSingleChar(it.word) }
                .sortedByDescending { it.freq }.take(5).map { it.word }
            val first = d.decodeCovered(s, 30).firstOrNull()?.word ?: "<none>"
            if (top5.isNotEmpty() && first !in top5 && first !in allowed(s)) {
                rows.add("$s\t$first\t${top5.joinToString(" ")}")
            }
        }
        File(outDir(), "ext_e5_advisory.tsv").writeText(
            "# $runStamp\nsyllable\ttopCandidate\tdictTop5\n" + rows.joinToString("\n") + if (rows.isNotEmpty()) "\n" else ""
        )
        File(outDir(), "ext_e5_summary.txt").writeText(
            "# $runStamp\nE5 — order advisory (report-only)\nsyllables: ${syls.size}\nadvisories: ${rows.size}\n"
        )
        assertTrue("E5 advisory written", File(outDir(), "ext_e5_advisory.tsv").exists())
    }
}
