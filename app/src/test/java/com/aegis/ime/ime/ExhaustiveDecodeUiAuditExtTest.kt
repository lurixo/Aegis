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

import com.aegis.ime.decoder.T9Pinyin
import com.aegis.ime.dict.BinaryDict
import com.aegis.ime.dict.CharBigramLM
import com.aegis.ime.engine.CandidateEngine
import com.aegis.ime.engine.DictEngine
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

/**
 * Level B (Robolectric) companions for the blind-spot expansions: prove through the REAL
 * [KeyboardController] event path what [ExhaustiveDecodeAuditExtTest] proves at the decoder seam.
 *
 *  E1-B  9-key sequential locking — two real `PICK_READING` taps; systematic subset = (Class-A1 ∪
 *        controls)² so every former offender appears in BOTH positions.
 *  E2/E3-B  26-key drill + partial commit — `onPickReadingIndex(0)` drill, `onPickCandidate` partial
 *        pick of an S1 char, then re-drill of the remainder; the drilled grids and the advancing
 *        reading label must track `dict.exact` per position.
 *
 * Same conventions as the merged Level B audit (real assets, `assumeTrue` guards, TSVs to
 * `AEGIS_AUDIT_DIR`). REPORT-ONLY.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExhaustiveDecodeUiAuditExtTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val assets = File("src/main/assets")
    private fun assetsPresent() = File(assets, "aegis_dict.bin").exists() && File(assets, "aegis_t9.bin").exists()

    private class Host : ImeHost {
        override fun commitText(text: CharSequence) {}
        override fun deleteBackward() {}
        override fun performEnter() {}
    }

    // one engine for the whole class (decode is read-only here); a fresh controller per case
    private val engine: DictEngine by lazy {
        DictEngine(
            BinaryDict.fromFile(File(assets, "aegis_dict.bin")),
            BinaryDict.fromFile(File(assets, "aegis_t9.bin")),
            CharBigramLM.fromFile(File(assets, "aegis_lm.bin")),
        )
    }
    private fun controller(e: CandidateEngine = engine): KeyboardController =
        KeyboardController(Host(), e).apply { attachView(InputView(ctx)) }
    private fun type(c: KeyboardController, s: String) = s.forEach { c.onKey(Key(it.toString(), output = it.toString())) }
    private fun pick(c: KeyboardController, reading: String) =
        c.onKey(Key(reading, output = reading, action = KeyAction.PICK_READING))
    private fun isSingleChar(w: String): Boolean = w.codePointCount(0, w.length) == 1

    private val dict by lazy { BinaryDict.fromFile(File(assets, "aegis_dict.bin")) }
    private fun dictSingles(key: String): Set<String> =
        dict.exact(key).filter { isSingleChar(it.word) }.map { it.word }.toSet()

    /** Same allowlist as the audit (en→嗯 is expected behaviour). */
    private fun allowed(reading: String): Set<String> =
        if (reading == "en") setOf("嗯") else emptySet()

    private val classA1 = listOf("dang", "deng", "geng", "heng", "keng", "leng", "nang", "ning", "tang", "xing", "ying")
    private val controls = listOf("hao", "ni", "shui", "ma")

    private fun outDir(): File {
        val p = System.getenv("AEGIS_AUDIT_DIR") ?: System.getProperty("aegis.audit.dir") ?: "build/decode-audit"
        val d = File(p); d.mkdirs(); return d
    }

    /** Run provenance embedded in every TSV/summary so a stale-artifact mixup is detectable. */
    private val runStamp: String by lazy {
        val rev = runCatching {
            ProcessBuilder("git", "rev-parse", "--short", "HEAD").redirectErrorStream(true)
                .start().inputStream.bufferedReader().readText().trim()
        }.getOrDefault("unknown")
        "run=${System.currentTimeMillis()} git=$rev"
    }

    /** char -> syllables it has a STANDALONE single entry under (cross-parse evidence vs oracle gap). */
    private val reverseSingles: Map<String, Set<String>> by lazy {
        val f = T9Pinyin::class.java.getDeclaredField("SYLLABLES").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") val syls = (f.get(T9Pinyin) as Set<String>)
        val m = HashMap<String, MutableSet<String>>()
        for (s in syls) for (ch in dictSingles(s)) m.getOrPut(ch) { HashSet() }.add(s)
        m
    }

    // ============ E1-B · 9-key sequential locking through two real PICK_READING taps ============
    // Subset: (classA1 + controls)² = 15² = 225 ordered pairs — every Class-A1 syllable in BOTH positions.
    @Test fun e1b_sequentialLock_subsetPairs() {
        assumeTrue(assetsPresent())
        val pool = classA1 + controls
        val fails = ArrayList<String>()      // pair \t issue \t detail — TRUE violations (hard-asserted empty)
        val classified = ArrayList<String>() // cross-parse dict-word channel rows (reported, not hard-asserted)
        for (s1 in pool) for (s2 in pool) {
            val c = controller()
            c.onKey(Key("", action = KeyAction.SWITCH_NINE))
            type(c, T9Pinyin.toT9(s1))
            if (s1 !in c.expandedReadings()) { fails.add("$s1+$s2\toption-S1\t${c.expandedReadings().take(8)}"); continue }
            pick(c, s1)
            type(c, T9Pinyin.toT9(s2))
            if (s2 !in c.expandedReadings()) { fails.add("$s1+$s2\toption-S2\t${c.expandedReadings().take(8)}"); continue }
            pick(c, s2)
            // the preedit label must be exactly the locked sequence
            if (c.preeditForTest() != "$s1'$s2") {
                fails.add("$s1+$s2\tlabel\tpreedit='${c.preeditForTest()}'")
            }
            // shown candidates: 1-cp candidates are the first-unit homophones -> must read S1;
            // 2-cp candidates covering both units -> each codepoint must read its locked syllable.
            val o1 = dictSingles(s1) + allowed(s1)
            val o2 = dictSingles(s2) + allowed(s2)
            val words = c.candidateWords()
            val leak1 = words.filter { isSingleChar(it) && it !in o1 }
            if (leak1.isNotEmpty()) fails.add("$s1+$s2\tchars-S1\t${leak1.take(6)}")
            // 2-cp candidates whose trailing char does not read the locked S2: a candidate that is a dict
            // word keyed under the boundary-less S1+S2 is NOT exempted — it is the cross-parse dict-word
            // channel (the whole key surfaces regardless of the lock; user-visible), recorded as its own
            // class. Chars with a standalone entry under another syllable are hard
            // cross-parse evidence; chars with none anywhere are seed-dict oracle gaps. Non-dict-word
            // (beam-assembled) failures remain TRUE violations.
            val dictWords = dict.exact(s1 + s2).map { it.word }.toSet()
            for (w in words) {
                if (w.codePointCount(0, w.length) != 2) continue
                val cp1 = String(Character.toChars(w.codePointBefore(w.length)))
                if (cp1 in o2) continue
                if (w in dictWords) {
                    val cls = if (!reverseSingles[cp1].isNullOrEmpty()) "cross-parse-dict-word" else "dictword-oracle-gap"
                    classified.add("$s1+$s2\t$cls\t$w [1]=$cp1 (standalone: ${reverseSingles[cp1]?.sorted()?.joinToString(",") ?: "none"})")
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
                "true violations: ${fails.size}\nclassified cross-parse-dict-word rows: " +
                "${classified.count { "\tcross-parse-dict-word\t" in it }}\n" +
                "classified dictword-oracle-gap rows: ${classified.count { "\tdictword-oracle-gap\t" in it }}\n"
        )
        assertTrue("E1-B sequential-lock TRUE violations: ${fails.take(8)}", fails.isEmpty())
    }

    // ===== E2/E3-B · 26-key drill + partial commit + re-drill through real controller events =====
    // Subset: classA1 x {hao,ni} + {hao,ni} x classA1 + the (deng,deng) diagonal = 45 ordered pairs.
    @Test fun e23b_drillPartialCommitRedrill_subsetPairs() {
        assumeTrue(assetsPresent())
        val cases = classA1.flatMap { a -> listOf("hao" to a, "ni" to a, a to "hao", a to "ni") } + ("deng" to "deng")
        val fails = ArrayList<String>()
        for ((s1, s2) in cases.distinct()) {
            val c = controller()
            c.onKey(Key("", action = KeyAction.SWITCH_ALPHA))
            type(c, s1 + s2)
            // pre-drill label = first unresolved syllable
            if (c.expandedReadings() != listOf(s1)) {
                fails.add("$s1+$s2\tlabel-S1\t${c.expandedReadings()}"); continue
            }
            // drill syllable 0: grid = S1's complete homophones
            c.onPickReadingIndex(0)
            val o1 = dictSingles(s1) + allowed(s1)
            val grid1 = c.candidateWords()
            val leak1 = grid1.filter { it !in o1 }
            if (leak1.isNotEmpty()) fails.add("$s1+$s2\tdrill1-leak\t${leak1.take(6)}")
            // partial commit: pick the first S1 char from the drilled grid
            val idx = grid1.indexOfFirst { it in dictSingles(s1) }
            if (idx < 0) { fails.add("$s1+$s2\tdrill1-empty\tno S1 char to pick"); continue }
            c.onPickCandidate(idx)
            // the remainder relabels to S2
            if (c.expandedReadings() != listOf(s2)) {
                fails.add("$s1+$s2\tlabel-S2\t${c.expandedReadings()} prefix='${c.composingPrefix()}'"); continue
            }
            // re-drill: grid = S2's complete homophones
            c.onPickReadingIndex(0)
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
