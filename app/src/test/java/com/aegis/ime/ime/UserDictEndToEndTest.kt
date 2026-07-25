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
import com.aegis.ime.decoder.PinyinDecoder
import com.aegis.ime.decoder.T9Pinyin
import com.aegis.ime.dict.BinaryDict
import com.aegis.ime.engine.DictEngine
import com.aegis.ime.layout.Key
import com.aegis.ime.layout.KeyAction
import com.aegis.ime.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UserDictEndToEndTest {

    private class EditorHost : ImeHost {
        val sb = StringBuilder()
        override fun commitText(text: CharSequence) { sb.append(text) }
        override fun deleteBackward() { if (sb.isNotEmpty()) sb.deleteCharAt(sb.length - 1) }
        override fun performEnter() {}
        override fun textBeforeCursor(n: Int): CharSequence = sb.takeLast(n)
        val text get() = sb.toString()
    }

    private val rows = listOf(
        EngineFixture.Row("ci", "次", 900), EngineFixture.Row("ci", "此", 850), EngineFixture.Row("ci", "词", 800),
        EngineFixture.Row("shi", "是", 950), EngineFixture.Row("shi", "时", 920), EngineFixture.Row("shi", "试", 680),
        EngineFixture.Row("ku", "库", 900), EngineFixture.Row("ku", "哭", 800),
        EngineFixture.Row("hao", "好", 950),
        EngineFixture.Row("ciku", "词库", 850),
    )
    private fun letterDict(): BinaryDict = EngineFixture.build(rows)
    private fun t9Dict(): BinaryDict = EngineFixture.build(rows.map { EngineFixture.Row(T9Pinyin.toT9(it.key), it.word, it.freq) })

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
        pick(c, "此")
        pick(c, "是")
        assertEquals("此是", h.text)
        assertEquals("stored under its reading", listOf("此是"), um.readingSnapshot()["cishi"])
        assertTrue("stored word is boosted", um.wordBoost("此是") > 0.0)

        clear(c)
        "cishi".forEach { c.onKey(out(it.toString())) }
        assertTrue("self-created 此是 recalled on the second typing", "此是" in c.candidateWords())
    }

    @Test fun assemble_threeCharWord_on26key() {
        val um = UserModel()
        val (c, h) = controller(um)
        switchAlpha(c)
        "cikuhao".forEach { c.onKey(out(it.toString())) }
        pick(c, "词")
        pick(c, "库")
        pick(c, "好")
        assertEquals("词库好", h.text)
        assertEquals("3-char self-created word stored", listOf("词库好"), um.readingSnapshot()["cikuhao"])
    }

    @Test fun assemble_selfCreatedWord_on9key() {
        val um = UserModel()
        val (c, h) = controller(um)
        switchNine(c)
        val digits = T9Pinyin.toT9("cishi")
        digits.forEach { c.onKey(out(it.toString())) }
        pick(c, "此")
        pick(c, "是")
        assertEquals("此是", h.text)
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

        val file = File.createTempFile("userdb-e2e", ".txt").also { it.deleteOnExit() }
        um1.save(file)
        val um2 = UserModel().apply { load(file) }
        val (c2, _) = controller(um2)
        switchAlpha(c2)
        "cishi".forEach { c2.onKey(out(it.toString())) }
        assertTrue("self-created word survives a restart", "此是" in c2.candidateWords())
    }

    @Test fun assembledWord_committedViaFlush_isAlsoLearned() {
        val um = UserModel()
        val (c, h) = controller(um)
        switchAlpha(c)
        "cishihao".forEach { c.onKey(out(it.toString())) }
        pick(c, "此")
        pick(c, "是")
        c.expireCandidateChoiceUndo()
        repeat(3) { c.onKey(Key("", action = KeyAction.BACKSPACE)) }
        assertEquals("此是", h.text)
        assertEquals("flush-committed assembled word is learned under its reading", listOf("此是"), um.readingSnapshot()["cishi"])
    }

    @Test fun exact_dictionary_word_is_saved_once_and_recalled_after_reload() {
        val um = UserModel()
        val (c, h) = controller(um)
        switchAlpha(c)
        "ci".forEach { c.onKey(out(it.toString())) }
        pick(c, "次")
        assertEquals("次", h.text)
        assertTrue("no recall entry for a lone character", um.readingSnapshot().isEmpty())

        clear(c)
        "ciku".forEach { c.onKey(out(it.toString())) }
        pick(c, "词库")
        assertEquals("次词库", h.text)
        assertEquals(listOf("词库"), um.readingSnapshot()["ciku"])
        assertEquals(1, um.userWordEntries().single { it.word == "词库" }.count)

        val file = File.createTempFile("userdb-exact", ".txt").also { it.deleteOnExit() }
        um.save(file)
        val loaded = UserModel().apply { load(file) }
        assertEquals(listOf("词库"), loaded.readingSnapshot()["ciku"])
        val fallback = EngineFixture.build(listOf(EngineFixture.Row("bie", "别", 100)))
        val recalled = PinyinDecoder(fallback, lm, userModel = loaded).decodeCovered("ciku", 30).map { it.word }
        assertTrue("词库" in recalled)
    }

    @Test fun same_word_only_and_same_reading_only_remain_distinct_learning_records() {
        val um = UserModel()
        val candidateEngine = engine(um)
        candidateEngine.learnWord("CI'KU", "词库", assembled = true)
        candidateEngine.learnWord("ci'gui", "词库", assembled = true)
        candidateEngine.learnWord("CI-KU", "此库", assembled = true)
        assertEquals(listOf("词库"), um.readingSnapshot()["cigui"])
        assertEquals(setOf("词库", "此库"), um.readingSnapshot().getValue("ciku").toSet())
    }
}
