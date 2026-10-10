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
import com.aegis.ime.dict.CharBigramLM
import com.aegis.ime.engine.DictEngine
import com.aegis.ime.layout.Key
import com.aegis.ime.user.UserLearning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class CrossCommitWordLearningTest {

    private class EditorHost : ImeHost {
        val sb = StringBuilder()
        override fun commitText(text: CharSequence) { sb.append(text) }
        override fun deleteBackward() { if (sb.isNotEmpty()) sb.deleteCharAt(sb.length - 1) }
        override fun performEnter() {}
        override fun textBeforeCursor(n: Int): CharSequence = sb.takeLast(n)
    }

    private val threeName = listOf("欧" to "ou", "阳" to "yang", "明" to "ming")

    private val rows = threeName.map { (ch, r) -> EngineFixture.Row(r, ch, 900) } +
        T9Pinyin.SYLLABLES.map { EngineFixture.Row(it, "丌", 1) }

    private val lmFile = File("src/main/assets/aegis_lm.bin")
    private val lm: CharBigramLM by lazy { CharBigramLM.fromFile(lmFile) }

    @Before fun requireLm() {
        assumeTrue("real LM asset present (production engine wiring)", lmFile.exists())
    }

    private fun controller(learning: UserLearning, nine: Boolean): KeyboardController {
        val letters = EngineFixture.build(rows)
        val digits = EngineFixture.build(rows.map { EngineFixture.Row(T9Pinyin.toT9(it.key), it.word, it.freq) })
        val engine = DictEngine(letters, digits, lm, userLearning = learning)
        return KeyboardController(EditorHost(), engine).also {
            it.userLearning = learning
            it.switchTextLayoutForTest(nine = nine)
        }
    }

    private fun keys(nine: Boolean, letters: String) = if (nine) T9Pinyin.toT9(letters) else letters

    private fun type(c: KeyboardController, s: String) = s.forEach { c.onKey(Key(it.toString(), output = it.toString())) }

    private fun pick(c: KeyboardController, word: String) {
        val i = c.candidateWords().indexOf(word)
        assertTrue("candidate '$word' present in ${c.candidateWords()}", i >= 0)
        c.onPickCandidate(i)
    }

    private fun typeCharByChar(c: KeyboardController, nine: Boolean, name: List<Pair<String, String>>) {
        for ((ch, r) in name) {
            type(c, keys(nine, r))
            pick(c, ch)
        }
        c.reset(preserveLayout = true)
    }

    private fun learnedAfterThresholdAndRecalledAfterReload(name: List<Pair<String, String>>, nine: Boolean) {
        val learning = UserLearning()
        val c = controller(learning, nine)
        val word = name.joinToString("") { it.first }
        val reading = name.joinToString("") { it.second }
        repeat(2) { typeCharByChar(c, nine, name) }
        assertTrue("no word before the threshold: ${learning.formedEntries()}", learning.formedEntries().isEmpty())
        typeCharByChar(c, nine, name)
        assertEquals(listOf(UserLearning.Formed(word, reading)), learning.formedEntries())

        val file = File.createTempFile("userlearn-chain", ".txt").also { it.deleteOnExit() }
        learning.save(file)
        val loaded = UserLearning().apply { load(file) }
        val c2 = controller(loaded, nine)
        type(c2, keys(nine, reading))
        assertTrue("$word recalled after a reload: ${c2.candidateWords()}", word in c2.candidateWords())
    }

    @Test fun three_character_name_typed_char_by_char_is_learned_on26key() =
        learnedAfterThresholdAndRecalledAfterReload(threeName, nine = false)

    @Test fun three_character_name_typed_char_by_char_is_learned_on9key() =
        learnedAfterThresholdAndRecalledAfterReload(threeName, nine = true)
}
