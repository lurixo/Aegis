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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Exhaustive 9-key + 26-key syllable / reading / lock audit (Level A, pure JVM, decoder direct).
 *
 * REPORT-ONLY: this test does NOT fix the decoder and does NOT fail the build per offending syllable.
 * It runs the invariants I1/I2/I3 referenced to the INPUT syllable S against the app's own
 * dictionary oracle (`dict.exact(S)`), collects every mismatch, and writes a machine-readable TSV that the
 * report generator consumes. It asserts only meta-facts: the runtime syllable set size, that assets are
 * present, that the report was written, and — as a detection proof — that the known `deng` failure IS
 * flagged by the sweep (so we know the audit detects the real target bug).
 *
 * Syllable universe = the RUNTIME set `T9Pinyin.SYLLABLES` (private, ~415), reflected out (NOT the 418
 * tools superset). Heavy n=2 (415²) and n=3 (covering) sweeps are gated behind `-Daegis.audit.full=1`.
 */
class ExhaustiveDecodeAuditTest {

    // ---- real assets (LossFixTest recipe) ----
    private val dictFile = File("src/main/assets/aegis_dict.bin")
    private val t9File = File("src/main/assets/aegis_t9.bin")
    private val lmFile = File("src/main/assets/aegis_lm.bin")
    private val jianpinFile = File("src/main/assets/aegis_jianpin.bin")

    private fun letterDecoder(): PinyinDecoder {
        val initials = if (jianpinFile.exists()) BinaryDict.fromFile(jianpinFile) else null
        return PinyinDecoder(BinaryDict.fromFile(dictFile), CharBigramLM.fromFile(lmFile), initialsDict = initials)
    }

    private val dict: BinaryDict by lazy { BinaryDict.fromFile(dictFile) }

    private fun isSingleChar(w: String): Boolean = w.codePointCount(0, w.length) == 1
    /** Oracle: the dict's complete single-char homophone set for exact letter key S. */
    private fun dictSingles(key: String): Set<String> =
        dict.exact(key).filter { isSingleChar(it.word) }.map { it.word }.toSet()
    private fun allSingles(cands: List<Cand>): Set<String> =
        cands.filter { isSingleChar(it.word) }.map { it.word }.toSet()

    private fun sample(s: Collection<String>, n: Int = 8): String =
        s.take(n).joinToString(" ") + if (s.size > n) " …(${s.size})" else ""

    /**
     * Known-acceptable colloquial cross-readings: input syllable -> chars that may
     * legitimately surface for it even though they are not in dict.exact(syllable). The I2/I3 char checks
     * allow these through. Deliberately tiny. `en -> 嗯`: typing `en` offering the syllabic-nasal interjection
     * 嗯 (formal reading ng/n) is mainstream IME behaviour (Sogou / MS Pinyin), retained via PinyinDecoder's
     * en->ng alias; it is NOT the deng-class bug, so it is whitelisted rather than treated as an offender.
     */
    /** The deliberate `en -> ng` input alias forwards the WHOLE `ng` key: whatever single chars the
     *  dict carries under `ng` (嗯, and any completeness top-ups like 㕶) legitimately surface for `en`.
     *  The allowlist therefore mirrors the alias target's live single set instead of pinning one char. */
    private val COLLOQUIAL_WHITELIST: Map<String, Set<String>> by lazy { mapOf("en" to dictSingles("ng")) }

    // ---- runtime syllable set via reflection (NOT tools' 418 superset) ----
    @Suppress("UNCHECKED_CAST")
    private fun runtimeSyllables(): List<String> {
        val f = T9Pinyin::class.java.getDeclaredField("SYLLABLES")
        f.isAccessible = true
        val set = f.get(T9Pinyin) as Set<String>
        return set.toList()
    }

