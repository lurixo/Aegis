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
import com.aegis.ime.engine.DictEngine
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreeditCaretEditingTest {

    private class RecordingHost : ImeHost {
        val commits = mutableListOf<String>()
        val text = StringBuilder()
        override fun commitText(text: CharSequence) { commits.add(text.toString()); this.text.append(text) }
        override fun deleteBackward() { if (text.isNotEmpty()) text.setLength(text.length - 1) }
        override fun performEnter() {}
        override fun textBeforeCursor(n: Int): CharSequence = text.substring(maxOf(0, text.length - n))
    }

    private data class Case(val word: String, val letters: String, val digits: String, val firstReading: String)

    private val corpus = listOf(
        Case("你好", "nihao", "64426", "ni"),
        Case("中国", "zhongguo", "94664486", "zhong"),
        Case("我们", "women", "96636", "wo"),
    )

    private fun out(s: String) = Key(s, output = s)
    private fun act(a: KeyAction) = Key("", action = a)

    private fun engine(): DictEngine {
        val dict = FullDictTestAssets.file(FullDictTestAssets.DICT)
        val t9 = FullDictTestAssets.file(FullDictTestAssets.T9)
        val lm = FullDictTestAssets.file(FullDictTestAssets.LM)
        assumeTrue(FullDictTestAssets.available(dict, t9, lm))
        return DictEngine(BinaryDict.fromFile(dict), BinaryDict.fromFile(t9), CharBigramLM.fromFile(lm))
    }

    private fun controller(engine: DictEngine, nine: Boolean, input: String): Pair<RecordingHost, KeyboardController> {
        val host = RecordingHost()
        val c = KeyboardController(host, engine)
        c.switchTextLayoutForTest(nine)
        input.forEach { c.onKey(out(it.toString())) }
        return host to c
    }

    private fun Case.input(nine: Boolean) = if (nine) digits else letters
    private fun Case.extra(nine: Boolean) = if (nine) "9" else "x"

    private fun forEachLayout(block: (nine: Boolean, case: Case) -> Unit) {
        for (nine in listOf(false, true)) for (case in corpus) block(nine, case)
    }

    private fun model(c: KeyboardController): PreeditModel = requireNotNull(c.preeditModelForTest())

    @Test fun tapping_the_preedit_enters_editing_with_the_caret_at_the_end() {
        forEachLayout { nine, case ->
            val (_, c) = controller(engine(), nine, case.input(nine))
            val before = c.preeditForTest()
            val cands = c.candidateWords()
            assertFalse(c.preeditEditing())
            c.onPreeditTap()
            assertTrue("$case nine=$nine enters editing", c.preeditEditing())
            assertEquals(case.input(nine).length, c.preeditCaretForTest())
            assertEquals(before, c.preeditForTest())
            assertEquals(cands, c.candidateWords())
            val m = model(c)
            assertEquals(before, m.text)
            assertEquals(before.length, m.displayCaret())
            assertTrue(case.word in cands)
        }
    }

    @Test fun typing_at_a_mid_caret_inserts_there_and_backspace_removes_it_again() {
        forEachLayout { nine, case ->
            val (_, c) = controller(engine(), nine, case.input(nine))
            val before = c.preeditForTest()
            c.onPreeditCaret(2)
            c.onKey(out(case.extra(nine)))
            assertEquals(3, c.preeditCaretForTest())
            val edited = c.preeditForTest()
            assertNotEquals("$case nine=$nine preedit changes live", before, edited)
            val raw = case.input(nine)
            assertEquals(raw.substring(0, 2) + case.extra(nine) + raw.substring(2), rawOf(c, nine))
            c.onKey(act(KeyAction.BACKSPACE))
            assertEquals(2, c.preeditCaretForTest())
            assertEquals(before, c.preeditForTest())
            assertTrue(c.preeditEditing())
            assertTrue(case.word in c.candidateWords())
        }
    }

    @Test fun backspace_after_a_locked_reading_unlocks_it_before_deleting_anything() {
        forEachLayout { nine, case ->
            val (_, c) = controller(engine(), nine, case.input(nine))
            c.onPickReadingIndex(c.expandedReadings().indexOf(case.firstReading))
            val lockedPreedit = c.preeditForTest()
            assertTrue(lockedPreedit.startsWith(case.firstReading + "'"))
            val lockLen = case.firstReading.length
            c.onPreeditCaret(lockLen)
            assertEquals(listOf(0 until lockLen), model(c).lockedRanges)
            c.onKey(act(KeyAction.BACKSPACE))
            assertEquals("$case nine=$nine lock removed, nothing deleted", lockedPreedit.length, c.preeditForTest().length)
            assertTrue(model(c).lockedRanges.isEmpty())
            assertEquals(lockLen, c.preeditCaretForTest())
            c.onKey(act(KeyAction.BACKSPACE))
            val raw = case.input(nine)
            assertEquals(raw.substring(0, lockLen - 1) + raw.substring(lockLen), rawOf(c, nine))
            assertEquals(lockLen - 1, c.preeditCaretForTest())
        }
    }

    @Test fun backspace_inside_a_locked_reading_only_unlocks() {
        forEachLayout { nine, case ->
            val (_, c) = controller(engine(), nine, case.input(nine))
            c.onPickReadingIndex(c.expandedReadings().indexOf(case.firstReading))
            c.onPreeditCaret(1)
            assertFalse(model(c).lockedRanges.isEmpty())
            c.onKey(act(KeyAction.BACKSPACE))
            assertTrue(model(c).lockedRanges.isEmpty())
            assertEquals(case.input(nine), rawOf(c, nine))
            assertEquals(1, c.preeditCaretForTest())
        }
    }

    @Test fun typing_after_the_last_lock_keeps_it_locked() {
        forEachLayout { nine, case ->
            val (_, c) = controller(engine(), nine, case.input(nine))
            c.onPickReadingIndex(c.expandedReadings().indexOf(case.firstReading))
            c.onPreeditCaret(case.input(nine).length)
            c.onKey(out(case.extra(nine)))
            assertEquals(listOf(case.firstReading.indices), model(c).lockedRanges)
            assertTrue(c.preeditForTest().startsWith(case.firstReading + "'"))
        }
    }

    @Test fun typing_inside_a_lock_unlocks_it_and_inserts() {
        forEachLayout { nine, case ->
            val (_, c) = controller(engine(), nine, case.input(nine))
            c.onPickReadingIndex(c.expandedReadings().indexOf(case.firstReading))
            c.onPreeditCaret(1)
            c.onKey(out(case.extra(nine)))
            assertTrue(model(c).lockedRanges.isEmpty())
            val raw = case.input(nine)
            assertEquals(raw.substring(0, 1) + case.extra(nine) + raw.substring(1), rawOf(c, nine))
        }
    }

    @Test fun segment_at_the_caret_adds_a_cut_and_backspace_on_it_removes_the_cut() {
        forEachLayout { nine, case ->
            val (_, c) = controller(engine(), nine, case.input(nine))
            val before = c.preeditForTest()
            c.onPreeditCaret(3)
            c.onKey(act(KeyAction.SEGMENT))
            val cut = c.preeditForTest()
            assertNotEquals(before, cut)
            assertEquals(case.input(nine), rawOf(c, nine))
            assertEquals(3, c.preeditCaretForTest())
            assertEquals(3, model(c).rawIndexForDisplay(model(c).displayCaret()))
            c.onKey(act(KeyAction.BACKSPACE))
            assertEquals("$case nine=$nine backspace on the cut removes only the cut", before, c.preeditForTest())
            assertEquals(case.input(nine), rawOf(c, nine))
        }
    }

    @Test fun leaving_edit_mode_keeps_the_buffer_and_resumes_tail_typing() {
        forEachLayout { nine, case ->
            val (_, c) = controller(engine(), nine, case.input(nine))
            c.onPreeditCaret(1)
            c.onPreeditEditDone()
            assertFalse(c.preeditEditing())
            assertEquals(case.input(nine), rawOf(c, nine))
            c.onKey(out(case.extra(nine)))
            assertEquals(case.input(nine) + case.extra(nine), rawOf(c, nine))
        }
    }

    @Test fun committing_a_candidate_ends_editing() {
        forEachLayout { nine, case ->
            val (host, c) = controller(engine(), nine, case.input(nine))
            c.onPreeditCaret(2)
            c.onPickCandidate(c.candidateWords().indexOf(case.word))
            assertFalse(c.preeditEditing())
            assertEquals(listOf(case.word), host.commits)
        }
    }

    @Test fun deleting_the_whole_buffer_leaves_editing() {
        forEachLayout { nine, case ->
            val (_, c) = controller(engine(), nine, case.input(nine))
            c.onPreeditCaret(case.input(nine).length)
            repeat(case.input(nine).length) { c.onKey(act(KeyAction.BACKSPACE)) }
            assertFalse(c.preeditEditing())
            assertEquals("", c.preeditForTest())
        }
    }

    @Test fun nine_key_left_column_follows_the_caret_edit() {
        val eng = engine()
        val (_, c) = controller(eng, true, "64426")
        val before = c.expandedReadings()
        c.onPreeditCaret(0)
        c.onKey(out("9"))
        assertNotEquals(before, c.expandedReadings())
        assertEquals("964426", rawOf(c, true))
        c.onKey(act(KeyAction.BACKSPACE))
        assertEquals(before, c.expandedReadings())
    }

    @Test fun nine_key_backspace_after_the_separator_removes_only_the_letter_before_it() {
        val (_, c) = controller(engine(), true, "64426")
        assertEquals("ni'hao", c.preeditForTest())
        c.onPreeditCaret(2)
        c.onKey(act(KeyAction.BACKSPACE))
        assertEquals("n'hao", c.preeditForTest())
        assertEquals("6426", rawOf(c, true))
        assertEquals(1, c.preeditCaretForTest())
        c.onKey(act(KeyAction.BACKSPACE))
        assertEquals("hao", c.preeditForTest())
        assertEquals("426", rawOf(c, true))
        assertEquals(0, c.preeditCaretForTest())
        c.onKey(out("6"))
        assertEquals(1, c.preeditCaretForTest())
        assertEquals("6426", rawOf(c, true))
        assertEquals("m'hao", c.preeditForTest())
        c.onKey(out("4"))
        assertEquals("ni'hao", c.preeditForTest())
        assertEquals("64426", rawOf(c, true))
        assertTrue("你好" in c.candidateWords())
    }

    @Test fun nine_key_edits_keep_the_untouched_syllables_verbatim() {
        val (_, c) = controller(engine(), true, "94664486")
        assertEquals("zhong'guo", c.preeditForTest())
        c.onPreeditCaret(8)
        c.onKey(act(KeyAction.BACKSPACE))
        assertEquals("zhong'gu", c.preeditForTest())
        c.onKey(act(KeyAction.BACKSPACE))
        assertEquals("zhong'g", c.preeditForTest())
        c.onKey(out("8"))
        assertEquals("zhong'" + T9Pinyin.guessLetters("48"), c.preeditForTest())
        c.onKey(out("6"))
        val tail = T9Pinyin.guessLetters("486")
        assertEquals("zhong'$tail", c.preeditForTest())
        assertTrue("中国" in c.candidateWords())
        c.onPreeditCaret(1)
        c.onKey(act(KeyAction.BACKSPACE))
        assertEquals("hong'$tail", c.preeditForTest())
        assertEquals("4664486", rawOf(c, true))
        c.onKey(out("9"))
        assertEquals("zhong'$tail", c.preeditForTest())
        assertEquals("94664486", rawOf(c, true))
    }

    @Test fun nine_key_lock_readings_replace_the_guessed_letters_while_editing() {
        val (_, c) = controller(engine(), true, "64426")
        c.onPreeditCaret(5)
        val readings = c.expandedReadings()
        val mi = readings.indexOf("mi")
        assumeTrue(mi >= 0)
        c.onPickReadingIndex(mi)
        assertTrue(c.preeditForTest(), c.preeditForTest().startsWith("mi'"))
        c.onPreeditCaret(5)
        c.onKey(act(KeyAction.BACKSPACE))
        assertEquals("mi'ha", c.preeditForTest())
        assertEquals(listOf(0 until 2), model(c).lockedRanges)
        c.onPreeditCaret(2)
        c.onKey(act(KeyAction.BACKSPACE))
        assertTrue(model(c).lockedRanges.isEmpty())
        assertEquals("mi'ha", c.preeditForTest())
        c.onKey(act(KeyAction.BACKSPACE))
        assertEquals("m'ha", c.preeditForTest())
    }

    @Test fun leaving_nine_key_editing_resumes_whole_string_guessing() {
        val (_, c) = controller(engine(), true, "64426")
        c.onPreeditCaret(2)
        c.onKey(act(KeyAction.BACKSPACE))
        assertEquals("n'hao", c.preeditForTest())
        c.onPreeditEditDone()
        assertEquals(T9Pinyin.preedit("6426"), c.preeditForTest())
        c.onPreeditTap()
        assertEquals(T9Pinyin.preedit("6426"), c.preeditForTest())
    }

    @Test fun caret_edits_of_the_committed_prefix_are_ignored() {
        forEachLayout { nine, case ->
            val (host, c) = controller(engine(), nine, case.input(nine) + case.input(nine))
            val first = c.candidateWords().indexOf(case.word)
            assertTrue("$case nine=$nine offers the partial word", first >= 0)
            c.onPickCandidate(first)
            assertEquals(case.word, c.composingPrefix())
            c.onPreeditCaret(0)
            c.onKey(act(KeyAction.BACKSPACE))
            assertEquals(case.word, c.composingPrefix())
            assertEquals(case.input(nine), rawOf(c, nine))
            assertTrue(host.commits.isEmpty())
        }
    }

    @Test fun backspace_right_after_a_partial_pick_restores_the_buffer_and_leaves_editing() {
        forEachLayout { nine, case ->
            val (host, c) = controller(engine(), nine, case.input(nine) + case.input(nine))
            c.onPreeditCaret(3)
            val idx = c.candidateWords().indexOf(case.word)
            assertTrue("$case nine=$nine offers the partial word", idx >= 0)
            c.onPickCandidate(idx)
            assertEquals(case.word, c.composingPrefix())
            c.onKey(act(KeyAction.BACKSPACE))
            assertEquals("", c.composingPrefix())
            assertEquals(case.input(nine) + case.input(nine), rawOf(c, nine))
            assertFalse(c.preeditEditing())
            assertTrue(host.commits.isEmpty())
        }
    }

    private fun rawOf(c: KeyboardController, nine: Boolean): String = c.rawComposingForTest()
}
