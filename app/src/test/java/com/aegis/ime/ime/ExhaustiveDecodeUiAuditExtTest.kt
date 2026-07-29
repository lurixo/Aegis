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

package com.aegis.ime.ime

import com.aegis.ime.decoder.FullDictTestAssets
import com.aegis.ime.decoder.T9Pinyin
import com.aegis.ime.dict.BinaryDict
import com.aegis.ime.dict.CharBigramLM
import com.aegis.ime.engine.CandidateEngine
import com.aegis.ime.engine.DictEngine
import com.aegis.ime.engine.InputAssociations
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExhaustiveDecodeUiAuditExtTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val assets = FullDictTestAssets.directory
    private fun assetsPresent() = FullDictTestAssets.available(
        File(assets, FullDictTestAssets.DICT),
        File(assets, FullDictTestAssets.T9),
        File(assets, FullDictTestAssets.LM),
        File(assets, FullDictTestAssets.JIANPIN),
    )

    private class Host : ImeHost {
        override fun commitText(text: CharSequence) {}
        override fun deleteBackward() {}
        override fun performEnter() {}
    }

    private val engine: DictEngine by lazy {
        DictEngine(
            BinaryDict.fromFile(File(assets, FullDictTestAssets.DICT)),
            BinaryDict.fromFile(File(assets, FullDictTestAssets.T9)),
            CharBigramLM.fromFile(File(assets, FullDictTestAssets.LM)),
            initialsDict = BinaryDict.fromFile(File(assets, FullDictTestAssets.JIANPIN)),
        )
    }
    private fun controller(e: CandidateEngine = engine): KeyboardController =
        KeyboardController(Host(), e).apply { attachView(InputView(ctx)) }
    private fun type(c: KeyboardController, s: String) = s.forEach { c.onKey(Key(it.toString(), output = it.toString())) }
    private fun pick(c: KeyboardController, reading: String) =
        c.onKey(Key(reading, output = reading, action = KeyAction.PICK_READING))
    private fun isSingleChar(w: String): Boolean = w.codePointCount(0, w.length) == 1

    private val dict by lazy { BinaryDict.fromFile(File(assets, FullDictTestAssets.DICT)) }
    private fun dictSingles(key: String): Set<String> =
        dict.exact(key).filter { isSingleChar(it.word) }.map { it.word }.toSet()

    private fun allowed(reading: String): Set<String> =
        if (reading == "en") dictSingles("ng") else emptySet()

    private val classA1 = listOf("dang", "deng", "geng", "heng", "keng", "leng", "nang", "ning", "tang", "xing", "ying")
    private val controls = listOf("hao", "ni", "shui", "ma")

    private fun outDir(): File {
        val p = System.getenv("AEGIS_AUDIT_DIR") ?: System.getProperty("aegis.audit.dir") ?: "build/decode-audit"
        val d = File(p); d.mkdirs(); return d
    }

    private val runStamp: String by lazy {
        val rev = runCatching {
            ProcessBuilder("git", "rev-parse", "--short", "HEAD").redirectErrorStream(true)
                .start().inputStream.bufferedReader().readText().trim()
        }.getOrDefault("unknown")
        "run=${System.currentTimeMillis()} git=$rev"
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

    private val reverseSingles: Map<String, Set<String>> by lazy {
        val f = T9Pinyin::class.java.getDeclaredField("SYLLABLES").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") val syls = (f.get(T9Pinyin) as Set<String>)
        val m = HashMap<String, MutableSet<String>>()
        for (s in syls) for (ch in dictSingles(s)) m.getOrPut(ch) { HashSet() }.add(s)
        m
    }

    @Test fun e1b_sequentialLock_subsetPairs() {
        assumeTrue(assetsPresent())
        val pool = classA1 + controls
        val fails = ArrayList<String>()
        val classified = ArrayList<String>()
        for (s1 in pool) for (s2 in pool) {
            val c = controller()
            c.onKey(Key("", action = KeyAction.SWITCH_NINE))
            type(c, T9Pinyin.toT9(s1))
            if (s1 !in c.expandedReadings()) { fails.add("$s1+$s2\toption-S1\t${c.expandedReadings().take(8)}"); continue }
            pick(c, s1)
            type(c, T9Pinyin.toT9(s2))
            if (s2 !in c.expandedReadings()) { fails.add("$s1+$s2\toption-S2\t${c.expandedReadings().take(8)}"); continue }
            pick(c, s2)
            if (c.preeditForTest() != "$s1'$s2") {
                fails.add("$s1+$s2\tlabel\tpreedit='${c.preeditForTest()}'")
            }
            val o1 = dictSingles(s1) + allowed(s1)
            val o2 = dictSingles(s2) + allowed(s2)
            val injected = InputAssociations.lookup(s1 + s2)
            val raw = c.candidateWords()
            val words = when {
                injected.isEmpty() -> raw
                raw.size > injected.size && raw.subList(1, 1 + injected.size) == injected ->
                    listOf(raw.first()) + raw.drop(1 + injected.size)
                raw.take(injected.size) == injected -> raw.drop(injected.size)
                else -> { fails.add("$s1+$s2\tsplice-contract\t${raw.take(6)} vs injected=$injected"); raw }
            }
            val leak1 = words.filter { isSingleChar(it) && it !in o1 }
            if (leak1.isNotEmpty()) fails.add("$s1+$s2\tchars-S1\t${leak1.take(6)}")
            val dictWords = dict.exact(s1 + s2).map { it.word }.toSet()
            for (w in words) {
                if (w.codePointCount(0, w.length) != 2) continue
                val cp1 = String(Character.toChars(w.codePointBefore(w.length)))
                if (cp1 in o2) continue
                if (w in dictWords) {
                    when (lockVerdict(w, s1 + s2, setOf(s1.length))) {
                        "crossing" -> fails.add("$s1+$s2\tcrossing-escaped\t$w [1]=$cp1")
                        "unverifiable" -> classified.add("$s1+$s2\tdictword-unverifiable\t$w [1]=$cp1 (standalone: ${reverseSingles[cp1]?.sorted()?.joinToString(",") ?: "none"})")
                        else -> fails.add("$s1+$s2\taligned-but-mismatch\t$w [1]=$cp1")
                    }
                } else {
                    fails.add("$s1+$s2\tchars-S2\t$w[1]=$cp1")
                }
            }
            if (dictSingles(s1).isNotEmpty() && words.none { isSingleChar(it) && it in o1 }) {
                fails.add("$s1+$s2\tno-S1-char\tno correct S1 single shown")
            }
        }
        val rows = fails.map { "$it" } + classified
        File(outDir(), "ext_e1b.tsv").writeText(
            "# $runStamp\npair\tissue\tdetail\n" + rows.joinToString("\n") + if (rows.isNotEmpty()) "\n" else ""
        )
        File(outDir(), "ext_e1b_summary.txt").writeText(
            "# $runStamp\nE1-B — 9-key sequential double-lock (controller)\npairs covered: ${pool.size * pool.size}\n" +
                "true violations: ${fails.size}\nclassified dictword-unverifiable rows: " +
                "${classified.count { "\tdictword-unverifiable\t" in it }}\n"
        )
        assertTrue("E1-B sequential-lock TRUE violations: ${fails.take(8)}", fails.isEmpty())
    }

    @Test fun e23b_drillPartialCommitRedrill_subsetPairs() {
        assumeTrue(assetsPresent())
        val cases = classA1.flatMap { a -> listOf("hao" to a, "ni" to a, a to "hao", a to "ni") } + ("deng" to "deng")
        val fails = ArrayList<String>()
        for ((s1, s2) in cases.distinct()) {
            val c = controller()
            c.onKey(Key("", action = KeyAction.SWITCH_ALPHA))
            type(c, s1 + s2)
            val firstReadings = c.expandedReadings()
            val expectedFirstReadings = T9Pinyin.leftColumnLetterReadings(s1 + s2, 24)
            if (firstReadings != expectedFirstReadings || s1 !in firstReadings) {
                fails.add("$s1+$s2\tlabel-S1\texpected=$expectedFirstReadings actual=$firstReadings"); continue
            }
            c.onPickReadingIndex(firstReadings.indexOf(s1))
            c.onPickReadingIndex(c.expandedReadings().indexOf(s1))
            val o1 = dictSingles(s1) + allowed(s1)
            val grid1 = c.candidateWords()
            val leak1 = grid1.filter { it !in o1 }
            if (leak1.isNotEmpty()) fails.add("$s1+$s2\tdrill1-leak\t${leak1.take(6)}")
            val idx = grid1.indexOfFirst { it in dictSingles(s1) }
            if (idx < 0) { fails.add("$s1+$s2\tdrill1-empty\tno S1 char to pick"); continue }
            c.onPickCandidate(idx)
            val secondReadings = c.expandedReadings()
            val expectedSecondReadings = T9Pinyin.leftColumnLetterReadings(s2, 24)
            if (secondReadings != expectedSecondReadings || s2 !in secondReadings) {
                fails.add(
                    "$s1+$s2\tlabel-S2\texpected=$expectedSecondReadings actual=$secondReadings " +
                        "prefix='${c.composingPrefix()}'"
                )
                continue
            }
            c.onPickReadingIndex(secondReadings.indexOf(s2))
            c.onPickReadingIndex(c.expandedReadings().indexOf(s2))
            val o2 = dictSingles(s2) + allowed(s2)
            val grid2 = c.candidateWords()
            val leak2 = grid2.filter { it !in o2 }
            if (leak2.isNotEmpty()) fails.add("$s1+$s2\tdrill2-leak\t${leak2.take(6)}")
            if (dictSingles(s2).isNotEmpty() && grid2.none { it in dictSingles(s2) }) {
                fails.add("$s1+$s2\tdrill2-empty\tno S2 chars after partial commit")
            }
        }
        File(outDir(), "ext_e23b.tsv").writeText(
            "# $runStamp\npair\tissue\tdetail\n" + fails.joinToString("\n") + if (fails.isNotEmpty()) "\n" else ""
        )
        File(outDir(), "ext_e23b_summary.txt").writeText(
            "# $runStamp\nE2/E3-B — 26-key drill + partial commit + re-drill (controller)\ncases covered: ${cases.distinct().size}\nviolations: ${fails.size}\n"
        )
        assertTrue("E2/E3-B drill/partial-commit violations: ${fails.take(8)}", fails.isEmpty())
    }
}
