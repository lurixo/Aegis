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
import com.aegis.ime.decoder.T9Pinyin
import com.aegis.ime.dict.BinaryDict
import com.aegis.ime.engine.DictEngine
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import com.aegis.ime.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * End-to-end self-created-word flow through the REAL controller + [DictEngine] + decoder: assemble a word the
 * dictionary does not carry character by character, commit it, and verify it (a) is stored under its reading
 * with a boost, (b) is recalled the next time the reading is typed (buffer cleared in between), and (c)
 * survives a "restart" (save → fresh model/engine). Covered on BOTH the 26-key and the 9-key layout, across
 * more than one word length/reading, and with the noise guards (a lone character and a real dictionary word
 * are NOT stored). No single word is special-cased — the same code path drives every case.
 */
class UserDictEndToEndTest {

    private class EditorHost : ImeHost {
        val sb = StringBuilder()
        override fun commitText(text: CharSequence) { sb.append(text) }
        override fun deleteBackward() { if (sb.isNotEmpty()) sb.deleteCharAt(sb.length - 1) }
        override fun performEnter() {}
        override fun textBeforeCursor(n: Int): CharSequence = sb.takeLast(n)
        val text get() = sb.toString()
    }

    // A controlled dictionary with ci/shi/ku/hao singles + the real word 词库 (ciku), and NO whole-word key for
    // the readings the tests assemble, so those words are genuinely self-created.
    private val rows = listOf(
        EngineFixture.Row("ci", "次", 900), EngineFixture.Row("ci", "此", 850), EngineFixture.Row("ci", "词", 800),
        EngineFixture.Row("shi", "是", 950), EngineFixture.Row("shi", "时", 920), EngineFixture.Row("shi", "试", 680),
        EngineFixture.Row("ku", "库", 900), EngineFixture.Row("ku", "哭", 800),
        EngineFixture.Row("hao", "好", 950),
        EngineFixture.Row("ciku", "词库", 850),
    )
    private fun letterDict(): BinaryDict = EngineFixture.build(rows)
    private fun t9Dict(): BinaryDict = EngineFixture.build(rows.map { EngineFixture.Row(T9Pinyin.toT9(it.key), it.word, it.freq) })

    // Production wiring: the char-bigram LM is always loaded in the real engine, so the end-to-end flow is
    // exercised with it too (an LM-less engine ranks differently). A missing asset SKIPS loudly rather than
    // silently downgrading to a non-production configuration.
    private val lmFile = java.io.File("src/main/assets/aegis_lm.bin")
    private val lm: com.aegis.ime.dict.CharBigramLM by lazy { com.aegis.ime.dict.CharBigramLM.fromFile(lmFile) }

    @org.junit.Before fun requireLm() {
        org.junit.Assume.assumeTrue("real LM asset present (production engine wiring)", lmFile.exists())
    }

    private fun engine(um: UserModel) = DictEngine(letterDict(), t9Dict(), lm, um)
    private fun out(s: String) = Key(s, output = s)
    private fun controller(um: UserModel): Pair<KeyboardController, EditorHost> {
        val h = EditorHost()
        return KeyboardController(h, engine(um)) to h
    }

    private fun pick(c: KeyboardController, word: String) {
        val i = c.candidateWords().indexOf(word)
        assertTrue("candidate '$word' present in ${c.candidateWords()}", i >= 0)
        c.onPickCandidate(i)
    }

    private fun switchAlpha(c: KeyboardController) = c.onKey(Key("", action = KeyAction.SWITCH_ALPHA))
    private fun switchNine(c: KeyboardController) = c.onKey(Key("", action = KeyAction.SWITCH_NINE))
    private fun clear(c: KeyboardController) = c.onKey(Key("", action = KeyAction.CLEAR_COMPOSING))

    @Test fun assemble_selfCreatedWord_on26key_thenRecall() {
        val um = UserModel()
        val (c, h) = controller(um)
        switchAlpha(c)
        "cishi".forEach { c.onKey(out(it.toString())) }
        pick(c, "此") // partial: covers "ci"
        pick(c, "是") // final: covers "shi"
        assertEquals("此是", h.text)
        assertEquals("stored under its reading", listOf("此是"), um.readingSnapshot()["cishi"])
        assertTrue("stored word is boosted", um.wordBoost("此是") > 0.0)

        // clear and retype the reading — the self-created word is now recalled as a candidate.
        clear(c)
        "cishi".forEach { c.onKey(out(it.toString())) }
        assertTrue("self-created 此是 recalled on the second typing", "此是" in c.candidateWords())
    }

