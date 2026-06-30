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

/**
 * debug.13 E4 — UI-1 (九键左列末组音节选完保留不消失) and UI-2 (26键展开左侧音节选择列 → 全量同音单字).
 *
 * UI-1 asserts the 9-key left column never vanishes after the LAST syllable is locked — it keeps that
 * syllable visible and re-pickable. UI-2 asserts the 26-key EXPAND screen's left column lists the 分词
 * syllables, drilling one surfaces its COMPLETE (uncapped) 同音单字 set, and picking one 逐字-commits.
 */
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

    // ============================== UI-1: 9-key last-syllable persists ==============================
    // UI-1 needs no engine candidates — the left column is built from the real T9 segmenter — so any
    // non-null engine works.
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
        val (_, c) = nineWithBuffer("6443") // ni(64) he(43)
        c.onPickReadingIndex(c.expandedReadings().indexOf("ni"))
        assertTrue("after locking ni the next syllable 'he' is offered", "he" in c.expandedReadings())
        c.onPickReadingIndex(c.expandedReadings().indexOf("he"))

        // THE UI-1 FIX: every syllable is locked, but the column must NOT go empty — it keeps the LAST
        // syllable ('he') visible (and its alternatives), and is never punctuation.
        val col = c.expandedReadings()
        assertTrue("column persists after the last lock, was $col", col.isNotEmpty())
        assertTrue("the last syllable 'he' stays offered, was $col", "he" in col)
        assertTrue("its alternative reading 'ge' (same 43 keys) is offered too, was $col", "ge" in col)
        assertTrue("the persisted column is all readings, never punctuation",
            c.nineLeftColumn().all { it.action == KeyAction.PICK_READING })
    }

    @Test fun repicking_the_persisted_last_syllable_swaps_its_reading_without_committing() {
        val (host, c) = nineWithBuffer("6443") // ni he
        c.onPickReadingIndex(c.expandedReadings().indexOf("ni"))
        c.onPickReadingIndex(c.expandedReadings().indexOf("he"))
        assertEquals("ni'he", c.preeditForTest())

        // Re-pick the SAME-length alternative reading of the persisted last syllable (he → ge).
        c.onPickReadingIndex(c.expandedReadings().indexOf("ge"))
        assertEquals("re-pick swaps the last syllable's reading", "ni'ge", c.preeditForTest())
        assertTrue("re-picking a reading never commits to the editor", host.commits.isEmpty())
    }

    @Test fun backspace_from_the_persisted_column_undoes_locks_not_digits() {
        val (host, c) = nineWithBuffer("6443") // ni he
        c.onPickReadingIndex(c.expandedReadings().indexOf("ni"))
        c.onPickReadingIndex(c.expandedReadings().indexOf("he"))

        // Two backspaces from the all-locked state must step back the TWO locks (A9), NOT delete two digits.
        c.onKey(act(KeyAction.BACKSPACE))
        c.onKey(act(KeyAction.BACKSPACE))
        c.onKey(act(KeyAction.ENTER)) // flush the still-intact 4-digit buffer

        // The locks gone, the active 4-digit buffer decodes canonically ("43" → "ge"); both digits are still
        // there (had backspace eaten digits, two presses would leave "64" → "ni").
        assertEquals("both locks undone, all four digits intact → full pinyin", listOf("nige"), host.commits)
    }

    // ============================== UI-2: 26-key syllable column → uncapped homophones ==============
    // Distinct synthetic homophones so a re-cap at the UI layer is observable (size shrinks). 40 > the
    // engine's internal MAX_CANDIDATES=30, proving the controller passes the whole set through.
    private val niHomophones = listOf("你") + (1..39).map { ('一' + it).toString() }
    private val haoHomophones = listOf("号") + (1..49).map { ('伀' + it).toString() }
    // ② (debug.18) repro fixture: ceshi → [ce, shi]. Distinct homophones so the 逐音节 picks are observable.
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
            // single-syllable remainders after a leading partial commit (so the column still segments)
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

    private fun alphaWithBuffer(letters: String): Pair<RecordingHost, KeyboardController> {
        val host = RecordingHost()
        val c = KeyboardController(host, syllabic)
        c.onKey(act(KeyAction.SWITCH_ALPHA)) // 26-key, lang stays CN → PINYIN mode
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
        c.onPickReadingIndex(0) // drill 'ni'
        assertEquals("drilled syllable recorded", 0, c.drilledSyllableForTest())
        // The WHOLE 40-char set reaches the grid — the controller does not re-impose any cap (he=257 / yi=875
        // 全出 at the device; here the synthetic 40 > the engine's MAX_CANDIDATES=30 proves no UI truncation).
        assertEquals("every ni 同音字 surfaces, uncapped", niHomophones, c.candidateWords())
        assertTrue("more than the 30-cap", c.candidateWords().size > 30)
    }

    @Test fun picking_a_homophone_from_the_first_syllable_partial_commits_then_continues() {
        val (host, c) = alphaWithBuffer("nihao")
        c.onPickReadingIndex(0)                       // drill 'ni'
        c.onPickCandidate(c.candidateWords().indexOf("你")) // pick 你 → 逐字 partial commit
        assertTrue("a partial 逐字 pick does not reach the editor yet", host.commits.isEmpty())
        assertEquals("你", c.composingPrefix())
        assertEquals("the drill clears so the remainder shows normally", -1, c.drilledSyllableForTest())
        assertEquals("the next unresolved syllable is now shown", listOf("hao"), c.expandedReadings())

        c.onKey(act(KeyAction.ENTER))                 // flush 你 + remaining 'hao'
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

    // ② (debug.18) the leading-first path stays normal: tap the leading syllable, reselect its char, normal flow.
    @Test fun ceshi_drilling_ce_first_partial_commits_then_continues_to_shi() {
        val (host, c) = alphaWithBuffer("ceshi")
        c.onPickReadingIndex(0)                            // 点首音节 ce
        c.onPickCandidate(c.candidateWords().indexOf("测")) // 回选 测 → 逐字 partial commit
        assertTrue("a leading partial pick does not reach the editor yet", host.commits.isEmpty())
        assertEquals("测", c.composingPrefix())
        assertEquals("the drill clears so the remainder shows normally", -1, c.drilledSyllableForTest())
        assertEquals("the buffer advanced to the 'shi' syllable", listOf("shi"), c.expandedReadings())

        c.onKey(act(KeyAction.ENTER))                      // flush 测 + remaining 'shi'
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

    @Test fun the_drill_clears_on_typing_or_backspace() {
        val (_, c) = alphaWithBuffer("nihao")
        c.onPickReadingIndex(0)
        assertEquals(0, c.drilledSyllableForTest())
        c.onKey(out("o")) // type → back to the normal candidate grid
        assertEquals("typing clears the drill", -1, c.drilledSyllableForTest())

        c.onPickReadingIndex(0) // (buffer is now 'nihaoo' → syllablesForReading empty, so this is a no-op)
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
        c.clearDrill() // the expand panel was closed (返回 / chevron)
        assertEquals("drill cleared on close", -1, c.drilledSyllableForTest())
        assertEquals("strip returns to the normal word candidates", "你好", c.candidateWords().firstOrNull())
    }

    @Test fun the_input_view_close_path_clears_the_drill() {
        // Wire the InputView↔controller hooks exactly as the IME service does, then drive open → drill → close.
        val iv = InputView(RuntimeEnvironment.getApplication())
        val c = KeyboardController(RecordingHost(), syllabic)
        iv.onPickReading = { i -> c.onPickReadingIndex(i) }
        iv.onPickCandidate = { i -> c.onPickCandidate(i) }
        iv.onExpandClosed = { c.clearDrill() }
        c.attachView(iv)
        c.onKey(act(KeyAction.SWITCH_ALPHA))
        "nihao".forEach { c.onKey(out(it.toString())) }

        iv.showExpandedCandidates()        // open the A2 grid
        c.onPickReadingIndex(0)            // drill 'ni'
        assertEquals(0, c.drilledSyllableForTest())
        iv.showPanel(null)                 // 返回 closes the grid → onExpandClosed → clearDrill
        assertEquals("the close path drops the drill", -1, c.drilledSyllableForTest())
    }

    @Test fun nine_key_reading_pick_still_locks_and_never_drills() {
        val (_, c) = nineWithBuffer("6443")
        c.onPickReadingIndex(c.expandedReadings().indexOf("ni"))
        assertEquals("the 9-key path locks (★E), it never sets a drill", -1, c.drilledSyllableForTest())
        // 'ni' is locked (shown verbatim); the active tail "43" renders canonically → "ge".
        assertEquals("locking advances the preedit", "ni'ge", c.preeditForTest())
    }

    // ---- end-to-end over the REAL dictionary (gated on assets): the UI surfaces the FULL he set ----

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
        "heshui".forEach { c.onKey(out(it.toString())) } // he(0..2) shui(2..6)
        assertEquals("26-key shows only the first unresolved syllable", listOf("he"), c.expandedReadings())

        c.onPickReadingIndex(0) // drill 'he'
        val shown = c.candidateWords().toSet()
        assertTrue("the UI lists EVERY he 同音字 the dict holds (no re-cap)", shown.containsAll(heSet))
        assertTrue("…and more than the old 30-cap", c.candidateWords().size > 30 || heSet.size <= 30)
        assertTrue("和 reachable through the drill", "和" in shown)
    }
}
