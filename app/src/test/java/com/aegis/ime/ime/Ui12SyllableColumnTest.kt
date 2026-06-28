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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Ui12SyllableColumnTest {

    private class RecordingHost : ImeHost {
        val commits = mutableListOf<String>()
        val text = StringBuilder()
        override fun commitText(text: CharSequence) { commits.add(text.toString()); this.text.append(text) }
        override fun deleteBackward() {
            if (text.isNotEmpty()) text.delete(text.length - 1, text.length)
        }
        override fun performEnter() {}
        override fun textBeforeCursor(n: Int): CharSequence = text.substring(maxOf(0, text.length - n))
    }

    private fun out(s: String) = Key(s, output = s)
    private fun act(a: KeyAction) = Key("", action = a)

    private val empty = object : CandidateEngine {
        override fun candidates(composing: String, t9: Boolean): List<String> = emptyList()
    }

    private fun nineWithBuffer(digits: String): Pair<RecordingHost, KeyboardController> {
        val host = RecordingHost()
        val c = KeyboardController(host, empty)
        c.switchTextLayoutForTest(nine = true)
        digits.forEach { c.onKey(out(it.toString())) }
        return host to c
    }

    @Test fun nine_left_column_persists_the_last_syllable_after_locking_all() {
        val (_, c) = nineWithBuffer("6443")
        c.onPickReadingIndex(c.expandedReadings().indexOf("ni"))
        assertTrue("after locking ni the next syllable 'he' is offered", "he" in c.expandedReadings())
        c.onPickReadingIndex(c.expandedReadings().indexOf("he"))

        val col = c.expandedReadings()
        assertEquals("the expanded column keeps every reading of the locked key sequence",
            T9Pinyin.leftColumnReadings("43", 24), col)
        assertTrue("including the locked reading itself, was $col", "he" in col)
        assertTrue("and its same-key alternative 'ge', was $col", "ge" in col)
        val keys = c.nineLeftColumn().map { it.label }
        assertTrue("the keyboard column still offers the alternative reading 'ge', was $keys", "ge" in keys)
        assertEquals("both columns offer the very same readings", keys, col)
        assertTrue("the persisted column is all readings, never punctuation",
            c.nineLeftColumn().all { it.action == KeyAction.PICK_READING })
    }

    @Test fun repicking_the_persisted_last_syllable_swaps_its_reading_without_committing() {
        val (host, c) = nineWithBuffer("6443")
        c.onPickReadingIndex(c.expandedReadings().indexOf("ni"))
        c.onPickReadingIndex(c.expandedReadings().indexOf("he"))
        assertEquals("ni'he", c.preeditForTest())

        c.onKey(Key("ge", output = "ge", action = KeyAction.PICK_READING))
        assertEquals("re-pick swaps the last syllable's reading", "ni'ge", c.preeditForTest())
        assertTrue("re-picking a reading never commits to the editor", host.commits.isEmpty())
    }

    @Test fun backspace_from_the_persisted_column_undoes_locks_not_digits() {
        val (host, c) = nineWithBuffer("6443")
        c.onPickReadingIndex(c.expandedReadings().indexOf("ni"))
        c.onPickReadingIndex(c.expandedReadings().indexOf("he"))

        c.onKey(act(KeyAction.BACKSPACE))
        c.onKey(act(KeyAction.BACKSPACE))
        c.onKey(act(KeyAction.ENTER))

        assertEquals("both locks undone, all four digits intact → full pinyin", listOf("nige"), host.commits)
    }

    private fun lockAndDrillFirst(c: KeyboardController) {
        val reading = c.expandedReadings().first()
        c.onPickReadingIndex(0)
        c.onPickReadingIndex(c.expandedReadings().indexOf(reading))
    }


    private fun realEngine(): DictEngine? {
        val p = File("src/main/assets/aegis_dict.bin")
        val t = File("src/main/assets/aegis_t9.bin")
        val l = File("src/main/assets/aegis_lm.bin")
        if (!p.exists() || !t.exists() || !l.exists()) return null
        return DictEngine(BinaryDict.fromFile(p), BinaryDict.fromFile(t), CharBigramLM.fromFile(l))
    }

    private fun isSingleChar(word: String): Boolean = word.codePointCount(0, word.length) == 1
    private fun biangChar(): String = String(Character.toChars(0x30EDD))

    @Test fun real_dict_drill_surfaces_every_homophone_the_dict_holds() {
        val eng = realEngine(); assertTrue("dict assets present", eng != null)
        val dict = BinaryDict.fromFile(File("src/main/assets/aegis_dict.bin"))
        val heSet = dict.exact("he").filter { isSingleChar(it.word) }.map { it.word }.toSet()
        assertTrue("dict has a meaningful he set", heSet.size > 8)

        val c = KeyboardController(RecordingHost(), eng!!)
        c.switchTextLayoutForTest(nine = false)
        "heshui".forEach { c.onKey(out(it.toString())) }
        assertEquals("26-key starts with the first unresolved syllable", "he", c.expandedReadings().first())

        lockAndDrillFirst(c)
        val shown = c.candidateWords().toSet()
        assertTrue("the UI lists EVERY he 同音字 the dict holds (no re-cap)", shown.containsAll(heSet))
        assertTrue("…and more than the old 30-cap", c.candidateWords().size > 30 || heSet.size <= 30)
        assertTrue("和 reachable through the drill", "和" in shown)
    }


    @Test fun real_dict_biang_is_available_in_expanded_reading_paths() {
        val eng = realEngine(); assertTrue("dict assets present", eng != null)
        val engine = eng!!
        val rare = biangChar()
        val alpha = KeyboardController(RecordingHost(), engine)
        alpha.switchTextLayoutForTest(nine = false)
        "biang".forEach { alpha.onKey(out(it.toString())) }

        assertEquals("26-key exposes biang as the leading selectable reading", "biang", alpha.expandedReadings().first())
        lockAndDrillFirst(alpha)
        assertTrue("26-key biang drill includes the rare character", rare in alpha.candidateWords())

        val nine = KeyboardController(RecordingHost(), engine)
        nine.switchTextLayoutForTest(nine = true)
        T9Pinyin.toT9("biang").forEach { nine.onKey(out(it.toString())) }
        val readings = nine.expandedReadings()
        val biang = readings.indexOf("biang")
        assertTrue("9-key exposes biang as a lockable reading, was $readings", biang >= 0)
        nine.onPickReadingIndex(biang)
        assertTrue("9-key locked biang includes the rare character", rare in nine.candidateWords())
    }
}