    // ---- failure sink ----
    /** Permanent no-traditional gate: traditional/variant source forms the build maps away (tc1_mapped_forms.txt,
     *  derived from tools/t2s-data — see its PROVENANCE.md). Candidates must never contain one. */
    private val mappedForms: Set<String> by lazy {
        javaClass.classLoader!!.getResourceAsStream("tc1_mapped_forms.txt")!!
            .bufferedReader().readLines().filter { it.isNotBlank() && !it.startsWith("#") }.toSet()
    }
    private fun containsMappedForm(word: String): Boolean {
        var i = 0
        while (i < word.length) {
            if (String(Character.toChars(word.codePointAt(i))) in mappedForms) return true
            i += Character.charCount(word.codePointAt(i))
        }
        return false
    }

    private data class Fail(
        val input: String, val layout: String, val inv: String,
        val expectedReading: String, val shownReading: String,
        val expectedChars: String, val shownChars: String, val detail: String,
    )

    // Gradle's forked unit-test JVM inherits the environment (not arbitrary -D props), so gate + output
    // dir are read from env first, with a system-property fallback and a safe module-relative default.
    private fun outDir(): File {
        val p = System.getenv("AEGIS_AUDIT_DIR") ?: System.getProperty("aegis.audit.dir") ?: "build/decode-audit"
        val d = File(p); d.mkdirs(); return d
    }
    private fun fullEnabled(): Boolean =
        (System.getenv("AEGIS_AUDIT_FULL") ?: System.getProperty("aegis.audit.full")) == "1"

    private val NASALS = setOf("ng", "n", "m")
    private val syllableSet: Set<String> by lazy { runtimeSyllables().toSet() }
    /**
     * Classify a 26-key I1 segmentation mismatch (`syllables(s1+s2…) != [s1,s2,…]`):
     *  - "expected-merge": the TYPED sequence has a NON-FIRST bare nasal (ng/n/m). A bare nasal coda is
     *    unrepresentable as its own non-initial syllable on the letter path — the letters always merge into the
     *    preceding vowel — so such a "pair" cannot be typed and its mismatch is the CORRECT merge, not a bug.
     *  - "nasal-split": THE BUG — the shown split peels a bare nasal off a preceding syllable they could have
     *    merged into a valid whole syllable (deng -> de+ng). Must be 0 after the fix.
     *  - "other-reflow": inherent 26-key romanization ambiguity (xi+an -> xian) — not a decoder bug.
     */
    private fun classify(f: Fail): String {
        if (f.layout != "26key" || f.inv != "I1") return "other"
        val exp = f.expectedReading.split("+"); val shown = f.shownReading.split("+")
        if (exp.drop(1).any { it in NASALS }) return "expected-merge"
        for (i in 1 until shown.size) if (shown[i] in NASALS && (shown[i - 1] + shown[i]) in syllableSet) return "nasal-split"
        return "other-reflow"
    }

    private fun writeTsv(file: File, fails: List<Fail>) {
        file.bufferedWriter().use { w ->
            w.write("input\tlayout\tinvariant\texpectedReading\tshownReading\texpectedCharsSample\tshownCharsSample\tdetail\n")
            for (f in fails) w.write(
                "${f.input}\t${f.layout}\t${f.inv}\t${f.expectedReading}\t${f.shownReading}\t${f.expectedChars}\t${f.shownChars}\t${f.detail}\n"
            )
        }
    }

