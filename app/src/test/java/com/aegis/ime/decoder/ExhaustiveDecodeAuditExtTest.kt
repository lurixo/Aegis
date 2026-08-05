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

class ExhaustiveDecodeAuditExtTest {

    private val dictFile = FullDictTestAssets.file(FullDictTestAssets.DICT)
    private val t9File = FullDictTestAssets.file(FullDictTestAssets.T9)
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
    private fun t9Decoder(): PinyinDecoder {
        assumeTrue("T9 dict + LM assets present", FullDictTestAssets.available(t9File, lmFile))
        return PinyinDecoder(BinaryDict.fromFile(t9File), CharBigramLM.fromFile(lmFile))
    }

    private val dict: BinaryDict by lazy { BinaryDict.fromFile(dictFile) }
    private val t9Dict: BinaryDict by lazy { BinaryDict.fromFile(t9File) }

    private fun isSingleChar(w: String): Boolean = w.codePointCount(0, w.length) == 1
    private fun dictSingles(key: String): Set<String> =
        dict.exact(key).filter { isSingleChar(it.word) }.map { it.word }.toSet()
    private fun t9Singles(key: String): Set<String> =
        t9Dict.exact(key).filter { isSingleChar(it.word) }.map { it.word }.toSet()
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

    private fun heavyEnabled(): Boolean =
        (System.getenv("AEGIS_AUDIT_HEAVY") ?: System.getProperty("aegis.audit.heavy")) == "1"

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

