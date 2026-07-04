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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.ln

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

        val sylList = syls
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
                // A variant vouches its own singles AND its prefix-completions' singles: with f_h on,
                // typing "fa" legitimately completes the variant "ha" to 好(hao) — the fuzzy analogue
                // of the vouched non-fuzzy prefix completion (按 covering the typed "a"). The unified
                // completion pool lets such singles surface where the old source-layered budget starved
                // them, so the ok-set includes every syllable EXTENDING a variant. Leak detection is
                // intact: a char reading a non-extension (等=deng for fuzzy "fa") is still flagged.
                val okSet = Fuzzy.variants(key, rules).flatMapTo(HashSet()) { v ->
                    sylList.filter { it.startsWith(v) }.flatMap { dictSingles(it) }
                } + allowed(key)
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

    // ================= E5 · candidate-order table (report-only, re-defined by the ordering invariant) =================
    // The old E5 heuristic ("#1 candidate not in the dict's top-5 singles") is NOT the user's ordering
    // principle: a common word/expansion outranking a RARE syllable's singles is the CORRECT direction
    // (常用先于生僻 — den→第二年, m→吗, full-pack chua→出啊 are all compliant). The heuristic rows are
    // kept as a table but each is now ADJUDICATED under the E6 rule; the enforcement lives in
    // [e6_orderingInvariant] (hard gate). This test never fails.
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
                val f = e6RawFreq(dict, s, first)
                val verdict = when {
                    f == null -> "composed-sentence"
                    f >= E6_COMMON -> "compliant: common candidate ahead of the syllable's own singles (常用先于生僻)"
                    else -> "band ($f): neither clearly rare nor clearly common — no E6 constraint"
                }
                rows.add("$s\t$first\t${top5.joinToString(" ")}\t$verdict")
            }
        }
        File(outDir(), "ext_e5_advisory.tsv").writeText(
            "# $runStamp\nsyllable\ttopCandidate\tdictTop5\ttc2Verdict\n" + rows.joinToString("\n") + if (rows.isNotEmpty()) "\n" else ""
        )
        File(outDir(), "ext_e5_summary.txt").writeText(
            "# $runStamp\nE5 — order advisory (report-only)\nsyllables: ${syls.size}\nadvisories: ${rows.size}\n"
        )
        assertTrue("E5 advisory written", File(outDir(), "ext_e5_advisory.tsv").exists())
    }

    // ================= E6 · ordering invariant — HARD GATE =================
    //
    // Invariant: 生僻不得排在非生僻之前. Scope = the COLD-START BASE ORDER of the user-visible strip
    // ([PinyinDecoder.decodeCovered]) — empty committed context, no [UserModel], no octagram. The
    // char-bigram LM is constructed (production edgeN) but is inert with no preceding context; bigram/
    // octagram/user-learning reranking is legitimate CONTEXT/EVIDENCE behaviour and is exempt from the
    // invariant (e.g. the wanxiang octagram lifts 皮袄 for "piao" by corpus collocation weight — that is
    // model evidence of commonness, not a rarity inversion).
    //
    // Rarity criterion, derived from the merged (simplified-only) frequency distribution (letter dict):
    //   RARE   = matched raw freq <= E6_RARE (100)  — below the seed build floor (--min-freq 400): the
    //            seed carries such entries only via the completeness fills; on the full pack freq<=100
    //            is the 47.8% long tail users perceive as 生僻;
    //   COMMON = matched raw freq >= E6_COMMON (1000) — around the seed's p90 (p75=828, p90=1318); the
    //            10x band between the two avoids litigating near-ties (band entries are unconstrained).
    //
    // O1: within a coverage bucket, the input-reading singles (∈ exact(bucketKey)) must be in
    //     non-increasing matched-key frequency order (归并后频次降序).
    // O2: within a coverage bucket, no RARE candidate may precede any COMMON candidate (字或词).
    // O2G (cross-coverage): the remainder layer — everything from the decoder-reported layer
    //     boundary ([PinyinDecoder.decodeCoveredLayered]) onward — is ONE global commonness order,
    //     so no RARE candidate may precede a later COMMON candidate there REGARDLESS of coverage
    //     bucket (a longer-coverage group's rare tail must not ride ahead of a shorter group's
    //     common singles, the expanded-grid inversion). The anchor is the TRUE boundary, not an
    //     inference from coverage values: the remainder layer itself emits whole-input-coverage
    //     candidates (a lone syllable's spilled singles), so a coverage-based anchor would silently
    //     exempt exactly the group a front-loading regression would misplace. The completion pool
    //     ahead of the boundary is exempt: completions legitimately outrank shorter-coverage
    //     leftovers, and the pool has its own O2 bucket gate. Re-emitted (word, shorter-coverage)
    //     duplicates rank INSIDE the same global order now, so O2G does not exempt them (the
    //     per-bucket rescue exemption below remains for the bucket gates only). Alias singles keep
    //     the bucket-gate convention: rarity is judged at the RAW alias frequency while production
    //     positions them at the e^-ALIAS_PENALTY discount, so an alias single raw-COMMON by less
    //     than e^3.5 over the COMMON line sits below where its raw frequency suggests; the live
    //     alias surface (en→ng: 嗯) is orders of magnitude above that band and the full-scale
    //     sweeps would surface any future alias that is not.
    // Exemptions, precise: list position 0 (the pinned best whole-input interpretation — the commit
    // default, not a ranked alternative; a lone rare syllable like chua rightly shows 欻 there) and
    // candidates whose frequency is unresolvable from the bucket key's exact/prefix/alias/jianpin views
    // (= composed sentences, which are not dictionary words at all). The alias singles (嗯 for en/36)
    // count at their RAW frequency for rarity but sit at the penalty-discounted position — always above
    // the rare tail, so both O1 (they are not input-reading singles) and O2 stay satisfied.
    //
    // Coverage: all 415 runtime syllables on BOTH key spaces (26-key letters; 9-key digit strings with
    // the production aliasDict wiring) + an n=2 pair sample; the full-pack config re-runs this whole
    // class via the asset-swap mechanism (audit reads src/main/assets).
    private fun e6Decoder(letters: Boolean): PinyinDecoder =
        if (letters) PinyinDecoder(
            dict, CharBigramLM.fromFile(lmFile),
            initialsDict = if (jianpinFile.exists()) BinaryDict.fromFile(jianpinFile) else null,
        )
        else PinyinDecoder(t9Dict, CharBigramLM.fromFile(lmFile), aliasDict = dict)

    private val E6_RARE = 100
    private val E6_COMMON = 1000

    /** Matched frequency of [word] for bucket [key] — the frequency of the INSTANCE the ranking used,
     *  not the word's global commonness. Resolution order mirrors the decoder's own source priority:
     *  1. the exact-key entry (a heteronym ranks by the READING it matched: the full pack keys 错
     *     under digit 28 at freq 1 for its rare cù reading — in the 28 tier it IS a rare candidate,
     *     exactly like 欸 under "ai"; judging it by the global cuò frequency would demand rarity
     *     inversions the reading-frequency order O1 itself prescribes);
     *  2. the alias target's exact entry (raw frequency — the discount is a position, not a rarity);
     *  3. the main-dict prefix view, then the jianpin prefix view (completion words like 恩怨 for
     *     "en": their matched instance is the prefix entry).
     *  null = no view knows the word (a composed sentence) — exempt.
     *  Views are cached per bucket key: one prefix scan per key, not per candidate. */
    private class E6View(val exact: Map<String, Int>, val alias: Map<String, Int>, val prefix: Map<String, Int>)
    private val e6KeyView = HashMap<String, E6View>()
    private fun e6RawFreq(source: BinaryDict, key: String, word: String): Int? {
        val view = e6KeyView.getOrPut((if (source === dict) "L:" else "D:") + key) {
            fun collect(entries: List<BinaryDict.WordFreq>): Map<String, Int> {
                val m = HashMap<String, Int>()
                for (wf in entries) if (wf.freq > (m[wf.word] ?: -1)) m[wf.word] = wf.freq
                return m
            }
            val aliasEntries = (if (key.firstOrNull() in '2'..'9') PinyinDecoder.T9_INPUT_ALIASES[key].orEmpty()
            else PinyinDecoder.INPUT_ALIASES[key].orEmpty()).flatMap { dict.exact(it) }
            val prefixEntries = source.prefixByFreq(key, E6_PREFIX_SCAN) +
                (if (jianpinFile.exists()) jianpin.prefixByFreq(key, E6_PREFIX_SCAN) else emptyList())
            E6View(collect(source.exact(key)), collect(aliasEntries), collect(prefixEntries))
        }
        return view.exact[word] ?: view.alias[word] ?: view.prefix[word]
    }

    private val jianpin: BinaryDict by lazy { BinaryDict.fromFile(jianpinFile) }
    private val E6_PREFIX_SCAN = 8192

    /** E6 verdicts for one decoded list. Returns violation rows ("O1"/"O2" + detail). */
    private fun e6Check(source: BinaryDict, input: String, layered: Pair<List<Cand>, Int>): List<String> {
        val (cands, remainderStart) = layered
        val rows = ArrayList<String>()
        val buckets = LinkedHashMap<Int, MutableList<Pair<String, Int?>>>()
        val atLonger = HashMap<String, Int>() // word -> longest coverage seen so far (list order)
        for ((pos, c) in cands.withIndex()) {
            if (pos == 0) continue // pinned best whole-input interpretation
            // Rescue re-emissions are exempt: a word already shown at a LONGER coverage re-appears
            // at its shorter coverage in the trailing recovery zone so it stays PICKABLE there (the
            // dedup key is word+coverage) — that zone restores reachability, it is not a ranking claim.
            if ((atLonger[c.word] ?: -1) > c.coveredLen) continue
            atLonger[c.word] = maxOf(atLonger[c.word] ?: -1, c.coveredLen)
            val key = input.substring(0, c.coveredLen.coerceIn(1, input.length))
            buckets.getOrPut(c.coveredLen) { ArrayList() }.add(c.word to e6RawFreq(source, key, c.word))
        }
        for ((cov, ws) in buckets) {
            val key = input.substring(0, cov.coerceIn(1, input.length))
            // O2: no rare before a later common
            var commonAfter: String? = null
            for (i in ws.indices.reversed()) {
                val (w, f) = ws[i]
                if (f != null && f <= E6_RARE && commonAfter != null) {
                    rows.add("$input\tcov$cov\tO2\t$w@$f before ${commonAfter}")
                }
                if (f != null && f >= E6_COMMON) commonAfter = w
            }
            // O1: input-reading singles in non-increasing matched-key frequency order
            val readingFreq = source.exact(key).filter { isSingleChar(it.word) }.associate { it.word to it.freq }
            var prev = Int.MAX_VALUE
            for ((w, _) in ws) {
                val f = readingFreq[w] ?: continue
                if (f > prev) rows.add("$input\tcov$cov\tO1\t$w@$f after a lower-freq same-reading single")
                prev = f
            }
        }
        // O2G: cross-coverage rare-before-common over the remainder layer (see the invariant note above),
        // anchored at the decoder-reported layer boundary. Position 0 stays exempt as everywhere in E6.
        val scanFrom = maxOf(remainderStart, 1)
        var commonAfter: String? = null
        for (i in cands.indices.reversed()) {
            if (i < scanFrom) break
            val c = cands[i]
            val key = input.substring(0, c.coveredLen.coerceIn(1, input.length))
            val f = e6RawFreq(source, key, c.word)
            if (f != null && f <= E6_RARE && commonAfter != null) {
                rows.add("$input\ttail\tO2G\t${c.word}@$f(cov${c.coveredLen}) before $commonAfter")
            }
            if (f != null && f >= E6_COMMON) commonAfter = "${c.word}(cov${c.coveredLen})"
        }
        return rows
    }

    @Test fun e6_orderingInvariant_allSyllables_bothKeyspaces() {
        assumeTrue(dictFile.exists() && t9File.exists() && lmFile.exists())
        val syls = runtimeSyllables()
        val dL = e6Decoder(letters = true)
        val dT = e6Decoder(letters = false)
        val rows = ArrayList<String>()
        for (s in syls) {
            rows.addAll(e6Check(dict, s, dL.decodeCoveredLayered(s, 30)))
            val dig = T9Pinyin.toT9(s)
            rows.addAll(e6Check(t9Dict, dig, dT.decodeCoveredLayered(dig, 30)))
        }
        // n=2 sample: 36-led digit pairs (alias surface) + rare-syllable-led letter pairs + controls
        val pairs = listOf(
            "en" to "de", "fo" to "le", "dong" to "shi", "chua" to "de", "den" to "hao",
            "m" to "le", "rua" to "ma", "nou" to "shi", "kei" to "de", "cen" to "hao",
            "ni" to "hao", "wo" to "de", "xian" to "zai", "liang" to "ge", "die" to "de",
        )
        for ((s1, s2) in pairs) {
            rows.addAll(e6Check(dict, s1 + s2, dL.decodeCoveredLayered(s1 + s2, 30)))
            val dig = T9Pinyin.toT9(s1 + s2)
            rows.addAll(e6Check(t9Dict, dig, dT.decodeCoveredLayered(dig, 30)))
        }
        File(outDir(), "ext_e6.tsv").writeText(
            "# $runStamp\ninput\tbucket\tinvariant\tdetail\n" + rows.joinToString("\n") + if (rows.isNotEmpty()) "\n" else ""
        )
        File(outDir(), "ext_e6_summary.txt").writeText(
            "# $runStamp\nE6 — ordering invariant (rare must not precede common; hard gate)\n" +
                "syllables: ${syls.size} x 2 keyspaces + ${pairs.size} pairs x 2\nviolations: ${rows.size}\n"
        )
        assertTrue("E6 ordering violations must be zero: ${rows.take(8)}", rows.isEmpty())
    }

    // ================= E7 · LOCKED/ATOMIC ordering invariant — HARD GATE =================
    //
    // E6 only ever drove the free-typing path ([PinyinDecoder.decodeCovered]); the LOCKED path
    // ([decodeCoveredAtomic], which both keyboards funnel a committed reading through — see
    // BothKeyboardsAtomicFixTest) had its own hand-rolled source layering (first-syllable singles, then the
    // composed alternative sentences dead last), so a rare single preceded a common multi-character candidate
    // there while E6 stayed green. E7 extends the SAME O1/O2 rare-before-common gate to that locked decode.
    //
    // Enumeration (mechanical, NOT sampled): ALL 415² ordered syllable PAIRS — every first syllable ×
    // every second syllable — the exhaustive lockable 2-syllable set, on BOTH keyspaces (26-key letters and
    // 9-key digit strings with the production aliasDict wiring). An n=3 sweep locks every first syllable
    // against representative common tails so the invariant is exercised past two syllables for the whole
    // syllable universe. The full-pack config re-runs this entire class through the asset-swap mechanism.
    //
    // O1: within the first-syllable single bucket, the input-reading singles (∈ exact(s1)) are non-increasing
    //     in matched frequency (alias singles — the en→ng alias — are not input-reading singles, exempt as in E6).
    // O2: no RARE single (matched reading frequency ≤ E6_RARE) precedes a COMMON multi-character candidate
    //     (≥2 codepoints whose RAREST covered reading-character frequency ≥ E6_COMMON). List position 0 (the
    //     pinned best interpretation / commit default) is exempt, exactly as in E6.
    // W (word layer first — the locked-path layering rule): NO multi-character candidate may rank after ANY
    //     single. The locked grid is words (pinned best, exact-word tier, composed alternatives), THEN the
    //     lossless single layer — the two never interleave. This subsumes O2's direction (a common word after
    //     a rare single is first of all a word after a single) and holds for common and rare words alike.
    // L (lossless, oracle INDEPENDENT of the production ranking metric): every native single of s1 (∈ exact(s1))
    //     must be emitted somewhere in the locked grid — the first-syllable homophone layer stays whole. This
    //     is a raw set-membership check against the dictionary, so — unlike O2, whose commonness partition is a
    //     monotone function of the same per-syllable frequency the production sort uses — it catches a mis-sized
    //     budget / dropped tail that a pure ranking re-derivation could not.
    // Native single char -> frequency per (source, key), memoised: the exhaustive sweep asks the same 415 first
    // syllables 415 times each and probes hundreds of single candidates per pair, so one scan of exact(key) is
    // reused instead of re-iterating it per candidate (which is a full-pack hot path over thousand-entry keys).
    private val nativeSinglesMap = HashMap<String, Map<String, Int>>()
    private fun nativeSinglesOf(source: BinaryDict, key: String): Map<String, Int> =
        nativeSinglesMap.getOrPut((if (source === dict) "L:" else "D:") + key) {
            val m = HashMap<String, Int>()
            for (wf in source.exact(key)) if (isSingleChar(wf.word)) m.putIfAbsent(wf.word, wf.freq)
            m
        }
    private fun nativeSingleFreq(source: BinaryDict, key: String, ch: String): Int? = nativeSinglesOf(source, key)[ch]

    /** O1+O2+W (+ optionally L) violation rows for one locked decode. [sylKeys] are the locked syllables in the
     *  decoder's own keyspace; codepoint i of a multi-char candidate reads covered syllable i. [checkLossless]
     *  is set only for the letter keyspace: there a valid syllable chunk stays atomic under a lock so
     *  sylKeys[0] IS the decoder's first syllable. On the T9 keyspace an ambiguous digit group (e.g. 3364 =
     *  deng/feng) can re-segment inside the chunk, so sylKeys[0] is not the emitted first-syllable key and the
     *  set-membership L check does not apply — and the locked path is letter-only in production anyway (both
     *  keyboards funnel a committed reading through the letter decodeCoveredAtomic; see BothKeyboardsAtomicFixTest). */
    private fun lockedOrderViolations(
        source: BinaryDict,
        sylKeys: List<String>,
        cands: List<Cand>,
        checkLossless: Boolean,
    ): List<String> {
        val rows = ArrayList<String>()
        val cum = IntArray(sylKeys.size + 1)
        for (i in sylKeys.indices) cum[i + 1] = cum[i] + sylKeys[i].length
        fun coveredSyls(cov: Int): Int { for (j in sylKeys.indices) if (cum[j + 1] == cov) return j + 1; return -1 }
        fun codepoints(w: String): List<String> {
            val o = ArrayList<String>(); var i = 0
            while (i < w.length) { val c = w.codePointAt(i); o.add(String(Character.toChars(c))); i += Character.charCount(c) }
            return o
        }
        val tag = sylKeys.joinToString("+")
        var rareSingleSeen: String? = null
        var anySingleSeen: String? = null
        val singleBucket = ArrayList<Pair<String, Int>>() // (char, native freq), list order
        val emittedFirstSingles = HashSet<String>()
        for ((pos, c) in cands.withIndex()) {
            val ncp0 = c.word.codePointCount(0, c.word.length)
            if (ncp0 == 1 && coveredSyls(c.coveredLen) == 1) emittedFirstSingles.add(c.word) // for L (incl. pos 0)
            if (pos == 0) continue // pinned best interpretation (commit default)
            val ncp = ncp0
            val ks = coveredSyls(c.coveredLen)
            // W: the word layer must be COMPLETE before the first single (no interleaving at all)
            if (ncp == 1 && anySingleSeen == null) anySingleSeen = "${c.word}@pos$pos"
            if (ncp >= 2 && anySingleSeen != null) {
                rows.add("$tag\tW\t${c.word} (multi-char) after single $anySingleSeen")
            }
            if (ncp == 1 && ks == 1) {
                val native = nativeSingleFreq(source, sylKeys[0], c.word) // O1 counts input-reading singles only
                if (native != null) singleBucket.add(c.word to native)
                val matched = e6RawFreq(source, sylKeys[0], c.word) // rarity by matched (raw) frequency
                if (matched != null && matched <= E6_RARE) rareSingleSeen = "${c.word}@$matched"
            } else if (ncp >= 2 && ks >= 1) {
                val chars = codepoints(c.word)
                var mn = Int.MAX_VALUE
                var resolvable = true
                for (i in 0 until minOf(ncp, ks)) {
                    val f = e6RawFreq(source, sylKeys.getOrElse(i) { "" }, chars[i])
                    if (f == null) { resolvable = false; break }
                    if (f < mn) mn = f
                }
                if (resolvable && mn >= E6_COMMON && rareSingleSeen != null) {
                    rows.add("$tag\tO2\t${c.word}(rarestChar=$mn) after rare single $rareSingleSeen")
                }
            }
        }
        var prev = Int.MAX_VALUE
        for ((w, f) in singleBucket) {
            if (f > prev) rows.add("$tag\tO1\t$w@$f after a lower-freq same-reading single")
            prev = f
        }
        // L: every native single of the first syllable must survive into the grid (lossless first-syllable layer)
        if (checkLossless) for (w in nativeSinglesOf(source, sylKeys[0]).keys) {
            if (w !in emittedFirstSingles) rows.add("$tag\tL\t$w native single of ${sylKeys[0]} dropped from the locked grid")
        }
        return rows
    }

    private fun lockedLetter(d: PinyinDecoder, s1: String, vararg rest: String): List<String> {
        val syls = listOf(s1, *rest)
        val input = syls.joinToString("")
        val cuts = HashSet<Int>(); var acc = 0
        for (k in 0 until syls.size - 1) { acc += syls[k].length; cuts.add(acc) }
        return lockedOrderViolations(dict, syls, d.decodeCoveredAtomic(input, 30, cuts), checkLossless = true)
    }

    private fun lockedDigit(d: PinyinDecoder, s1: String, vararg rest: String): List<String> {
        val syls = listOf(s1, *rest).map { T9Pinyin.toT9(it) }
        val input = syls.joinToString("")
        val cuts = HashSet<Int>(); var acc = 0
        for (k in 0 until syls.size - 1) { acc += syls[k].length; cuts.add(acc) }
        return lockedOrderViolations(t9Dict, syls, d.decodeCoveredAtomic(input, 30, cuts), checkLossless = false)
    }

    @Test fun e7_lockedOrderingInvariant_allPairs_bothKeyspaces() {
        assumeTrue("heavy sweep gated: set AEGIS_AUDIT_FULL=1", fullEnabled())
        assumeTrue(dictFile.exists() && t9File.exists() && lmFile.exists())
        val syls = runtimeSyllables()
        val dL = e6Decoder(letters = true)
        val dT = e6Decoder(letters = false)
        val rows = ArrayList<String>()
        var pairsChecked = 0L
        var done = 0
        // n=2: ALL 415² ordered pairs, both keyspaces
        for (s1 in syls) {
            for (s2 in syls) {
                rows.addAll(lockedLetter(dL, s1, s2))
                rows.addAll(lockedDigit(dT, s1, s2))
                pairsChecked++
            }
            done += syls.size
            if (done % (syls.size * 50) == 0) println("[E7] ~$done/${syls.size * syls.size}")
        }
        // n=3: every first syllable against representative common tails (past two syllables, whole universe)
        var triplesChecked = 0L
        val tails = listOf("shi" to "jian", "de" to "shi", "hao" to "de", "zhong" to "guo")
        for (s1 in syls) for ((a, b) in tails) {
            rows.addAll(lockedLetter(dL, s1, a, b))
            rows.addAll(lockedDigit(dT, s1, a, b))
            triplesChecked++
        }
        writeTsv(File(outDir(), "ext_e7.tsv"), rows.map { r ->
            val p = r.split("\t"); Fail(p.getOrElse(0) { "" }, "locked", p.getOrElse(1) { "" }, "", "", p.getOrElse(2) { "" })
        })
        summary(File(outDir(), "ext_e7_summary.txt"), "E7 — locked/atomic ordering invariant (O1+O2+W, hard gate)",
            "pairs (both keyspaces): $pairsChecked; triples: $triplesChecked",
            rows.map { r -> val p = r.split("\t"); Fail(p.getOrElse(0) { "" }, "locked", p.getOrElse(1) { "" }, "", "", p.getOrElse(2) { "" }) })
        assertTrue("E7 locked ordering violations must be zero (${rows.size}): ${rows.take(8)}", rows.isEmpty())
    }

    // Always-on companion (NOT gated): the 415² sweep above runs only under AEGIS_AUDIT_FULL, so a default CI
    // run would leave the locked path with no ordering gate. This representative subset — first syllables that
    // carry a long rare tail (where the rare-before-common inversion surfaces) plus common controls, each locked
    // against common tails, on both keyspaces — runs every build and asserts O1+O2+W+L = 0.
    @Test fun e7b_lockedOrderingInvariant_representative_alwaysOn() {
        assumeTrue(dictFile.exists() && t9File.exists() && lmFile.exists())
        val dL = e6Decoder(letters = true)
        val dT = e6Decoder(letters = false)
        val firstSyllables = listOf(
            "ce", "ci", "chai", "shi", "xian", "ni", "wo", "bu", "de", "hao", "ma", "zhong", "guo",
            "fo", "den", "chua", "rua", "nou", "kei", "cen", "m", "die", "liang", "en", "jiu",
        )
        val tails = listOf("shi", "de", "hao", "jian")
        val rows = ArrayList<String>()
        for (s1 in firstSyllables) for (s2 in tails) {
            rows.addAll(lockedLetter(dL, s1, s2))
            rows.addAll(lockedDigit(dT, s1, s2))
        }
        assertTrue("E7b locked ordering violations must be zero (${rows.size}): ${rows.take(8)}", rows.isEmpty())
    }

    // ================= E8 · SELF-CREATED USER WORDS merged — ordering invariant + recall =================
    //
    // The real-user-dictionary feature injects self-created words (words the user assembled character by
    // character that the dictionary does not carry) into the decode as candidates. This class proves that
    // merging them in does NOT break the O1/O2 free-typing gate (E6), the O1/O2/L locked gate (E7), or the
    // alias presence (TEN), and that each merged word is actually RECALLED for its reading on both keyspaces.
    //
    // The user words are generated MECHANICALLY from the syllable universe — every first syllable's top single
    // joined with a spread of tail syllables (+ a three-syllable form) — kept only where the reading has no
    // whole-word dict key (a genuine self-created word). No single word is special-cased. The checks reuse the
    // SAME e6Check / lockedOrderViolations oracles the no-user audits use, so the guarantee is against the exact
    // audited invariants, now with a populated model wired into the production decoder.

    private data class GenWord(val syllables: List<String>, val word: String) {
        val reading: String get() = syllables.joinToString("")
    }

    private fun topSingle(s: String): String? =
        dict.exact(s).filter { isSingleChar(it.word) }.maxByOrNull { it.freq }?.word

    /** Diverse self-created words from the syllable universe: top-single(s1) + top-single(tail) for a spread of
     *  tails (common, mid, and the alias-bearing en), plus one three-syllable form per first syllable. Only
     *  readings the dictionary has no whole-word key for are kept, so each is a genuine self-created word. */
    private fun generatedUserWords(syls: List<String>, tails: List<String>): List<GenWord> {
        val out = ArrayList<GenWord>()
        val c2Shi = topSingle("shi")
        val c3Jian = topSingle("jian")
        for (s1 in syls) {
            val c1 = topSingle(s1) ?: continue
            for (s2 in tails) {
                val c2 = topSingle(s2) ?: continue
                val word = c1 + c2
                if (dict.exact(s1 + s2).none { it.word == word }) out.add(GenWord(listOf(s1, s2), word))
            }
            if (c2Shi != null && c3Jian != null) {
                val word3 = c1 + c2Shi + c3Jian
                if (dict.exact(s1 + "shi" + "jian").none { it.word == word3 }) {
                    out.add(GenWord(listOf(s1, "shi", "jian"), word3))
                }
            }
        }
        return out
    }

    private fun populatedModel(words: List<GenWord>): UserModel =
        UserModel().apply { for (gw in words) recordWord(gw.reading, gw.word, 1L, incrementCount = true) }

    // Cached heavy assets so a per-case decoder build (E9 constructs one per user model) does not re-read the LM.
    private val lmModel: CharBigramLM by lazy { CharBigramLM.fromFile(lmFile) }
    private val jianpinDict: BinaryDict? by lazy { if (jianpinFile.exists()) BinaryDict.fromFile(jianpinFile) else null }

    private fun userDecoder(letters: Boolean, um: UserModel): PinyinDecoder =
        if (letters) PinyinDecoder(dict, lmModel, userModel = um, initialsDict = jianpinDict)
        else PinyinDecoder(t9Dict, lmModel, userModel = um, aliasDict = dict)

    /** Free-typing ordering (E6 oracle) over every generated reading — where injection is actually exercised —
     *  plus every single syllable (where a multi-syllable user word can never match, so the result must equal
     *  the no-user baseline: injection is inert where it should be). Both keyspaces. Always-on representative;
     *  the full generated set is gated. */
    private fun runFreeTypingOrdering(words: List<GenWord>): List<String> {
        val um = populatedModel(words)
        val dL = userDecoder(letters = true, um)
        val dT = userDecoder(letters = false, um)
        val rows = ArrayList<String>()
        for (s in runtimeSyllables()) {
            rows.addAll(e6Check(dict, s, dL.decodeCoveredLayered(s, 30)))
            val dig = T9Pinyin.toT9(s)
            rows.addAll(e6Check(t9Dict, dig, dT.decodeCoveredLayered(dig, 30)))
        }
        for (gw in words) {
            rows.addAll(e6Check(dict, gw.reading, dL.decodeCoveredLayered(gw.reading, 30)))
            val dig = T9Pinyin.toT9(gw.reading)
            rows.addAll(e6Check(t9Dict, dig, dT.decodeCoveredLayered(dig, 30)))
        }
        return rows
    }

    @Test fun e8_userWords_freeTypingOrdering_representative_alwaysOn() {
        assumeTrue(dictFile.exists() && t9File.exists() && lmFile.exists())
        val words = generatedUserWords(runtimeSyllables(), listOf("shi", "en"))
        assertTrue("generator produced a diverse word set", words.size > 200)
        val rows = runFreeTypingOrdering(words)
        assertTrue("E8 free-typing ordering with user words merged must be zero (${rows.size}): ${rows.take(8)}", rows.isEmpty())
    }

    @Test fun e8_userWords_freeTypingOrdering_full() {
        assumeTrue("heavy sweep gated: set AEGIS_AUDIT_FULL=1", fullEnabled())
        assumeTrue(dictFile.exists() && t9File.exists() && lmFile.exists())
        val words = generatedUserWords(runtimeSyllables(), listOf("shi", "de", "hao", "jian", "guo", "cong", "en"))
        val rows = runFreeTypingOrdering(words)
        writeTsv(File(outDir(), "ext_e8.tsv"), rows.map { Fail("", "user", "O", "", "", it) })
        summary(File(outDir(), "ext_e8_summary.txt"), "E8 — self-created words merged, free-typing ordering",
            "generated words: ${words.size}; single syllables + generated readings x 2 keyspaces", rows.map { Fail("", "user", "O", "", "", it) })
        assertTrue("E8 free-typing ordering violations must be zero (${rows.size}): ${rows.take(8)}", rows.isEmpty())
    }

    private fun runLockedOrdering(words: List<GenWord>): List<String> {
        val um = populatedModel(words)
        val dL = userDecoder(letters = true, um)
        val dT = userDecoder(letters = false, um)
        val rows = ArrayList<String>()
        for (gw in words) {
            val s = gw.syllables
            rows.addAll(lockedLetter(dL, s[0], *s.drop(1).toTypedArray()))
            rows.addAll(lockedDigit(dT, s[0], *s.drop(1).toTypedArray()))
        }
        return rows
    }

    @Test fun e8_userWords_lockedOrdering_representative_alwaysOn() {
        assumeTrue(dictFile.exists() && t9File.exists() && lmFile.exists())
        val words = generatedUserWords(runtimeSyllables(), listOf("shi", "en"))
        val rows = runLockedOrdering(words)
        assertTrue("E8 locked ordering with user words merged must be zero (${rows.size}): ${rows.take(8)}", rows.isEmpty())
    }

    @Test fun e8_userWords_lockedOrdering_full() {
        assumeTrue("heavy sweep gated: set AEGIS_AUDIT_FULL=1", fullEnabled())
        assumeTrue(dictFile.exists() && t9File.exists() && lmFile.exists())
        val words = generatedUserWords(runtimeSyllables(), listOf("shi", "de", "hao", "jian", "guo", "cong", "en"))
        val rows = runLockedOrdering(words)
        assertTrue("E8 locked ordering violations must be zero (${rows.size}): ${rows.take(8)}", rows.isEmpty())
    }

    /** Recall: each merged self-created word must appear for its reading on the free-typing letter path, the
     *  T9 path, and the locked path — otherwise the feature is broken even if the invariants pass. */
    @Test fun e8_userWords_recall_bothKeyspaces_andLocked() {
        assumeTrue(dictFile.exists() && t9File.exists() && lmFile.exists())
        val words = generatedUserWords(runtimeSyllables(), listOf("shi", "en"))
        val um = populatedModel(words)
        val dL = userDecoder(letters = true, um)
        val dT = userDecoder(letters = false, um)
        val misses = ArrayList<String>()
        for (gw in words) {
            if (gw.word !in dL.decodeCovered(gw.reading, 30).map { it.word }) misses.add("letters:${gw.reading}=${gw.word}")
            val dig = T9Pinyin.toT9(gw.reading)
            if (gw.word !in dT.decodeCovered(dig, 30).map { it.word }) misses.add("t9:$dig=${gw.word}")
            val cuts = HashSet<Int>(); var acc = 0
            for (k in 0 until gw.syllables.size - 1) { acc += gw.syllables[k].length; cuts.add(acc) }
            if (gw.word !in dL.decodeCoveredAtomic(gw.reading, 30, cuts).map { it.word }) misses.add("locked:${gw.reading}=${gw.word}")
        }
        assertTrue("every merged self-created word must be recalled (${misses.size} misses): ${misses.take(8)}", misses.isEmpty())
    }

    // ================= E9 · POSITION-0 GUARD, margin-aware — HARD GATE =================
    //
    // E6/E7/E8 all EXEMPT list position 0 (`e6Check`/`lockedOrderViolations` do `if (pos == 0) continue`), so an
    // injection that is fully O1/O2/L-safe can still seize #0 — the commit default. The guard bisects the
    // hijack-prone class MECHANICALLY into:
    //
    //  · CANONICAL (structural): the natural #0 (no user model) is a whole dictionary entry for the reading
    //    (是的/你好吗/测过/试试-class). ZERO TOLERANCE: a fresh (count 1) self-created word must never displace
    //    it, on either keyspace.
    //  · STRONG (margin): the natural is a composed sentence whose analytic score margin over the mechanically
    //    constructed fresh challenger exceeds the boost of a single use. Same zero tolerance.
    //  · WEAK: a composed juxtaposition whose margin is within one use's boost — the near-degenerate naturals
    //    (no or floor-level bigram support and/or out-gunned unigrams). A fresh user word MAY overtake these —
    //    the user's own just-assembled word beating a meaningless juxtaposition is desired adaptation — but the
    //    seizer must be exactly the user's word and the former natural must stay reachable (see below).
    //
    // The margin is computed by [MarginOracle], an independent reimplementation of the sentence-path score from
    // raw dictionary frequencies and LM conditionals (the lockVerdict pattern: an oracle that audits the decoder
    // rather than quoting it). Every quantity is mechanical: dict/LM tables, the decoder's own documented
    // penalties, and the single-use boost read from the PUBLIC UserModel API — no free threshold anywhere. A
    // bigram-only binary (observed / above-floor) does NOT bisect this boundary: 是的's own bigram sits BELOW its
    // backoff floor (的 is that common) yet 是的 is absolutely protected (whole-entry), while some flipped
    // juxtapositions carry technically-observed bigrams that are simply out-gunned on unigrams — which is why
    // the criterion is the margin itself, with the bigram term as its dominant component.
    //
    // Reachability of a displaced weak natural (production surfaces): a composed non-winner sentence never rides
    // the free-typing list (only the pinned best interpretation is a composed candidate there), so retention is
    // asserted through the surfaces that DO carry it: every character stays pickable at its syllable position
    // (the lossless per-syllable drill — exactly how a juxtaposition is assembled), and/or the whole string
    // appears among the locked-path alternatives; a natural whose own syllabification disagrees with the
    // displayed segmentation (a resegmentation artifact, e.g. three characters over two displayed syllables)
    // is reachable via explicit boundary input and is verified as exactly that class.

    private fun singlesByFreq(key: String): List<String> =
        dict.exact(key).filter { isSingleChar(it.word) }.sortedByDescending { it.freq }.map { it.word }

    /** Analytic sentence-path score oracle over raw dict/LM tables — independent of the decoder. Mirrors the
     *  audit configuration of [PinyinDecoder.bestSentence]: exact edges (top [PinyinDecoder.EDGE_N] per span)
     *  plus input-alias edges at [PinyinDecoder.ALIAS_PENALTY]; score = Σ(ln f − lnTotal) + λ·logCond at each
     *  internal word boundary (λ = 1, the production default); no context, no octagram, no fuzzy. */
    private inner class MarginOracle(val source: BinaryDict, val t9: Boolean) {
        val lnTotal = ln(source.totalFreq.coerceAtLeast(1).toDouble())
        private fun aliases(key: String): List<String> =
            (if (t9) PinyinDecoder.T9_INPUT_ALIASES[key] else PinyinDecoder.INPUT_ALIASES[key]).orEmpty()
        private fun cps(w: String): List<String> {
            val o = ArrayList<String>(4); var i = 0
            while (i < w.length) { val c = w.codePointAt(i); o.add(String(Character.toChars(c))); i += Character.charCount(c) }
            return o
        }
        /** Best derivation score of the exact string [nat] over [key], or -inf if not derivable. */
        fun natScore(key: String, nat: String): Double {
            val parts = cps(nat)
            val n = key.length; val m = parts.size
            val neg = Double.NEGATIVE_INFINITY
            val dp = Array(n + 1) { DoubleArray(m + 1) { neg } }
            dp[0][0] = 0.0
            for (p in 0 until n) for (i in 0 until m) {
                if (dp[p][i] == neg) continue
                for (q in p + 1..n) {
                    val span = key.substring(p, q)
                    fun tryWords(words: List<BinaryDict.WordFreq>, penalty: Double) {
                        for (wf in words) {
                            val wcps = cps(wf.word)
                            val k = wcps.size
                            if (i + k > m) continue
                            var ok = true
                            for (j in 0 until k) if (wcps[j] != parts[i + j]) { ok = false; break }
                            if (!ok) continue
                            val uni = ln(wf.freq.toDouble()) - lnTotal
                            val bi = if (i == 0) 0.0 else lmModel.logCond(parts[i - 1].codePointAt(0), parts[i].codePointAt(0))
                            val v = dp[p][i] + uni + bi - penalty
                            if (v > dp[q][i + k]) dp[q][i + k] = v
                        }
                    }
                    tryWords(source.exact(span).take(PinyinDecoder.EDGE_N), 0.0)
                    for (a in aliases(span)) tryWords(dict.exact(a).take(PinyinDecoder.EDGE_N), PinyinDecoder.ALIAS_PENALTY)
                }
            }
            return dp[n][m]
        }
        /** Maximin rarest-covered-char frequency of the user word (mirror of the decoder's userWordFreq). */
        private fun uwFreq(key: String, uw: String): Double {
            val parts = cps(uw)
            val n = key.length; val m = parts.size
            val neg = Double.NEGATIVE_INFINITY
            val dp = Array(n + 1) { DoubleArray(m + 1) { neg } }
            dp[0][0] = Double.MAX_VALUE
            for (p in 0 until n) for (i in 0 until m) {
                if (dp[p][i] == neg) continue
                for (q in p + 1..minOf(n, p + PinyinDecoder.MAX_SYLLABLE_KEY_LEN)) {
                    val f = source.exact(key.substring(p, q)).firstOrNull {
                        it.word.codePointCount(0, it.word.length) == 1 && it.word == parts[i]
                    }?.freq ?: continue
                    val v = minOf(dp[p][i], f.toDouble())
                    if (v > dp[q][i + 1]) dp[q][i + 1] = v
                }
            }
            val best = dp[n][m]
            return if (best == neg || best == Double.MAX_VALUE) 1.0 else best.coerceAtLeast(1.0)
        }
        /** The fresh user-word whole-input edge's path score, WITHOUT the usage boost. */
        fun uwScoreNoBoost(key: String, uw: String): Double {
            val ncp = uw.codePointCount(0, uw.length)
            return ln(uwFreq(key, uw).toInt().coerceAtLeast(1).toDouble()) - lnTotal - (ncp - 1) * lnTotal
        }
    }

    /** The single-use boost, read from the public API — the definitional boundary of the count=1 gate. */
    private val boost1: Double by lazy {
        UserModel().apply { recordWord("zz", "占位", 1L, incrementCount = true) }.wordBoost("占位")
    }

    private class Pos0Stats {
        var cases = 0; var canonical = 0; var strong = 0; var weak = 0
        var weakFlips = 0; var weakFlips2cp = 0; var weakFlipsT9 = 0
        var retainFree = 0; var retainPickable = 0; var retainPieces = 0; var retainAtomic = 0
        var retainResegment = 0; var retainJianpinGuess = 0
        var canonicalTieSwap = 0
        var weakFlipUnseenBigram = 0
        var canonicalMarginMin = Double.MAX_VALUE
        val violations = ArrayList<String>()
    }

    /** Sweep [tuples] (each a syllable list forming one reading) through classification + the count=1 gates on
     *  both keyspaces, mutating ONE shared model (record → decode → remove) so no per-case decoder is built. */
    private fun pos0Sweep(tuples: Sequence<List<String>>, progressEvery: Int = 0): Pos0Stats {
        val st = Pos0Stats()
        val um = UserModel()
        val dL = e6Decoder(letters = true)
        val dT = e6Decoder(letters = false)
        val dLu = PinyinDecoder(dict, lmModel, userModel = um, initialsDict = jianpinDict)
        val dTu = PinyinDecoder(t9Dict, lmModel, userModel = um, aliasDict = dict)
        val oracleL = MarginOracle(dict, t9 = false)
        val oracleT = MarginOracle(t9Dict, t9 = true)
        val natTCache = HashMap<String, String?>()
        var done = 0
        for (sylTuple in tuples) {
            done++
            if (progressEvery > 0 && done % progressEvery == 0) println("[E9] ~$done tuples")
            val r = sylTuple.joinToString("")
            val nat = dL.decodeCovered(r, 30).firstOrNull()?.word ?: continue
            if (nat.codePointCount(0, nat.length) < 2) continue
            // mechanically constructed fresh challenger: the syllables' top singles, differing from the natural
            val chars = ArrayList<String>(sylTuple.size)
            var okUw = true
            for ((k, s) in sylTuple.withIndex()) {
                val pick = if (k == sylTuple.lastIndex) {
                    val prefix = chars.joinToString("")
                    singlesByFreq(s).firstOrNull { prefix + it != nat }
                } else singlesByFreq(s).firstOrNull()
                if (pick == null) { okUw = false; break }
                chars.add(pick)
            }
            if (!okUw) continue
            val uw = chars.joinToString("")
            if (uw == nat || dict.exact(r).any { it.word == uw }) continue
            st.cases++
            val isCanonical = dict.exact(r).any { it.word == nat }
            val margin = oracleL.natScore(r, nat) - oracleL.uwScoreNoBoost(r, uw)
            val isStrong = !isCanonical && margin > boost1
            when {
                isCanonical -> { st.canonical++; if (margin < st.canonicalMarginMin) st.canonicalMarginMin = margin }
                isStrong -> st.strong++
                else -> st.weak++
            }
            um.recordWord(r, uw, 1L, incrementCount = true)
            // ---- letter keyspace gate ----
            val listL = dLu.decodeCovered(r, 30).map { it.word }
            val topL = listL.firstOrNull()
            if (topL != nat) {
                when {
                    isCanonical -> {
                        if (canonicalTieSwap(dict, r, nat, topL, uw)) st.canonicalTieSwap++
                        else st.violations.add("canonical-flip letters $r nat=$nat top=$topL uw=$uw")
                    }
                    isStrong -> st.violations.add("strong-flip letters $r nat=$nat top=$topL uw=$uw margin=${"%.2f".format(margin)}")
                    else -> {
                        st.weakFlips++
                        if (nat.codePointCount(0, nat.length) == 2) st.weakFlips2cp++
                        if (topL != uw) st.violations.add("weak-flip-not-user-word letters $r nat=$nat top=$topL uw=$uw")
                        if (!weakNaturalRetained(dLu, dict, t9 = false, r, nat, listL, st)) {
                            st.violations.add("weak-flip-natural-unreachable letters $r nat=$nat uw=$uw")
                        }
                        if (!bigramSupportedAll(nat)) st.weakFlipUnseenBigram++
                    }
                }
            }
            // ---- T9 keyspace gate (classified against ITS OWN natural) ----
            val digits = T9Pinyin.toT9(r)
            val natT = natTCache.getOrPut(digits) { dT.decodeCovered(digits, 30).firstOrNull()?.word }
            if (natT != null) {
                val listT = dTu.decodeCovered(digits, 30).map { it.word }
                val topT = listT.firstOrNull()
                if (topT != natT) {
                    val canonicalT = t9Dict.exact(digits).any { it.word == natT }
                    val strongT = !canonicalT &&
                        (oracleT.natScore(digits, natT) - oracleT.uwScoreNoBoost(digits, uw)) > boost1
                    when {
                        canonicalT -> {
                            if (canonicalTieSwap(t9Dict, digits, natT, topT, uw)) st.canonicalTieSwap++
                            else st.violations.add("canonical-flip t9 $digits nat=$natT top=$topT uw=$uw")
                        }
                        strongT -> st.violations.add("strong-flip t9 $digits nat=$natT top=$topT uw=$uw")
                        else -> {
                            st.weakFlipsT9++
                            if (topT != uw) st.violations.add("weak-flip-not-user-word t9 $digits nat=$natT top=$topT uw=$uw")
                            if (!weakNaturalRetained(dTu, t9Dict, t9 = true, digits, natT, listT, st)) {
                                st.violations.add("weak-flip-natural-unreachable t9 $digits nat=$natT uw=$uw")
                            }
                        }
                    }
                }
            }
            um.removeWord(uw)
        }
        return st
    }

    /** An exact-frequency TIE between two whole dictionary words of the same key: the pinned best swings
     *  between candidates the model literally cannot distinguish (strict score comparison + map iteration
     *  order), and recording a user word may perturb that order. The default remains a canonical dictionary
     *  word of identical frequency and the user's word seized nothing — a disclosed class, not a hijack. */
    private fun canonicalTieSwap(source: BinaryDict, key: String, nat: String, top: String?, uw: String): Boolean {
        if (top == null || top == uw) return false
        val natF = source.exact(key).firstOrNull { it.word == nat }?.freq ?: return false
        val topF = source.exact(key).firstOrNull { it.word == top }?.freq ?: return false
        return natF == topF
    }

    /** Retention of a displaced weak natural, on EITHER keyspace: free list; per-syllable pickability under
     *  the displayed segmentation; piecewise assembly (each derivation-edge word offered while its span leads
     *  the remaining buffer — the production partial-pick flow, covering multi-character word pieces such as a
     *  two-character word keyed under a single syllable); locked-path alternatives under the displayed
     *  boundaries; for a natural whose own derivation boundaries DIFFER from the displayed segmentation (a
     *  resegmentation artifact) an end-to-end proof that forcing ITS boundaries (the explicit separator route)
     *  surfaces it; and — letter keyspace only, POSITIVELY VERIFIED — the initials-abbreviation guess class:
     *  a natural underivable from exact/alias syllable words that IS derivable once the initials dictionary's
     *  edges are admitted. Such a sentence exists on exactly one production surface (the pinned best guess) by
     *  construction — losing #0 to ANYTHING (another dict word or a user word alike) removes it, a property of
     *  the initials channel that predates user words — so it is disclosed as its own counted class. A natural
     *  deriving from NO channel at all fails loud instead of being silently excused. */
    private fun weakNaturalRetained(d: PinyinDecoder, source: BinaryDict, t9: Boolean, key: String, nat: String, list: List<String>, st: Pos0Stats): Boolean {
        if (nat in list) { st.retainFree++; return true }
        val displayed = d.syllables(key)
        var pick = displayed.isNotEmpty() && nat.codePointCount(0, nat.length) == displayed.size
        if (pick) {
            var ci = 0
            for ((idx, _) in displayed.withIndex()) {
                if (ci >= nat.length) { pick = false; break }
                val ch = String(Character.toChars(nat.codePointAt(ci)))
                if (ch !in d.homophonesAt(key, idx)) { pick = false; break }
                ci += Character.charCount(nat.codePointAt(ci))
            }
        }
        if (pick) { st.retainPickable++; return true }
        // syllable spans are in INPUT units on both keyspaces (letters / digits), so span ends are the cuts
        val cutsDisplayed = displayed.dropLast(1).mapTo(HashSet()) { it.end }
        val parse = natParseEdges(source, t9, includeJianpin = false, key, nat)
        if (parse != null && parse.all { (s, _, w) -> w in d.decodeCovered(key.substring(s), 30).map { c -> c.word } }) {
            st.retainPieces++; return true
        }
        if (nat in d.decodeCoveredAtomic(key, 30, cutsDisplayed).map { it.word }) { st.retainAtomic++; return true }
        if (parse == null) {
            if (!t9 && natParseEdges(source, t9, includeJianpin = true, key, nat) != null) {
                st.retainJianpinGuess++
                return true
            }
            return false // derivable from no channel at all — a real, loud retention loss
        }
        // A resegmentation artifact (its own boundaries differ from the displayed segmentation) is reachable by
        // typing those boundaries explicitly. Prove it end-to-end on the separator-carrying buffer: an
        // all-singles parse assembles through the LOSSLESS per-position homophone layer (each char must be
        // offered at its position); a parse with word edges must surface whole through the atomic decode there.
        val cutsOwn = parse.dropLast(1).mapTo(sortedSetOf()) { it.second }
        if (cutsOwn != cutsDisplayed) {
            if (parse.all { it.third.codePointCount(0, it.third.length) == 1 }) {
                val sep = StringBuilder(key)
                for (c in cutsOwn.reversed()) sep.insert(c, PinyinDecoder.SEP)
                val sepInput = sep.toString()
                var ok = true
                for ((idx, edge) in parse.withIndex()) {
                    if (edge.third !in d.homophonesAt(sepInput, idx)) { ok = false; break }
                }
                if (ok) { st.retainResegment++; return true }
            }
            if (nat in d.decodeCoveredAtomic(key, 30, cutsOwn).map { it.word }) { st.retainResegment++; return true }
        }
        return false
    }

    /** A derivation of [nat] over [key] as a list of (spanStart, spanEnd, word) edges (words of [source] over
     *  exact-key spans, input aliases included, optionally the initials dictionary), preferring the parse with
     *  the MOST edges (finest boundaries — the assembled route), or null when no such derivation exists.
     *  Backtrace inconsistencies fail LOUD (they would mean the DP itself is broken, and silently returning
     *  null here would convert retention failures into passes). */
    private fun natParseEdges(source: BinaryDict, t9: Boolean, includeJianpin: Boolean, key: String, nat: String): List<Triple<Int, Int, String>>? {
        val parts = ArrayList<String>(4)
        var i = 0
        while (i < nat.length) { val c = nat.codePointAt(i); parts.add(String(Character.toChars(c))); i += Character.charCount(c) }
        val n = key.length; val m = parts.size
        val edges = Array(n + 1) { IntArray(m + 1) { -1 } }   // best edge count to (p, i)
        fun spanWords(span: String): List<BinaryDict.WordFreq> =
            source.exact(span) +
                (if (t9) PinyinDecoder.T9_INPUT_ALIASES[span] else PinyinDecoder.INPUT_ALIASES[span])
                    .orEmpty().flatMap { dict.exact(it) } +
                (if (includeJianpin) jianpinDict?.exact(span).orEmpty() else emptyList())
        edges[0][0] = 0
        for (p in 0 until n) for (ii in 0 until m) {
            if (edges[p][ii] < 0) continue
            for (q in p + 1..n) {
                for (wf in spanWords(key.substring(p, q))) {
                    val w = wf.word
                    val k = w.codePointCount(0, w.length)
                    if (ii + k > m) continue
                    var ok = true
                    var ci = 0
                    for (j in 0 until k) {
                        if (String(Character.toChars(w.codePointAt(ci))) != parts[ii + j]) { ok = false; break }
                        ci += Character.charCount(w.codePointAt(ci))
                    }
                    if (!ok) continue
                    if (edges[p][ii] + 1 > edges[q][ii + k]) edges[q][ii + k] = edges[p][ii] + 1
                }
            }
        }
        if (edges[n][m] < 0) return null
        // backtrace: at (p, ii) find a predecessor (prev, i2) one edge earlier whose span word matches
        val out = ArrayList<Triple<Int, Int, String>>()
        var p = n; var ii = m
        while (p > 0) {
            var stepped = false
            outer@ for (prev in p - 1 downTo 0) {
                for (i2 in ii - 1 downTo 0) {
                    if (edges[prev][i2] != edges[p][ii] - 1) continue
                    val w = parts.subList(i2, ii).joinToString("")
                    if (spanWords(key.substring(prev, p)).any { it.word == w }) {
                        out.add(Triple(prev, p, w)); p = prev; ii = i2; stepped = true; break@outer
                    }
                }
            }
            if (!stepped) throw AssertionError("natParseEdges backtrace inconsistent for $key/$nat")
        }
        if (ii != 0) throw AssertionError("natParseEdges backtrace left codepoints over for $key/$nat")
        out.reverse()
        return out
    }

    // -- Minimal reflective LM-table reader, used ONLY for the report's bigram-support histogram of weak flips
    // (the classification itself never uses it). Self-checked against the public logCond in the baseline test:
    // the reader reconstructs logCond from the raw tables and must agree byte-exactly.
    private val lmTableFields by lazy {
        fun f(n: String): Any = CharBigramLM::class.java.getDeclaredField(n).apply { isAccessible = true }.get(lmModel)
        val buf = f("buf") as java.nio.ByteBuffer
        val offs = intArrayOf(
            f("numChars") as Int, f("charCodesOff") as Int, f("rowStartOff") as Int,
            f("biC2Off") as Int, f("biCountOff") as Int, f("rowTotalOff") as Int, f("uniCountOff") as Int,
        )
        Triple(buf, offs, f("totalUni") as Long)
    }
    private fun lmCharId(cp: Int): Int {
        val (buf, o, _) = lmTableFields
        var lo = 0; var hi = o[0]
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (buf.getInt(o[1] + mid * 4) < cp) lo = mid + 1 else hi = mid }
        return if (lo < o[0] && buf.getInt(o[1] + lo * 4) == cp) lo else -1
    }
    private fun lmBigramCount(c1: Int, c2: Int): Long {
        val (buf, o, _) = lmTableFields
        val id1 = lmCharId(c1); val id2 = lmCharId(c2)
        if (id1 < 0 || id2 < 0) return 0L
        val start = buf.getInt(o[2] + id1 * 4); val end = buf.getInt(o[2] + (id1 + 1) * 4)
        var lo = start; var hi = end
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (buf.getInt(o[3] + mid * 4) < id2) lo = mid + 1 else hi = mid }
        return if (lo < end && buf.getInt(o[3] + lo * 4) == id2) buf.getLong(o[4] + lo * 8) else 0L
    }
    private fun lmBigramObserved(c1: Int, c2: Int): Boolean = lmBigramCount(c1, c2) > 0
    /** Full reconstruction of [CharBigramLM.logCond] from the raw tables — the reader's self-check oracle. */
    private fun lmLogCondReconstructed(c1: Int, c2: Int): Double {
        val (buf, o, totalUni) = lmTableFields
        val id1 = lmCharId(c1); val id2 = lmCharId(c2)
        val cnt = lmBigramCount(c1, c2)
        if (id1 >= 0 && id2 >= 0 && cnt > 0) {
            return ln(cnt.toDouble()) - ln(buf.getLong(o[5] + id1 * 8).toDouble())
        }
        val uni = if (id2 >= 0) ln(buf.getLong(o[6] + id2 * 8).toDouble()) - ln(totalUni.coerceAtLeast(1).toDouble())
        else -ln(totalUni.coerceAtLeast(1).toDouble())
        return ln(0.4) + uni
    }
    private fun bigramSupportedAll(w: String): Boolean {
        var prev = -1; var i = 0
        while (i < w.length) {
            val cp = w.codePointAt(i)
            if (prev >= 0 && !lmBigramObserved(prev, cp)) return false
            prev = cp; i += Character.charCount(cp)
        }
        return true
    }

    private fun asFails(st: Pos0Stats): List<Fail> = st.violations.map { Fail("", "pos0", "gate", "", "", it) }

    private fun pos0Summary(st: Pos0Stats): String =
        "cases=${st.cases} canonical=${st.canonical} strong=${st.strong} weak=${st.weak} " +
            "weakFlipsLetters=${st.weakFlips} (2cp=${st.weakFlips2cp}) weakFlipsT9=${st.weakFlipsT9} " +
            "retention(free=${st.retainFree} pickable=${st.retainPickable} pieces=${st.retainPieces} atomic=${st.retainAtomic} resegment=${st.retainResegment} jianpinGuess=${st.retainJianpinGuess}) canonicalTieSwap=${st.canonicalTieSwap} " +
            "weakFlipUnseenBigram=${st.weakFlipUnseenBigram} canonicalMarginMin=${"%.2f".format(st.canonicalMarginMin)} " +
            "boost1=${"%.3f".format(boost1)} violations=${st.violations.size}"

    /** 2-syllable tuples: every first syllable × [tails]. */
    private fun tuples2(syls: List<String>, tails: List<String>): Sequence<List<String>> = sequence {
        for (s1 in syls) for (s2 in tails) yield(listOf(s1, s2))
    }

    /** 3-syllable covering tuples: every ordered (s1, mid) pair, with the tail rotating over the whole syllable
     *  set — so every syllable appears in every position across the sweep without enumerating 415³. */
    private fun tuples3Covering(syls: List<String>): Sequence<List<String>> = sequence {
        for (i in syls.indices) for (j in syls.indices) yield(listOf(syls[i], syls[j], syls[(i + j) % syls.size]))
    }

    @Test fun e9_pos0MarginAware_representative_alwaysOn() {
        assumeTrue(dictFile.exists() && t9File.exists() && lmFile.exists())
        val syls = runtimeSyllables()
        // mechanical stride — a spread of tails/mids across the whole syllable set, no curated list; the
        // 3-syllable subset rotates its tail so all three positions vary across the sweep
        val tails = syls.filterIndexed { i, _ -> i % 21 == 0 }
        val mids = syls.filterIndexed { i, _ -> i % 83 == 0 }
        val heads3 = syls.filterIndexed { i, _ -> i % 7 == 0 }
        val st = pos0Sweep(tuples2(syls, tails) + sequence {
            for ((i, s1) in heads3.withIndex()) for ((j, mid) in mids.withIndex()) {
                yield(listOf(s1, mid, syls[(i * mids.size + j) % syls.size]))
            }
        })
        assertTrue("representative sweep must exercise all three classes: ${pos0Summary(st)}",
            st.canonical > 100 && st.strong > 100 && st.weak > 0)
        assertTrue("canonical/strong count=1 displacement and weak-flip UX gates must all hold: " +
            "${st.violations.size} ${st.violations.take(6)}", st.violations.isEmpty())
        assertTrue("canonical minimum analytic margin must exceed the single-use boost (zero-tolerance backing): " +
            "min=${st.canonicalMarginMin} boost1=$boost1", st.canonicalMarginMin > boost1)
    }

    @Test fun e9_pos0MarginAware_full() {
        assumeTrue("heavy sweep gated: set AEGIS_AUDIT_FULL=1", fullEnabled())
        assumeTrue(dictFile.exists() && t9File.exists() && lmFile.exists())
        val syls = runtimeSyllables()
        val st2 = pos0Sweep(tuples2(syls, syls), progressEvery = 20000)          // ALL 415² ordered pairs
        val st3 = pos0Sweep(tuples3Covering(syls), progressEvery = 20000)        // 415² covering triples
        writeTsv(File(outDir(), "ext_e9.tsv"), asFails(st2) + asFails(st3))
        File(outDir(), "ext_e9_summary.txt").writeText(
            "# $runStamp\nE9 — position-0 guard, margin-aware (canonical/strong zero-tolerance; weak = user's own word + retained)\n" +
                "pairs: ${pos0Summary(st2)}\ntriples: ${pos0Summary(st3)}\n"
        )
        assertTrue("E9 full pair violations must be zero: ${st2.violations.take(8)}", st2.violations.isEmpty())
        assertTrue("E9 full triple violations must be zero: ${st3.violations.take(8)}", st3.violations.isEmpty())
        assertTrue("full sweep exercises the weak class (flips observed and all conformant)", st2.weakFlips > 0)
    }

    // Widened-tail baseline: the exact 30-tail enumeration that exposed the residual count=1 flips on the
    // pre-margin-aware gate. Under the margin-aware criterion every flip must fall in the WEAK class (verified
    // by classification, not by exclusion from enumeration), the canonical/strong classes must be flip-free,
    // and the legacy narrow predicate's flip set (composed 2-char non-dict naturals) is measured and reported.
    @Test fun e9_pos0_widenedTailBaseline() {
        assumeTrue("heavy sweep gated: set AEGIS_AUDIT_FULL=1", fullEnabled())
        assumeTrue(dictFile.exists() && t9File.exists() && lmFile.exists())
        // reflective LM reader self-check: the reconstructed logCond must agree byte-exactly with the public API
        // over a spread of observed and unseen pairs (drift here would corrupt the report histogram)
        val syls0 = runtimeSyllables()
        for (i in 0 until 200) {
            val a = singlesByFreq(syls0[(i * 7) % syls0.size]).firstOrNull() ?: continue
            val b = singlesByFreq(syls0[(i * 13 + 5) % syls0.size]).firstOrNull() ?: continue
            val c1 = a.codePointAt(0); val c2 = b.codePointAt(0)
            assertTrue("lm reflection self-check drifted for $a$b",
                Math.abs(lmModel.logCond(c1, c2) - lmLogCondReconstructed(c1, c2)) < 1e-9)
        }
        val syls = runtimeSyllables()
        val tails30 = listOf("de", "shi", "guo", "le", "men", "hao", "ma", "zi", "ren",
            "min", "li", "jian", "dao", "xin", "sheng", "hua", "tian", "shan", "feng", "yun",
            "long", "hu", "jia", "wang", "lin", "mu", "yu", "jin", "bao", "ke")
        val st = pos0Sweep(tuples2(syls, tails30))
        // the bigram-support composition of weak flips is a REPORTED characterisation (no judged ratio gate)
        File(outDir(), "ext_e9_baseline30.txt").writeText(
            "# $runStamp\nE9 widened-tail baseline (30 tails)\n${pos0Summary(st)}\n" +
                "weakFlipUnseenBigramRatio=${st.weakFlipUnseenBigram}/${st.weakFlips}\n"
        )
        assertTrue("baseline violations (canonical/strong flips, wrong seizer, lost natural) must be zero: " +
            "${st.violations.take(8)}", st.violations.isEmpty())
        assertTrue("the widened boundary must actually exercise weak flips (previously-residual class)", st.weakFlips > 0)
    }

    // Production-config rise curve: the steady state of a self-created word against a CANONICAL natural default
    // is rank 1 (directly under the default) immediately and monotonically — seizing #0 requires genuinely heavy
    // accumulated use (hundreds-level), and IS reachable (fair rise) for the lowest-margin canonical readings.
    // All order-of-magnitude assertions; no reading or count is pinned.
    @Test fun e9_riseCurve_productionConfig() {
        assumeTrue(dictFile.exists() && t9File.exists() && lmFile.exists())
        val syls = runtimeSyllables()
        val tails = syls.filterIndexed { i, _ -> i % 21 == 0 }
        val dL = e6Decoder(letters = true)
        val oracleL = MarginOracle(dict, t9 = false)
        // mechanically collect canonical cases with their analytic margins. The rank-1 steady-state claims
        // apply to COMMON-character user words (the 是得-class); a rare-character word sinking deep is the
        // separately-asserted rare-before-common design, so cases whose challenger carries a rare character
        // (below the audit's established E6_COMMON line) are excluded from THIS curve sample.
        data class C(val r: String, val nat: String, val uw: String, val margin: Double)
        val canon = ArrayList<C>()
        fun topCommonSingle(s: String, excludeMakes: String?, nat: String?): String? {
            val top = singlesByFreq(s).firstOrNull { excludeMakes == null || nat == null || excludeMakes + it != nat } ?: return null
            val f = dict.exact(s).firstOrNull { it.word == top }?.freq ?: 0
            return if (f >= E6_COMMON) top else null
        }
        outer@ for (s1 in syls) {
            val c1 = topCommonSingle(s1, null, null) ?: continue
            for (s2 in tails) {
                val r = s1 + s2
                val nat = dL.decodeCovered(r, 30).firstOrNull()?.word ?: continue
                if (nat.codePointCount(0, nat.length) < 2) continue
                if (dict.exact(r).none { it.word == nat }) continue
                val c2 = topCommonSingle(s2, c1, nat) ?: continue
                val uw = c1 + c2
                if (uw == nat || dict.exact(r).any { it.word == uw }) continue
                canon.add(C(r, nat, uw, oracleL.natScore(r, nat) - oracleL.uwScoreNoBoost(r, uw)))
                if (canon.size >= 400) break@outer
            }
        }
        assertTrue("canonical cases collected: ${canon.size}", canon.size >= 50)
        val byMargin = canon.sortedBy { it.margin }
        val um = UserModel()
        val dLu = PinyinDecoder(dict, lmModel, userModel = um, initialsDict = jianpinDict)
        fun rankAt(c: C, count: Int): Int {
            repeat(count) { um.recordWord(c.r, c.uw, it.toLong(), incrementCount = true) }
            val rank = dLu.decodeCovered(c.r, 30).map { it.word }.indexOf(c.uw)
            um.removeWord(c.uw)
            return rank
        }
        // steady state + monotonicity on a mechanical sample spanning the margin range. Knob-free gates only:
        // the fresh word is always recalled, NEVER seizes #0 from a canonical default at count=1, and its rank
        // never worsens with accumulated use. The first-use rank distribution (rank 1 directly under the
        // default in the dominant case; a corner reading may interleave stronger prefix-completions) is a
        // REPORTED characterisation, not a judged gate.
        val deep = byMargin.takeLast(6)
        val boundary = byMargin.take(6)
        val counts = listOf(1, 4, 16, 64)
        val firstUseRanks = sortedMapOf<Int, Int>()
        for (c in deep + boundary) {
            var prev = Int.MAX_VALUE
            for (n in counts) {
                val rank = rankAt(c, n)
                assertTrue("recalled at every count (${c.r} n=$n rank=$rank)", rank >= 0)
                if (n == 1) {
                    assertTrue("fresh word never seizes a canonical #0 (${c.r} rank=$rank)", rank >= 1)
                    firstUseRanks[rank] = (firstUseRanks[rank] ?: 0) + 1
                }
                assertTrue("rank must never worsen with more use (${c.r} n=$n: $rank > $prev)", rank <= prev)
                prev = rank
            }
        }
        File(outDir(), "ext_e9_risecurve.txt").writeText(
            "# $runStamp\nE9 rise curve, production config\n" +
                "canonical sample first-use rank histogram (rank -> cases): $firstUseRanks\n"
        )
        // hundreds-level protection: DEEP canonical defaults (the high-margin half) are not seized within tens
        // of uses — the price of protecting canonical firsts is that seizing #0 takes genuinely heavy use
        for (c in byMargin.takeLast(6)) {
            assertTrue("a deep canonical default is not seized within tens of uses (${c.r} margin=${"%.2f".format(c.margin)})",
                rankAt(c, 64) >= 1)
        }
        // fair rise: pick the witness mechanically — the smallest canonical margin inside the band that the
        // production boost curve maps to (64, 2048] uses (bounds derived from the boost curve itself, not tuned:
        // margin ∈ (boost(64), boost(2048)) ⇔ predicted seize count in (64, 2048)). The decoder must agree.
        val boost64 = UserModel().apply { repeat(64) { recordWord("zz", "占位", it.toLong(), incrementCount = true) } }.wordBoost("占位")
        val boost2048 = UserModel().apply { repeat(2048) { recordWord("zz", "占位", it.toLong(), incrementCount = true) } }.wordBoost("占位")
        val w = byMargin.firstOrNull { it.margin > boost64 && it.margin < boost2048 }
        assertTrue("a canonical reading must exist whose margin maps to hundreds-level seizing", w != null)
        var minCount = -1
        for (n in listOf(96, 128, 192, 256, 384, 512, 768, 1024, 1536, 2048)) {
            if (rankAt(w!!, n) == 0) { minCount = n; break }
        }
        assertTrue("the band canonical reading (${w!!.r}, margin=${"%.2f".format(w.margin)}) reaches #0 at " +
            "hundreds-level use (minCount=$minCount expected in (64, 2048])", minCount in 65..2048)
    }

    /** TEN: the en→嗯 alias presence still holds with self-created words merged (en is a single syllable, which
     *  no multi-syllable user word can match, so the alias surface must be untouched). */
    @Test fun e8_userWords_doNotDisturbAliasPresence() {
        assumeTrue(dictFile.exists() && lmFile.exists())
        val um = populatedModel(generatedUserWords(runtimeSyllables(), listOf("shi", "en")))
        val dL = userDecoder(letters = true, um)
        for ((s, targets) in PinyinDecoder.INPUT_ALIASES) {
            for (target in targets) {
                val topAlias = dict.exact(target).filter { isSingleChar(it.word) }.maxByOrNull { it.freq }?.word ?: continue
                assertTrue("alias target $topAlias for $s still surfaces with user words merged",
                    topAlias in dL.decodeCovered(s, 30).map { it.word })
            }
        }
    }
}
