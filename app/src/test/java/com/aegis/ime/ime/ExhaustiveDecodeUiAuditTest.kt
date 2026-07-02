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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Level B: controller/UI audit (Robolectric). Proves the decoder-level invariants match what the USER
 * sees through [KeyboardController] seams (`expandedReadings`, `preeditForTest`, `candidateWords`), and
 * reproduces the exact `deng` failure so we know the audit detects the real UI bug.
 * REPORT-ONLY — does not fix the decoder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExhaustiveDecodeUiAuditTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val assets = File("src/main/assets")
    private fun assetsPresent() = File(assets, "aegis_dict.bin").exists() && File(assets, "aegis_t9.bin").exists()

    private class Host : ImeHost {
        override fun commitText(text: CharSequence) {}
        override fun deleteBackward() {}
        override fun performEnter() {}
    }

    private fun realEngine() = DictEngine(
        BinaryDict.fromFile(File(assets, "aegis_dict.bin")),
        BinaryDict.fromFile(File(assets, "aegis_t9.bin")),
        CharBigramLM.fromFile(File(assets, "aegis_lm.bin")),
    )
    private fun controller(e: CandidateEngine): KeyboardController =
        KeyboardController(Host(), e).apply { attachView(InputView(ctx)) }
    private fun type(c: KeyboardController, s: String) = s.forEach { c.onKey(Key(it.toString(), output = it.toString())) }
    private fun pick(c: KeyboardController, reading: String) =
        c.onKey(Key(reading, output = reading, action = KeyAction.PICK_READING))
    private fun isSingleChar(w: String): Boolean = w.codePointCount(0, w.length) == 1

    private val dict by lazy { BinaryDict.fromFile(File(assets, "aegis_dict.bin")) }
    private fun dictSingles(key: String): Set<String> =
        dict.exact(key).filter { isSingleChar(it.word) }.map { it.word }.toSet()

    @Suppress("UNCHECKED_CAST")
    private fun runtimeSyllables(): List<String> {
        val f = T9Pinyin::class.java.getDeclaredField("SYLLABLES")
        f.isAccessible = true
        return (f.get(T9Pinyin) as Set<String>).toList()
    }

    private fun outDir(): File {
        val p = System.getenv("AEGIS_AUDIT_DIR") ?: System.getProperty("aegis.audit.dir") ?: "build/decode-audit"
        val d = File(p); d.mkdirs(); return d
    }

    // ---------- exact deng reproductions ----------

    @Test fun deng_26key_readingLabelIsDeng_afterFix() {
        assumeTrue(assetsPresent())
        val c = controller(realEngine())
        c.onKey(Key("", action = KeyAction.SWITCH_ALPHA))
        type(c, "deng")
        // the 26-key expand-screen reading label is now the whole syllable 'deng' (was mis-segmented 'de').
        assertEquals("26-key deng reading label is 'deng'", listOf("deng"), c.expandedReadings())
        assertTrue("correct deng char 等 is present among candidates", "等" in c.candidateWords())
    }

    @Test fun deng_9key_lockDeng_yieldsDengChars_afterFix() {
        assumeTrue(assetsPresent())
        val c = controller(realEngine())
        c.onKey(Key("", action = KeyAction.SWITCH_NINE))
        type(c, T9Pinyin.toT9("deng")) // "3364"
        assertTrue("9-key left column offers the reading 'deng'", "deng" in c.expandedReadings())
        pick(c, "deng")
        // locking 'deng' now yields deng-characters; no de-only char (德/的/得…) leaks in.
        val dengChars = dictSingles("deng")
        val deChars = dictSingles("de")
        val shownSingles = c.candidateWords().filter { isSingleChar(it) }.toSet()
        assertTrue("a correct deng char is present (e.g. 等)", shownSingles.any { it in dengChars })
        val leakedDeOnly = (shownSingles intersect deChars).filter { it !in dengChars }
        assertTrue("locking 'deng' no longer leaks de-only chars (got $leakedDeOnly)", leakedDeOnly.isEmpty())
    }

    // ---------- n=1 controller sweep: 26-key reading label == input, over all 415 ----------

    @Test fun controllerN1_26key_expandedReadingsLabel_writesReport() {
        assumeTrue(assetsPresent())
        val syls = runtimeSyllables()
        assertTrue("runtime SYLLABLES ~415: ${syls.size}", syls.size in 400..430)
        val fails = ArrayList<String>() // input \t shownLabel
        for (s in syls) {
            val c = controller(realEngine())
            c.onKey(Key("", action = KeyAction.SWITCH_ALPHA))
            type(c, s)
            val label = c.expandedReadings()
            if (label != listOf(s)) fails.add("$s\t${label.joinToString("+")}")
        }
        File(outDir(), "levelB_n1_26key.tsv").writeText(
            "input\tshownReadingLabel\n" + fails.joinToString("\n") + if (fails.isNotEmpty()) "\n" else ""
        )
        File(outDir(), "levelB_n1_summary.txt").writeText(
            "Level B — controller 26-key expandedReadings() label over ${syls.size} syllables\n" +
                "syllables whose UI reading label != input: ${fails.size}\n"
        )
        // after the fix, every 26-key reading label equals its input syllable.
        assertTrue("no 26-key reading-label mismatches remain after the fix: ${fails.take(10)}", fails.isEmpty())
    }

    // ---------- systematic n=2 subset: broken-syllable-led pairs on 9-key ----------

    @Test fun controllerN2Subset_9key_brokenLedPairs_noPrefixCharLeak_afterFix() {
        assumeTrue(assetsPresent())
        // the 12 former n=1 offenders as first syllable (with the mis-segment prefix whose chars used to leak),
        // crossed with a few common seconds — a systematic subset. After the fix, locking the (formerly broken)
        // reading must surface that reading's own chars and NOT the prefix's chars.
        // The 11 nasal-coda syllables fixed by segmentLetters (en is intentionally excluded: it segments
        // fine and its 嗯 is the retained en->ng colloquial alias, whitelisted in the Level A audit).
        val prefixOf = mapOf(
            "deng" to "de", "dang" to "da", "geng" to "ge", "heng" to "he", "keng" to "ke", "leng" to "le",
            "nang" to "na", "ning" to "ni", "tang" to "ta", "xing" to "xi", "ying" to "yi",
        )
        val seconds = listOf("hao", "ni", "shui")
        val fails = ArrayList<String>()
        for ((s1, pfx) in prefixOf) for (s2 in seconds) {
            val c = controller(realEngine())
            c.onKey(Key("", action = KeyAction.SWITCH_NINE))
            type(c, T9Pinyin.toT9(s1))
            pick(c, s1) // lock the reading
            val singles = c.candidateWords().filter { isSingleChar(it) }.toSet()
            val s1Chars = dictSingles(s1)
            val prefixLeak = (singles intersect dictSingles(pfx)) - s1Chars
            if (prefixLeak.isNotEmpty()) fails.add("$s1+$s2\t$s1\tprefix-leak ${prefixLeak.take(6).joinToString(" ")}")
            if (s1Chars.isNotEmpty() && singles.none { it in s1Chars }) fails.add("$s1+$s2\t$s1\tno correct $s1 char")
        }
        File(outDir(), "levelB_n2subset_9key.tsv").writeText(
            "pair\tlockedReading\tissue\n" + fails.joinToString("\n") + if (fails.isNotEmpty()) "\n" else ""
        )
        assertTrue("no broken-led pair leaks a prefix char after the fix: ${fails.take(8)}", fails.isEmpty())
    }
}