    @Test fun assemble_threeCharWord_on26key() {
        val um = UserModel()
        val (c, h) = controller(um)
        switchAlpha(c)
        "cikuhao".forEach { c.onKey(out(it.toString())) }
        pick(c, "词") // covers "ci"
        pick(c, "库") // covers "ku"
        pick(c, "好") // covers "hao" (final)
        assertEquals("词库好", h.text)
        assertEquals("3-char self-created word stored", listOf("词库好"), um.readingSnapshot()["cikuhao"])
    }

    @Test fun assemble_selfCreatedWord_on9key() {
        val um = UserModel()
        val (c, h) = controller(um)
        switchNine(c) // the test controller starts on 26-key (no reset()); move to the 9-key layout
        val digits = T9Pinyin.toT9("cishi")
        digits.forEach { c.onKey(out(it.toString())) }
        pick(c, "此") // covers the ci digits
        pick(c, "是") // covers the shi digits (final)
        assertEquals("此是", h.text)
        // 9-key digit groups are ambiguous, so the stored letter reading is the default segmentation; what must
        // hold is that its T9 digit key equals the typed digits, so recall works whenever those digits are typed.
        val storedUnderTheseDigits = um.readingSnapshot().entries
            .firstOrNull { T9Pinyin.toT9(it.key) == digits && "此是" in it.value }
        assertTrue("9-key assembly stores 此是 under a reading matching the typed digits", storedUnderTheseDigits != null)

        clear(c)
        digits.forEach { c.onKey(out(it.toString())) }
        assertTrue("recalled on 9-key", "此是" in c.candidateWords())
    }

    @Test fun selfCreatedWord_survivesRestart() {
        val um1 = UserModel()
        val (c1, h1) = controller(um1)
        switchAlpha(c1)
        "cishi".forEach { c1.onKey(out(it.toString())) }
        pick(c1, "此"); pick(c1, "是")
        assertEquals("此是", h1.text)

        // "restart": persist and reload into a brand-new model + engine + controller.
        val file = File.createTempFile("userdb-e2e", ".txt").also { it.deleteOnExit() }
        um1.save(file)
        val um2 = UserModel().apply { load(file) }
        val (c2, _) = controller(um2)
        switchAlpha(c2)
        "cishi".forEach { c2.onKey(out(it.toString())) }
        assertTrue("self-created word survives a restart", "此是" in c2.candidateWords())
    }

    @Test fun assembledWord_committedViaFlush_isAlsoLearned() {
        // Assemble 此是 into the confirmed prefix (two partial picks), then backspace the remaining buffer to
        // empty — that commits the prefix through the flush path, NOT a final candidate pick. It must still be
        // learned, so finishing a self-created word either way is consistent.
        val um = UserModel()
        val (c, h) = controller(um)
        switchAlpha(c)
        "cishihao".forEach { c.onKey(out(it.toString())) }
        pick(c, "此") // partial: covers "ci"; buffer "shihao" remains
        pick(c, "是") // partial: covers "shi"; committedPrefix="此是"; buffer "hao" remains
        c.expireCandidateChoiceUndo() // retire the pick-undo (as the service does before a panel op) so the
        repeat(3) { c.onKey(Key("", action = KeyAction.BACKSPACE)) } // backspaces empty the "hao" tail -> flush commits 此是
        assertEquals("此是", h.text)
        assertEquals("flush-committed assembled word is learned under its reading", listOf("此是"), um.readingSnapshot()["cishi"])
    }

    @Test fun loneCharacter_and_realDictWord_areNotStoredAsSelfCreated() {
        val um = UserModel()
        val (c, h) = controller(um)
        switchAlpha(c)
        // a lone single character committed whole is not a self-created word
        "ci".forEach { c.onKey(out(it.toString())) }
        pick(c, "次")
        assertEquals("次", h.text)
        assertTrue("no recall entry for a lone character", um.readingSnapshot().isEmpty())

        clear(c)
        // a real dictionary word (词库 is keyed under ciku) is recalled from the dict, so it is not re-stored
        "ciku".forEach { c.onKey(out(it.toString())) }
        pick(c, "词库")
        assertEquals("次词库", h.text)
        assertNull("dictionary word not stored as a self-created word", um.readingSnapshot()["ciku"])
        assertFalse("no spurious recall entries", um.readingSnapshot().containsKey("ciku"))
    }
}
