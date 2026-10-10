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

class SingleCharacterWordReadingTest {

    private class EditorHost : ImeHost {
        val sb = StringBuilder()
        override fun commitText(text: CharSequence) { sb.append(text) }
        override fun deleteBackward() { if (sb.isNotEmpty()) sb.deleteCharAt(sb.length - 1) }
        override fun performEnter() {}
        override fun textBeforeCursor(n: Int): CharSequence = sb.takeLast(n)
    }

    private class Dictionaries(val letters: BinaryDict, val digits: BinaryDict, val initials: BinaryDict?)

    private val rows = listOf(
        "层" to "ceng", "峦" to "luan", "存" to "cun", "化" to "hua", "嗯" to "ng", "嗯" to "n",
        "银" to "yin", "行" to "xing", "卡" to "ka",
    ).map { (ch, r) -> EngineFixture.Row(r, ch, 900) } + T9Pinyin.SYLLABLES.map { EngineFixture.Row(it, "丌", 1) }

    private val fixture by lazy {
        Dictionaries(
            EngineFixture.build(rows),
            EngineFixture.build(
                rows.map { EngineFixture.Row(T9Pinyin.toT9(it.key), it.word, it.freq) } +
                    EngineFixture.Row(T9Pinyin.toT9("hang"), "行", 900),
            ),
            null,
        )
    }

    private val lmFile = File("src/main/assets/aegis_lm.bin")
    private val lm: CharBigramLM by lazy { CharBigramLM.fromFile(lmFile) }

    @Before fun requireLm() {
        assumeTrue("real LM asset present (production engine wiring)", lmFile.exists())
    }

    private fun shipped(): Dictionaries {
        val dir = System.getenv("AEGIS_FULLDICT_DIR")
        assumeTrue("full-dict check only when AEGIS_FULLDICT_DIR is set", !dir.isNullOrEmpty())
        return Dictionaries(
            BinaryDict.fromFile(File(dir, "aegis_dict.bin")),
            BinaryDict.fromFile(File(dir, "aegis_t9.bin")),
            BinaryDict.fromFile(File(dir, "aegis_jianpin.bin")),
        )
    }

    private fun controller(d: Dictionaries, learning: UserLearning, nine: Boolean): KeyboardController {
        val engine = DictEngine(d.letters, d.digits, lm, initialsDict = d.initials, userLearning = learning)
        return KeyboardController(EditorHost(), engine).also {
            it.userLearning = learning
            it.switchTextLayoutForTest(nine = nine)
        }
    }

    private fun keys(nine: Boolean, letters: String) = if (nine) T9Pinyin.toT9(letters) else letters

    private fun type(c: KeyboardController, s: String) = s.forEach { c.onKey(Key(it.toString(), output = it.toString())) }

    private fun pick(c: KeyboardController, word: String) {
        val i = c.candidateWords().indexOf(word)
        assertTrue("candidate '$word' present in ${c.candidateWords().take(40)}", i >= 0)
        c.onPickCandidate(i)
    }

    private fun formedReading(
        d: Dictionaries,
        nine: Boolean,
        name: List<Pair<String, String>>,
        offered: Boolean = false,
    ): String {
        val learning = UserLearning()
        val c = controller(d, learning, nine)
        val word = name.joinToString("") { it.first }
        val typed = name.joinToString("") { it.second }
        repeat(3) {
            for ((ch, r) in name) {
                type(c, keys(nine, r))
                pick(c, ch)
            }
            c.reset(preserveLayout = true)
        }
        val formed = learning.formedEntries()
        assertEquals("only $word is formed: $formed", listOf(word), formed.map { it.word })
        val reading = formed.single().reading
        assertEquals("the reading keeps the keys that were pressed", T9Pinyin.toT9(typed), T9Pinyin.toT9(reading))
        if (offered) {
            for (layout in listOf(false, true)) {
                val again = controller(d, learning, layout)
                type(again, keys(layout, reading))
                assertTrue("$word is offered for ${keys(layout, reading)}: ${again.candidateWords().take(40)}", word in again.candidateWords())
            }
        }
        return reading
    }

    private fun nameWithAnAliasedCharacterKeepsItsOtherReadings(d: Dictionaries, nine: Boolean) {
        assertEquals("cengluanen", formedReading(d, nine, listOf("层" to "ceng", "峦" to "luan", "嗯" to "en")))
        assertEquals("cunenhua", formedReading(d, nine, listOf("存" to "cun", "嗯" to "en", "化" to "hua")))
    }

    private fun bareNasalReadingsAreKeptOnTheShippedDictionary(nine: Boolean) {
        val d = shipped()
        val cases = listOf(
            listOf("存" to "cun", "嗯" to "ng", "化" to "hua") to "cunnghua",
            listOf("存" to "cun", "嗯" to "n", "峦" to "luan") to "cunnluan",
            listOf("层" to "ceng", "呣" to "m", "化" to "hua") to "cengmhua",
            listOf("存" to "cun", "哼" to "heng", "峦" to "luan") to "cunhengluan",
        )
        for ((name, reading) in cases) assertEquals(reading, formedReading(d, nine, name, offered = true))
    }

    @Test fun name_with_an_aliased_character_keeps_its_other_readings_on26key() =
        nameWithAnAliasedCharacterKeepsItsOtherReadings(fixture, nine = false)

    @Test fun name_with_an_aliased_character_keeps_its_other_readings_on9key() =
        nameWithAnAliasedCharacterKeepsItsOtherReadings(fixture, nine = true)

    @Test fun name_with_an_aliased_character_on_the_shipped_dictionary_on26key() =
        nameWithAnAliasedCharacterKeepsItsOtherReadings(shipped(), nine = false)

    @Test fun name_with_an_aliased_character_on_the_shipped_dictionary_on9key() =
        nameWithAnAliasedCharacterKeepsItsOtherReadings(shipped(), nine = true)

    @Test fun reading_missing_from_the_character_table_falls_back_alone_on9key() {
        val reading = formedReading(fixture, nine = true, listOf("银" to "yin", "行" to "hang", "卡" to "ka"))
        assertTrue("银 and 卡 keep their own readings: $reading", reading.startsWith("yin") && reading.endsWith("ka"))
    }

    @Test fun bare_nasal_readings_are_kept_on_the_shipped_dictionary_on26key() =
        bareNasalReadingsAreKeptOnTheShippedDictionary(nine = false)

    @Test fun bare_nasal_readings_are_kept_on_the_shipped_dictionary_on9key() =
        bareNasalReadingsAreKeptOnTheShippedDictionary(nine = true)
}
