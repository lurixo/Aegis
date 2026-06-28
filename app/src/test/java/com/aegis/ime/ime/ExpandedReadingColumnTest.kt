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

import com.aegis.ime.decoder.Cand
import com.aegis.ime.decoder.Syllable
import com.aegis.ime.engine.CandidateEngine
import com.aegis.ime.ime.theme.ImePalette
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExpandedReadingColumnTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val palette = ImePalette.STATIC_LIGHT

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

    private val xieChars = listOf("写", "谢", "些")
    private val zheChars = listOf("这", "着", "折")
    private val niChars = listOf("你", "泥", "拟")
    private val miChars = listOf("米", "迷", "密")
    private val heChars = listOf("和", "河")
    private val haoChars = listOf("好", "号")

    private fun wordsFor(key: String): List<String> = when (key) {
        "943", "xie" -> listOf("写字", "谢")
        "zhe" -> listOf("这个", "着")
        "xian" -> listOf("先", "县")
        "6443", "nihe" -> listOf("你和")
        "mihe" -> listOf("弥合")
        "nihao" -> listOf("你好")
        else -> listOf("字")
    }

    private val engine = object : CandidateEngine {
        override fun candidates(composing: String, t9: Boolean): List<String> =
            candidatesCovered(composing, t9).map { it.word }

        override fun candidatesCovered(
            composing: String,
            t9: Boolean,
            cuts: Set<Int>,
            context: CharSequence,
        ): List<Cand> = wordsFor(composing).map { Cand(it, composing.length) }

        override fun candidatesForLockedReadingCovered(
            letters: String,
            cuts: Set<Int>,
            context: CharSequence,
        ): List<Cand> = if (letters == "xian" && 2 in cuts) {
            listOf(Cand("西安", letters.length))
        } else {
            wordsFor(letters).map { Cand(it, letters.length) }
        }

        override fun syllablesForReading(letters: String): List<Syllable> = when (letters) {
            "xie" -> listOf(Syllable("xie", 0, 3))
            "zhe" -> listOf(Syllable("zhe", 0, 3))
            "xian" -> listOf(Syllable("xian", 0, 4))
            "nihe" -> listOf(Syllable("ni", 0, 2), Syllable("he", 2, 4))
            "mihe" -> listOf(Syllable("mi", 0, 2), Syllable("he", 2, 4))
            "nihao" -> listOf(Syllable("ni", 0, 2), Syllable("hao", 2, 5))
            else -> emptyList()
        }

        override fun homophonesForReadingAt(letters: String, index: Int): List<String> = when {
            letters == "xie" -> xieChars
            letters == "zhe" -> zheChars
            letters == "nihe" && index == 0 -> niChars
            letters == "mihe" && index == 0 -> miChars
            letters == "nihao" && index == 0 -> niChars
            letters == "nihao" && index == 1 -> haoChars
            letters == "nihe" || letters == "mihe" -> heChars
            else -> emptyList()
        }
    }

    private fun out(s: String) = Key(s, output = s)
    private fun act(a: KeyAction) = Key("", action = a)

    private fun typed(nine: Boolean, input: String): Pair<RecordingHost, KeyboardController> {
        val host = RecordingHost()
        val c = KeyboardController(host, engine)
        c.switchTextLayoutForTest(nine)
        input.forEach { c.onKey(out(it.toString())) }
        return host to c
    }

    private fun nine(digits: String) = typed(nine = true, digits)

    private fun alpha(letters: String) = typed(nine = false, letters)

    private fun expanded(c: KeyboardController): InputView {
        val iv = InputView(ctx).apply { applyPalette(palette) }
        iv.onPickReading = { i -> c.onPickReadingIndex(i) }
        iv.onPickCandidate = { i -> c.onPickCandidate(i) }
        iv.onExpandClosed = { c.clearDrill() }
        c.attachView(iv)
        iv.showExpandedCandidates()
        return iv
    }

    private fun pick(c: KeyboardController, reading: String) {
        val index = c.expandedReadings().indexOf(reading)
        assertTrue("'$reading' must be offered, was ${c.expandedReadings()}", index >= 0)
        c.onPickReadingIndex(index)
    }

    @Test fun alpha_switch_of_a_separated_earlier_lock_leaves_backspace_stepping_correctly() {
        val (host, c) = alpha("'ni'hao")
        pick(c, "ni")
        pick(c, "hao")

        pick(c, "hao")

        assertEquals("the drill opens on the earlier syllable", 0, c.drilledSyllableForTest())
        assertEquals(niChars, c.candidateWords())

        pick(c, "n")

        assertEquals("the switched lock still covers the separator it was locked over", "n'i'hao", c.preeditForTest())
        assertEquals("dropping the later lock ends the drill", -1, c.drilledSyllableForTest())
        assertTrue("switching a reading never commits", host.commits.isEmpty())

        c.onKey(act(KeyAction.BACKSPACE))
        assertEquals("the first backspace undoes the one surviving lock", "'ni'hao", c.preeditForTest())
        c.onKey(act(KeyAction.BACKSPACE))
        assertEquals("the next backspace deletes a letter instead of stepping on nothing", "'ni'ha", c.preeditForTest())
    }

    @Test fun nine_key_same_width_switch_leaves_backspace_undoing_exactly_the_two_locks() {
        val (host, c) = nine("6443")
        pick(c, "ni")
        pick(c, "he")
        pick(c, "he")

        pick(c, "mi")

        assertEquals("mi'he", c.preeditForTest())
        c.onKey(act(KeyAction.BACKSPACE))
        assertEquals("the first backspace undoes the later lock", "mi'ge", c.preeditForTest())
        c.onKey(act(KeyAction.BACKSPACE))
        assertEquals("the second backspace undoes the switched lock", "ni'ge", c.preeditForTest())
        c.onKey(act(KeyAction.ENTER))
        assertEquals("both locks undone, all four digits intact", listOf("nige"), host.commits)
    }

    @Test fun alpha_expanded_panel_rail_switches_readings_under_the_finger() {
        val (_, c) = alpha("xian")
        val iv = expanded(c)
        val grid = iv.expandedGridForTest()
        val column = c.expandedReadings()
        assertTrue(grid.tapReadingForTest(column.indexOf("xian")))
        assertEquals("the rail keeps every same-letter reading after the lock", column, grid.renderedReadingTextsForTest())

        assertTrue(grid.tapReadingForTest(c.expandedReadings().indexOf("xi")))

        assertEquals("the grid follows the switched reading", listOf("西安"), grid.renderedCandidateTextsForTest())
        assertEquals("xi'an", c.preeditForTest())
    }
}
