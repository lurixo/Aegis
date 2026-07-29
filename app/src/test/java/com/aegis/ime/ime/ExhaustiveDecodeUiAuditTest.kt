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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExhaustiveDecodeUiAuditTest {

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

    private fun realEngine() = DictEngine(
        BinaryDict.fromFile(File(assets, FullDictTestAssets.DICT)),
        BinaryDict.fromFile(File(assets, FullDictTestAssets.T9)),
        CharBigramLM.fromFile(File(assets, FullDictTestAssets.LM)),
        initialsDict = BinaryDict.fromFile(File(assets, FullDictTestAssets.JIANPIN)),
    )
    private fun controller(e: CandidateEngine): KeyboardController =
        KeyboardController(Host(), e).apply { attachView(InputView(ctx)) }
    private fun type(c: KeyboardController, s: String) = s.forEach { c.onKey(Key(it.toString(), output = it.toString())) }
    private fun pick(c: KeyboardController, reading: String) =
        c.onKey(Key(reading, output = reading, action = KeyAction.PICK_READING))
    private fun isSingleChar(w: String): Boolean = w.codePointCount(0, w.length) == 1

    private val dict by lazy { BinaryDict.fromFile(File(assets, FullDictTestAssets.DICT)) }
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


    @Test fun deng_26key_readingLabelStartsWithDeng_afterFix() {
        assumeTrue(assetsPresent())
        val c = controller(realEngine())
        c.onKey(Key("", action = KeyAction.SWITCH_ALPHA))
        type(c, "deng")
        val shown = c.expandedReadings()
        assertEquals("26-key deng reading labels start with 'deng'", "deng", shown.firstOrNull())
        assertEquals(
            "26-key deng reading labels contain every legal leading option and the fallback",
            T9Pinyin.leftColumnLetterReadings("deng", 24),
            shown,
        )
        assertTrue("correct deng char 等 is present among candidates", "等" in c.candidateWords())
    }

    @Test fun deng_9key_lockDeng_yieldsDengChars_afterFix() {
        assumeTrue(assetsPresent())
        val c = controller(realEngine())
        c.onKey(Key("", action = KeyAction.SWITCH_NINE))
        type(c, T9Pinyin.toT9("deng"))
        assertTrue("9-key left column offers the reading 'deng'", "deng" in c.expandedReadings())
        pick(c, "deng")
        val dengChars = dictSingles("deng")
        val deChars = dictSingles("de")
        val shownSingles = c.candidateWords().filter { isSingleChar(it) }.toSet()
        assertTrue("a correct deng char is present (e.g. 等)", shownSingles.any { it in dengChars })
        val leakedDeOnly = (shownSingles intersect deChars).filter { it !in dengChars }
        assertTrue("locking 'deng' no longer leaks de-only chars (got $leakedDeOnly)", leakedDeOnly.isEmpty())
    }


    @Test fun controllerN1_26key_expandedReadings_writesReport() {
        assumeTrue(assetsPresent())
        val syls = runtimeSyllables()
        assertTrue("runtime SYLLABLES ~415: ${syls.size}", syls.size in 400..430)
        val fails = ArrayList<String>()
        for (s in syls) {
            val c = controller(realEngine())
            c.onKey(Key("", action = KeyAction.SWITCH_ALPHA))
            type(c, s)
            val shown = c.expandedReadings()
            val expected = T9Pinyin.leftColumnLetterReadings(s, 24)
            if (shown.firstOrNull() != s || shown != expected) {
                fails.add("$s\t${expected.joinToString("+")}\t${shown.joinToString("+")}")
            }
        }
        File(outDir(), "levelB_n1_26key.tsv").writeText(
            "input\texpectedReadings\tshownReadings\n" + fails.joinToString("\n") + if (fails.isNotEmpty()) "\n" else ""
        )
        File(outDir(), "levelB_n1_summary.txt").writeText(
            "Level B — controller 26-key expandedReadings() over ${syls.size} syllables\n" +
                "syllables whose first or complete UI reading options mismatch: ${fails.size}\n"
        )
        assertTrue("no 26-key reading-option mismatches remain after the fix: ${fails.take(10)}", fails.isEmpty())
    }


    @Test fun controllerN2Subset_9key_brokenLedPairs_noPrefixCharLeak_afterFix() {
        assumeTrue(assetsPresent())
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
            pick(c, s1)
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