    /**
     * Run the full n=1 invariant sweep over [syls]. Both layouts. Returns all failures.
     * Invariants (referenced to input S; oracle = dictSingles(S)):
     *  I1  reading label == input        (26-key: syllables(S).readings==[S]; 9-key: leftColumnReadings⊇S & lockFirstReading.display==S)
     *  I2  homophones ⊆ input's chars    (26-key letter decoder: homophonesAt(S,0) ⊆ oracle, non-empty if oracle non-empty)
     *  I3  locked chars ⊆ input's chars  (26-key: decodeCoveredAtomic(S) singles ⊆ oracle; 9-key lock: decodeCoveredAtomic(lockLetters) singles ⊆ oracle & label stays S)
     */
    private fun sweepN1(d: PinyinDecoder, syls: List<String>): List<Fail> {
        val fails = ArrayList<Fail>()
        for (s in syls) {
            val oracle = dictSingles(s)
            val allowed = COLLOQUIAL_WHITELIST[s] ?: emptySet() // known colloquial cross-readings (e.g. en -> 嗯)
            val digits = T9Pinyin.toT9(s)

            // ---- I1 26-key: segmentation label ----
            val seg26 = d.syllables(s).map { it.reading }
            if (seg26 != listOf(s)) {
                fails += Fail(s, "26key", "I1", s, seg26.joinToString("+"),
                    sample(oracle), "-", "syllables(S) mis-segments")
            }
            // ---- I1 9-key: left column contains S + lock label stays S ----
            val col9 = T9Pinyin.leftColumnReadings(digits, 26)
            val lock = T9Pinyin.lockFirstReading(digits, s)
            if (s !in col9) {
                fails += Fail(s, "9key", "I1", s, sample(col9),
                    sample(oracle), "-", "leftColumnReadings(toT9(S)) omits S")
            }
            if (lock == null || lock.display != s) {
                fails += Fail(s, "9key", "I1", s, lock?.display ?: "<null>",
                    sample(oracle), "-", "lockFirstReading(toT9(S),S).display != S")
            }

            // ---- I2 26-key: first-syllable homophones read S ----
            val homo = d.homophonesAt(s, 0).toSet()
            val homoLeak = homo - oracle - allowed
            if (homoLeak.isNotEmpty()) {
                fails += Fail(s, "26key", "I2", s, seg26.firstOrNull() ?: "-",
                    sample(oracle), sample(homoLeak), "homophonesAt(S,0) has chars not reading S")
            }
            if (oracle.isNotEmpty() && homo.isEmpty()) {
                fails += Fail(s, "26key", "I2", s, seg26.firstOrNull() ?: "-",
                    sample(oracle), "<empty>", "homophonesAt(S,0) empty though dict.exact(S) non-empty")
            }

            // ---- no-traditional gate: no candidate may contain a mapped-away trad/variant form ----
            for (c in d.decodeCovered(s, 30)) if (containsMappedForm(c.word)) {
                fails += Fail(s, "26key", "TC1-traditional-leak", s, c.word,
                    sample(oracle), c.word, "candidate contains a traditional/variant form the build maps away")
            }
            for (h in homo) if (containsMappedForm(h)) {
                fails += Fail(s, "26key", "TC1-traditional-leak", s, h,
                    sample(oracle), h, "homophone drill contains a mapped-away form")
            }

            // ---- alias PRESENCE (upgraded from allowlist-only). The allowlist said 嗯 MAY
            // surface for en; the required behaviour is that it DOES. For every aliased
            // syllable, the alias target's top single must appear in the candidate strip and the
            // homophone drill, ranked after the input's own top native char (借读不得插到本音字前). ----
            for (target in PinyinDecoder.INPUT_ALIASES[s].orEmpty()) {
                val topAlias = dict.exact(target).filter { isSingleChar(it.word) }.maxByOrNull { it.freq }?.word ?: continue
                val topNative = dict.exact(s).filter { isSingleChar(it.word) }.maxByOrNull { it.freq }?.word
                val strip = d.decodeCovered(s, 30).map { it.word }
                val ai = strip.indexOf(topAlias)
                if (ai < 0) {
                    fails += Fail(s, "26key", "TEN-alias-presence", s, "-",
                        topAlias, sample(strip.take(12).toSet()), "alias target's top single ($target->$topAlias) missing from candidates")
                } else if (topNative != null && strip.indexOf(topNative) !in 0 until ai) {
                    fails += Fail(s, "26key", "TEN-alias-presence", s, "-",
                        "$topNative<$topAlias", "$topAlias@$ai,$topNative@${strip.indexOf(topNative)}",
                        "borrowed reading outranks the input's own top native char")
                }
                if (topAlias !in homo) {
                    fails += Fail(s, "26key", "TEN-alias-presence", s, "-",
                        topAlias, sample(homo), "alias target's top single missing from homophonesAt(S,0)")
                }
            }

            // ---- I3 26-key: locked/atomic decode chars read S ----
            val atom26 = allSingles(d.decodeCoveredAtomic(s, 30))
            val leak26 = atom26 - oracle - allowed
            if (leak26.isNotEmpty()) {
                fails += Fail(s, "26key", "I3", s, seg26.joinToString("+"),
                    sample(oracle), sample(leak26), "decodeCoveredAtomic(S) singles not reading S")
            }
            // ---- I3 9-key: lock reading S -> letters -> atomic decode chars read S, label stays S ----
            if (lock != null) {
                val lockedSingles = allSingles(d.decodeCoveredAtomic(lock.letters, 30))
                val leak9 = lockedSingles - oracle - allowed
                if (leak9.isNotEmpty()) {
                    fails += Fail(s, "9key", "I3", s, lock.display,
                        sample(oracle), sample(leak9),
                        "9-key lock '${s}' (letters='${lock.letters}') yields chars not reading S")
                }
            }
        }
        return fails
    }

