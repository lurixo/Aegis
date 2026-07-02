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


    @Test fun deng_26key_readingLabelShowsDe_whileCharsAreCorrect() {
        assumeTrue(assetsPresent())
        val c = controller(realEngine())
        c.onKey(Key("", action = KeyAction.SWITCH_ALPHA))
        type(c, "deng")
        assertEquals("26-key deng reading label is the mis-segmented 'de'",
            listOf("de"), c.expandedReadings())
        val words = c.candidateWords()
        assertTrue("correct deng char 等 is present among candidates", "等" in words)
    }

    @Test fun deng_9key_lockDeng_yieldsDeSoundChars() {
        assumeTrue(assetsPresent())
        val c = controller(realEngine())
        c.onKey(Key("", action = KeyAction.SWITCH_NINE))
        type(c, T9Pinyin.toT9("deng"))
        assertTrue("9-key left column offers the reading 'deng'", "deng" in c.expandedReadings())
        pick(c, "deng")
        val words = c.candidateWords()
        val dengChars = dictSingles("deng")
        val deChars = dictSingles("de")
        val shownSingles = words.filter { isSingleChar(it) }.toSet()
        val leaked = shownSingles intersect deChars
        assertTrue("locking 'deng' surfaces de-sound chars (${leaked.take(6)}) that do not read deng",
            leaked.isNotEmpty() && leaked.any { it !in dengChars })
    }


    @Test fun controllerN1_26key_expandedReadingsLabel_writesReport() {
        assumeTrue(assetsPresent())
        val syls = runtimeSyllables()
        assertTrue("runtime SYLLABLES ~415: ${syls.size}", syls.size in 400..430)
        val fails = ArrayList<String>()
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
        assertTrue("controller sweep must flag deng (label != deng)", fails.any { it.startsWith("deng\t") })
    }


    @Test fun controllerN2Subset_9key_brokenLedPairs_lockChars() {
        assumeTrue(assetsPresent())
        val broken = listOf("deng", "dang", "geng", "heng", "keng", "leng", "nang", "ning", "tang", "xing", "ying", "en")
        val seconds = listOf("hao", "ni", "shui")
        val fails = ArrayList<String>()
        for (s1 in broken) for (s2 in seconds) {
            val c = controller(realEngine())
            c.onKey(Key("", action = KeyAction.SWITCH_NINE))
            type(c, T9Pinyin.toT9(s1))
            pick(c, s1)
            val singles = c.candidateWords().filter { isSingleChar(it) }.toSet()
            val leaked = singles - dictSingles(s1)
            if (leaked.isNotEmpty()) fails.add("$s1+$s2\t$s1\t${leaked.take(6).joinToString(" ")}")
        }
        File(outDir(), "levelB_n2subset_9key.tsv").writeText(
            "pair\tlockedReading\tleakedChars\n" + fails.joinToString("\n") + if (fails.isNotEmpty()) "\n" else ""
        )
        assertTrue("locking a broken first syllable on 9-key leaks wrong chars (propagation to pairs)",
            fails.isNotEmpty())
    }
}
