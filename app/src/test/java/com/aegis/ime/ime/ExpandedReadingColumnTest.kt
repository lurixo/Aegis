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
import com.aegis.ime.decoder.T9Pinyin
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

    private fun typed(layout: KeyAction, input: String): Pair<RecordingHost, KeyboardController> {
        val host = RecordingHost()
        val c = KeyboardController(host, engine)
        c.onKey(act(layout))
        input.forEach { c.onKey(out(it.toString())) }
        return host to c
    }

    private fun nine(digits: String) = typed(KeyAction.SWITCH_NINE, digits)

    private fun alpha(letters: String) = typed(KeyAction.SWITCH_ALPHA, letters)

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

    @Test fun nine_key_lock_that_eats_the_input_keeps_the_whole_reading_column() {
        val (_, c) = nine("943")
        val before = c.expandedReadings()
        assertEquals("every reading of the key sequence is offered", T9Pinyin.leftColumnReadings("943", 24), before)
        assertTrue("xie among them, was $before", "xie" in before)
        assertTrue("zhe among them, was $before", "zhe" in before)

        pick(c, "xie")

        assertEquals("xie", c.preeditForTest())
        assertEquals("a lock that eats the input must not shrink the column", before, c.expandedReadings())
        assertEquals("the locked reading is the marked one", "xie", c.lockedHighlightReading())
        assertEquals(
            "the expanded column and the keyboard column offer the same readings",
            c.nineLeftColumn().map { it.label },
            c.expandedReadings(),
        )
    }

    @Test fun nine_key_column_switches_the_locked_reading_and_refreshes_the_panel() {
        val (host, c) = nine("943")
        pick(c, "xie")
        assertEquals(wordsFor("xie").first(), c.candidateWords().first())
        val column = c.expandedReadings()

        pick(c, "zhe")

        assertEquals("switching rewrites the locked reading", "zhe", c.preeditForTest())
        assertEquals("a same-width switch leaves the column in place", column, c.expandedReadings())
        assertEquals("the mark follows the switch", "zhe", c.lockedHighlightReading())
        assertEquals("the panel decodes the newly chosen reading", wordsFor("zhe").first(), c.candidateWords().first())
        assertTrue("the abandoned reading leaves the panel", wordsFor("xie").first() !in c.candidateWords())
        assertTrue("switching a reading never commits", host.commits.isEmpty())
    }

    @Test fun nine_key_column_switches_a_drilled_earlier_syllable_and_keeps_the_later_lock() {
        val (host, c) = nine("6443")
        pick(c, "ni")
        pick(c, "he")
        assertEquals("ni'he", c.preeditForTest())

        pick(c, "he")

        assertEquals("the drill opens on the earlier syllable", 0, c.drilledSyllableForTest())
        val column = c.expandedReadings()
        assertEquals(
            "the drilled syllable keeps every reading of its key sequence",
            T9Pinyin.leftColumnReadings("64", 24),
            column,
        )
        assertEquals("the drilled reading is the marked one", "ni", c.lockedHighlightReading())
        assertEquals(niChars, c.candidateWords())

        pick(c, "mi")

        assertEquals("switching an earlier syllable keeps the later lock", "mi'he", c.preeditForTest())
        assertEquals("a same-width switch leaves the column in place", column, c.expandedReadings())
        assertEquals("the mark follows the switch", "mi", c.lockedHighlightReading())
        assertEquals("the drill stays on the switched syllable", 0, c.drilledSyllableForTest())
        assertEquals("and the panel lists the new reading's homophones", miChars, c.candidateWords())
        assertTrue("switching a reading never commits", host.commits.isEmpty())
    }

    @Test fun nine_key_expanded_panel_rail_switches_readings_under_the_finger() {
        val (_, c) = nine("943")
        val iv = expanded(c)
        val grid = iv.expandedGridForTest()
        val column = c.expandedReadings()
        assertTrue(grid.tapReadingForTest(column.indexOf("xie")))
        assertEquals("the rail keeps every same-key reading after the lock", column, grid.renderedReadingTextsForTest())
        assertEquals(c.candidateWords(), grid.renderedCandidateTextsForTest())

        assertTrue(grid.tapReadingForTest(column.indexOf("zhe")))

        assertEquals("the rail is unchanged by the switch", column, grid.renderedReadingTextsForTest())
        assertEquals("the grid follows the switched reading", c.candidateWords(), grid.renderedCandidateTextsForTest())
        assertEquals("which now leads with the new reading", wordsFor("zhe").first(), grid.renderedCandidateTextsForTest().first())
        assertEquals(
            "the switched reading carries the mark",
            palette.candidateFirst,
            iv.expandedReadingTextColorForTest(column.indexOf("zhe")),
        )
        assertEquals(
            "the abandoned reading drops back to the plain color",
            palette.candidateText,
            iv.expandedReadingTextColorForTest(column.indexOf("xie")),
        )
    }

    @Test fun alpha_lock_that_eats_the_input_keeps_the_whole_reading_column() {
        val (_, c) = alpha("xian")
        val before = c.expandedReadings()
        assertEquals("every reading of the letters is offered", T9Pinyin.leftColumnLetterReadings("xian", 24), before)
        assertTrue("more than one reading to choose from, was $before", before.size > 1)

        pick(c, "xian")

        assertEquals("xian", c.preeditForTest())
        assertEquals("a lock that eats the input must not shrink the column", before, c.expandedReadings())
        assertEquals("the locked reading is the marked one", "xian", c.lockedHighlightReading())
    }

    @Test fun alpha_column_switches_the_locked_reading_and_refreshes_the_panel() {
        val (host, c) = alpha("xian")
        pick(c, "xian")
        assertEquals(wordsFor("xian"), c.candidateWords())

        pick(c, "xi")

        assertEquals("the switch hands the uncovered letters back to the buffer", "xi'an", c.preeditForTest())
        assertEquals("the mark follows the switch", "xi", c.lockedHighlightReading())
        assertTrue("the shorter reading stays selectable, was ${c.expandedReadings()}", "xi" in c.expandedReadings())
        assertEquals("the panel decodes against the new lock", listOf("西安"), c.candidateWords())
        assertTrue("switching a reading never commits", host.commits.isEmpty())
    }

    @Test fun alpha_column_keeps_a_drilled_syllable_switchable() {
        val (host, c) = alpha("nihao")
        pick(c, "ni")
        pick(c, "hao")

        pick(c, "hao")

        assertEquals("the drill opens on the earlier syllable", 0, c.drilledSyllableForTest())
        val column = c.expandedReadings()
        assertEquals(
            "the drilled syllable keeps every reading of its letters",
            T9Pinyin.leftColumnLetterReadings("ni", 24),
            column,
        )
        assertTrue("which is more than the drilled reading alone, was $column", column.size > 1)
        assertEquals("the drilled reading is the marked one", "ni", c.lockedHighlightReading())
        assertEquals(niChars, c.candidateWords())

        pick(c, "n")

        assertEquals("switching the drilled syllable rewrites the lock", "n'ihao", c.preeditForTest())
        assertEquals("a switch that uncovers letters ends the drill", -1, c.drilledSyllableForTest())
        assertTrue("the panel leaves the abandoned homophone grid", c.candidateWords() != niChars)
        assertTrue("switching a reading never commits", host.commits.isEmpty())
    }

    @Test fun alpha_switch_inside_a_separated_lock_span_keeps_the_separator_covered() {
        val (host, c) = alpha("ni'hao")
        pick(c, "ni")
        pick(c, "hao")
        assertEquals("ni'hao", c.preeditForTest())

        pick(c, "ha")

        assertEquals("the switched lock still covers the separator it was locked over", "ni'ha'o", c.preeditForTest())
        assertEquals("the mark follows the switch", "ha", c.lockedHighlightReading())
        assertTrue("switching a reading never commits", host.commits.isEmpty())

        c.onKey(act(KeyAction.BACKSPACE))
        assertEquals("the first backspace undoes the switched lock", "ni'hao", c.preeditForTest())
        c.onKey(act(KeyAction.BACKSPACE))
        assertEquals("the second backspace undoes the leading lock", "ni'hao", c.preeditForTest())
        c.onKey(act(KeyAction.BACKSPACE))
        assertEquals("only then do the typed letters go", "ni'ha", c.preeditForTest())
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
