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
import com.aegis.ime.decoder.PinyinDecoder
import com.aegis.ime.decoder.T9Pinyin
import com.aegis.ime.dict.BinaryDict
import com.aegis.ime.dict.CharBigramLM
import com.aegis.ime.engine.DictEngine
import com.aegis.ime.layout.Key
import com.aegis.ime.user.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class AliasedReadingUserWordTest {

    private class EditorHost : ImeHost {
        val sb = StringBuilder()
        override fun commitText(text: CharSequence) { sb.append(text) }
        override fun deleteBackward() { if (sb.isNotEmpty()) sb.deleteCharAt(sb.length - 1) }
        override fun performEnter() {}
        override fun textBeforeCursor(n: Int): CharSequence = sb.takeLast(n)
    }

    private val dictFile = FullDictTestAssets.file(FullDictTestAssets.DICT)
    private val t9File = FullDictTestAssets.file(FullDictTestAssets.T9)
    private val lmFile = FullDictTestAssets.file(FullDictTestAssets.LM)
    private val jianpinFile = FullDictTestAssets.file(FullDictTestAssets.JIANPIN)

    private val letters by lazy { BinaryDict.fromFile(dictFile) }
    private val digits by lazy { BinaryDict.fromFile(t9File) }
    private val initials by lazy { BinaryDict.fromFile(jianpinFile) }
    private val lm by lazy { CharBigramLM.fromFile(lmFile) }

    private val clock = 1_700_000_000_000L

    private val misread = listOf(
        "cunanhua" to "存嗯化",
        "cunenhua" to "存安化",
        "cunnghua" to "存恩化",
    )

    @Before fun requireAssets() = assumeTrue(
        "production dictionary, T9 table, language model and jianpin table present",
        FullDictTestAssets.available(dictFile, t9File, lmFile, jianpinFile),
    )

    private fun controller(model: UserModel, nine: Boolean): KeyboardController {
        val engine = DictEngine(letters, digits, lm, userModel = model, initialsDict = initials)
        return KeyboardController(EditorHost(), engine).also { it.switchTextLayoutForTest(nine = nine) }
    }

    private fun keys(nine: Boolean, reading: String) = if (nine) T9Pinyin.toT9(reading) else reading

    private fun type(c: KeyboardController, keys: String) = keys.forEach { c.onKey(Key(it.toString(), output = it.toString())) }

    private fun pick(c: KeyboardController, word: String) {
        val i = c.candidateWords().indexOf(word)
        assertTrue("candidate '$word' present in ${c.candidateWords().take(40)}", i >= 0)
        c.onPickCandidate(i)
    }

    private fun recorded(reading: String, word: String) =
        UserModel().apply { repeat(3) { recordWord(reading, word, clock, incrementCount = true) } }

    private fun offered(model: UserModel, nine: Boolean, syllables: List<String>): List<String> {
        val c = controller(model, nine)
        syllables.forEachIndexed { i, syllable ->
            if (i > 0) c.onKey(Key("'", output = "'"))
            type(c, keys(nine, syllable))
        }
        return c.candidateWords()
    }

    private fun wordPickedCharacterByCharacterIsLearnedUnderTheTypedReading(nine: Boolean) {
        val model = UserModel()
        val c = controller(model, nine)
        type(c, keys(nine, "cunenhua"))
        for (ch in listOf("存", "嗯", "化")) pick(c, ch)
        assertEquals(listOf("cunenhua" to "存嗯化"), model.unmarkedReadings())
        val words = offered(model, nine, listOf("cunenhua"))
        assertTrue("存嗯化 is offered for ${keys(nine, "cunenhua")}: ${words.take(40)}", "存嗯化" in words)
    }

    private fun separatedSyllablesKeepTheWordFiledUnderAnAlias(nine: Boolean) {
        val syllables = listOf("cun", "en", "hua")
        val words = offered(recorded("cunenhua", "存嗯化"), nine, syllables)
        assertTrue("存嗯化 is offered for $syllables: ${words.take(40)}", "存嗯化" in words)
        assertFalse("存安化 is not read as $syllables", "存安化" in offered(recorded("cunenhua", "存安化"), nine, syllables))
    }

    @Test fun word_picked_character_by_character_is_learned_under_the_typed_reading_on26key() =
        wordPickedCharacterByCharacterIsLearnedUnderTheTypedReading(nine = false)

    @Test fun word_picked_character_by_character_is_learned_under_the_typed_reading_on9key() =
        wordPickedCharacterByCharacterIsLearnedUnderTheTypedReading(nine = true)

    @Test fun separated_syllables_keep_the_word_filed_under_an_alias_on26key() =
        separatedSyllablesKeepTheWordFiledUnderAnAlias(nine = false)

    @Test fun separated_syllables_keep_the_word_filed_under_an_alias_on9key() =
        separatedSyllablesKeepTheWordFiledUnderAnAlias(nine = true)

    @Test fun every_input_alias_spells_the_characters_it_stands_for() {
        val engine = DictEngine(letters, digits, lm, initialsDict = initials)
        var checked = 0
        for ((typed, targets) in PinyinDecoder.INPUT_ALIASES) for (target in targets) {
            val singles = letters.exact(target).map { it.word }.filter { it.codePointCount(0, it.length) == 1 }
            assertTrue("$target lists single characters", singles.isNotEmpty())
            for (single in singles) {
                val reading = "cun${typed}hua"
                assertEquals(reading, engine.spelledReading("存${single}化", reading))
                assertEquals(typed, engine.spelledReading(single, typed))
                checked++
            }
        }
        assertTrue("at least one alias is covered", checked > 0)
    }

    @Test fun reading_a_word_does_not_have_is_still_refused() {
        val engine = DictEngine(letters, digits, lm, initialsDict = initials)
        for ((reading, word) in misread) {
            assertNotEquals("$word is not read as $reading", reading, engine.spelledReading(word, reading))
        }
    }
}