    @Test fun e2_laterSyllableHomophones_allPairs() {
        assumeTrue("full sweep gated: set AEGIS_AUDIT_FULL=1", fullEnabled())
        assumeTrue(FullDictTestAssets.available(dictFile, lmFile, jianpinFile))
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
        assumeTrue("scheduled sweep gated: set AEGIS_AUDIT_HEAVY=1", heavyEnabled())
        assumeTrue(FullDictTestAssets.available(dictFile, lmFile, t9File, jianpinFile))
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
            "pairs covered: ${syls.size.toLong() * syls.size} on letters AND T9; remainder integrity per distinct S2 (${syls.size})", fails)
        assertTrue("E3 report written", File(outDir(), "ext_e3.tsv").exists())
    }

    @Test fun e4_fuzzyOn_allSyllables_andRuleIsolation() {
        assumeTrue(FullDictTestAssets.available(dictFile, lmFile, jianpinFile))
        val syls = runtimeSyllables()
        val allKeys = Fuzzy.RULES.map { it.key }.toSet()
        val fails = ArrayList<Fail>()

        val dBase = letterDecoder()
        val baseline = HashMap<String, Set<Pair<String, Int>>>()
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
                val okSet = Fuzzy.variants(key, rules).flatMapTo(HashSet()) { v ->
                    sylList.filter { it.startsWith(v) }.flatMap { dictSingles(it) }
                } + allowed(key)
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
        assumeTrue(FullDictTestAssets.available(dictFile, lmFile, jianpinFile))
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

    private fun e6Decoder(letters: Boolean): PinyinDecoder =
        if (letters) PinyinDecoder(
            dict, CharBigramLM.fromFile(lmFile),
            initialsDict = BinaryDict.fromFile(jianpinFile),
        )
        else PinyinDecoder(t9Dict, CharBigramLM.fromFile(lmFile), aliasDict = dict)

    private val E6_RARE = 100
    private val E6_COMMON = 1000

    private val HEAVY_USE = 256

    private class E6View(val exact: Map<String, Int>, val alias: Map<String, Int>, val prefix: Map<String, Int>) {
        val entries = exact.size + alias.size + prefix.size
    }
    private val E6_VIEW_ENTRY_BUDGET = 4_000_000
    private var e6ViewEntries = 0
    private val e6KeyView = LinkedHashMap<String, E6View>(16, 0.75f, true)
    private fun e6RawFreq(source: BinaryDict, key: String, word: String): Int? {
        val cacheKey = (if (source === dict) "L:" else "D:") + key
        val view = e6KeyView[cacheKey] ?: run {
            fun collect(entries: List<BinaryDict.WordFreq>): Map<String, Int> {
                val m = HashMap<String, Int>()
                for (wf in entries) if (wf.freq > (m[wf.word] ?: -1)) m[wf.word] = wf.freq
                return m
            }
            val aliasEntries = (if (key.firstOrNull() in '2'..'9') PinyinDecoder.T9_INPUT_ALIASES[key].orEmpty()
            else PinyinDecoder.INPUT_ALIASES[key].orEmpty()).flatMap { dict.exact(it) }
            val prefixEntries = source.prefixByFreq(key, E6_PREFIX_SCAN) +
                jianpin.prefixByFreq(key, E6_PREFIX_SCAN)
            E6View(collect(source.exact(key)), collect(aliasEntries), collect(prefixEntries))
                .also {
                    e6KeyView[cacheKey] = it
                    e6ViewEntries += it.entries
                    val eldest = e6KeyView.entries.iterator()
                    while (e6ViewEntries > E6_VIEW_ENTRY_BUDGET && eldest.hasNext()) {
                        val removed = eldest.next()
                        e6ViewEntries -= removed.value.entries
                        eldest.remove()
                    }
                }
        }
        return view.exact[word] ?: view.alias[word] ?: view.prefix[word]
    }

    private val jianpin: BinaryDict by lazy { BinaryDict.fromFile(jianpinFile) }
    private val E6_PREFIX_SCAN = 8192

    private fun e6Check(source: BinaryDict, input: String, layered: Pair<List<Cand>, Int>): List<String> {
        val (cands, remainderStart) = layered
        val rows = ArrayList<String>()
        val exactWords = source.exact(input).filterNot { isSingleChar(it.word) }.mapTo(HashSet()) { it.word }
        val buckets = LinkedHashMap<Int, MutableList<Pair<String, Int?>>>()
        val atLonger = HashMap<String, Int>()
        for ((pos, c) in cands.withIndex()) {
            if (pos == 0) continue
            if ((atLonger[c.word] ?: -1) > c.coveredLen) continue
            atLonger[c.word] = maxOf(atLonger[c.word] ?: -1, c.coveredLen)
            val key = input.substring(0, c.coveredLen.coerceIn(1, input.length))
            buckets.getOrPut(c.coveredLen) { ArrayList() }.add(c.word to e6RawFreq(source, key, c.word))
        }
        for ((cov, ws) in buckets) {
            val key = input.substring(0, cov.coerceIn(1, input.length))
            var commonAfter: String? = null
            for (i in ws.indices.reversed()) {
                val (w, f) = ws[i]
                if (f != null && f <= E6_RARE && commonAfter != null && w !in exactWords) {
                    rows.add("$input\tcov$cov\tO2\t$w@$f before ${commonAfter}")
                }
                if (f != null && f >= E6_COMMON) commonAfter = w
            }
            val readingFreq = source.exact(key).filter { isSingleChar(it.word) }.associate { it.word to it.freq }
            var prev = Int.MAX_VALUE
            for ((w, _) in ws) {
                val f = readingFreq[w] ?: continue
                if (f > prev) rows.add("$input\tcov$cov\tO1\t$w@$f after a lower-freq same-reading single")
                prev = f
            }
        }
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
        assumeTrue(FullDictTestAssets.available(dictFile, t9File, lmFile, jianpinFile))
        val syls = runtimeSyllables()
        val dL = e6Decoder(letters = true)
        val dT = e6Decoder(letters = false)
        val rows = ArrayList<String>()
        for (s in syls) {
            rows.addAll(e6Check(dict, s, dL.decodeCoveredLayered(s, 30)))
            val dig = T9Pinyin.toT9(s)
            rows.addAll(e6Check(t9Dict, dig, dT.decodeCoveredLayered(dig, 30)))
        }
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

    private val nativeSinglesMap = HashMap<String, Map<String, Int>>()
    private fun nativeSinglesOf(source: BinaryDict, key: String): Map<String, Int> =
        nativeSinglesMap.getOrPut((if (source === dict) "L:" else "D:") + key) {
            val m = HashMap<String, Int>()
            for (wf in source.exact(key)) if (isSingleChar(wf.word)) m.putIfAbsent(wf.word, wf.freq)
            m
        }
    private fun nativeSingleFreq(source: BinaryDict, key: String, ch: String): Int? = nativeSinglesOf(source, key)[ch]

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
        val tag = sylKeys.joinToString("+")
        val singlePositions = ArrayList<Int>()
        val singleBucket = ArrayList<Pair<String, Int>>()
        val emittedFirstSingles = HashSet<String>()
        for ((pos, c) in cands.withIndex()) {
            val ncp = c.word.codePointCount(0, c.word.length)
            val ks = coveredSyls(c.coveredLen)
            if (ncp >= 2) continue
            singlePositions.add(pos)
            if (ks != 1) continue
            emittedFirstSingles.add(c.word)
            val native = nativeSingleFreq(source, sylKeys[0], c.word)
            if (native != null) singleBucket.add(c.word to native)
        }
        if (singlePositions.isNotEmpty()) {
            val start = singlePositions.first()
            val end = singlePositions.last()
            if (end - start + 1 != singlePositions.size) {
                rows.add("$tag\tW\tmulti-char candidates split the single segment between $start and $end")
            }
            if (start > PinyinDecoder.STAGED_REAL_WORD_SLOTS) {
                rows.add("$tag\tS\tthe single segment starts at $start, past the real word slots")
            }
        }
        var prev = Int.MAX_VALUE
        for ((w, f) in singleBucket) {
            if (f > prev) rows.add("$tag\tO1\t$w@$f after a lower-freq same-reading single")
            prev = f
        }
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
        return lockedOrderViolations(
            t9Dict, syls, d.decodeCoveredAtomic(input, 30, cuts),
            checkLossless = T9Pinyin.segment(syls[0])?.size == 1,
        )
    }

    @Test fun e7_lockedOrderingInvariant_allPairs_bothKeyspaces() {
        assumeTrue("full sweep gated: set AEGIS_AUDIT_FULL=1", fullEnabled())
        assumeTrue(FullDictTestAssets.available(dictFile, t9File, lmFile, jianpinFile))
        val syls = runtimeSyllables()
        val dL = e6Decoder(letters = true)
        val dT = e6Decoder(letters = false)
        val rows = ArrayList<String>()
        var pairsChecked = 0L
        var done = 0
        for (s1 in syls) {
            for (s2 in syls) {
                rows.addAll(lockedLetter(dL, s1, s2))
                rows.addAll(lockedDigit(dT, s1, s2))
                pairsChecked++
            }
            done += syls.size
            if (done % (syls.size * 50) == 0) println("[E7] ~$done/${syls.size * syls.size}")
        }
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

    @Test fun e7b_lockedOrderingInvariant_representative_alwaysOn() {
        assumeTrue(FullDictTestAssets.available(dictFile, t9File, lmFile, jianpinFile))
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


    private data class GenWord(val syllables: List<String>, val word: String) {
        val reading: String get() = syllables.joinToString("")
    }

    private fun topSingle(s: String): String? =
        dict.exact(s).filter { isSingleChar(it.word) }.maxByOrNull { it.freq }?.word

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

    private val lmModel: CharBigramLM by lazy { CharBigramLM.fromFile(lmFile) }
    private val jianpinDict: BinaryDict by lazy { BinaryDict.fromFile(jianpinFile) }

    private fun userDecoder(letters: Boolean, um: UserModel): PinyinDecoder =
        if (letters) PinyinDecoder(dict, lmModel, userModel = um, initialsDict = jianpinDict)
        else PinyinDecoder(t9Dict, lmModel, userModel = um, aliasDict = dict)

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
        assumeTrue(FullDictTestAssets.available(dictFile, t9File, lmFile, jianpinFile))
        val words = generatedUserWords(runtimeSyllables(), listOf("shi", "en"))
        assertTrue("generator produced a diverse word set", words.size > 200)
        val rows = runFreeTypingOrdering(words)
        assertTrue("E8 free-typing ordering with user words merged must be zero (${rows.size}): ${rows.take(8)}", rows.isEmpty())
    }

    @Test fun e8_userWords_freeTypingOrdering_full() {
        assumeTrue("full sweep gated: set AEGIS_AUDIT_FULL=1", fullEnabled())
        assumeTrue(FullDictTestAssets.available(dictFile, t9File, lmFile, jianpinFile))
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
        assumeTrue(FullDictTestAssets.available(dictFile, t9File, lmFile, jianpinFile))
        val words = generatedUserWords(runtimeSyllables(), listOf("shi", "en"))
        val rows = runLockedOrdering(words)
        assertTrue("E8 locked ordering with user words merged must be zero (${rows.size}): ${rows.take(8)}", rows.isEmpty())
    }

    @Test fun e8_userWords_lockedOrdering_full() {
        assumeTrue("full sweep gated: set AEGIS_AUDIT_FULL=1", fullEnabled())
        assumeTrue(FullDictTestAssets.available(dictFile, t9File, lmFile, jianpinFile))
        val words = generatedUserWords(runtimeSyllables(), listOf("shi", "de", "hao", "jian", "guo", "cong", "en"))
        val rows = runLockedOrdering(words)
        assertTrue("E8 locked ordering violations must be zero (${rows.size}): ${rows.take(8)}", rows.isEmpty())
    }

    @Test fun e8_userWords_recall_bothKeyspaces_andLocked() {
        assumeTrue(FullDictTestAssets.available(dictFile, t9File, lmFile, jianpinFile))
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


    private fun singlesByFreq(key: String): List<String> =
        dict.exact(key).filter { isSingleChar(it.word) }.sortedByDescending { it.freq }.map { it.word }

    private inner class MarginOracle(val source: BinaryDict, val t9: Boolean) {
        val lnTotal = ln(source.totalFreq.coerceAtLeast(1).toDouble())
        private fun aliases(key: String): List<String> =
            (if (t9) PinyinDecoder.T9_INPUT_ALIASES[key] else PinyinDecoder.INPUT_ALIASES[key]).orEmpty()
        private fun cps(w: String): List<String> {
            val o = ArrayList<String>(4); var i = 0
            while (i < w.length) { val c = w.codePointAt(i); o.add(String(Character.toChars(c))); i += Character.charCount(c) }
            return o
        }
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
        fun uwScoreNoBoost(key: String, uw: String): Double {
            val ncp = uw.codePointCount(0, uw.length)
            return ln(uwFreq(key, uw).toInt().coerceAtLeast(1).toDouble()) - lnTotal - (ncp - 1) * lnTotal
        }
    }

    private val boost1: Double by lazy {
        UserModel().apply { recordWord("zz", "占位", 1L, incrementCount = true) }.wordBoost("占位")
    }

    private class Pos0Stats {
        var cases = 0; var casesT9 = 0; var canonical = 0; var strong = 0; var weak = 0
        var weakFlips = 0; var weakFlips2cp = 0; var weakFlipsT9 = 0
        var userWordListed = 0; var userWordListedT9 = 0
        var userWordDisplacedNatural = 0; var userWordDisplacedNaturalT9 = 0
        var topChanged = 0; var topChangedT9 = 0
        var retainFree = 0; var retainPickable = 0; var retainPieces = 0; var retainAtomic = 0
        var retainResegment = 0; var retainJianpinGuess = 0
        var canonicalTieSwap = 0
        var weakFlipUnseenBigram = 0
        var canonicalMarginMin = Double.MAX_VALUE
        val violations = ArrayList<String>()
    }

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
            val listL = dLu.decodeCovered(r, 30).map { it.word }
            val topL = listL.firstOrNull()
            if (uw in listL) st.userWordListed++
            if (topL != nat) {
                st.topChanged++
                if (topL == uw) st.userWordDisplacedNatural++
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
            val digits = T9Pinyin.toT9(r)
            val natT = natTCache.getOrPut(digits) { dT.decodeCovered(digits, 30).firstOrNull()?.word }
            if (natT != null) {
                st.casesT9++
                val listT = dTu.decodeCovered(digits, 30).map { it.word }
                val topT = listT.firstOrNull()
                if (uw in listT) st.userWordListedT9++
                if (topT != natT) {
                    st.topChangedT9++
                    if (topT == uw) st.userWordDisplacedNaturalT9++
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

    private fun canonicalTieSwap(source: BinaryDict, key: String, nat: String, top: String?, uw: String): Boolean {
        if (top == null || top == uw) return false
        val natF = source.exact(key).firstOrNull { it.word == nat }?.freq ?: return false
        val topF = source.exact(key).firstOrNull { it.word == top }?.freq ?: return false
        return natF == topF
    }

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
            return false
        }
        val cutsOwn = parse.dropLast(1).mapTo(sortedSetOf()) { it.second }
        if (cutsOwn != cutsDisplayed) {
            if (parse.all { it.third.codePointCount(0, it.third.length) == 1 }) {
                val sep = StringBuilder(key)
                for (c in cutsOwn.toList().asReversed()) sep.insert(c, PinyinDecoder.SEP)
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

    private fun natParseEdges(source: BinaryDict, t9: Boolean, includeJianpin: Boolean, key: String, nat: String): List<Triple<Int, Int, String>>? {
        val parts = ArrayList<String>(4)
        var i = 0
        while (i < nat.length) { val c = nat.codePointAt(i); parts.add(String(Character.toChars(c))); i += Character.charCount(c) }
        val n = key.length; val m = parts.size
        val edges = Array(n + 1) { IntArray(m + 1) { -1 } }
        fun spanWords(span: String): List<BinaryDict.WordFreq> =
            source.exact(span) +
                (if (t9) PinyinDecoder.T9_INPUT_ALIASES[span] else PinyinDecoder.INPUT_ALIASES[span])
                    .orEmpty().flatMap { dict.exact(it) } +
                (if (includeJianpin) jianpinDict.exact(span) else emptyList())
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

    private val lmTableFields by lazy {
        fun f(n: String): Any = CharBigramLM::class.java.getDeclaredField(n).apply { isAccessible = true }.get(lmModel)!!
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
            "boost1=${"%.3f".format(boost1)} " +
            "singleUse(compared=${st.cases}/${st.casesT9} userWordListed=${st.userWordListed}/${st.userWordListedT9} " +
            "userWordDisplacedNatural=${st.userWordDisplacedNatural}/${st.userWordDisplacedNaturalT9} topChanged=${st.topChanged}/${st.topChangedT9}) " +
            "violations=${st.violations.size}"

    private fun tuples2(syls: List<String>, tails: List<String>): Sequence<List<String>> = sequence {
        for (s1 in syls) for (s2 in tails) yield(listOf(s1, s2))
    }

    private fun tuples3Covering(syls: List<String>): Sequence<List<String>> = sequence {
        for (i in syls.indices) for (j in syls.indices) yield(listOf(syls[i], syls[j], syls[(i + j) % syls.size]))
    }

    @Test fun e9_pos0MarginAware_representative_alwaysOn() {
        assumeTrue(FullDictTestAssets.available(dictFile, t9File, lmFile, jianpinFile))
        val syls = runtimeSyllables()
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
        assertTrue("representative sweep: a word used once must not displace the natural reading at position 0: ${pos0Summary(st)}",
            st.userWordDisplacedNatural == 0 && st.userWordDisplacedNaturalT9 == 0)
        assertTrue("representative sweep: that comparison must be live — a non-empty population on both " +
            "keyspaces with the user word ranked among the candidates: ${pos0Summary(st)}",
            st.cases > 0 && st.casesT9 > 0 && st.userWordListed > 0 && st.userWordListedT9 > 0)
    }

    @Test fun e9_pos0MarginAware_full() {
        assumeTrue("scheduled sweep gated: set AEGIS_AUDIT_HEAVY=1", heavyEnabled())
        assumeTrue(FullDictTestAssets.available(dictFile, t9File, lmFile, jianpinFile))
        val syls = runtimeSyllables()
        val st2 = pos0Sweep(tuples2(syls, syls), progressEvery = 20000)
        val st3 = pos0Sweep(tuples3Covering(syls), progressEvery = 20000)
        writeTsv(File(outDir(), "ext_e9.tsv"), asFails(st2) + asFails(st3))
        File(outDir(), "ext_e9_summary.txt").writeText(
            "# $runStamp\nE9 — position-0 guard, margin-aware (canonical/strong zero-tolerance; weak = user's own word + retained)\n" +
                "pairs: ${pos0Summary(st2)}\ntriples: ${pos0Summary(st3)}\n"
        )
        assertTrue("E9 full pair violations must be zero: ${st2.violations.take(8)}", st2.violations.isEmpty())
        assertTrue("E9 full triple violations must be zero: ${st3.violations.take(8)}", st3.violations.isEmpty())
        assertTrue("E9 full pair sweep: a word used once must not displace the natural reading at position 0: ${pos0Summary(st2)}",
            st2.userWordDisplacedNatural == 0 && st2.userWordDisplacedNaturalT9 == 0)
        assertTrue("E9 full triple sweep: a word used once must not displace the natural reading at position 0: ${pos0Summary(st3)}",
            st3.userWordDisplacedNatural == 0 && st3.userWordDisplacedNaturalT9 == 0)
        assertTrue("E9 full pair sweep: that comparison must be live — a non-empty population on both " +
            "keyspaces with the user word ranked among the candidates: ${pos0Summary(st2)}",
            st2.cases > 0 && st2.casesT9 > 0 && st2.userWordListed > 0 && st2.userWordListedT9 > 0)
        assertTrue("E9 full triple sweep: that comparison must be live — a non-empty population on both " +
            "keyspaces with the user word ranked among the candidates: ${pos0Summary(st3)}",
            st3.cases > 0 && st3.casesT9 > 0 && st3.userWordListed > 0 && st3.userWordListedT9 > 0)
    }

    @Test fun e9_pos0_widenedTailBaseline() {
        assumeTrue("full sweep gated: set AEGIS_AUDIT_FULL=1", fullEnabled())
        assumeTrue(FullDictTestAssets.available(dictFile, t9File, lmFile, jianpinFile))
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
        File(outDir(), "ext_e9_baseline30.txt").writeText(
            "# $runStamp\nE9 widened-tail baseline (30 tails)\n${pos0Summary(st)}\n" +
                "weakFlipUnseenBigramRatio=${st.weakFlipUnseenBigram}/${st.weakFlips}\n"
        )
        assertTrue("baseline violations (canonical/strong flips, wrong seizer, lost natural) must be zero: " +
            "${st.violations.take(8)}", st.violations.isEmpty())
        assertTrue("widened baseline: a word used once must not displace the natural reading at position 0: ${pos0Summary(st)}",
            st.userWordDisplacedNatural == 0 && st.userWordDisplacedNaturalT9 == 0)
        assertTrue("widened baseline: that comparison must be live — a non-empty population on both " +
            "keyspaces with the user word ranked among the candidates: ${pos0Summary(st)}",
            st.cases > 0 && st.casesT9 > 0 && st.userWordListed > 0 && st.userWordListedT9 > 0)
    }

    @Test fun e9_riseCurve_productionConfig() {
        assumeTrue(FullDictTestAssets.available(dictFile, t9File, lmFile, jianpinFile))
        val syls = runtimeSyllables()
        val tails = syls.filterIndexed { i, _ -> i % 21 == 0 }
        val dL = e6Decoder(letters = true)
        val oracleL = MarginOracle(dict, t9 = false)
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
        for (c in byMargin.takeLast(6)) {
            assertTrue("a deep canonical default is not seized within tens of uses (${c.r} margin=${"%.2f".format(c.margin)})",
                rankAt(c, 64) >= 1)
        }
        fun rankAndLead(c: C, count: Int): Pair<Int, Boolean> {
            repeat(count) { um.recordWord(c.r, c.uw, it.toLong(), incrementCount = true) }
            val listed = dLu.decodeCovered(c.r, 30).map { it.word }
            um.removeWord(c.uw)
            val at = listed.indexOf(c.uw)
            val exact = dict.exact(c.r).filterNot { isSingleChar(it.word) }.map { it.word }.toSet()
            return at to (at >= 0 && listed.subList(0, at).all { it in exact })
        }
        var risers = 0
        val climbs = ArrayList<String>()
        for (c in deep + boundary) {
            val early = rankAndLead(c, 1).first
            val (late, leadsLate) = rankAndLead(c, HEAVY_USE)
            assertTrue("a heavily used word must stay recalled (${c.r} rank=$late)", late >= 0)
            assertTrue("at heavy use only a reading exact dictionary word may sit ahead of the user word " +
                "(${c.r} rank=$late)", leadsLate)
            if (late < early) risers++
            climbs.add("${c.r}\t${c.uw}\t$early\t$late")
        }
        File(outDir(), "ext_e9_risecurve.txt").writeText(
            "# $runStamp\nE9 rise curve, production config\n" +
                "canonical sample first-use rank histogram (rank -> cases): $firstUseRanks\n" +
                "risers (rank at 1 use -> rank at $HEAVY_USE uses): $risers of ${(deep + boundary).size}\n" +
                climbs.joinToString("\n", postfix = "\n")
        )
        assertTrue("more use must actually move some canonical user word up, otherwise learning is inert " +
            "(risers=$risers of ${(deep + boundary).size})", risers >= 1)
        val boost64 = UserModel().apply { repeat(64) { recordWord("zz", "占位", it.toLong(), incrementCount = true) } }.wordBoost("占位")
        val boost2048 = UserModel().apply { repeat(2048) { recordWord("zz", "占位", it.toLong(), incrementCount = true) } }.wordBoost("占位")
        val w = byMargin.firstOrNull { it.margin > boost64 && it.margin < boost2048 }
        assertTrue("a canonical reading must exist whose margin maps to hundreds-level seizing", w != null)
        val bandEarly = rankAndLead(w!!, 1).first
        val (bandLate, bandLeads) = rankAndLead(w, HEAVY_USE)
        assertTrue("the band canonical reading (${w.r}, margin=${"%.2f".format(w.margin)}) must lead every " +
            "assembled candidate at heavy use and never fall back (first use rank=$bandEarly, " +
            "rank at $HEAVY_USE=$bandLate, leads=$bandLeads)", bandLeads && bandLate <= bandEarly)
    }

    @Test fun e8_userWords_doNotDisturbAliasPresence() {
        assumeTrue(FullDictTestAssets.available(dictFile, lmFile, jianpinFile))
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