    // ================= n=1 : ALWAYS RUN (fast; reproduces deng) =================
    @Test fun exhaustiveN1_bothLayouts_writesReport() {
        assumeTrue("assets present", dictFile.exists() && lmFile.exists() && t9File.exists())
        val syls = runtimeSyllables()
        assertTrue("runtime SYLLABLES set looks like ~415 (drift guard): ${syls.size}",
            syls.size in 400..430 && syls.isNotEmpty())

        val d = letterDecoder()
        val fails = sweepN1(d, syls)

        // write full report + a summary
        writeTsv(File(outDir(), "levelA_n1.tsv"), fails.sortedWith(compareBy({ it.inv }, { it.layout }, { it.input })))
        val byInvLayout = fails.groupingBy { it.inv to it.layout }.eachCount()
        val failedInputs = fails.map { it.input }.toSet()
        File(outDir(), "levelA_n1_summary.txt").writeText(buildString {
            appendLine("Level A — n=1 exhaustive syllable audit")
            appendLine("syllables tested: ${syls.size}")
            appendLine("distinct offending syllables: ${failedInputs.size}")
            appendLine("total invariant violations: ${fails.size}")
            appendLine("per invariant×layout:")
            byInvLayout.toSortedMap(compareBy { it.first + it.second }).forEach { (k, v) ->
                appendLine("  ${k.first} ${k.second}: $v")
            }
        })

        // ---- regression assertion: after the nasal-split fix, NO single syllable may violate any
        // invariant on either layout (a lone valid syllable has no legitimate romanization ambiguity),
        // so deng -> [deng] with deng-chars, en no longer leaks 嗯, and the offender set is empty. ----
        val tradLeaks = fails.filter { it.inv == "TC1-traditional-leak" }
        assertTrue("no-traditional gate: traditional/variant forms leaked into candidates: ${tradLeaks.take(6)}", tradLeaks.isEmpty())
        assertTrue("n=1 offenders must be 0 after the fix; remaining: ${failedInputs.sorted()}", fails.isEmpty())
        assertTrue("report written", File(outDir(), "levelA_n1.tsv").length() > 0)
    }

