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

import com.aegis.ime.decoder.EngineFixture
import com.aegis.ime.engine.DictEngine
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BothKeyboardsAtomicFixTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val fixture = EngineFixture.dict()

    private class Host : ImeHost {
        override fun commitText(text: CharSequence) {}
        override fun deleteBackward() {}
        override fun performEnter() {}
    }

    private fun controller(): KeyboardController =
        KeyboardController(Host(), DictEngine(fixture, fixture, null)).apply { attachView(InputView(ctx)) }

    private fun KeyboardController.type(s: String) = s.forEach { onKey(Key(it.toString(), output = it.toString())) }
    private fun KeyboardController.pick(reading: String) = onKey(Key(reading, output = reading, action = KeyAction.PICK_READING))
    private fun t9(letters: String): String = com.aegis.ime.decoder.T9Pinyin.toT9(letters)

    private fun nineKeyLocked(readings: List<String>): List<String> {
        val c = controller()
        c.switchTextLayoutForTest(nine = true)
        for (r in readings) { c.type(t9(r)); c.pick(r) }
        return c.candidateWords()
    }

    private fun nineKeyPartialLocked(locked: String, activeTail: String): Pair<String, List<String>> {
        val c = controller()
        c.switchTextLayoutForTest(nine = true)
        c.type(t9(locked)); c.pick(locked)
        c.type(t9(activeTail))
        return c.preeditForTest() to c.candidateWords()
    }

    private fun alphaSeparated(readings: List<String>): List<String> {
        val c = controller()
        c.switchTextLayoutForTest(nine = false)
        readings.forEachIndexed { i, r -> if (i > 0) c.onKey(Key("'", output = "'")); c.type(r) }
        return c.candidateWords()
    }

    private fun isSupp(s: String) = s.codePointCount(0, s.length) == 1 && Character.isSupplementaryCodePoint(s.codePointAt(0))

    private fun assertAtomic(label: String, words: List<String>, syllables: Int, topWord: String?, presentSentence: String?) {
        assertFalse("$label: no extension-area single in the top 10", words.take(10).any { isSupp(it) })
        assertFalse("$label: NO candidate contains 西安 (a bounded xian is never re-split)", words.any { it.contains("西安") })
        if (topWord != null) assertTrue("$label: $topWord must lead (#1/#2)", words.take(2).contains(topWord))
        if (presentSentence != null) assertTrue("$label: $presentSentence present", presentSentence in words)
    }

    private fun han(words: List<String>) = words.filter { w -> w.all { it.code > 0x2E80 } }

    private fun bothMatch(readings: List<String>, syllables: Int, topWord: String?, sentence: String?) {
        val nine = nineKeyLocked(readings)
        val alpha = alphaSeparated(readings)
        assertAtomic("9-key $readings", nine, syllables, topWord, sentence)
        assertAtomic("26-key $readings", alpha, syllables, topWord, sentence)
        assertEquals("both keyboards funnel to the SAME atomic decode for $readings", han(nine), han(alpha))
    }

    private fun alphaDrilled(reading: String): List<String> {
        val c = controller()
        c.switchTextLayoutForTest(nine = false)
        c.type(reading)
        c.onPickReadingIndex(0)
        return c.candidateWords()
    }

    @Test fun ciku_bothKeyboards() = bothMatch(listOf("ci", "ku"), 2, topWord = "词库", sentence = null)

    @Test fun jiujian_bothKeyboards() = bothMatch(listOf("jiu", "jian"), 2, topWord = "九键", sentence = null)

    @Test fun diuzi_bothKeyboards() = bothMatch(listOf("diu", "zi"), 2, topWord = null, sentence = "丢字")

    @Test fun bushixian_bothKeyboards_noXiAnAndKeepsBushixian() =
        bothMatch(listOf("bu", "shi", "xian"), 3, topWord = null, sentence = "不实现")

    @Test fun ninekey_and_alpha_grids_are_identical_for_every_input() {
        for (r in listOf(listOf("ci", "ku"), listOf("diu", "zi"), listOf("bu", "shi", "xian"), listOf("jiu", "jian"))) {
            assertEquals("identical decoded grid for $r", han(nineKeyLocked(r)), han(alphaSeparated(r)))
        }
    }

    @Test fun single_selected_xiang_stays_on_the_selected_reading() {
        val cases = listOf(
            "9-key locked xiang" to nineKeyLocked(listOf("xiang")),
            "26-key drilled xiang" to alphaDrilled("xiang"),
        )
        for ((label, words) in cases) {
            assertTrue("$label: common xiang homophones stay prominent", words.take(7).containsAll(listOf("向", "想", "相", "像", "香")))
            assertFalse("$label: no xian word leak", "西安" in words)
            assertFalse("$label: no xi prefix leak", "西" in words)
            assertFalse("$label: no xia prefix leak", "下" in words)
        }
    }

    @Test fun nineKey_partiallyLockedXiangThenKuCode_staysOnTheSelectedReading() {
        val (preedit, words) = nineKeyPartialLocked("xiang", "ku")

        assertTrue("preedit keeps selected xiang and a live tail, was $preedit", preedit.startsWith("xiang'"))
        assertTrue("common xiang homophones stay prominent", words.take(7).containsAll(listOf("向", "想", "相", "像", "香")))
        assertFalse("partial locked xiang must not leak xian candidates", "西安" in words)
        assertFalse("partial locked xiang must not leak xi prefix singles", "西" in words)
        assertFalse("partial locked xiang must not leak xia prefix singles", "下" in words)
    }
}
