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
class Ui12SyllableColumnTest {

    private class RecordingHost : ImeHost {
        val commits = mutableListOf<String>()
        override fun commitText(text: CharSequence) { commits.add(text.toString()) }
        override fun deleteBackward() {}
        override fun performEnter() {}
    }

    private fun out(s: String) = Key(s, output = s)
    private fun act(a: KeyAction) = Key("", action = a)

    private val empty = object : CandidateEngine {
        override fun candidates(composing: String, t9: Boolean): List<String> = emptyList()
    }

    private fun nineWithBuffer(digits: String): Pair<RecordingHost, KeyboardController> {
        val host = RecordingHost()
        val c = KeyboardController(host, empty)
        c.onKey(act(KeyAction.SWITCH_NINE))
        digits.forEach { c.onKey(out(it.toString())) }
        return host to c
    }

    @Test fun nine_left_column_persists_the_last_syllable_after_locking_all() {
        val (_, c) = nineWithBuffer("6443")
        c.onPickReadingIndex(c.expandedReadings().indexOf("ni"))
        assertTrue("after locking ni the next syllable 'he' is offered", "he" in c.expandedReadings())
        c.onPickReadingIndex(c.expandedReadings().indexOf("he"))

        val col = c.expandedReadings()
        assertTrue("column persists after the last lock, was $col", col.isNotEmpty())
        assertTrue("the last syllable 'he' stays offered, was $col", "he" in col)
        assertTrue("its alternative reading 'ge' (same 43 keys) is offered too, was $col", "ge" in col)
        assertTrue("the persisted column is all readings, never punctuation",
            c.nineLeftColumn().all { it.action == KeyAction.PICK_READING })
    }

    @Test fun repicking_the_persisted_last_syllable_swaps_its_reading_without_committing() {
        val (host, c) = nineWithBuffer("6443")
        c.onPickReadingIndex(c.expandedReadings().indexOf("ni"))
        c.onPickReadingIndex(c.expandedReadings().indexOf("he"))
        assertEquals("ni'he", c.preeditForTest())

        c.onPickReadingIndex(c.expandedReadings().indexOf("ge"))
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

    private val niHomophones = listOf("你") + (1..39).map { ('一' + it).toString() }
    private val haoHomophones = listOf("号") + (1..49).map { ('伀' + it).toString() }
    private val ceHomophones = listOf("测", "侧", "厕", "策", "册")
    private val shiHomophones = listOf("试", "是", "事", "时", "市")

    private val syllabic = object : CandidateEngine {
        override fun candidates(composing: String, t9: Boolean): List<String> =
            candidatesCovered(composing, t9).map { it.word }
        override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>, context: CharSequence): List<Cand> = when {
            composing.isEmpty() -> emptyList()
            composing == "ceshi" -> listOf(Cand("测试", composing.length), Cand("测", 2))
            else -> listOf(Cand("你好", composing.length), Cand("你", 2))
        }
        override fun syllablesForReading(letters: String): List<Syllable> = when (letters) {
            "nihao" -> listOf(Syllable("ni", 0, 2), Syllable("hao", 2, 5))
            "ceshi" -> listOf(Syllable("ce", 0, 2), Syllable("shi", 2, 5))
            "hao" -> listOf(Syllable("hao", 0, 3))
            "shi" -> listOf(Syllable("shi", 0, 3))
            else -> emptyList()
        }
        override fun homophonesForReadingAt(letters: String, index: Int): List<String> = when {
            letters == "nihao" && index == 0 -> niHomophones
            letters == "nihao" && index == 1 -> haoHomophones
            letters == "ceshi" && index == 0 -> ceHomophones
            letters == "ceshi" && index == 1 -> shiHomophones
            letters == "hao" && index == 0 -> haoHomophones
            letters == "shi" && index == 0 -> shiHomophones
            else -> emptyList()
        }
    }

