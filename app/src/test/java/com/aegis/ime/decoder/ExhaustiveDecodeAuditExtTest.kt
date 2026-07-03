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

class ExhaustiveDecodeAuditExtTest {

    private val dictFile = File("src/main/assets/aegis_dict.bin")
    private val t9File = File("src/main/assets/aegis_t9.bin")
    private val lmFile = File("src/main/assets/aegis_lm.bin")
    private val jianpinFile = File("src/main/assets/aegis_jianpin.bin")

    private fun letterDecoder(fuzzy: Set<String> = emptySet()): PinyinDecoder {
        val initials = if (jianpinFile.exists()) BinaryDict.fromFile(jianpinFile) else null
        return PinyinDecoder(
            BinaryDict.fromFile(dictFile), CharBigramLM.fromFile(lmFile),
            fuzzyRules = fuzzy, initialsDict = initials,
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

    private val COLLOQUIAL_WHITELIST: Map<String, Set<String>> = mapOf("en" to setOf("嗯"))
    private fun allowed(reading: String): Set<String> = COLLOQUIAL_WHITELIST[reading] ?: emptySet()

    @Suppress("UNCHECKED_CAST")
    private fun runtimeSyllables(): List<String> {
        val f = T9Pinyin::class.java.getDeclaredField("SYLLABLES")
        f.isAccessible = true
        return (f.get(T9Pinyin) as Set<String>).toList().sorted()
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
            w.write("input\tlayout\tcheck\texpected\tshown\tdetail\n")
            for (f in fails) w.write("${f.input}\t${f.layout}\t${f.check}\t${f.expected}\t${f.shown}\t${f.detail}\n")
        }
    }

    private fun summary(file: File, title: String, covered: String, fails: List<Fail>) {
        val byCheck = fails.groupingBy { it.check }.eachCount().toSortedMap()
        File(outDir(), file.name).writeText(buildString {
            appendLine(title)
            appendLine(covered)
            appendLine("total violations: ${fails.size}; distinct inputs: ${fails.map { it.input }.toSet().size}")
            byCheck.forEach { (k, v) -> appendLine("  $k: $v") }
        })
    }

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

                val seg = d.syllables("$s1'$s2").map { it.reading }
                if (seg != listOf(s1, s2)) {
                    fails += Fail(input, "9key", "E1-label", "$s1+$s2", seg.joinToString("+"),
                        "syllables(S1'S2) != locked readings")
                }

                val cands = d.decodeCoveredAtomic(input, 30, setOf(cut))
                val firstLeak = cands.filter { it.coveredLen == cut && isSingleChar(it.word) }
                    .map { it.word }.filter { it !in o1 }
                if (firstLeak.isNotEmpty()) {
                    fails += Fail(input, "9key", "E1-chars-S1", sample(o1), sample(firstLeak),
                        "locked-decode first-unit singles not reading S1")
                }
                val dictWords = dict.exact(input).map { it.word }.toSet()
                for (c in cands) {
                    if (c.coveredLen != input.length || c.word.codePointCount(0, c.word.length) != 2) continue
                    if (c.word in dictWords) continue
                    val cp0 = String(Character.toChars(c.word.codePointAt(0)))
                    val cp1 = String(Character.toChars(c.word.codePointBefore(c.word.length)))
                    if (cp0 !in o1) fails += Fail(input, "9key", "E1-chars-S1",
                        sample(o1), "${c.word}[0]=$cp0", "sentence char 0 not reading S1")
                    if (cp1 !in o2) fails += Fail(input, "9key", "E1-chars-S2",
                        sample(o2), "${c.word}[1]=$cp1", "sentence char 1 not reading S2")
                }
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
        assertTrue("E1 report written", File(outDir(), "ext_e1.tsv").exists())
    }

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

    @Test fun e3_partialCommitContinue_allPairs() {
        assumeTrue("heavy sweep gated: set AEGIS_AUDIT_FULL=1", fullEnabled())
        assumeTrue(dictFile.exists() && lmFile.exists() && t9File.exists())
        val syls = runtimeSyllables()
        val d = letterDecoder()
        val t9 = t9Decoder()
        val fails = ArrayList<Fail>()

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

    @Test fun e4_fuzzyOn_allSyllables_andRuleIsolation() {
        assumeTrue(dictFile.exists() && lmFile.exists())
        val syls = runtimeSyllables()
        val allKeys = Fuzzy.RULES.map { it.key }.toSet()
        val fails = ArrayList<Fail>()

        val dBase = letterDecoder()
        val baseline = HashMap<String, Set<Pair<String, Int>>>()
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
            val drillLeak = d.homophonesAt(s, 0).toSet() - dBase.homophonesAt(s, 0).toSet()
            if (drillLeak.isNotEmpty()) {
                fails += Fail(s, "26key", "$tag-drill", sample(dictSingles(s)), sample(drillLeak),
                    "fuzzy leaked into homophonesAt (must stay identical to fuzzy-off)")
            }
        }

        val dAll = letterDecoder(fuzzy = allKeys)
        for (s in syls) checkOne(dAll, s, allKeys, "E4-all")

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
            "syllable\ttopCandidate\tdictTop5\n" + rows.joinToString("\n") + if (rows.isNotEmpty()) "\n" else ""
        )
        File(outDir(), "ext_e5_summary.txt").writeText(
            "E5 — order advisory (report-only)\nsyllables: ${syls.size}\nadvisories: ${rows.size}\n"
        )
        assertTrue("E5 advisory written", File(outDir(), "ext_e5_advisory.tsv").exists())
    }
}
