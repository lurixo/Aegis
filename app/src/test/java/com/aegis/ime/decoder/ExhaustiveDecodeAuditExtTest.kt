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

class ExhaustiveDecodeAuditExtTest {

    private val dictFile = FullDictTestAssets.file(FullDictTestAssets.DICT)
    private val lmFile = FullDictTestAssets.file(FullDictTestAssets.LM)
    private val jianpinFile = FullDictTestAssets.file(FullDictTestAssets.JIANPIN)

    private fun letterDecoder(fuzzy: Set<String> = emptySet()): PinyinDecoder {
        assumeTrue(
            "26-key dict + LM + jianpin assets present",
            FullDictTestAssets.available(dictFile, lmFile, jianpinFile),
        )
        return PinyinDecoder(
            BinaryDict.fromFile(dictFile), CharBigramLM.fromFile(lmFile),
            fuzzyRules = fuzzy, initialsDict = BinaryDict.fromFile(jianpinFile),
        )
    }

    private val dict: BinaryDict by lazy { BinaryDict.fromFile(dictFile) }

    private fun isSingleChar(w: String): Boolean = w.codePointCount(0, w.length) == 1
    private fun dictSingles(key: String): Set<String> =
        dict.exact(key).filter { isSingleChar(it.word) }.map { it.word }.toSet()
    private fun sample(s: Collection<String>, n: Int = 8): String =
        s.take(n).joinToString(" ") + if (s.size > n) " …(${s.size})" else ""

    private val COLLOQUIAL_WHITELIST: Map<String, Set<String>> by lazy { mapOf("en" to dictSingles("ng")) }
    private fun allowed(reading: String): Set<String> = COLLOQUIAL_WHITELIST[reading] ?: emptySet()

    @Suppress("UNCHECKED_CAST")
    private fun runtimeSyllables(): List<String> {
        val f = T9Pinyin::class.java.getDeclaredField("SYLLABLES")
        f.isAccessible = true
        val syls = (f.get(T9Pinyin) as Set<String>).toList().sorted()
        assertTrue("runtime SYLLABLES set looks like ~415 (drift guard): ${syls.size}", syls.size in 400..430)
        return syls
    }

    private val reverseSingles: Map<String, Set<String>> by lazy {
        val m = HashMap<String, MutableSet<String>>()
        for (s in runtimeSyllables()) for (ch in dictSingles(s)) m.getOrPut(ch) { HashSet() }.add(s)
        m
    }

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

    @Test fun e1_sequentialLock_allPairs() {
        assumeTrue("full sweep gated: set AEGIS_AUDIT_FULL=1", fullEnabled())
        assumeTrue(FullDictTestAssets.available(dictFile, lmFile, jianpinFile))
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
                    val cp0 = String(Character.toChars(c.word.codePointAt(0)))
                    val cp1 = String(Character.toChars(c.word.codePointBefore(c.word.length)))
                    val bad = ArrayList<Pair<String, String>>()
                    if (cp0 !in o1) bad.add(cp0 to "S1=$s1")
                    if (cp1 !in o2) bad.add(cp1 to "S2=$s2")
                    if (bad.isEmpty()) continue
                    if (c.word in dictWords) {
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
            "pairs covered: ${syls.size.toLong() * syls.size} (all ordered ${syls.size}²)", fails)
        val escaped = fails.filter { it.check == "E1-crossing-escaped" || it.check == "E1-aligned-but-mismatch" }
        assertTrue("lock-boundary filter escaped on ${escaped.size} pairs: ${escaped.take(5)}", escaped.isEmpty())
        assertTrue("E1 report written", File(outDir(), "ext_e1.tsv").exists())
    }

    private val lm: CharBigramLM by lazy { CharBigramLM.fromFile(lmFile) }
}