    private fun learningSyllabic(learns: MutableList<Pair<String?, String>>) = object : CandidateEngine {
        override fun candidates(composing: String, t9: Boolean): List<String> =
            candidatesCovered(composing, t9).map { it.word }
        override fun candidatesCovered(composing: String, t9: Boolean, cuts: Set<Int>, context: CharSequence): List<Cand> =
            if (composing.isEmpty()) emptyList() else listOf(Cand("你好", composing.length), Cand("你", 2))
        override fun syllablesForReading(letters: String): List<Syllable> = when (letters) {
            "nihao" -> listOf(Syllable("ni", 0, 2), Syllable("hao", 2, 5))
            "hao" -> listOf(Syllable("hao", 0, 3))
            else -> emptyList()
        }
        override fun homophonesForReadingAt(letters: String, index: Int): List<String> = when {
            letters == "nihao" && index == 0 -> niHomophones
            letters == "hao" && index == 0 -> haoHomophones
            else -> emptyList()
        }
        override fun learn(prevWord: String?, word: String) { learns.add(prevWord to word) }
    }

    private fun alphaWithBuffer(letters: String): Pair<RecordingHost, KeyboardController> {
        val host = RecordingHost()
        val c = KeyboardController(host, syllabic)
        c.onKey(act(KeyAction.SWITCH_ALPHA))
        letters.forEach { c.onKey(out(it.toString())) }
        return host to c
    }

    @Test fun expand_left_column_shows_only_the_first_unresolved_syllable_on_26key() {
        val (_, c) = alphaWithBuffer("nihao")
        assertEquals("26-key starts with only the first unresolved syllable", listOf("ni"), c.expandedReadings())
        assertEquals("nothing drilled yet", -1, c.drilledSyllableForTest())
    }

    @Test fun drilling_a_syllable_shows_its_complete_uncapped_homophone_set() {
        val (_, c) = alphaWithBuffer("nihao")
        c.onPickReadingIndex(0)
        assertEquals("drilled syllable recorded", 0, c.drilledSyllableForTest())
        assertEquals("every ni 同音字 surfaces, uncapped", niHomophones, c.candidateWords())
        assertTrue("more than the 30-cap", c.candidateWords().size > 30)
    }

    @Test fun picking_a_homophone_from_the_first_syllable_partial_commits_then_continues() {
        val (host, c) = alphaWithBuffer("nihao")
        c.onPickReadingIndex(0)
        c.onPickCandidate(c.candidateWords().indexOf("你"))
        assertTrue("a partial 逐字 pick does not reach the editor yet", host.commits.isEmpty())
        assertEquals("你", c.composingPrefix())
        assertEquals("the drill clears so the remainder shows normally", -1, c.drilledSyllableForTest())
        assertEquals("the next unresolved syllable is now shown", listOf("hao"), c.expandedReadings())

        c.onKey(act(KeyAction.ENTER))
        assertEquals(listOf("你hao"), host.commits)
    }

    @Test fun non_leftmost_syllable_cannot_be_drilled_from_the_26key_column() {
        val (host, c) = alphaWithBuffer("nihao")
        assertEquals(listOf("ni"), c.expandedReadings())
        c.onPickReadingIndex(1)
        assertEquals("out-of-visible-range drill ignored", -1, c.drilledSyllableForTest())
        assertEquals("normal candidates remain visible", "你好", c.candidateWords().firstOrNull())
        assertTrue("nothing commits while trying to pick a hidden syllable", host.commits.isEmpty())
    }

    @Test fun ceshi_starts_at_ce_and_advances_to_shi_after_ce_is_chosen() {
        val (host, c) = alphaWithBuffer("ceshi")
        assertEquals("26-key shows only the first unresolved syllable", listOf("ce"), c.expandedReadings())
        c.onPickReadingIndex(1)
        assertEquals("shi is not visible yet", -1, c.drilledSyllableForTest())

        c.onPickReadingIndex(0)
        c.onPickCandidate(c.candidateWords().indexOf("测"))
        assertTrue("the leading partial pick remains in preedit", host.commits.isEmpty())
        assertEquals("测", c.composingPrefix())
        assertEquals("after ce is chosen, shi becomes the visible unresolved syllable", listOf("shi"), c.expandedReadings())
    }