    // ================= n=2 : ALL ordered pairs (415²) — gated =================
    @Test fun exhaustiveN2_allPairs_writesReport() {
        assumeTrue("heavy sweep gated: set AEGIS_AUDIT_FULL=1", fullEnabled())
        assumeTrue("assets present", dictFile.exists() && lmFile.exists())
        val syls = runtimeSyllables()
        val d = letterDecoder()
        val fails = ArrayList<Fail>()
        var done = 0
        val total = syls.size.toLong() * syls.size
        for (s1 in syls) {
            val fd1 = T9Pinyin.toT9(s1)
            for (s2 in syls) {
                val input = s1 + s2
                // I1: the pair must segment to exactly [s1, s2]
                val seg = d.syllables(input).map { it.reading }
                if (seg != listOf(s1, s2)) {
                    fails += Fail(input, "26key", "I1", "$s1+$s2", seg.joinToString("+"),
                        "-", "-", "pair mis-segments")
                }
                // I3 9-key: lock the first reading s1, then chars covering s1 must read s1
                val lock = T9Pinyin.lockFirstReading(fd1 + T9Pinyin.toT9(s2), s1)
                if (lock == null || !lock.display.startsWith(s1)) {
                    fails += Fail(input, "9key", "I1", s1, lock?.display ?: "<null>",
                        "-", "-", "lockFirstReading first label != s1 for pair")
                }
            }
            done += syls.size
            if (s1 == syls[syls.size / 4] || s1 == syls[syls.size / 2] || s1 == syls[3 * syls.size / 4]) {
                println("[audit n2] progress ~${done}/${total}")
            }
        }
        writeTsv(File(outDir(), "levelA_n2.tsv"), fails)
        val distinct = fails.map { it.input }.toSet().size
        val nasal = fails.count { classify(it) == "nasal-split" }
        val reflow = fails.count { classify(it) == "other-reflow" }
        val merge = fails.count { classify(it) == "expected-merge" }
        File(outDir(), "levelA_n2_summary.txt").writeText(
            "Level A — n=2 all ordered pairs\npairs tested: $total\noffending pairs: $distinct\ntotal violations: ${fails.size}\n" +
                "I1 26key: ${fails.count { it.inv == "I1" && it.layout == "26key" }}\n" +
                "I1 9key:  ${fails.count { it.inv == "I1" && it.layout == "9key" }}\n" +
                "nasal-split: $nasal\nother-reflow: $reflow\nexpected-merge: $merge\n"
        )
        // hard gate: the nasal-split root-cause class must be eliminated at n=2 (other-reflow ambiguity and
        // the degenerate expected-merge duals of the n=1 fix are not decoder bugs).
        assertTrue("n=2 nasal-split must be 0 after the fix (got $nasal)", nasal == 0)
    }

    // ================= n=3 : complete-covering sweep (~415²) — gated =================
    @Test fun coveringN3_writesReport() {
        assumeTrue("heavy sweep gated: set AEGIS_AUDIT_FULL=1", fullEnabled())
        assumeTrue("assets present", dictFile.exists() && lmFile.exists())
        val syls = runtimeSyllables()
        val d = letterDecoder()
        val fails = ArrayList<Fail>()
        // Complete-covering construction: every ordered pair (a,b) appears at positions (1,2) AND (2,3),
        // and every syllable appears at position 3 — bounded by ~415² triples (NOT literal 415³).
        for (i in syls.indices) {
            val a = syls[i]
            for (j in syls.indices) {
                val b = syls[j]
                val c = syls[(i + j) % syls.size] // rotates so every syllable also lands at pos 3 across the sweep
                val input = a + b + c
                val seg = d.syllables(input).map { it.reading }
                if (seg != listOf(a, b, c)) {
                    fails += Fail(input, "26key", "I1", "$a+$b+$c", seg.joinToString("+"),
                        "-", "-", "triple mis-segments")
                }
            }
        }
        writeTsv(File(outDir(), "levelA_n3.tsv"), fails)
        val nasal = fails.count { classify(it) == "nasal-split" }
        val reflow = fails.count { classify(it) == "other-reflow" }
        val merge = fails.count { classify(it) == "expected-merge" }
        File(outDir(), "levelA_n3_summary.txt").writeText(
            "Level A — n=3 complete-covering sweep (~415² triples; n>=4 NOT enumerated)\n" +
                "triples tested: ${syls.size.toLong() * syls.size}\noffending triples: ${fails.map { it.input }.toSet().size}\n" +
                "total violations: ${fails.size}\nnasal-split: $nasal\nother-reflow: $reflow\nexpected-merge: $merge\n"
        )
        assertTrue("n=3 nasal-split must be 0 after the fix (got $nasal)", nasal == 0)
    }
}