    @Test fun ceshi_drilling_ce_first_partial_commits_then_continues_to_shi() {
        val (host, c) = alphaWithBuffer("ceshi")
        c.onPickReadingIndex(0)
        c.onPickCandidate(c.candidateWords().indexOf("测"))
        assertTrue("a leading partial pick does not reach the editor yet", host.commits.isEmpty())
        assertEquals("测", c.composingPrefix())
        assertEquals("the drill clears so the remainder shows normally", -1, c.drilledSyllableForTest())
        assertEquals("the buffer advanced to the 'shi' syllable", listOf("shi"), c.expandedReadings())

        c.onKey(act(KeyAction.ENTER))
        assertEquals(listOf("测shi"), host.commits)
    }

    @Test fun backspace_after_a_preedit_homophone_choice_restores_the_choice_grid() {
        val (host, c) = alphaWithBuffer("nihao")
        c.onPickReadingIndex(0)
        assertEquals(0, c.drilledSyllableForTest())
        assertEquals(niHomophones, c.candidateWords())

        c.onPickCandidate(c.candidateWords().indexOf("你"))
        assertTrue("the partial choice is still IME preedit", host.commits.isEmpty())
        assertEquals("你", c.composingPrefix())
        assertEquals(listOf("hao"), c.expandedReadings())

        c.onKey(act(KeyAction.BACKSPACE))
        assertTrue("undoing the preedit choice does not delete editor text", host.commits.isEmpty())
        assertEquals("the chosen prefix is removed", "", c.composingPrefix())
        assertEquals("the first syllable is offered again", listOf("ni"), c.expandedReadings())
        assertEquals("the original syllable remains drilled", 0, c.drilledSyllableForTest())
        assertEquals("the homophone grid is restored", niHomophones, c.candidateWords())

        c.onPickCandidate(c.candidateWords().indexOf("你"))
        assertEquals("the syllable can be chosen again", "你", c.composingPrefix())
        assertEquals(listOf("hao"), c.expandedReadings())
    }

    @Test fun backspace_after_a_preedit_homophone_choice_discards_its_learning() {
        val learns = mutableListOf<Pair<String?, String>>()
        val host = RecordingHost()
        val c = KeyboardController(host, learningSyllabic(learns))
        c.onKey(act(KeyAction.SWITCH_ALPHA))
        "nihao".forEach { c.onKey(out(it.toString())) }

        c.onPickReadingIndex(0)
        c.onPickCandidate(c.candidateWords().indexOf("你"))
        assertTrue("preedit-only choices must not learn before editor commit", learns.isEmpty())
        assertEquals("你", c.composingPrefix())

        c.onKey(act(KeyAction.BACKSPACE))
        assertEquals("the undone prefix is removed", "", c.composingPrefix())
        assertEquals("the original homophone grid is restored", niHomophones, c.candidateWords())

        val replacement = niHomophones[1]
        c.onPickCandidate(c.candidateWords().indexOf(replacement))
        assertTrue("the replacement is still preedit-only until commit", learns.isEmpty())
        c.onKey(act(KeyAction.ENTER))

        assertEquals(listOf(replacement + "hao"), host.commits)
        assertEquals("only the replacement choice is learned after it reaches the editor", listOf(null to replacement), learns)
    }

    @Test fun the_drill_clears_on_typing_or_backspace() {
        val (_, c) = alphaWithBuffer("nihao")
        c.onPickReadingIndex(0)
        assertEquals(0, c.drilledSyllableForTest())
        c.onKey(out("o"))
        assertEquals("typing clears the drill", -1, c.drilledSyllableForTest())

        c.onPickReadingIndex(0)
        assertEquals("drilling an un-segmentable buffer is a no-op", -1, c.drilledSyllableForTest())
    }

    @Test fun out_of_range_syllable_drill_is_a_no_op() {
        val (_, c) = alphaWithBuffer("nihao")
        c.onPickReadingIndex(99)
        assertEquals("out-of-range drill ignored", -1, c.drilledSyllableForTest())
        assertEquals("grid still shows the normal word candidates", "你好", c.candidateWords().firstOrNull())
    }

    @Test fun closing_the_expand_grid_clears_the_drill() {
        val (_, c) = alphaWithBuffer("nihao")
        c.onPickReadingIndex(0)
        assertEquals("drilled grid shows single-char homophones", niHomophones, c.candidateWords())
        c.clearDrill()
        assertEquals("drill cleared on close", -1, c.drilledSyllableForTest())
        assertEquals("strip returns to the normal word candidates", "你好", c.candidateWords().firstOrNull())
    }

    @Test fun the_input_view_close_path_clears_the_drill() {
        val iv = InputView(RuntimeEnvironment.getApplication())
        val c = KeyboardController(RecordingHost(), syllabic)
        iv.onPickReading = { i -> c.onPickReadingIndex(i) }
        iv.onPickCandidate = { i -> c.onPickCandidate(i) }
        iv.onExpandClosed = { c.clearDrill() }
        c.attachView(iv)
        c.onKey(act(KeyAction.SWITCH_ALPHA))
        "nihao".forEach { c.onKey(out(it.toString())) }

        iv.showExpandedCandidates()
        c.onPickReadingIndex(0)
        assertEquals(0, c.drilledSyllableForTest())
        iv.showPanel(null)
        assertEquals("the close path drops the drill", -1, c.drilledSyllableForTest())
    }

    @Test fun nine_key_reading_pick_still_locks_and_never_drills() {
        val (_, c) = nineWithBuffer("6443")
        c.onPickReadingIndex(c.expandedReadings().indexOf("ni"))
        assertEquals("the 9-key path locks (★E), it never sets a drill", -1, c.drilledSyllableForTest())
        assertEquals("locking advances the preedit", "ni'ge", c.preeditForTest())
    }


    private fun realEngine(): DictEngine? {
        val p = File("src/main/assets/aegis_dict.bin")
        val t = File("src/main/assets/aegis_t9.bin")
        val l = File("src/main/assets/aegis_lm.bin")
        if (!p.exists() || !t.exists() || !l.exists()) return null
        return DictEngine(BinaryDict.fromFile(p), BinaryDict.fromFile(t), CharBigramLM.fromFile(l))
    }

    @Test fun real_dict_drill_surfaces_every_homophone_the_dict_holds() {
        val eng = realEngine(); assumeTrue("dict assets present", eng != null)
        val dict = BinaryDict.fromFile(File("src/main/assets/aegis_dict.bin"))
        val heSet = dict.exact("he").filter { it.word.length == 1 }.map { it.word }.toSet()
        assumeTrue("dict has a meaningful he set", heSet.size > 8)

        val c = KeyboardController(RecordingHost(), eng!!)
        c.onKey(act(KeyAction.SWITCH_ALPHA))
        "heshui".forEach { c.onKey(out(it.toString())) }
        assertEquals("26-key shows only the first unresolved syllable", listOf("he"), c.expandedReadings())

        c.onPickReadingIndex(0)
        val shown = c.candidateWords().toSet()
        assertTrue("the UI lists EVERY he 同音字 the dict holds (no re-cap)", shown.containsAll(heSet))
        assertTrue("…and more than the old 30-cap", c.candidateWords().size > 30 || heSet.size <= 30)
        assertTrue("和 reachable through the drill", "和" in shown)
    }
}
